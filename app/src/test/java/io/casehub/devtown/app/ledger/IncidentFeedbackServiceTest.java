package io.casehub.devtown.app.ledger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.casehub.devtown.domain.FlaggedAgent;
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
import io.casehub.platform.api.identity.ActorType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(LedgerEnabledTestProfile.class)
@TestSecurity(user = "devtown-admin", roles = {"devtown-admin"})
class IncidentFeedbackServiceTest {

    private static final String TENANT = "test-tenant";
    private static final String REPO = "casehubio/devtown";

    @Inject IncidentFeedbackService service;
    @Inject LedgerEntryRepository ledgerRepo;

    @Test
    void happyPath_singleAgent_writesOneAttestation() {
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        seedMergeDecision(caseId, REPO, 4200, "APPROVED", now);
        UUID wdeId = seedWorkerDecision(caseId, "claude:analyst@v1",
                ReviewDomain.SECURITY_REVIEW, now.plusMillis(100));

        IncidentFeedback feedback = new IncidentFeedback(
                REPO, 4200, "INC-789", IncidentSeverity.HIGH,
                "Timing attack in crypto", ReviewDomain.SECURITY_REVIEW, null);

        IncidentFeedbackResult result = service.recordFeedback(feedback);

        assertThat(result.caseId()).isEqualTo(caseId);
        assertThat(result.attestationsWritten()).isEqualTo(1);
        assertThat(result.flaggedAgents()).hasSize(1);

        FlaggedAgent fa = result.flaggedAgents().get(0);
        assertThat(fa.agentId()).isEqualTo("claude:analyst@v1");
        assertThat(fa.capabilityTag()).isEqualTo(ReviewDomain.SECURITY_REVIEW);
        assertThat(fa.attestationId()).isNotNull();

        // Verify attestation fields in the ledger
        List<LedgerAttestation> attestations = findAttestationsForEntry(wdeId);
        assertThat(attestations).hasSize(1);

        LedgerAttestation att = attestations.get(0);
        assertThat(att.verdict).isEqualTo(AttestationVerdict.FLAGGED);
        assertThat(att.confidence).isEqualTo(0.7);
        assertThat(att.capabilityTag).isEqualTo(ReviewDomain.SECURITY_REVIEW);
        assertThat(att.trustDimension).isEqualTo("review-thoroughness");
        assertThat(att.attestorType).isEqualTo(ActorType.SYSTEM);
        assertThat(att.attestorRole).isEqualTo("INCIDENT_FEEDBACK");
        assertThat(att.dimensionScore).isNull();
        assertThat(att.evidence).startsWith("Incident INC-789:");
        assertThat(att.subjectId).isEqualTo(caseId);
    }

    @Test
    void multipleAgents_flagsAll() {
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        seedMergeDecision(caseId, REPO, 4201, "APPROVED", now);
        UUID wde1 = seedWorkerDecision(caseId, "claude:analyst-a@v1",
                ReviewDomain.SECURITY_REVIEW, now.plusMillis(100));
        UUID wde2 = seedWorkerDecision(caseId, "claude:analyst-b@v2",
                ReviewDomain.SECURITY_REVIEW, now.plusMillis(200));

        IncidentFeedback feedback = new IncidentFeedback(
                REPO, 4201, "INC-790", IncidentSeverity.CRITICAL,
                "Multiple agents missed", ReviewDomain.SECURITY_REVIEW, null);

        IncidentFeedbackResult result = service.recordFeedback(feedback);

        assertThat(result.attestationsWritten()).isEqualTo(2);
        assertThat(result.flaggedAgents()).hasSize(2);

        List<LedgerAttestation> att1 = findAttestationsForEntry(wde1);
        List<LedgerAttestation> att2 = findAttestationsForEntry(wde2);
        assertThat(att1).hasSize(1);
        assertThat(att2).hasSize(1);
    }

