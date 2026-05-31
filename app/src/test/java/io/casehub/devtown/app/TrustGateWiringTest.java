package io.casehub.devtown.app;

import io.casehub.devtown.app.routing.DevtownObligorTrustPolicy;
import io.casehub.ledger.api.model.ActorTrustScore.ScoreType;
import io.casehub.ledger.runtime.repository.ActorTrustScoreRepository;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.spi.ObligorTrustContext;
import io.casehub.qhorus.api.spi.ObligorTrustPolicy;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class TrustGateWiringTest {

    @Inject ObligorTrustPolicy trustPolicy;
    @Inject TrustGateService trustGateService;
    @Inject ActorTrustScoreRepository trustScoreRepository;

    private static ObligorTrustContext ctx(String agentId) {
        return new ObligorTrustContext(agentId, UUID.randomUUID(), "pr-review-1/work");
    }

    /** Seed a GLOBAL trust score row for an agent. Uses upsert so the row is actually created. */
    private void seedGlobalScore(String agentId, double score) {
        // upsert is @Transactional — commits before returning; TrustGateService.currentScore
        // reads trustScore from the GLOBAL row via findByActorId.
        trustScoreRepository.upsert(
            agentId, ScoreType.GLOBAL,
            /*capabilityKey*/ null, /*dimensionKey*/ null,
            ActorType.AGENT, score,
            /*decisionCount*/ 1, /*overturnedCount*/ 0,
            /*alpha*/ 1.15, /*beta*/ 5.85,
            /*attestationPositive*/ 0, /*attestationNegative*/ 0,
            Instant.now());
    }

    @Test
    void devtownPolicyIsActiveBean() {
        assertThat(trustPolicy).isInstanceOf(DevtownObligorTrustPolicy.class);
    }

    @Test
    void permitsBootstrapAgent_noScoreRows() {
        String agentId = "bootstrap-agent-" + UUID.randomUUID();
        assertThat(trustPolicy.permits(ctx(agentId))).isTrue();
    }

    @Test
    void deniesNonBootstrapAgentBelowFloor() {
        // Seed a score of 0.15, below the configured floor of 0.30.
        // upsert creates the GLOBAL row so currentScore returns 0.15 → policy denies.
        String agentId = "low-trust-agent-" + UUID.randomUUID();
        seedGlobalScore(agentId, 0.15);
        assertThat(trustPolicy.permits(ctx(agentId))).isFalse();
    }

    @Test
    void permitsAgentWithScoreAtFloor() {
        // At the boundary: score == floor (0.30 == 0.30) must permit (inclusive).
        String agentId = "at-floor-agent-" + UUID.randomUUID();
        seedGlobalScore(agentId, 0.30);
        assertThat(trustPolicy.permits(ctx(agentId))).isTrue();
    }

    @Test
    void currentScoreReturnsEmptyForUnseenAgent() {
        String agentId = "unseen-agent-" + UUID.randomUUID();
        Optional<Double> score = trustGateService.currentScore(agentId);
        assertThat(score).isEmpty();
    }
}
