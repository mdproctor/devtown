package io.casehub.devtown.app.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
// LedgerPersistenceUnit no longer needed — queries go through InMemoryLedgerEntryRepository
import io.casehub.platform.api.identity.ActorType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
// EntityManager removed — use ledgerRepo for consistent in-memory querying
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@QuarkusTest
@TestProfile(LedgerEnabledTestProfile.class)
class MergeDecisionObserverTest {

    @Inject Event<CaseLifecycleEvent> caseLifecycleEvents;
    @Inject LedgerEntryRepository ledgerRepo;
    @Inject ObjectMapper objectMapper;

    @Test
    void completedCase_writesApprovedMergeDecision() {
        UUID caseId = UUID.randomUUID();
        String tenancyId = "test-tenant";

        ObjectNode snapshot = buildPrSnapshot("casehubio/devtown", "42", "abc123def");

        CaseLifecycleEvent event = new CaseLifecycleEvent(
                caseId, tenancyId, "COMPLETE", "CASE_COMPLETED",
                "COMPLETED", "system", "ORCHESTRATOR", "trace-1",
                null, null, snapshot, null, null);

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

            assertThat(d.supplementJson).isNotNull();
            assertThat(d.supplementJson).contains("casehub-devtown:pr-review-v1");
            assertThat(d.supplementJson).contains("/api/reviews/42/contest");
        });
    }

    @Test
    void cancelledCase_writesRejectedMergeDecision() {
        UUID caseId = UUID.randomUUID();
        String tenancyId = "test-tenant";

        ObjectNode snapshot = buildPrSnapshot("casehubio/engine", "99", "def456");

        CaseLifecycleEvent event = new CaseLifecycleEvent(
                caseId, tenancyId, "CANCEL", "CASE_CANCELLED",
                "CANCELLED", "system", "ORCHESTRATOR", "trace-2",
                null, null, snapshot, null, null);

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

        ObjectNode snapshot = buildPrSnapshot("casehubio/ledger", "7", "fff000");

        CaseLifecycleEvent event = new CaseLifecycleEvent(
                caseId, tenancyId, "FAULT", "CASE_FAULTED",
                "FAULTED", "system", "ORCHESTRATOR", "trace-3",
                null, null, snapshot, null, null);

        caseLifecycleEvents.fireAsync(event);

        await().during(Duration.ofMillis(500))
                .atMost(2, SECONDS)
                .untilAsserted(() -> {
            List<MergeDecisionLedgerEntry> decisions = findMergeDecisions(caseId);
            assertThat(decisions).isEmpty();
        });
    }

    private List<MergeDecisionLedgerEntry> findMergeDecisions(UUID caseId) {
        return ledgerRepo.findBySubjectId(caseId, "test-tenant").stream()
                         .filter(MergeDecisionLedgerEntry.class::isInstance)
                         .map(MergeDecisionLedgerEntry.class::cast)
                         .toList();
    }

    private ObjectNode buildPrSnapshot(String repo, String prId, String headSha) {
        ObjectNode snapshot = objectMapper.createObjectNode();
        ObjectNode pr = snapshot.putObject("pr");
        pr.put("repo", repo);
        pr.put("id", prId);
        pr.put("headSha", headSha);
        pr.put("baseRef", "main");
        pr.put("linesChanged", 100);
        pr.put("contributor", "test-user");
        pr.putArray("changedPaths").add("src/Main.java");
        return snapshot;
    }
}
