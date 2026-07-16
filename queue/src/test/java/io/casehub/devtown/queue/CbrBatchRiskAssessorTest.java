package io.casehub.devtown.queue;

import io.casehub.devtown.domain.cbr.PrFeatureVector;
import io.casehub.devtown.domain.cbr.Precedent;
import io.casehub.devtown.domain.cbr.SimilarityScore;
import io.casehub.devtown.domain.queue.PriorityLane;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CbrBatchRiskAssessorTest {

    private static final Instant NOW = Instant.parse("2026-07-14T12:00:00Z");

    private QueuedPr pr(int number) {
        return new QueuedPr(number, "casehubio/devtown", "sha" + number, "alice",
                            0.8, PriorityLane.NORMAL, NOW, Set.of());
    }

    private Precedent precedent(String outcome) {
        var vector = PrFeatureVector.from("casehubio/devtown", 1, "alice", 50, List.of("src/Main.java"));
        return new Precedent(UUID.randomUUID(), new SimilarityScore(0.85, Map.of()), vector, outcome, Map.of(), null);
    }

    @Test
    void noPrecedent_returnsNeutralRisk() {
        var assessor = new CbrBatchRiskAssessor((v, t) -> List.of());
        var result = assessor.assessRisk(List.of(pr(1)), "casehubio/devtown", "tenant-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).riskScore()).isEqualTo(0.0);
    }

    @Test
    void allSuccessPrecedents_returnsZeroRisk() {
        var assessor = new CbrBatchRiskAssessor((v, t) -> List.of(
            precedent("success"), precedent("COMPLETED"), precedent("merged")
        ));
        var result = assessor.assessRisk(List.of(pr(1)), "casehubio/devtown", "tenant-1");

        assertThat(result.get(0).riskScore()).isEqualTo(0.0);
    }

    @Test
    void allFailurePrecedents_returnsFullRisk() {
        var assessor = new CbrBatchRiskAssessor((v, t) -> List.of(
            precedent("batch-failure"), precedent("rejected"), precedent("FAULTED")
        ));
        var result = assessor.assessRisk(List.of(pr(1)), "casehubio/devtown", "tenant-1");

        assertThat(result.get(0).riskScore()).isEqualTo(1.0);
    }

    @Test
    void mixedOutcomes_returnsProportionalRisk() {
        var assessor = new CbrBatchRiskAssessor((v, t) -> List.of(
            precedent("success"),
            precedent("batch-failure"),
            precedent("merged"),
            precedent("rejected"),
            precedent("success")
        ));
        var result = assessor.assessRisk(List.of(pr(1)), "casehubio/devtown", "tenant-1");

        assertThat(result.get(0).riskScore()).isEqualTo(0.4); // 2/5
    }

    @Test
    void multipleCandidate_eachScoredIndependently() {
        var assessor = new CbrBatchRiskAssessor((v, t) -> {
            if (v.prNumber() == 1) return List.of(precedent("batch-failure"));
            return List.of(precedent("success"));
        });
        var result = assessor.assessRisk(List.of(pr(1), pr(2)), "casehubio/devtown", "tenant-1");

        assertThat(result.get(0).riskScore()).isEqualTo(1.0);
        assertThat(result.get(1).riskScore()).isEqualTo(0.0);
    }

    @Test
    void preservesOtherFieldsOfQueuedPr() {
        var assessor = new CbrBatchRiskAssessor((v, t) -> List.of(precedent("batch-failure")));
        var original = pr(42);
        var result = assessor.assessRisk(List.of(original), "casehubio/devtown", "tenant-1");

        var assessed = result.get(0);
        assertThat(assessed.number()).isEqualTo(42);
        assertThat(assessed.trustScore()).isEqualTo(0.8);
        assertThat(assessed.author()).isEqualTo("alice");
        assertThat(assessed.riskScore()).isEqualTo(1.0);
    }
}
