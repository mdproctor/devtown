package io.casehub.devtown.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.Binding;
import io.casehub.api.model.Goal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class PrReviewCaseHubTest {

    @Inject PrReviewCaseHub caseHub;

    @Test
    void definitionLoads() {
        var def = caseHub.getDefinition();
        assertThat(def).isNotNull();
        assertThat(def.getNamespace()).isEqualTo("devtown");
        assertThat(def.getName()).isEqualTo("pr-review");
        assertThat(def.getVersion()).isEqualTo("1.0.0");
    }

    @Test
    void hasNineBindings() {
        var def = caseHub.getDefinition();
        assertThat(def.getBindings()).hasSize(9);
        var names = def.getBindings().stream().map(Binding::getName).toList();
        assertThat(names).containsExactlyInAnyOrder(
            "initial-analysis", "run-ci",
            "security-review", "architecture-review", "style-check",
            "test-coverage", "performance-analysis",
            "human-approval", "merge");
    }

    @Test
    void hasThreeGoals() {
        var def = caseHub.getDefinition();
        assertThat(def.getGoals()).hasSize(3);
        var names = def.getGoals().stream().map(Goal::getName).toList();
        assertThat(names).containsExactlyInAnyOrder("pr-approved", "security-verified", "ci-passing");
    }

    @Test
    void hasEightCapabilities() {
        var def = caseHub.getDefinition();
        // human-decision:pr-approval removed — human-approval now uses humanTask binding target
        assertThat(def.getCapabilities()).hasSize(8);
    }

    @Test
    void hasCompletion() {
        var def = caseHub.getDefinition();
        assertThat(def.getCompletion()).isNotNull();
    }
}
