package io.casehub.devtown.app.trust;

import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.api.spi.routing.RoutingResult;
import io.casehub.devtown.app.ledger.IncidentFeedbackService;
import io.casehub.devtown.app.ledger.MergeDecisionLedgerEntry;
import io.casehub.devtown.domain.IncidentFeedback;
import io.casehub.devtown.domain.IncidentFeedbackResult;
import io.casehub.devtown.domain.IncidentSeverity;
import io.casehub.devtown.domain.ReviewDomain;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.ledger.core.trust.TrustGateService;
import io.casehub.ledger.model.WorkerDecisionEntry;
import io.casehub.ledger.runtime.service.TrustScoreJob;
import io.casehub.platform.api.identity.ActorType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(TrustScoringTestProfile.class)
@TestSecurity(user = "devtown-admin", roles = {"devtown-admin"})
class TrustFeedbackClosedLoopTest {

    private static final String TENANT = "test-tenant";
    private static final String REPO = "casehubio/devtown";
    private static final String AGENT_A = "claude:security-a@v1";
    private static final String AGENT_B = "claude:security-b@v1";

    @Inject IncidentFeedbackService incidentFeedbackService;
    @Inject LedgerEntryRepository ledgerRepo;
    @Inject TrustGateService trustGateService;
    @Inject AgentRoutingStrategy routingStrategy;
    @Inject TrustScoreJob trustScoreJob;


    @Inject
    @io.casehub.ledger.jpa.LedgerPersistenceUnit
    jakarta.persistence.EntityManager em;

    @org.junit.jupiter.api.BeforeEach
    void cleanDatabase() {
        QuarkusTransaction.requiringNew().run(() -> {
            em.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
            em.createNativeQuery("TRUNCATE TABLE ledger_attestation").executeUpdate();
            em.createNativeQuery("TRUNCATE TABLE worker_decision_entry").executeUpdate();
            em.createNativeQuery("TRUNCATE TABLE merge_decision_ledger_entry").executeUpdate();
            em.createNativeQuery("TRUNCATE TABLE ledger_entry").executeUpdate();
            em.createNativeQuery("TRUNCATE TABLE actor_trust_score").executeUpdate();
            em.createNativeQuery("TRUNCATE TABLE trust_score_snapshot").executeUpdate();
            em.createNativeQuery("SET REFERENTIAL_INTEGRITY TRUE").executeUpdate();
        });
    }

    @Test
    void closedLoop_soundBuildsTrust_incidentDegradesTrust_routingShifts() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Phase 1: Equal positive history — both agents get 10 SOUND attestations
        for (int i = 0; i < 10; i++) {
            UUID caseId = UUID.randomUUID();
            seedMergeDecision(caseId, REPO, 700 + i, "APPROVED", now.plusSeconds(i * 10));
            seedWorkerDecision(caseId, AGENT_A, ReviewDomain.SECURITY_REVIEW,
                now.plusSeconds(i * 10).plusMillis(100), AttestationVerdict.SOUND);
        }
        for (int i = 0; i < 10; i++) {
            UUID caseId = UUID.randomUUID();
            seedMergeDecision(caseId, REPO, 800 + i, "APPROVED", now.plusSeconds(200 + i * 10));
            seedWorkerDecision(caseId, AGENT_B, ReviewDomain.SECURITY_REVIEW,
                now.plusSeconds(200 + i * 10).plusMillis(100), AttestationVerdict.SOUND);
        }

        // Phase 2: Trust scores materialized from SOUND attestations
        QuarkusTransaction.requiringNew().run(() -> trustScoreJob.runComputation());

        OptionalDouble scoreABefore = QuarkusTransaction.requiringNew().call(
            () -> trustGateService.currentScore(AGENT_A, ReviewDomain.SECURITY_REVIEW));
        OptionalDouble scoreBBefore = QuarkusTransaction.requiringNew().call(
            () -> trustGateService.currentScore(AGENT_B, ReviewDomain.SECURITY_REVIEW));
        assertThat(scoreABefore).as("Agent A must have a capability score").isPresent();
        assertThat(scoreBBefore).as("Agent B must have a capability score").isPresent();

        // Phase 3: Incident feedback — FLAGGED attestation against Agent A
        IncidentFeedback feedback = new IncidentFeedback(
            REPO, 700, "INC-2025-001", IncidentSeverity.CRITICAL,
            "SQL injection missed during security review",
            ReviewDomain.SECURITY_REVIEW, null);
        IncidentFeedbackResult result = incidentFeedbackService.recordFeedback(feedback);
        assertThat(result.attestationsWritten()).isEqualTo(1);
        assertThat(result.flaggedAgents().get(0).agentId()).isEqualTo(AGENT_A);

