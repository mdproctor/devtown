package io.casehub.devtown.domain.sla;

import io.casehub.devtown.domain.cbr.CapabilityOutcome;
import io.casehub.devtown.domain.cbr.PrFeatureVector;
import io.casehub.devtown.domain.cbr.Precedent;
import io.casehub.devtown.domain.cbr.SimilarityScore;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SlaEstimatorTest {

    private static Precedent precedent(Duration completionTime) {
        return new Precedent(
            UUID.randomUUID(),
            new SimilarityScore(0.8, Map.of()),
            new PrFeatureVector("r", 1, "a", 10, Set.of(), Set.of(), Set.of(), false, false),
            "approved",
            Map.of("style-review", new CapabilityOutcome("COMPLETED", "approved")),
            completionTime
        );
    }

    @Test
    void emptyPrecedents_returnsEmpty() {
        assertThat(SlaEstimator.estimate(List.of())).isEmpty();
    }

    @Test
    void allNullDurations_returnsEmpty() {
        assertThat(SlaEstimator.estimate(List.of(
            precedent(null), precedent(null)
        ))).isEmpty();
    }

    @Test
    void singlePrecedent_medianEqualsThatDuration() {
        var result = SlaEstimator.estimate(List.of(precedent(Duration.ofMinutes(10))));
        assertThat(result).isPresent();
        assertThat(result.get().median()).isEqualTo(Duration.ofMinutes(10));
        assertThat(result.get().precedentCount()).isEqualTo(1);
    }

    @Test
    void oddCount_middleElementIsMedian() {
        var result = SlaEstimator.estimate(List.of(
            precedent(Duration.ofMinutes(5)),
            precedent(Duration.ofMinutes(15)),
            precedent(Duration.ofMinutes(10))
        ));
        assertThat(result).isPresent();
        assertThat(result.get().median()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void evenCount_upperMiddleElementIsMedian() {
        var result = SlaEstimator.estimate(List.of(
            precedent(Duration.ofMinutes(5)),
            precedent(Duration.ofMinutes(10)),
            precedent(Duration.ofMinutes(15)),
            precedent(Duration.ofMinutes(20))
        ));
        assertThat(result).isPresent();
        assertThat(result.get().median()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void negativeAndZeroDurationsFiltered() {
        var result = SlaEstimator.estimate(List.of(
            precedent(Duration.ofMinutes(-5)),
            precedent(Duration.ZERO),
            precedent(Duration.ofMinutes(12))
        ));
        assertThat(result).isPresent();
        assertThat(result.get().median()).isEqualTo(Duration.ofMinutes(12));
        assertThat(result.get().precedentCount()).isEqualTo(1);
    }

    @Test
    void minAndMaxCorrect() {
        var result = SlaEstimator.estimate(List.of(
            precedent(Duration.ofMinutes(3)),
            precedent(Duration.ofMinutes(25)),
            precedent(Duration.ofMinutes(12))
        ));
        assertThat(result).isPresent();
        assertThat(result.get().min()).isEqualTo(Duration.ofMinutes(3));
        assertThat(result.get().max()).isEqualTo(Duration.ofMinutes(25));
    }

    @Test
    void nullDurationsMixedWithValid_onlyValidCounted() {
        var result = SlaEstimator.estimate(List.of(
            precedent(null),
            precedent(Duration.ofMinutes(7)),
            precedent(Duration.ofMinutes(14)),
            precedent(null)
        ));
        assertThat(result).isPresent();
        assertThat(result.get().precedentCount()).isEqualTo(2);
        assertThat(result.get().median()).isEqualTo(Duration.ofMinutes(14));
    }
}
