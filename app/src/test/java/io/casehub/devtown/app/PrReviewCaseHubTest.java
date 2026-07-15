package io.casehub.devtown.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.Binding;
import io.casehub.api.model.HumanTaskTarget;
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
    void hasTwentyOneBindings() {
        var def = caseHub.getDefinition();
        assertThat(def.getBindings()).hasSize(21);
        var names = def.getBindings().stream().map(Binding::getName).toList();
        assertThat(names).containsExactlyInAnyOrder(
                "initial-analysis", "run-ci",
                "security-review", "architecture-review", "style-check",
                "test-coverage", "performance-analysis",
                "precedent-security-review", "precedent-architecture-review",
                "human-approval",
                "security-review-reduced-scope", "architecture-review-reduced-scope",
                "security-review-human-escalation", "architecture-review-human-escalation",
                "style-check-human-escalation", "test-coverage-human-escalation",
                "performance-analysis-human-escalation", "code-analysis-human-escalation",
                "ci-runner-human-escalation",
                "enqueue-for-merge", "merge-direct");
    }

    @Test
    void capabilityBindingsHaveOutcomePolicy() {
        var def = caseHub.getDefinition();
        var capabilityBindings = def.getBindings().stream()
                .filter(b -> b.target() instanceof io.casehub.api.model.CapabilityTarget)
                .filter(b -> !"merge-direct".equals(b.getName()))
                .filter(b -> !"enqueue-for-merge".equals(b.getName()))
                .toList();
        assertThat(capabilityBindings).isNotEmpty();
        for (Binding b : capabilityBindings) {
            assertThat(b.getOutcomePolicy())
                    .as("binding '%s' should have outcomePolicy", b.getName())
                    .isNotNull();
            assertThat(b.getOutcomePolicy().maxRerouteAttempts())
                    .as("binding '%s' maxRerouteAttempts", b.getName())
                    .isEqualTo(2);
        }
    }

    @Test
    void tier4EscalationBindingsHaveHumanTask() {
        var def = caseHub.getDefinition();
        var escalationBindings = def.getBindings().stream()
                .filter(b -> b.getName().endsWith("-human-escalation"))
                .toList();
        assertThat(escalationBindings).hasSize(7);
        for (Binding b : escalationBindings) {
            assertThat(b.target())
                    .as("binding '%s' should target a humanTask", b.getName())
                    .isInstanceOf(HumanTaskTarget.class);
        }
    }

    @Test
    void hasSevenGoals() {
        var def = caseHub.getDefinition();
        assertThat(def.getGoals()).hasSize(7);
        var names = def.getGoals().stream().map(g -> g.getName()).toList();
        assertThat(names).containsExactlyInAnyOrder(
            "pr-approved", "security-verified", "ci-passing", "merge-completed",
            "review-blocked", "review-rejected", "review-abandoned");
    }

    @Test
    void hasNineCapabilities() {
        var def = caseHub.getDefinition();
        assertThat(def.getCapabilities()).hasSize(9);
    }

    @Test
    void hasCompletion() {
        var def = caseHub.getDefinition();
        assertThat(def.getCompletion()).isNotNull();
    }
}