        // Phase 4: Recompute — FLAGGED decreases Agent A's score
        QuarkusTransaction.requiringNew().run(() -> trustScoreJob.runComputation());

        OptionalDouble scoreAAfter = QuarkusTransaction.requiringNew().call(
            () -> trustGateService.currentScore(AGENT_A, ReviewDomain.SECURITY_REVIEW));
        assertThat(scoreAAfter).isPresent();
        assertThat(scoreAAfter.getAsDouble())
            .as("Agent A's score must decrease after FLAGGED incident (was %s)", scoreABefore.getAsDouble())
            .isLessThan(scoreABefore.getAsDouble());

        // Agent B unchanged
        OptionalDouble scoreBAfter = QuarkusTransaction.requiringNew().call(
            () -> trustGateService.currentScore(AGENT_B, ReviewDomain.SECURITY_REVIEW));
        assertThat(scoreBAfter).isPresent();
        assertThat(scoreBAfter.getAsDouble()).isEqualTo(scoreBBefore.getAsDouble());

        // Phase 5: Routing shift — Agent B wins (Agent A degraded)
        AgentCandidate candidateA = new AgentCandidate(AGENT_A, Set.of(ReviewDomain.SECURITY_REVIEW), 0, AgentHealth.READY, null, null, Map.of());
        AgentCandidate candidateB = new AgentCandidate(AGENT_B, Set.of(ReviewDomain.SECURITY_REVIEW), 0, AgentHealth.READY, null, null, Map.of());
        AgentRoutingContext context = new AgentRoutingContext(UUID.randomUUID(), ReviewDomain.SECURITY_REVIEW, null, TENANT, List.of(), null, Map.of());

        RoutingResult routingResult = routingStrategy.select(context, List.of(candidateA, candidateB));

        assertThat(routingResult).isInstanceOf(RoutingResult.Selected.class);
        assertThat(((RoutingResult.Selected) routingResult).single().executorId())
            .as("Agent B selected — Agent A's score degraded by incident")
            .isEqualTo(AGENT_B);
    }

    void seedMergeDecision(UUID caseId, String repo, int prNumber,
                           String decision, Instant occurredAt) {
        QuarkusTransaction.requiringNew().run(() -> {
            MergeDecisionLedgerEntry mde = new MergeDecisionLedgerEntry();
            mde.subjectId = caseId;
            mde.caseId = caseId;
            mde.tenancyId = TENANT;
            mde.entryType = LedgerEntryType.EVENT;
            mde.prNumber = prNumber;
            mde.repository = repo;
            mde.decision = decision;
            mde.actorId = "system";
            mde.actorType = ActorType.SYSTEM;
            mde.actorRole = "ORCHESTRATOR";
            mde.occurredAt = occurredAt;
            ledgerRepo.save(mde, TENANT);
        });
    }

    UUID seedWorkerDecision(UUID caseId, String workerId,
                            String capabilityTag, Instant occurredAt) {
        return seedWorkerDecision(caseId, workerId, capabilityTag, occurredAt, null);
    }

    UUID seedWorkerDecision(UUID caseId, String workerId,
                            String capabilityTag, Instant occurredAt,
                            AttestationVerdict attestationVerdict) {
        UUID entryId = QuarkusTransaction.requiringNew().call(() -> {
            WorkerDecisionEntry wde = new WorkerDecisionEntry();
            wde.subjectId     = caseId;
            wde.caseId        = caseId;
            wde.tenancyId     = TENANT;
            wde.entryType     = LedgerEntryType.EVENT;
            wde.workerId      = workerId;
            wde.actorId       = workerId;
            wde.actorType     = ActorType.SYSTEM;
            wde.actorRole     = "WORKER";
            wde.capabilityTag = capabilityTag;
            wde.occurredAt    = occurredAt;
            return ledgerRepo.save(wde, TENANT).id;
        });

        if (attestationVerdict != null) {
            QuarkusTransaction.requiringNew().run(() -> {
                io.casehub.ledger.jpa.LedgerAttestation att =
                        new io.casehub.ledger.jpa.LedgerAttestation();
                att.ledgerEntryId = entryId;
                att.subjectId     = caseId;
                att.attestorId    = "test-attestor";
                att.attestorType  = ActorType.SYSTEM;
                att.attestorRole  = "TEST_OUTCOME_ATTESTOR";
                att.verdict       = attestationVerdict;
                att.confidence    = 1.0;
                att.capabilityTag = capabilityTag;
                att.occurredAt    = occurredAt.plusSeconds(60);
                ledgerRepo.saveAttestation(att, TENANT);
            });
        }

        return entryId;}
}
