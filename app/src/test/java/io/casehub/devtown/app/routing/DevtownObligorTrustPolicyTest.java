package io.casehub.devtown.app.routing;

import io.casehub.devtown.domain.preferences.DoublePreference;
import io.casehub.devtown.domain.trust.TrustGatePreferenceKeys;
import io.casehub.ledger.api.model.ActorTrustScore.ScoreType;
import io.casehub.ledger.runtime.model.ActorTrustScore;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.preferences.MapPreferences;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.qhorus.api.spi.ObligorTrustContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DevtownObligorTrustPolicyTest {

    // Qualified map key: "casehubio.devtown.trust-gate.min-obligor-trust"
    private static final String PREF_KEY = TrustGatePreferenceKeys.MIN_OBLIGOR_TRUST.qualifiedName();

    /** Returns null for all preference keys — exercises the null-guard path (floor = 0.0 → disabled). */
    private static final PreferenceProvider EMPTY_PROVIDER = scope -> new MapPreferences(Map.of());

    /** Threshold explicitly set to 0.0 — gate disabled. */
    private static final PreferenceProvider GATE_DISABLED = scope ->
        new MapPreferences(Map.of(PREF_KEY, "0.0"));

    /** Threshold set to 0.30. */
    private static final PreferenceProvider GATE_030 = scope ->
        new MapPreferences(Map.of(PREF_KEY, "0.30"));

    /**
     * Stub repository — none of its methods should be called because the test
     * overrides {@code currentScore()} in the {@link TrustGateService} subclass.
     */
    private static final ActorTrustScoreRepository STUB_REPO = new ActorTrustScoreRepository() {
        @Override public Optional<ActorTrustScore> findByActorId(String actorId) { throw new UnsupportedOperationException(); }
        @Override public Optional<ActorTrustScore> findCapabilityScore(String actorId, String capabilityTag) { throw new UnsupportedOperationException(); }
        @Override public Optional<ActorTrustScore> findDimensionScore(String actorId, String dimension) { throw new UnsupportedOperationException(); }
        @Override public Optional<ActorTrustScore> findCapabilityDimension(String actorId, String capabilityTag, String dimension) { throw new UnsupportedOperationException(); }
        @Override public List<ActorTrustScore> findCapabilityDimensions(String actorId, String capabilityTag) { throw new UnsupportedOperationException(); }
        @Override public List<ActorTrustScore> findByActorIdAndScoreType(String actorId, ScoreType scoreType) { throw new UnsupportedOperationException(); }
        @Override public void upsert(String actorId, ScoreType scoreType, String capabilityKey, String dimensionKey, ActorType actorType, double trustScore, int decisionCount, int overturnedCount, double alpha, double beta, int attestationPositive, int attestationNegative, Instant lastComputedAt) { throw new UnsupportedOperationException(); }
        @Override public void updateGlobalTrustScore(String actorId, double globalTrustScore) { throw new UnsupportedOperationException(); }
        @Override public List<ActorTrustScore> findAll() { throw new UnsupportedOperationException(); }
        @Override public List<ActorTrustScore> findAllByLastComputedAtAfter(Instant since) { throw new UnsupportedOperationException(); }
    };

    /** Creates a TrustGateService subclass that overrides currentScore() to return a fixed value. */
    private static TrustGateService fixedScoreService(Optional<Double> score) {
        return new TrustGateService(STUB_REPO) {
            @Override
            public Optional<Double> currentScore(String actorId) {
                return score;
            }
        };
    }

    private static ObligorTrustContext ctx() {
        return new ObligorTrustContext("agent-1", UUID.randomUUID(), "code-analysis");
    }

    // ===== Test cases =====

    /**
     * EMPTY_PROVIDER returns null for the threshold key.
     * Null-guard path: treat as 0.0 → gate disabled → always permit.
     */
    @Test
    void permitsWhenThresholdPrefNull_nullGuardPath() {
        var policy = new DevtownObligorTrustPolicy(EMPTY_PROVIDER, fixedScoreService(Optional.of(0.05)));
        assertThat(policy.permits(ctx())).isTrue();
    }

    /**
     * Threshold explicitly set to 0.0 — gate disabled → always permit regardless of score.
     */
    @Test
    void permitsWhenThresholdExplicitZero_explicitDisabledPath() {
        var policy = new DevtownObligorTrustPolicy(GATE_DISABLED, fixedScoreService(Optional.of(0.05)));
        assertThat(policy.permits(ctx())).isTrue();
    }

    /**
     * Bootstrap agent: no ledger observations yet (score = empty).
     * Gate at 0.30 → must permit (bootstrap path).
     */
    @Test
    void permitsBootstrapAgent_noLedgerObservations() {
        var policy = new DevtownObligorTrustPolicy(GATE_030, fixedScoreService(Optional.empty()));
        assertThat(policy.permits(ctx())).isTrue();
    }

    /**
     * Score (0.50) strictly above threshold (0.30) → must permit.
     */
    @Test
    void permitsWhenScoreAboveFloor() {
        var policy = new DevtownObligorTrustPolicy(GATE_030, fixedScoreService(Optional.of(0.50)));
        assertThat(policy.permits(ctx())).isTrue();
    }

    /**
     * Score (0.30) exactly at threshold (0.30) → must permit (boundary inclusive).
     */
    @Test
    void permitsWhenScoreAtFloor() {
        var policy = new DevtownObligorTrustPolicy(GATE_030, fixedScoreService(Optional.of(0.30)));
        assertThat(policy.permits(ctx())).isTrue();
    }

    /**
     * Score (0.20) below threshold (0.30) → must deny.
     */
    @Test
    void deniesWhenScoreBelowFloor() {
        var policy = new DevtownObligorTrustPolicy(GATE_030, fixedScoreService(Optional.of(0.20)));
        assertThat(policy.permits(ctx())).isFalse();
    }
}
