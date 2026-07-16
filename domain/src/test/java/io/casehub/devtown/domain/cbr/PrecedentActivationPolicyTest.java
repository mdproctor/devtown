package io.casehub.devtown.domain.cbr;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class PrecedentActivationPolicyTest {

    private static final SimilarityScore SCORE = new SimilarityScore(1.0, Map.of());
    private static final PrFeatureVector VECTOR = new PrFeatureVector(
        "repo", 1, "dev", 100, Set.of(), Set.of(), Set.of(), false, false);

    private static final CapabilityOutcome FINDINGS = new CapabilityOutcome("COMPLETED", "FINDINGS_PRESENT");
    private static final CapabilityOutcome APPROVED = new CapabilityOutcome("COMPLETED", "approved");
    private static final CapabilityOutcome FAILED = new CapabilityOutcome("FAILED", null);

    private Precedent precedent(Map<String, CapabilityOutcome> outcomes) {
        return new Precedent(UUID.randomUUID(), SCORE, VECTOR, "flagged", outcomes, null);
    }

    private Precedent precedent(double similarity, Map<String, CapabilityOutcome> outcomes) {
        return new Precedent(UUID.randomUUID(), new SimilarityScore(similarity, Map.of()),
                             VECTOR, "flagged", outcomes, null);
    }


    private static Function<String, ActivationThreshold> defaultThreshold() {
        return cap -> ActivationThreshold.DEFAULT;
    }


    @Test
    void emptyPrecedentsReturnsEmptySet() {
        assertThat(PrecedentActivationPolicy.evaluate(List.of(), defaultThreshold())).isEmpty();
    }

    @Test
    void belowMinFindingsNotActivated() {
        var precedents = List.of(
            precedent(Map.of("security-review", FINDINGS)),
            precedent(Map.of("security-review", APPROVED)),
            precedent(Map.of("security-review", APPROVED)),
            precedent(Map.of("security-review", APPROVED)),
            precedent(Map.of("security-review", APPROVED))
        );
        assertThat(PrecedentActivationPolicy.evaluate(precedents, defaultThreshold())).isEmpty();
    }

    @Test
    void belowMinFractionNotActivated() {
        var precedents = List.of(
            precedent(Map.of("security-review", FINDINGS)),
            precedent(Map.of("security-review", FINDINGS)),
            precedent(Map.of("security-review", APPROVED)),
            precedent(Map.of("security-review", APPROVED)),
            precedent(Map.of("security-review", APPROVED)),
            precedent(Map.of("security-review", APPROVED)),
            precedent(Map.of("security-review", APPROVED)),
            precedent(Map.of("security-review", APPROVED)),
            precedent(Map.of("security-review", APPROVED)),
            precedent(Map.of("security-review", APPROVED))
        );
        assertThat(PrecedentActivationPolicy.evaluate(precedents, defaultThreshold())).isEmpty();
    }

    @Test
    void meetsBothThresholdsActivated() {
        var precedents = List.of(
            precedent(Map.of("security-review", FINDINGS)),
            precedent(Map.of("security-review", FINDINGS)),
            precedent(Map.of("security-review", FINDINGS)),
            precedent(Map.of("security-review", APPROVED)),
            precedent(Map.of("security-review", APPROVED))
        );
        assertThat(PrecedentActivationPolicy.evaluate(precedents, defaultThreshold()))
            .containsExactly("security-review");
    }

    @Test
    void multipleCapabilitiesEvaluatedIndependently() {
        var precedents = List.of(
            precedent(Map.of("security-review", FINDINGS, "architecture-review", FINDINGS)),
            precedent(Map.of("security-review", FINDINGS, "architecture-review", APPROVED)),
            precedent(Map.of("security-review", FINDINGS)),
            precedent(Map.of("security-review", APPROVED)),
            precedent(Map.of("architecture-review", APPROVED))
        );
        assertThat(PrecedentActivationPolicy.evaluate(precedents, defaultThreshold()))
            .containsExactly("security-review");
    }

    @Test
    void exactBoundaryMinFindingsActivated() {
        var precedents = List.of(
            precedent(Map.of("security-review", FINDINGS)),
            precedent(Map.of("security-review", FINDINGS)),
            precedent(Map.of("security-review", APPROVED))
        );
        assertThat(PrecedentActivationPolicy.evaluate(precedents, defaultThreshold()))
            .containsExactly("security-review");
    }

    @Test
    void failedOutcomeNotCountedAsFindings() {
        var precedents = List.of(
            precedent(Map.of("security-review", FAILED)),
            precedent(Map.of("security-review", FAILED)),
            precedent(Map.of("security-review", FAILED)),
            precedent(Map.of("security-review", FAILED)),
            precedent(Map.of("security-review", FAILED))
        );
        assertThat(PrecedentActivationPolicy.evaluate(precedents, defaultThreshold())).isEmpty();
    }

    @Test
    void capabilityAbsentFromPrecedentNotCounted() {
        var precedents = List.of(
            precedent(Map.of("security-review", FINDINGS)),
            precedent(Map.of("security-review", FINDINGS)),
            precedent(Map.of("style-review", APPROVED)),
            precedent(Map.of("style-review", APPROVED)),
            precedent(Map.of("style-review", APPROVED))
        );
        assertThat(PrecedentActivationPolicy.evaluate(precedents, defaultThreshold()))
            .containsExactly("security-review");
    }

    @Test
    void multipleCapabilitiesBothActivated() {
        var precedents = List.of(
            precedent(Map.of("security-review", FINDINGS, "architecture-review", FINDINGS)),
            precedent(Map.of("security-review", FINDINGS, "architecture-review", FINDINGS)),
            precedent(Map.of("security-review", FINDINGS, "architecture-review", FINDINGS))
        );
        assertThat(PrecedentActivationPolicy.evaluate(precedents, defaultThreshold()))
            .containsExactlyInAnyOrder("security-review", "architecture-review");
    }

    @Test
    void perCapabilityThreshold_lowerBarActivates() {
        var precedents = List.of(
                precedent(Map.of("security-review", FINDINGS)),
                precedent(Map.of("security-review", FINDINGS)),
                precedent(Map.of("security-review", APPROVED)),
                precedent(Map.of("security-review", APPROVED)),
                precedent(Map.of("security-review", APPROVED))
                                );
        // 2/5 = 0.4 — meets default but NOT a 0.5 fraction threshold
        assertThat(PrecedentActivationPolicy.evaluate(precedents,
                                                      cap -> new ActivationThreshold(2.0, 0.5))).isEmpty();
        // Lower bar for security-review specifically
        Function<String, ActivationThreshold> perCap = cap ->
                                                               "security-review".equals(cap) ? new ActivationThreshold(2.0, 0.3)
                                                                                             : ActivationThreshold.DEFAULT;
        assertThat(PrecedentActivationPolicy.evaluate(precedents, perCap))
                .containsExactly("security-review");
    }

    @Test
    void perCapabilityThreshold_higherBarRejects() {
        var precedents = List.of(
                precedent(Map.of("security-review", FINDINGS, "architecture-review", FINDINGS)),
                precedent(Map.of("security-review", FINDINGS, "architecture-review", FINDINGS)),
                precedent(Map.of("security-review", FINDINGS, "architecture-review", APPROVED))
                                );
        // 3/3 for security (meets any bar), 2/3 for architecture
        // Raise bar for architecture-review: minFindings=3 → not enough
        Function<String, ActivationThreshold> perCap = cap ->
                                                               "architecture-review".equals(cap) ? new ActivationThreshold(3.0, 0.4)
                                                                                                 : ActivationThreshold.DEFAULT;
        assertThat(PrecedentActivationPolicy.evaluate(precedents, perCap))
                .containsExactly("security-review");
    }

    @Test
    void perCapabilityThreshold_differentThresholdsPerCapability() {
        var precedents = List.of(
                precedent(Map.of("security-review", FINDINGS, "architecture-review", FINDINGS)),
                precedent(Map.of("security-review", APPROVED, "architecture-review", FINDINGS)),
                precedent(Map.of("security-review", APPROVED, "architecture-review", APPROVED)),
                precedent(Map.of("security-review", APPROVED, "architecture-review", APPROVED)),
                precedent(Map.of("security-review", APPROVED, "architecture-review", APPROVED))
                                );
        // security: 1/5 findings, architecture: 2/5 findings
        // security with minFindings=1, minFraction=0.1 → activates
        // architecture with default (2, 0.4) → 2/5=0.4 → activates
        Map<String, ActivationThreshold> overrides = Map.of(
                "security-review", new ActivationThreshold(1.0, 0.1));
        assertThat(PrecedentActivationPolicy.evaluate(precedents,
                                                      cap -> overrides.getOrDefault(cap, ActivationThreshold.DEFAULT)))
                .containsExactlyInAnyOrder("security-review", "architecture-review");
    }

    @Test
    void weightedAccumulation_highSimilarityContributesMore() {
        var precedents = List.of(
                precedent(0.95, Map.of("security-review", FINDINGS)),
                precedent(0.95, Map.of("security-review", FINDINGS)),
                precedent(0.3, Map.of("security-review", APPROVED))
                                );
        // evidence = 0.95 + 0.95 = 1.9, totalWeight = 2.2
        // fraction = 1.9/2.2 ≈ 0.86 >= 0.4 ✅
        // but evidence 1.9 < 2.0 → NOT activated
        assertThat(PrecedentActivationPolicy.evaluate(precedents, defaultThreshold())).isEmpty();
    }

    @Test
    void weightedAccumulation_lowSimilarityNeedsMorePrecedents() {
        var precedents = List.of(
                precedent(0.5, Map.of("security-review", FINDINGS)),
                precedent(0.5, Map.of("security-review", FINDINGS)),
                precedent(0.5, Map.of("security-review", FINDINGS)),
                precedent(0.5, Map.of("security-review", APPROVED)),
                precedent(0.5, Map.of("security-review", APPROVED))
                                );
        // evidence = 1.5, totalWeight = 2.5, fraction = 1.5/2.5 = 0.6 >= 0.4 ✅
        // but evidence 1.5 < 2.0 → NOT activated
        assertThat(PrecedentActivationPolicy.evaluate(precedents, defaultThreshold())).isEmpty();

        // Add one more finding
        var more = List.of(
                precedent(0.5, Map.of("security-review", FINDINGS)),
                precedent(0.5, Map.of("security-review", FINDINGS)),
                precedent(0.5, Map.of("security-review", FINDINGS)),
                precedent(0.5, Map.of("security-review", FINDINGS)),
                precedent(0.5, Map.of("security-review", APPROVED))
                          );
        // evidence = 2.0, totalWeight = 2.5, fraction = 2.0/2.5 = 0.8 >= 0.4 ✅
        // evidence 2.0 >= 2.0 → activated
        assertThat(PrecedentActivationPolicy.evaluate(more, defaultThreshold()))
                .containsExactly("security-review");
    }

    @Test
    void weightedAccumulation_mixedSimilarityScores() {
        var precedents = List.of(
                precedent(0.9, Map.of("security-review", FINDINGS)),
                precedent(0.8, Map.of("security-review", FINDINGS)),
                precedent(0.7, Map.of("security-review", FINDINGS)),
                precedent(0.3, Map.of("security-review", APPROVED)),
                precedent(0.3, Map.of("security-review", APPROVED))
                                );
        // evidence = 0.9 + 0.8 + 0.7 = 2.4, totalWeight = 3.0
        // fraction = 2.4/3.0 = 0.8 >= 0.4 ✅, evidence 2.4 >= 2.0 ✅
        assertThat(PrecedentActivationPolicy.evaluate(precedents, defaultThreshold()))
                .containsExactly("security-review");
    }

    @Test
    void weightedAccumulation_fractionUsesTotalWeight() {
        var precedents = List.of(
                precedent(1.0, Map.of("security-review", FINDINGS)),
                precedent(1.0, Map.of("security-review", FINDINGS)),
                precedent(1.0, Map.of("security-review", APPROVED)),
                precedent(1.0, Map.of("security-review", APPROVED)),
                precedent(1.0, Map.of("security-review", APPROVED)),
                precedent(1.0, Map.of("security-review", APPROVED)),
                precedent(1.0, Map.of("security-review", APPROVED))
                                );
        // evidence = 2.0, totalWeight = 7.0
        // fraction = 2.0/7.0 ≈ 0.286 < 0.4 → NOT activated despite meeting minEvidence
        assertThat(PrecedentActivationPolicy.evaluate(precedents, defaultThreshold())).isEmpty();
    }
}
