package io.casehub.devtown.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.model.Binding;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CoordinatedChangeCaseHubTest {

    @Inject CoordinatedChangeCaseHub caseHub;

    @Test
    void definitionLoads() {
        var def = caseHub.getDefinition();
        assertThat(def).isNotNull();
        assertThat(def.getNamespace()).isEqualTo("devtown");
        assertThat(def.getName()).isEqualTo("coordinated-change");
        assertThat(def.getVersion()).isEqualTo("1.0.0");
    }

    @Test
    void hasThreeBindings() {
        var def = caseHub.getDefinition();
        assertThat(def.getBindings()).hasSize(3);
        var names = def.getBindings().stream().map(Binding::getName).toList();
        assertThat(names).containsExactlyInAnyOrder(
                "merge-all-repos", "rollback-on-merge-failure", "rollback-human-escalation");
    }

    @Test
    void hasFourGoals() {
        var def = caseHub.getDefinition();
        assertThat(def.getGoals()).hasSize(4);
        var names = def.getGoals().stream().map(g -> g.getName()).toList();
        assertThat(names).containsExactlyInAnyOrder(
            "all-repos-merged", "review-faulted", "merge-failed", "coordination-abandoned");
    }

    @Test
    void hasTwoCapabilities() {
        var def = caseHub.getDefinition();
        assertThat(def.getCapabilities()).hasSize(2);
    }

    @Test
    void hasCoordinatedMergeWorker() {
        var def = caseHub.getDefinition();
        assertThat(def.getWorkers()).anySatisfy(w ->
            assertThat(w.capabilityNames()).contains("coordinated-merge"));
    }

    @Test
    void hasCoordinatedRollbackWorker() {
        var def = caseHub.getDefinition();
        assertThat(def.getWorkers()).anySatisfy(w ->
                                                        assertThat(w.capabilityNames()).contains("coordinated-rollback"));
    }

}
