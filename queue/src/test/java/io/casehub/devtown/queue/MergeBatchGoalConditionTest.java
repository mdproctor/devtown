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

class MergeBatchGoalConditionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static CaseDefinition def;
    private static Scope rootScope;

    @BeforeAll
    static void loadDefinition() throws IOException {
        def = CaseDefinitionYamlMapper.load(
            MergeBatchGoalConditionTest.class.getClassLoader()
                .getResourceAsStream("devtown/merge-batch.yaml"));
        rootScope = Scope.newEmptyScope();
        BuiltinFunctionLoader.getInstance().loadFunctions(Versions.JQ_1_6, rootScope);
    }

    private static String goalCondition(String goalName) {
        return def.getGoals().stream()
            .filter(g -> g.getName().equals(goalName))
            .findFirst()
            .map(g -> ((JQExpressionEvaluator) g.getCondition()).expression())
            .orElseThrow(() -> new AssertionError("Goal not found: " + goalName));
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

    @Nested class BatchMerged {
        @Test void satisfied_whenMergeResultSuccess() {
            var ctx = Map.<String, Object>of(
                "mergeResult", Map.of("status", "success"));
            assertThat(eval(goalCondition("batch-merged"), ctx)).isTrue();
        }

        @Test void notSatisfied_whenMergeResultFailed() {
            var ctx = Map.<String, Object>of(
                "mergeResult", Map.of("status", "failed"));
            assertThat(eval(goalCondition("batch-merged"), ctx)).isFalse();
        }

        @Test void notSatisfied_whenNoMergeResult() {
            assertThat(eval(goalCondition("batch-merged"), Map.of())).isFalse();
        }
    }

    @Nested class SinglePrRejected {
        @Test void satisfied_whenBatchSizeOneAndRejectedPrsNonEmpty() {
            var ctx = Map.<String, Object>of(
                "batch", Map.of("size", 1),
                "rejectedPrs", List.of(Map.of("number", 456)));
            assertThat(eval(goalCondition("single-pr-rejected"), ctx)).isTrue();
        }

        @Test void notSatisfied_whenBatchSizeGreaterThanOne() {
            var ctx = Map.<String, Object>of(
                "batch", Map.of("size", 3),
                "rejectedPrs", List.of(Map.of("number", 456)));
            assertThat(eval(goalCondition("single-pr-rejected"), ctx)).isFalse();
        }

        @Test void notSatisfied_whenRejectedPrsEmpty() {
            var ctx = Map.<String, Object>of(
                "batch", Map.of("size", 1),
                "rejectedPrs", List.of());
            assertThat(eval(goalCondition("single-pr-rejected"), ctx)).isFalse();
        }
    }

    @Nested class AllCulpritsIsolated {
        @Test void satisfied_whenBothBisectHalvesPresent() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("bisectLeft", Map.of("mergeResult", Map.of("status", "success")));
            ctx.put("bisectRight", Map.of("rejectedPrs", List.of(Map.of("number", 456))));
            assertThat(eval(goalCondition("all-culprits-isolated"), ctx)).isTrue();
        }

        @Test void notSatisfied_whenOnlyBisectLeftPresent() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("bisectLeft", Map.of("mergeResult", Map.of("status", "success")));
            assertThat(eval(goalCondition("all-culprits-isolated"), ctx)).isFalse();
        }

        @Test void notSatisfied_whenNeitherPresent() {
            assertThat(eval(goalCondition("all-culprits-isolated"), Map.of())).isFalse();
        }
    }

    @Nested class MergeApprovalRejected {
        @Test void satisfied_whenMergeApprovalRejected() {
            var ctx = Map.<String, Object>of(
                "mergeApproval", Map.of("outcome", "REJECTED"));
            assertThat(eval(goalCondition("merge-approval-rejected"), ctx)).isTrue();
        }

        @Test void satisfied_whenMergeApprovalBlocked() {
            var ctx = Map.<String, Object>of(
                "mergeApproval", Map.of("outcome", "BLOCKED"));
            assertThat(eval(goalCondition("merge-approval-rejected"), ctx)).isTrue();
        }

        @Test void notSatisfied_whenMergeApprovalApproved() {
            var ctx = Map.<String, Object>of(
                "mergeApproval", Map.of("outcome", "APPROVED"));
            assertThat(eval(goalCondition("merge-approval-rejected"), ctx)).isFalse();
        }

        @Test void notSatisfied_whenNoMergeApproval() {
            assertThat(eval(goalCondition("merge-approval-rejected"), Map.of())).isFalse();
        }
    }

    @Nested class MergeTerminalFailure {
        @Test void satisfied_whenMergeEscalationRejected() {
            var ctx = Map.<String, Object>of(
                "mergeEscalation", Map.of("outcome", "REJECTED"));
            assertThat(eval(goalCondition("merge-terminal-failure"), ctx)).isTrue();
        }

        @Test void satisfied_whenMergeEscalationBlocked() {
            var ctx = Map.<String, Object>of(
                "mergeEscalation", Map.of("outcome", "BLOCKED"));
            assertThat(eval(goalCondition("merge-terminal-failure"), ctx)).isTrue();
        }

        @Test void notSatisfied_whenMergeEscalationApproved() {
            var ctx = Map.<String, Object>of(
                "mergeEscalation", Map.of("outcome", "APPROVED"));
            assertThat(eval(goalCondition("merge-terminal-failure"), ctx)).isFalse();
        }

        @Test void notSatisfied_whenNoMergeEscalation() {
            assertThat(eval(goalCondition("merge-terminal-failure"), Map.of())).isFalse();
        }
    }

    @Nested class TipTestTerminalFailure {
        @Test void satisfied_whenTipTestEscalationRejectBatch() {
            var ctx = Map.<String, Object>of(
                "tipTestEscalation", Map.of("outcome", "REJECT_BATCH"));
            assertThat(eval(goalCondition("tip-test-terminal-failure"), ctx)).isTrue();
        }

        @Test void satisfied_whenTipTestEscalationBlocked() {
            var ctx = Map.<String, Object>of(
                "tipTestEscalation", Map.of("outcome", "BLOCKED"));
            assertThat(eval(goalCondition("tip-test-terminal-failure"), ctx)).isTrue();
        }

        @Test void notSatisfied_whenTipTestEscalationRetry() {
            var ctx = Map.<String, Object>of(
                "tipTestEscalation", Map.of("outcome", "RETRY"));
            assertThat(eval(goalCondition("tip-test-terminal-failure"), ctx)).isFalse();
        }

        @Test void notSatisfied_whenNoTipTestEscalation() {
            assertThat(eval(goalCondition("tip-test-terminal-failure"), Map.of())).isFalse();
        }
    }
}