    @Test
    void unknownPr_throws404() {
        IncidentFeedback feedback = new IncidentFeedback(
                REPO, 9999, "INC-404", IncidentSeverity.MEDIUM,
                "Unknown PR", ReviewDomain.SECURITY_REVIEW, null);

        assertThatThrownBy(() -> service.recordFeedback(feedback))
                .isInstanceOf(WebApplicationException.class)
                .hasMessageContaining("No APPROVED merge decision for")
                .extracting(ex -> ((WebApplicationException) ex).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void noAgentForCapability_throws404() {
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        seedMergeDecision(caseId, REPO, 4202, "APPROVED", now);
        seedWorkerDecision(caseId, "claude:analyst@v1",
                ReviewDomain.CODE_ANALYSIS, now.plusMillis(100));

        IncidentFeedback feedback = new IncidentFeedback(
                REPO, 4202, "INC-404-2", IncidentSeverity.HIGH,
                "Wrong capability", ReviewDomain.SECURITY_REVIEW, null);

        assertThatThrownBy(() -> service.recordFeedback(feedback))
                .isInstanceOf(WebApplicationException.class)
                .hasMessageContaining("No agent performed")
                .extracting(ex -> ((WebApplicationException) ex).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void ambiguousPr_throws409() {
        UUID case1 = UUID.randomUUID();
        UUID case2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        seedMergeDecision(case1, REPO, 4203, "APPROVED", now);
        seedMergeDecision(case2, REPO, 4203, "APPROVED", now.plusMillis(1000));

        IncidentFeedback feedback = new IncidentFeedback(
                REPO, 4203, "INC-409", IncidentSeverity.HIGH,
                "Ambiguous", ReviewDomain.SECURITY_REVIEW, null);

        assertThatThrownBy(() -> service.recordFeedback(feedback))
                .isInstanceOf(WebApplicationException.class)
                .extracting(ex -> ((WebApplicationException) ex).getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void disambiguatedWithCaseId_resolvesCorrectly() {
        UUID case1 = UUID.randomUUID();
        UUID case2 = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        seedMergeDecision(case1, REPO, 4204, "APPROVED", now);
        seedMergeDecision(case2, REPO, 4204, "APPROVED", now.plusMillis(1000));
        seedWorkerDecision(case2, "claude:analyst@v1",
                ReviewDomain.SECURITY_REVIEW, now.plusMillis(1100));

        IncidentFeedback feedback = new IncidentFeedback(
                REPO, 4204, "INC-OK", IncidentSeverity.MEDIUM,
                "Disambiguated", ReviewDomain.SECURITY_REVIEW, case2);

        IncidentFeedbackResult result = service.recordFeedback(feedback);

        assertThat(result.caseId()).isEqualTo(case2);
        assertThat(result.attestationsWritten()).isEqualTo(1);
    }

    @Test
    void caseIdProvided_noApprovedDecision_throws404() {
        UUID randomCaseId = UUID.randomUUID();

        IncidentFeedback feedback = new IncidentFeedback(
                REPO, 4205, "INC-BAD-CASE", IncidentSeverity.HIGH,
                "Bad caseId", ReviewDomain.SECURITY_REVIEW, randomCaseId);

        assertThatThrownBy(() -> service.recordFeedback(feedback))
                .isInstanceOf(WebApplicationException.class)
                .hasMessageContaining("No APPROVED merge decision for caseId")
                .extracting(ex -> ((WebApplicationException) ex).getResponse().getStatus())
                .isEqualTo(404);
    }

    @Test
    void invalidCapability_throws400() {
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        seedMergeDecision(caseId, REPO, 4206, "APPROVED", now);

        IncidentFeedback feedback = new IncidentFeedback(
                REPO, 4206, "INC-400", IncidentSeverity.HIGH,
                "Invalid cap", "made-up-capability", null);

        assertThatThrownBy(() -> service.recordFeedback(feedback))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown review capability");
    }

    @Test
    void nonReviewCapability_throws400() {
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        seedMergeDecision(caseId, REPO, 4207, "APPROVED", now);

        IncidentFeedback feedback = new IncidentFeedback(
                REPO, 4207, "INC-400-2", IncidentSeverity.MEDIUM,
                "Non-review", "ci-runner", null);

        assertThatThrownBy(() -> service.recordFeedback(feedback))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Unknown review capability");
    }

    @Test
    void idempotent_sameIncidentTwice_noNewAttestations() {
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        seedMergeDecision(caseId, REPO, 4208, "APPROVED", now);
        seedWorkerDecision(caseId, "claude:analyst@v1",
                ReviewDomain.SECURITY_REVIEW, now.plusMillis(100));

        IncidentFeedback feedback = new IncidentFeedback(
                REPO, 4208, "INC-IDEM", IncidentSeverity.HIGH,
                "Idempotent", ReviewDomain.SECURITY_REVIEW, null);

        IncidentFeedbackResult result1 = service.recordFeedback(feedback);
        assertThat(result1.attestationsWritten()).isEqualTo(1);
        UUID firstAttestationId = result1.flaggedAgents().get(0).attestationId();

        IncidentFeedbackResult result2 = service.recordFeedback(feedback);
        assertThat(result2.attestationsWritten()).isEqualTo(0);
        assertThat(result2.flaggedAgents()).hasSize(1);
        assertThat(result2.flaggedAgents().get(0).attestationId()).isEqualTo(firstAttestationId);
    }

    @Test
    void differentIncidents_sameEntry_bothRecorded() {
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        seedMergeDecision(caseId, REPO, 4209, "APPROVED", now);
        UUID wdeId = seedWorkerDecision(caseId, "claude:analyst@v1",
                ReviewDomain.SECURITY_REVIEW, now.plusMillis(100));

        IncidentFeedback feedback1 = new IncidentFeedback(
                REPO, 4209, "INC-ONE", IncidentSeverity.HIGH,
                "First incident", ReviewDomain.SECURITY_REVIEW, null);
        IncidentFeedback feedback2 = new IncidentFeedback(
                REPO, 4209, "INC-TWO", IncidentSeverity.CRITICAL,
                "Second incident", ReviewDomain.SECURITY_REVIEW, null);

        IncidentFeedbackResult result1 = service.recordFeedback(feedback1);
        IncidentFeedbackResult result2 = service.recordFeedback(feedback2);

        assertThat(result1.attestationsWritten()).isEqualTo(1);
        assertThat(result2.attestationsWritten()).isEqualTo(1);

        List<LedgerAttestation> attestations = findAttestationsForEntry(wdeId);
        assertThat(attestations).hasSize(2);
    }

    @Test
    void eachSeverity_correctConfidence() {
        IncidentSeverity[] severities = IncidentSeverity.values();
        int prBase = 4210;

        for (int i = 0; i < severities.length; i++) {
            UUID caseId = UUID.randomUUID();
            int prNumber = prBase + i;
            Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            seedMergeDecision(caseId, REPO, prNumber, "APPROVED", now);
            UUID wdeId = seedWorkerDecision(caseId, "claude:analyst@v1",
                    ReviewDomain.SECURITY_REVIEW, now.plusMillis(100));

            IncidentFeedback feedback = new IncidentFeedback(
                    REPO, prNumber, "INC-SEV-" + i, severities[i],
                    "Severity test", ReviewDomain.SECURITY_REVIEW, null);

            service.recordFeedback(feedback);

            List<LedgerAttestation> attestations = findAttestationsForEntry(wdeId);
            assertThat(attestations).hasSize(1);

            double expectedConfidence = switch (severities[i]) {
                case CRITICAL -> 0.9;
                case HIGH -> 0.7;
                case MEDIUM -> 0.5;
                case LOW -> 0.3;
            };

            assertThat(attestations.get(0).confidence).isEqualTo(expectedConfidence);
        }
    }

    @Test
    void restEndpoint_happyPath() {
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        seedMergeDecision(caseId, REPO, 4220, "APPROVED", now);
        seedWorkerDecision(caseId, "claude:analyst@v1", ReviewDomain.CODE_ANALYSIS, now.plusMillis(100));

        given()
            .contentType("application/json")
            .body("""
                {
                  "repository": "casehubio/devtown",
                  "prNumber": 4220,
                  "incidentId": "INC-REST-1",
                  "severity": "HIGH",
                  "description": "REST test",
                  "reviewCapability": "code-analysis"
                }
                """)
        .when()
            .post("/api/incident-feedback")
        .then()
            .statusCode(200)
            .body("attestationsWritten", org.hamcrest.Matchers.equalTo(1))
            .body("flaggedAgents.size()", org.hamcrest.Matchers.equalTo(1))
            .body("flaggedAgents[0].agentId", org.hamcrest.Matchers.equalTo("claude:analyst@v1"));
    }

    @Test
    void restEndpoint_unknownPr_returns404() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "repository": "casehubio/nonexistent",
                  "prNumber": 999,
                  "incidentId": "INC-REST-2",
                  "severity": "LOW",
                  "description": "not found",
                  "reviewCapability": "code-analysis"
                }
                """)
        .when()
            .post("/api/incident-feedback")
        .then()
            .statusCode(404);
    }

    @Test
    void restEndpoint_invalidCapability_returns400() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "repository": "casehubio/devtown",
                  "prNumber": 1,
                  "incidentId": "INC-REST-3",
                  "severity": "LOW",
                  "description": "invalid",
                  "reviewCapability": "ci-runner"
                }
                """)
        .when()
            .post("/api/incident-feedback")
        .then()
            .statusCode(400);
    }

    // --- Test data seeding helpers ---

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
            return saved.id;
        });
    }

    List<LedgerAttestation> findAttestationsForEntry(UUID entryId) {
        return QuarkusTransaction.requiringNew().call(
                () -> ledgerRepo.findAttestationsByEntryId(entryId, TENANT));
    }
}
