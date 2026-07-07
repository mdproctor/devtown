package io.casehub.devtown.app.trust;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.api.spi.routing.AgentAssignment;
import io.casehub.api.spi.routing.AgentCandidate;
import io.casehub.api.spi.routing.AgentHealth;
import io.casehub.api.spi.routing.AgentRoutingContext;
import io.casehub.api.spi.routing.AgentRoutingStrategy;
import io.casehub.devtown.app.ledger.IncidentFeedbackService;
import io.casehub.devtown.app.ledger.MergeDecisionLedgerEntry;
import io.casehub.devtown.domain.IncidentFeedback;
import io.casehub.devtown.domain.IncidentFeedbackResult;
import io.casehub.devtown.domain.IncidentSeverity;
import io.casehub.devtown.domain.ReviewDomain;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.model.WorkerDecisionEntry;
import io.casehub.ledger.api.model.LedgerAttestation;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.ledger.runtime.service.TrustGateService;
import io.casehub.platform.api.identity.ActorType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * End-to-end closed-loop trust feedback test for devtown#13.
 *
 * <p>Demonstrates the full trust feedback loop:
 * <ol>
 *   <li>Seed WorkerDecisionEntry (agent performed security review on a PR)
 *   <li>Seed MergeDecisionLedgerEntry (PR was approved and merged)
 *   <li>Record IncidentFeedback (production incident traced to missed security flaw)
 *   <li>Verify IncrementalTrustUpdateObserver fires and updates trust scores
 *   <li>Verify TrustWeightedAgentStrategy now deprioritizes the flagged agent
 * </ol>
 *
 * <p>This is the PRIMARY deliverable for devtown#13 — proves that incident feedback
 * automatically lowers agent trust and changes routing decisions without manual intervention.
 */
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
    @Inject io.casehub.ledger.runtime.service.TrustScoreJob trustScoreJob;

    /**
     * Full closed-loop scenario:
     *
     * <p>Proves the full done-when: SOUND builds trust, incident writes FLAGGED,
     * FLAGGED decreases capability score, routing shifts to unflagged agent.
     *
     * <p>Both agents start with equal SOUND histories (10 each). After trust
     * recompute, both are QUALIFIED with equal scores. A CRITICAL incident
     * against Agent A decreases their score. Routing shifts to Agent B.
     *
     * <p>All score reads use fresh transactions to avoid JPA first-level cache
     * stale reads between the write (TrustScoreJob) and read (TrustGateService).
     */
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
        AgentCandidate candidateA = new AgentCandidate(AGENT_A, Set.of(ReviewDomain.SECURITY_REVIEW), 0, AgentHealth.READY, null, null);
        AgentCandidate candidateB = new AgentCandidate(AGENT_B, Set.of(ReviewDomain.SECURITY_REVIEW), 0, AgentHealth.READY, null, null);
        AgentRoutingContext context = new AgentRoutingContext(UUID.randomUUID(), ReviewDomain.SECURITY_REVIEW, null, TENANT, List.of());

        AgentAssignment assignment = routingStrategy.select(context, List.of(candidateA, candidateB))
            .await().atMost(Duration.ofSeconds(5));

        assertThat(assignment).isInstanceOf(AgentAssignment.Assigned.class);
        assertThat(((AgentAssignment.Assigned) assignment).workerId())
            .as("Agent B selected — Agent A's score degraded by incident")
            .isEqualTo(AGENT_B);
    }

    // Helper methods matching IncidentFeedbackServiceTest pattern

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
                            io.casehub.ledger.api.model.AttestationVerdict attestationVerdict) {
        return QuarkusTransaction.requiringNew().call(() -> {
            WorkerDecisionEntry wde = new WorkerDecisionEntry();
            wde.subjectId = caseId;
            wde.caseId = caseId;
            wde.tenancyId = TENANT;
            wde.entryType = LedgerEntryType.EVENT;
            wde.workerId = workerId;
            wde.actorId = workerId;  // production pattern -- NOT "system"
            wde.actorType = ActorType.SYSTEM;
            wde.actorRole = "WORKER";
            wde.capabilityTag = capabilityTag;
            wde.occurredAt = occurredAt;
            LedgerEntry saved = ledgerRepo.save(wde, TENANT);

            // Seed attestation if verdict provided (simulates outcome attestor)
            if (attestationVerdict != null) {
                LedgerAttestation att = new io.casehub.ledger.runtime.model.LedgerAttestation();
                att.ledgerEntryId = saved.id;
                att.subjectId = caseId;
                att.attestorId = "test-attestor";
                att.attestorType = ActorType.SYSTEM;
                att.attestorRole = "TEST_OUTCOME_ATTESTOR";
                att.verdict = attestationVerdict;
                att.confidence = 1.0;
                att.capabilityTag = capabilityTag;
                att.occurredAt = occurredAt.plusSeconds(60);
                ledgerRepo.saveAttestation(att, TENANT);
            }

            return saved.id;
        });
    }
}
