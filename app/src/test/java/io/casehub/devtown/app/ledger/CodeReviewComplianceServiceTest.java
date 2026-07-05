package io.casehub.devtown.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.blocks.routing.RequirementStatus;
import io.casehub.devtown.review.compliance.CodeReviewComplianceEvidence;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.casehub.ledger.model.WorkerDecisionEntry;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.api.model.supplement.ComplianceSupplement;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

@QuarkusTest
@TestProfile(LedgerEnabledTestProfile.class)
@TestSecurity(user = "devtown-admin", roles = {"devtown-admin"})
class CodeReviewComplianceServiceTest {

    @Inject CodeReviewComplianceService complianceService;
    @Inject LedgerEntryRepository ledgerRepo;
    @Inject CurrentPrincipal principal;

    @Test
    void fullCase_allRequirementsClosed() {
        UUID caseId = UUID.randomUUID();
        String tenancyId = principal.tenancyId();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // 1. Seed a CaseLedgerEntry (COMPLETED)
        CaseLedgerEntry cle = new CaseLedgerEntry();
        cle.subjectId = caseId;
        cle.caseId = caseId;
        cle.tenancyId = tenancyId;
        cle.entryType = LedgerEntryType.EVENT;
        cle.eventType = "CASE_COMPLETED";
        cle.caseStatus = "COMPLETED";
        cle.actorId = "system";
        cle.actorType = ActorType.SYSTEM;
        cle.actorRole = "ORCHESTRATOR";
        cle.occurredAt = now;
        saveLedgerEntry(cle);

        // 2. Seed a WorkerDecisionEntry
        WorkerDecisionEntry wde = new WorkerDecisionEntry();
        wde.subjectId = caseId;
        wde.caseId = caseId;
        wde.tenancyId = tenancyId;
        wde.entryType = LedgerEntryType.EVENT;
        wde.workerId = "agent-1";
        wde.capabilityTag = "security-review";
        wde.trustScoreAtRouting = 0.85;
        wde.thresholdApplied = 0.70;
        wde.actorId = "system";
        wde.actorType = ActorType.SYSTEM;
        wde.actorRole = "ROUTER";
        wde.occurredAt = now.plusMillis(100);
        saveLedgerEntry(wde);

        // 3. Seed a MergeDecisionLedgerEntry (APPROVED, with ComplianceSupplement, causal link)
        MergeDecisionLedgerEntry mde = new MergeDecisionLedgerEntry();
        mde.subjectId = caseId;
        mde.caseId = caseId;
        mde.tenancyId = tenancyId;
        mde.entryType = LedgerEntryType.EVENT;
        mde.prNumber = 4300;
        mde.repository = "casehubio/devtown";
        mde.commitSha = "abc123def";
        mde.decision = "APPROVED";
        mde.actorId = "system";
        mde.actorType = ActorType.SYSTEM;
        mde.actorRole = "ORCHESTRATOR";
        mde.occurredAt = now.plusMillis(200);
        mde.causedByEntryId = cle.id;

        DevtownComplianceSupplement cs = new DevtownComplianceSupplement();
        cs.algorithmRef = "casehub-devtown:pr-review-v1";
        cs.humanOverrideAvailable = true;
        cs.contestationUri = "/api/reviews/4300/contest";
        mde.attach(cs);
        saveLedgerEntry(mde);

        // Query the compliance evidence
        Optional<CodeReviewComplianceEvidence> result =
                QuarkusTransaction.requiringNew().call(() -> complianceService.findEvidence(caseId, tenancyId));

        assertThat(result).isPresent();
        CodeReviewComplianceEvidence evidence = result.get();
        assertThat(evidence.caseId()).isEqualTo(caseId);
        assertThat(evidence.generatedAt()).isNotNull();

        // Audit chain: 3 entries, chain verified, CLOSED
        assertThat(evidence.auditChain().events()).hasSize(3);
        assertThat(evidence.auditChain().chainVerified()).isTrue();
        assertThat(evidence.auditChain().treeRoot()).isNotNull();
        assertThat(evidence.auditChain().status()).isEqualTo(RequirementStatus.CLOSED);

        // Trust routing: 1 decision, CLOSED
        assertThat(evidence.trustRouting().status()).isEqualTo(RequirementStatus.CLOSED);
        assertThat(evidence.trustRouting().decisions()).hasSize(1);
        assertThat(evidence.trustRouting().decisions().get(0).capabilityTag())
                .isEqualTo("security-review");
        assertThat(evidence.trustRouting().decisions().get(0).workerId())
                .isEqualTo("agent-1");
        assertThat(evidence.trustRouting().decisions().get(0).trustScoreAtRouting())
                .isEqualTo(0.85);

        // GDPR: capability wired, no erasure performed
        assertThat(evidence.gdpr().status()).isEqualTo(RequirementStatus.CLOSED);
        assertThat(evidence.gdpr().erasureCapabilityWired()).isTrue();
        assertThat(evidence.gdpr().pseudonymisationActive()).isTrue();
        assertThat(evidence.gdpr().erasurePerformed()).isFalse();
        assertThat(evidence.gdpr().erasureReceiptIds()).isEmpty();

        // Review SLA: GAP in Layer 4
        assertThat(evidence.reviewSla().status()).isEqualTo(RequirementStatus.GAP);
    }

    @Test
    void emptyCaseId_returnsEmpty() {
        UUID unknownCaseId = UUID.randomUUID();

        Optional<CodeReviewComplianceEvidence> result =
                QuarkusTransaction.requiringNew().call(() -> complianceService.findEvidence(unknownCaseId, principal.tenancyId()));

        assertThat(result).isEmpty();
    }

    @Test
    void restEndpoint_returnsEvidence() {
        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Seed a MergeDecisionLedgerEntry so evidence is non-empty
        MergeDecisionLedgerEntry mde = new MergeDecisionLedgerEntry();
        mde.subjectId = caseId;
        mde.caseId = caseId;
        mde.tenancyId = principal.tenancyId();
        mde.entryType = LedgerEntryType.EVENT;
        mde.prNumber = 99;
        mde.repository = "casehubio/engine";
        mde.commitSha = "def456";
        mde.decision = "APPROVED";
        mde.actorId = "system";
        mde.actorType = ActorType.SYSTEM;
        mde.actorRole = "ORCHESTRATOR";
        mde.occurredAt = now;
        saveLedgerEntry(mde);

        given()
            .when()
                .get("/api/compliance/code-review/" + caseId)
            .then()
                .statusCode(200)
                .body("caseId", org.hamcrest.Matchers.equalTo(caseId.toString()));
    }

    @Test
    void restEndpoint_returns404ForUnknownCase() {
        UUID unknownCaseId = UUID.randomUUID();

        given()
            .when()
                .get("/api/compliance/code-review/" + unknownCaseId)
            .then()
                .statusCode(404);
    }

    private <T extends LedgerEntry> T saveLedgerEntry(T entry) {
        return QuarkusTransaction.requiringNew().call(() -> {
            ledgerRepo.save(entry, principal.tenancyId());
            return entry;
        });
    }
}
