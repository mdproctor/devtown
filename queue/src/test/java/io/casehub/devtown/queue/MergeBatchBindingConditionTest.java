/*
 * Copyright 2026-Present The Case Hub Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.casehub.devtown.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.converter.CaseDefinitionYamlMapper;
import io.casehub.api.model.evaluator.JQExpressionEvaluator;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.thisptr.jackson.jq.BuiltinFunctionLoader;
import net.thisptr.jackson.jq.JsonQuery;
import net.thisptr.jackson.jq.Scope;
import net.thisptr.jackson.jq.Versions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MergeBatchBindingConditionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static CaseDefinition def;
    private static Scope rootScope;

    @BeforeAll
    static void loadDefinition() throws IOException {
        def = CaseDefinitionYamlMapper.load(
            MergeBatchBindingConditionTest.class.getClassLoader()
                .getResourceAsStream("devtown/merge-batch.yaml"));
        rootScope = Scope.newEmptyScope();
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, rootScope);
    }

    private static String bindingCondition(String bindingName) {
        return def.getBindings().stream()
            .filter(b -> b.getName().equals(bindingName))
            .findFirst()
            .map(b -> ((JQExpressionEvaluator) b.getWhen()).expression())
            .orElseThrow(() -> new AssertionError("Binding not found: " + bindingName));
    }

    private static boolean eval(String jqExpr, Map<String, Object> context) {
        try {
            JsonNode node = MAPPER.valueToTree(context);
            Scope childScope = Scope.newChildScope(rootScope);
            JsonQuery query = JsonQuery.compile(jqExpr, Versions.JQ_1_6);
            List<JsonNode> out = new ArrayList<>();
            query.apply(childScope, node, out::add);
            return !out.isEmpty() && out.getFirst().isBoolean() && out.getFirst().asBoolean();
        } catch (Exception e) {
            throw new RuntimeException("JQ evaluation failed for: " + jqExpr, e);
        }
    }

    private static Map<String, Object> batch(int size, String riskLevel) {
        return Map.of("size", size, "riskLevel", riskLevel,
            "prs", List.of(), "id", "batch-001");
    }

    @Nested class TestBatchTip {
        @Test void fires_whenBatchPresentAndNoTipTest() {
            var ctx = Map.<String, Object>of("batch", batch(3, "ROUTINE"));
            assertThat(eval(bindingCondition("test-batch-tip"), ctx)).isTrue();
        }

        @Test void doesNotFire_whenTipTestAlreadyPresent() {
            var ctx = Map.<String, Object>of(
                "batch", batch(3, "ROUTINE"),
                "tipTest", Map.of("status", "passing"));
            assertThat(eval(bindingCondition("test-batch-tip"), ctx)).isFalse();
        }

        @Test void doesNotFire_whenEscalatedRetryIsTrue() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "ROUTINE"));
            ctx.put("tipTestEscalatedRetry", true);
            assertThat(eval(bindingCondition("test-batch-tip"), ctx)).isFalse();
        }

        @Test void doesNotFire_whenBatchSizeIsZero() {
            var ctx = Map.<String, Object>of("batch", batch(0, "ROUTINE"));
            assertThat(eval(bindingCondition("test-batch-tip"), ctx)).isFalse();
        }
    }

    @Nested class TipTestEscalation {
        @Test void fires_whenReroutesExhaustedAndNoEscalation() {
            var ctx = Map.<String, Object>of(
                "batch", batch(3, "ROUTINE"),
                "tipTest", Map.of("status", "REROUTES_EXHAUSTED"));
            assertThat(eval(bindingCondition("tip-test-escalation"), ctx)).isTrue();
        }

        @Test void doesNotFire_whenEscalationAlreadyPresent() {
            var ctx = Map.<String, Object>of(
                "batch", batch(3, "ROUTINE"),
                "tipTest", Map.of("status", "REROUTES_EXHAUSTED"),
                "tipTestEscalation", Map.of("outcome", "RETRY"));
            assertThat(eval(bindingCondition("tip-test-escalation"), ctx)).isFalse();
        }

        @Test void doesNotFire_whenTipTestPassing() {
            var ctx = Map.<String, Object>of(
                "batch", batch(3, "ROUTINE"),
                "tipTest", Map.of("status", "passing"));
            assertThat(eval(bindingCondition("tip-test-escalation"), ctx)).isFalse();
        }
    }

    @Nested class TipTestAfterEscalation {
        @Test void fires_whenRetryApprovedAndStillExhausted() {
            var ctx = Map.<String, Object>of(
                "batch", batch(3, "ROUTINE"),
                "tipTest", Map.of("status", "REROUTES_EXHAUSTED"),
                "tipTestEscalation", Map.of("outcome", "RETRY"));
            assertThat(eval(bindingCondition("tip-test-after-escalation"), ctx)).isTrue();
        }

        @Test void doesNotFire_whenEscalationRejected() {
            var ctx = Map.<String, Object>of(
                "batch", batch(3, "ROUTINE"),
                "tipTest", Map.of("status", "REROUTES_EXHAUSTED"),
                "tipTestEscalation", Map.of("outcome", "REJECT_BATCH"));
            assertThat(eval(bindingCondition("tip-test-after-escalation"), ctx)).isFalse();
        }
    }

    @Nested class MergeBatch {
        @Test void fires_whenTipPassesAndRoutineRisk() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "ROUTINE"));
            ctx.put("tipTest", Map.of("status", "passing"));
            assertThat(eval(bindingCondition("merge-batch"), ctx)).isTrue();
        }

        @Test void fires_whenTipPassesAndElevatedRisk() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "ELEVATED"));
            ctx.put("tipTest", Map.of("status", "passing"));
            assertThat(eval(bindingCondition("merge-batch"), ctx)).isTrue();
        }

        @Test void fires_whenHighRiskButHumanApproved() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "HIGH"));
            ctx.put("tipTest", Map.of("status", "passing"));
            ctx.put("mergeApproval", Map.of("outcome", "APPROVED"));
            assertThat(eval(bindingCondition("merge-batch"), ctx)).isTrue();
        }

        @Test void doesNotFire_whenHighRiskAndNoApproval() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "HIGH"));
            ctx.put("tipTest", Map.of("status", "passing"));
            assertThat(eval(bindingCondition("merge-batch"), ctx)).isFalse();
        }

        @Test void doesNotFire_whenMergeResultAlreadyPresent() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "ROUTINE"));
            ctx.put("tipTest", Map.of("status", "passing"));
            ctx.put("mergeResult", Map.of("status", "success"));
            assertThat(eval(bindingCondition("merge-batch"), ctx)).isFalse();
        }

        @Test void doesNotFire_whenEscalatedRetryIsTrue() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "ROUTINE"));
            ctx.put("tipTest", Map.of("status", "passing"));
            ctx.put("mergeEscalatedRetry", true);
            assertThat(eval(bindingCondition("merge-batch"), ctx)).isFalse();
        }

        @Test void doesNotFire_whenTipTestFailing() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "ROUTINE"));
            ctx.put("tipTest", Map.of("status", "failing"));
            assertThat(eval(bindingCondition("merge-batch"), ctx)).isFalse();
        }
    }

    @Nested class HumanMergeApproval {
        @Test void fires_whenHighRiskAndTipPassing() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "HIGH"));
            ctx.put("tipTest", Map.of("status", "passing"));
            assertThat(eval(bindingCondition("human-merge-approval"), ctx)).isTrue();
        }

        @Test void fires_whenCriticalRisk() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "CRITICAL"));
            ctx.put("tipTest", Map.of("status", "passing"));
            assertThat(eval(bindingCondition("human-merge-approval"), ctx)).isTrue();
        }

        @Test void doesNotFire_whenRoutineRisk() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "ROUTINE"));
            ctx.put("tipTest", Map.of("status", "passing"));
            assertThat(eval(bindingCondition("human-merge-approval"), ctx)).isFalse();
        }

        @Test void doesNotFire_whenApprovalAlreadyPresent() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "HIGH"));
            ctx.put("tipTest", Map.of("status", "passing"));
            ctx.put("mergeApproval", Map.of("outcome", "APPROVED"));
            assertThat(eval(bindingCondition("human-merge-approval"), ctx)).isFalse();
        }
    }

    @Nested class MergeEscalation {
        @Test void fires_whenReroutesExhaustedAndNoEscalation() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "ROUTINE"));
            ctx.put("mergeResult", Map.of("status", "REROUTES_EXHAUSTED"));
            assertThat(eval(bindingCondition("merge-escalation"), ctx)).isTrue();
        }

        @Test void doesNotFire_whenEscalationAlreadyPresent() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "ROUTINE"));
            ctx.put("mergeResult", Map.of("status", "REROUTES_EXHAUSTED"));
            ctx.put("mergeEscalation", Map.of("outcome", "APPROVED"));
            assertThat(eval(bindingCondition("merge-escalation"), ctx)).isFalse();
        }
    }

    @Nested class MergeAfterEscalation {
        @Test void fires_whenEscalationApprovedAndStillExhausted() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "ROUTINE"));
            ctx.put("mergeResult", Map.of("status", "REROUTES_EXHAUSTED"));
            ctx.put("mergeEscalation", Map.of("outcome", "APPROVED"));
            assertThat(eval(bindingCondition("merge-after-escalation"), ctx)).isTrue();
        }

        @Test void doesNotFire_whenEscalationRejected() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "ROUTINE"));
            ctx.put("mergeResult", Map.of("status", "REROUTES_EXHAUSTED"));
            ctx.put("mergeEscalation", Map.of("outcome", "REJECTED"));
            assertThat(eval(bindingCondition("merge-after-escalation"), ctx)).isFalse();
        }
    }

    @Nested class ComputeBisectionSplit {
        @Test void fires_whenTipFailsAndBatchLargerThanOne() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(4, "ROUTINE"));
            ctx.put("tipTest", Map.of("status", "failing"));
            assertThat(eval(bindingCondition("compute-bisection-split"), ctx)).isTrue();
        }

        @Test void doesNotFire_whenBatchSizeIsOne() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(1, "ROUTINE"));
            ctx.put("tipTest", Map.of("status", "failing"));
            assertThat(eval(bindingCondition("compute-bisection-split"), ctx)).isFalse();
        }

        @Test void doesNotFire_whenSplitResultAlreadyPresent() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(4, "ROUTINE"));
            ctx.put("tipTest", Map.of("status", "failing"));
            ctx.put("splitResult", Map.of("left", Map.of(), "right", Map.of()));
            assertThat(eval(bindingCondition("compute-bisection-split"), ctx)).isFalse();
        }

        @Test void doesNotFire_whenTipTestPassing() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(4, "ROUTINE"));
            ctx.put("tipTest", Map.of("status", "passing"));
            assertThat(eval(bindingCondition("compute-bisection-split"), ctx)).isFalse();
        }
    }

    @Nested class BisectLeft {
        @Test void fires_whenSplitResultPresentAndBisectLeftNull() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(4, "ROUTINE"));
            ctx.put("splitResult", Map.of("left", Map.of("size", 2), "right", Map.of("size", 2)));
            assertThat(eval(bindingCondition("bisect-left"), ctx)).isTrue();
        }

        @Test void doesNotFire_whenBisectLeftAlreadyPresent() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(4, "ROUTINE"));
            ctx.put("splitResult", Map.of("left", Map.of("size", 2), "right", Map.of("size", 2)));
            ctx.put("bisectLeft", Map.of("mergeResult", Map.of("status", "success")));
            assertThat(eval(bindingCondition("bisect-left"), ctx)).isFalse();
        }

        @Test void doesNotFire_whenNoSplitResult() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(4, "ROUTINE"));
            assertThat(eval(bindingCondition("bisect-left"), ctx)).isFalse();
        }
    }

    @Nested class BisectRight {
        @Test void fires_whenSplitResultPresentAndBisectRightNull() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(4, "ROUTINE"));
            ctx.put("splitResult", Map.of("left", Map.of("size", 2), "right", Map.of("size", 2)));
            assertThat(eval(bindingCondition("bisect-right"), ctx)).isTrue();
        }

        @Test void doesNotFire_whenBisectRightAlreadyPresent() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(4, "ROUTINE"));
            ctx.put("splitResult", Map.of("left", Map.of("size", 2), "right", Map.of("size", 2)));
            ctx.put("bisectRight", Map.of("mergeResult", Map.of("status", "success")));
            assertThat(eval(bindingCondition("bisect-right"), ctx)).isFalse();
        }
    }

    @Nested class RejectSinglePr {
        @Test void fires_whenTipFailsAndBatchSizeOneAndNoRejections() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(1, "ROUTINE"));
            ctx.put("tipTest", Map.of("status", "failing"));
            ctx.put("rejectedPrs", List.of());
            assertThat(eval(bindingCondition("reject-single-pr"), ctx)).isTrue();
        }

        @Test void doesNotFire_whenRejectedPrsNonEmpty() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(1, "ROUTINE"));
            ctx.put("tipTest", Map.of("status", "failing"));
            ctx.put("rejectedPrs", List.of(Map.of("number", 456)));
            assertThat(eval(bindingCondition("reject-single-pr"), ctx)).isFalse();
        }

        @Test void doesNotFire_whenBatchSizeGreaterThanOne() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(3, "ROUTINE"));
            ctx.put("tipTest", Map.of("status", "failing"));
            ctx.put("rejectedPrs", List.of());
            assertThat(eval(bindingCondition("reject-single-pr"), ctx)).isFalse();
        }

        @Test void doesNotFire_whenTipTestPassing() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("batch", batch(1, "ROUTINE"));
            ctx.put("tipTest", Map.of("status", "passing"));
            ctx.put("rejectedPrs", List.of());
            assertThat(eval(bindingCondition("reject-single-pr"), ctx)).isFalse();
        }
    }
}
