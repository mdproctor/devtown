package io.casehub.devtown.app;

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
import org.junit.jupiter.api.Test;

class CoordinatedChangeBindingConditionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static CaseDefinition def;
    private static Scope rootScope;

    @BeforeAll
    static void loadDefinition() throws IOException {
        def = CaseDefinitionYamlMapper.load(
            CoordinatedChangeBindingConditionTest.class.getClassLoader()
                .getResourceAsStream("casehub/devtown/coordinated-change.yaml"));
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

    @Test
    void rollbackFiresOnMergeFailure() {
        var ctx = new HashMap<String, Object>();
        ctx.put("mergeResults", List.of(
            Map.of("repo", "casehubio/engine", "status", "success", "mergeSha", "sha1"),
            Map.of("repo", "casehubio/platform", "status", "failed", "reason", "conflict")
        ));

        assertThat(eval(bindingCondition("rollback-on-merge-failure"), ctx)).isTrue();
    }

    @Test
    void rollbackDoesNotReFireAfterResults() {
        var ctx = new HashMap<String, Object>();
        ctx.put("mergeResults", List.of(
            Map.of("repo", "casehubio/engine", "status", "success", "mergeSha", "sha1"),
            Map.of("repo", "casehubio/platform", "status", "failed", "reason", "conflict")
        ));
        ctx.put("rollbackResults", List.of(
            Map.of("repo", "casehubio/engine", "status", "success")
        ));

        assertThat(eval(bindingCondition("rollback-on-merge-failure"), ctx)).isFalse();
    }

    @Test
    void escalationFiresOnRevertFailure() {
        var ctx = new HashMap<String, Object>();
        ctx.put("rollbackResults", List.of(
            Map.of("repo", "casehubio/engine", "status", "conflict", "reason", "branch protection")
        ));

        assertThat(eval(bindingCondition("rollback-human-escalation"), ctx)).isTrue();
    }

    @Test
    void escalationDoesNotReFireAfterHumanCompletion() {
        var ctx = new HashMap<String, Object>();
        ctx.put("rollbackResults", List.of(
            Map.of("repo", "casehubio/engine", "status", "conflict", "reason", "branch protection")
        ));
        ctx.put("rollbackEscalation", Map.of("outcome", "RESOLVED"));

        assertThat(eval(bindingCondition("rollback-human-escalation"), ctx)).isFalse();
    }

    @Test
    void escalationDoesNotFireWhenAllRevertsSucceed() {
        var ctx = new HashMap<String, Object>();
        ctx.put("rollbackResults", List.of(
            Map.of("repo", "casehubio/engine", "status", "success"),
            Map.of("repo", "casehubio/work", "status", "success")
        ));

        assertThat(eval(bindingCondition("rollback-human-escalation"), ctx)).isFalse();
    }
}
