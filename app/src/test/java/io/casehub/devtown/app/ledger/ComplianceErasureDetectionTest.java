package io.casehub.devtown.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.devtown.review.compliance.CodeReviewComplianceEvidence;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

@QuarkusTest
@TestProfile(TokenisationEnabledTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ComplianceErasureDetectionTest {

    @Inject CodeReviewComplianceService complianceService;
    @Inject LedgerEntryRepository ledgerRepo;
    @Inject GdprErasureService erasureService;
    @Inject CurrentPrincipal principal;

    @Test
    @Order(2)
    void gdprRequirement_detectsErasedActor() {
        final UUID caseId = UUID.randomUUID();
        final String tenancyId = principal.tenancyId();
        final String humanActorId = "erasure-detect-reviewer-" + UUID.randomUUID();

        QuarkusTransaction.requiringNew().run(() -> {
            CaseLedgerEntry cle = new CaseLedgerEntry();
            cle.subjectId = caseId;
            cle.caseId = caseId;
            cle.entryType = LedgerEntryType.EVENT;
            cle.eventType = "REVIEW_COMPLETED";
            cle.caseStatus = "IN_PROGRESS";
            cle.actorId = humanActorId;
            cle.actorType = ActorType.HUMAN;
            cle.actorRole = "REVIEWER";
            cle.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            ledgerRepo.save(cle, tenancyId);
        });

        erasureService.erase(humanActorId, tenancyId, "GDPR test");

        Optional<CodeReviewComplianceEvidence> result =
                QuarkusTransaction.requiringNew().call(
                        () -> complianceService.findEvidence(caseId, tenancyId));

        assertThat(result).isPresent();
        assertThat(result.get().gdpr().erasurePerformed()).isTrue();
        assertThat(result.get().gdpr().erasureReceiptIds()).isNotEmpty();
    }

    @Test
    @Order(1)
    void gdprRequirement_noErasure_showsFalse() {
        final UUID caseId = UUID.randomUUID();
        final String tenancyId = principal.tenancyId();

        QuarkusTransaction.requiringNew().run(() -> {
            CaseLedgerEntry cle = new CaseLedgerEntry();
            cle.subjectId = caseId;
            cle.caseId = caseId;
            cle.entryType = LedgerEntryType.EVENT;
            cle.eventType = "REVIEW_COMPLETED";
            cle.caseStatus = "IN_PROGRESS";
            cle.actorId = "system";
            cle.actorType = ActorType.SYSTEM;
            cle.actorRole = "ORCHESTRATOR";
            cle.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            ledgerRepo.save(cle, tenancyId);
        });

        Optional<CodeReviewComplianceEvidence> result =
                QuarkusTransaction.requiringNew().call(
                        () -> complianceService.findEvidence(caseId, tenancyId));

        assertThat(result).isPresent();
        assertThat(result.get().gdpr().erasurePerformed()).isFalse();
        assertThat(result.get().gdpr().erasureReceiptIds()).isEmpty();
    }
}
