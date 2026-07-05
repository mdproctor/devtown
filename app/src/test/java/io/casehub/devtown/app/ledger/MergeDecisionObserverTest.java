package io.casehub.devtown.app.ledger;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.engine.internal.context.CaseContextImpl;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(LedgerEnabledTestProfile.class)
class MergeDecisionObserverTest {

    @Inject Event<CaseLifecycleEvent> caseLifecycleEvents;
    @Inject CaseInstanceRepository caseInstanceRepo;
    @Inject LedgerEntryRepository ledgerRepo;

    @Test
    void completedCase_writesApprovedMergeDecision() {
        UUID caseId = UUID.randomUUID();
        String tenancyId = "test-tenant";
        seedCaseInstance(caseId, tenancyId, "casehubio/devtown", "42", "abc123def");

        CaseLifecycleEvent event = new CaseLifecycleEvent(
                caseId, tenancyId, "COMPLETE", "CASE_COMPLETED",
                "COMPLETED", "system", "ORCHESTRATOR", "trace-1");

        caseLifecycleEvents.fireAsync(event);

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<MergeDecisionLedgerEntry> decisions = findMergeDecisions(caseId);

            assertThat(decisions).hasSize(1);

            MergeDecisionLedgerEntry d = decisions.get(0);
            assertThat(d.decision).isEqualTo("APPROVED");
            assertThat(d.prNumber).isEqualTo(42);
            assertThat(d.repository).isEqualTo("casehubio/devtown");
            assertThat(d.commitSha).isEqualTo("abc123def");
            assertThat(d.caseId).isEqualTo(caseId);
            assertThat(d.tenancyId).isEqualTo(tenancyId);
            assertThat(d.entryType).isEqualTo(LedgerEntryType.EVENT);
            assertThat(d.actorId).isEqualTo("system");
            assertThat(d.actorType).isEqualTo(ActorType.SYSTEM);
            assertThat(d.actorRole).isEqualTo("ORCHESTRATOR");
            assertThat(d.occurredAt).isNotNull();

            // ComplianceSupplement attached
            assertThat(d.compliance()).isPresent();
            d.compliance().ifPresent(cs -> {
                assertThat(cs.algorithmRef).isEqualTo("casehub-devtown:pr-review-v1");
                assertThat(cs.humanOverrideAvailable).isTrue();
                assertThat(cs.contestationUri).isEqualTo("/api/reviews/42/contest");
            });
        });
    }

    @Test
    void cancelledCase_writesRejectedMergeDecision() {
        UUID caseId = UUID.randomUUID();
        String tenancyId = "test-tenant";
        seedCaseInstance(caseId, tenancyId, "casehubio/engine", "99", "def456");

        CaseLifecycleEvent event = new CaseLifecycleEvent(
                caseId, tenancyId, "CANCEL", "CASE_CANCELLED",
                "CANCELLED", "system", "ORCHESTRATOR", "trace-2");

        caseLifecycleEvents.fireAsync(event);

        await().atMost(5, SECONDS).untilAsserted(() -> {
            List<MergeDecisionLedgerEntry> decisions = findMergeDecisions(caseId);

            assertThat(decisions).hasSize(1);

            MergeDecisionLedgerEntry d = decisions.get(0);
            assertThat(d.decision).isEqualTo("REJECTED");
            assertThat(d.prNumber).isEqualTo(99);
            assertThat(d.repository).isEqualTo("casehubio/engine");
            assertThat(d.commitSha).isEqualTo("def456");
        });
    }

    @Test
    void faultedCase_writesNoMergeDecision() {
        UUID caseId = UUID.randomUUID();
        String tenancyId = "test-tenant";
        seedCaseInstance(caseId, tenancyId, "casehubio/ledger", "7", "fff000");

        CaseLifecycleEvent event = new CaseLifecycleEvent(
                caseId, tenancyId, "FAULT", "CASE_FAULTED",
                "FAULTED", "system", "ORCHESTRATOR", "trace-3");

        caseLifecycleEvents.fireAsync(event);

        // Give async observer time to process (if it were to incorrectly fire)
        await().during(Duration.ofMillis(500))
                .atMost(2, SECONDS)
                .untilAsserted(() -> {
            List<MergeDecisionLedgerEntry> decisions = findMergeDecisions(caseId);
            assertThat(decisions).isEmpty();
        });
    }

    /**
     * Queries ledger entries inside a new transaction — required because
     * Awaitility polling runs outside the test method's transaction scope,
     * and the JPA EntityManager needs an active transaction. Forces eager
     * initialization of the LAZY supplements collection to avoid
     * LazyInitializationException after the transaction closes.
     */
    private List<MergeDecisionLedgerEntry> findMergeDecisions(UUID caseId) {
        return QuarkusTransaction.requiringNew().call(() -> {
            List<LedgerEntry> entries = ledgerRepo.findBySubjectId(caseId, "test-tenant");
            return entries.stream()
                    .filter(MergeDecisionLedgerEntry.class::isInstance)
                    .map(MergeDecisionLedgerEntry.class::cast)
                    .peek(d -> d.supplements.size()) // force LAZY init
                    .toList();
        });
    }

    private void seedCaseInstance(UUID caseId, String tenancyId,
                                  String repo, String prId, String headSha) {
        CaseInstance ci = new CaseInstance();
        ci.setUuid(caseId);

        CaseContextImpl ctx = new CaseContextImpl();
        ctx.set("pr", Map.of(
                "repo", repo,
                "id", prId,
                "headSha", headSha,
                "baseRef", "main",
                "linesChanged", 100,
                "contributor", "test-user",
                "changedPaths", List.of("src/Main.java")));
        ci.setCaseContext(ctx);

        caseInstanceRepo.save(ci, tenancyId);
    }
}
