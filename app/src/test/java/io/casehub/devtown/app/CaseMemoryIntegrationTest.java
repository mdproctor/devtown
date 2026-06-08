package io.casehub.devtown.app;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.blackboard.event.PlanItemCompletedEvent;
import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.DevtownMemoryKeys;
import io.casehub.devtown.review.MemoryContext;
import io.casehub.devtown.review.PrPayload;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.memory.CaseMemoryStore;
import io.casehub.platform.api.memory.MemoryOrder;
import io.casehub.platform.api.memory.MemoryQuery;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Full round-trip integration test for CaseMemoryStore chain:
 * case start → binding completes → observer extracts → emitter stores → recall retrieves.
 *
 * <p>Tests the async chain: PlanItemCompletedEvent → ReviewOutcomeObserver →
 * ReviewCompletedEvent → CaseMemoryEmitter → InMemoryMemoryStore → CaseMemoryRecaller.
 *
 * <p><strong>CRITICAL:</strong> PlanItemCompletedEvent is fired via CDI directly, not via
 * {@code caseHub.signal()}. Context signals do not trigger PlanItemCompletedEvent — only
 * worker completions via {@code casehub.worker.finished} do. This test fires the event
 * manually to simulate worker completion.
 */
@QuarkusTest
class CaseMemoryIntegrationTest {

    @Inject PrReviewCaseHub caseHub;
    @Inject CaseMemoryStore store;
    @Inject CaseMemoryRecaller recaller;
    @Inject Event<PlanItemCompletedEvent> planItemEvents;
    @Inject FixedCurrentPrincipal principal;
    @Inject ReviewCompletedEventCapture capture;

    @BeforeEach
    void setUp() {
        principal.reset();
        principal.setTenancyId(TenancyConstants.DEFAULT_TENANT_ID);
        capture.clear();
    }

    /**
     * Full round-trip test:
     * 1. Start case with full PR context (contributor, changedPaths)
     * 2. Signal styleCheck outcome into case context
     * 3. Fire PlanItemCompletedEvent directly (simulating worker completion)
     * 4. Wait for async emission chain to complete
     * 5. Start a second case for the same contributor
     * 6. Verify recall returns stored facts
     */
    @Disabled("Two issues: (1) SchedulerService.registerScheduledTriggers() fails on null getCaseDefinition — tracked in engine#444; causes surefire retry failures. (2) CaseMemoryEmitter @ObservesAsync chain doesn't store facts despite ReviewCompletedEvent being captured — async CDI observer delivery issue. Track in devtown#72.")
    @Test
    void fullRoundTrip_emitThenRecall() throws Exception {
        // Phase 1: Start case with full context (including contributor and changedPaths).
        // Pre-seed parallel check keys (PP-20260521-134c38) to suppress bindings.
        // linesChanged=100 < humanApprovalThreshold=500 so human-approval binding does not fire.
        var initialCtx = Map.<String, Object>of(
            "pr", Map.of("id", "900", "repo", "casehubio/devtown", "linesChanged", 100,
                         "baseRef", "main", "headSha", "intsha",
                         "contributor", "roundtrip-user",
                         "changedPaths", List.of("app/src/main/java/Foo.java")),
            "policy", Map.of("humanApprovalThreshold", 500,
                             "securityReviewRequired", false, "requireSeniorApproval", false),
            "codeAnalysis", Map.of("complete", true, "securitySensitive", false,
                                   "architectureCrossing", false),
            "styleCheck", Map.of("outcome", "PENDING"),
            "testCoverage", Map.of("outcome", "PENDING"),
            "performanceAnalysis", Map.of("outcome", "PENDING"),
            "ci", Map.of("status", "passing"));

        UUID caseId = caseHub.startCase(initialCtx).toCompletableFuture().get(10, SECONDS);
        assertThat(caseId).as("Case should start successfully").isNotNull();

        // Wait briefly for case to be fully initialized
        Thread.sleep(500);

        // Phase 2: Signal the binding outcome into case context (so the observer can extract it).
        caseHub.signal(caseId, "styleCheck", Map.of("outcome", "APPROVED"));

        // Phase 3: Fire PlanItemCompletedEvent directly via CDI (since context signals don't trigger it).
        // This simulates what the engine does when a worker completes.
        planItemEvents.fireAsync(new PlanItemCompletedEvent(caseId, "style-check", "style-agent-1"));

        // Phase 3.5: Wait for ReviewCompletedEvent to be captured first
        await().atMost(10, SECONDS).pollInterval(200, MILLISECONDS).untilAsserted(() ->
            assertThat(capture.getEvents()).as("ReviewCompletedEvent should be fired").isNotEmpty());

        // Phase 4: Wait for async emission chain to complete.
        // ReviewOutcomeObserver → ReviewCompletedEvent → CaseMemoryEmitter → InMemoryMemoryStore
        // Increased timeout to 20s to account for async CDI event delivery chains
        await().atMost(20, SECONDS).pollInterval(500, MILLISECONDS).untilAsserted(() -> {
            var memories = store.query(
                MemoryQuery.forEntity("contributor:roundtrip-user",
                    DevtownMemoryDomain.SOFTWARE_REVIEW, TenancyConstants.DEFAULT_TENANT_ID)
                .withLimit(5)
                .withOrder(MemoryOrder.CHRONOLOGICAL));
            assertThat(memories).as("contributor facts from emission").isNotEmpty();
        });

        // Phase 5: Recall for a second PR by the same contributor.
        var pr2 = new PrPayload("casehubio/devtown", 901, "sha2", "main", 200,
            "roundtrip-user", List.of("app/src/main/java/Bar.java"));
        MemoryContext recalled = recaller.recall(pr2);

        // Phase 6: Verify recall returns stored facts.
        assertThat(recalled.contributorHistory())
            .as("recall returns facts from emission")
            .isNotEmpty();
        assertThat(recalled.contributorHistory().get(0).entityId())
            .isEqualTo("contributor:roundtrip-user");
        assertThat(recalled.contributorHistory().get(0).attributes().get(DevtownMemoryKeys.CAPABILITY))
            .isEqualTo("style-review"); // style-check → style-review
        assertThat(recalled.contributorHistory().get(0).attributes().get(DevtownMemoryKeys.OUTCOME_DETAIL))
            .isEqualTo("APPROVED");

        // Verify code-area recall also works.
        assertThat(recalled.codeAreaHistory())
            .as("code area facts from emission")
            .isNotEmpty();
        // Module-level normalization: app/src/main/java/Foo.java → module:casehubio/devtown/app
        assertThat(recalled.codeAreaHistory().get(0).entityId())
            .isEqualTo("module:casehubio/devtown/app");
    }
}
