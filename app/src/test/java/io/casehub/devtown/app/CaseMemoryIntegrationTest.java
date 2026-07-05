package io.casehub.devtown.app;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.engine.common.spi.event.PlanItemCompletedEvent;
import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.DevtownMemoryKeys;
import io.casehub.devtown.review.MemoryContext;
import io.casehub.devtown.review.PrPayload;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryOrder;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Full round-trip integration test for CaseMemoryStore chain:
 * case start → binding completes → observer extracts → emitter stores → recall retrieves.
 *
 * <p>Tests the async chain: PlanItemCompletedEvent → ReviewOutcomeObserver →
 * ReviewCompletedEvent → CaseMemoryEmitter → InMemoryMemoryStore → CaseMemoryRecaller.
 *
 * <p>The case starts with the style-check outcome already set to APPROVED in the
 * initial context. This avoids the engine signal race condition (engine#494 —
 * the signal may not propagate before the observer reads the context). The
 * signal-then-extract path is covered by {@code ReviewOutcomeObserverTest}.
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

    @Test
    void fullRoundTrip_emitThenRecall() throws Exception {
        // Start case with style-check outcome already APPROVED in initial context.
        // Pre-seed all parallel check keys (PP-20260521-134c38) to suppress bindings.
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
            "styleCheck", Map.of("outcome", "APPROVED"),
            "testCoverage", Map.of("outcome", "PENDING"),
            "performanceAnalysis", Map.of("outcome", "PENDING"),
            "ci", Map.of("status", "passing"));

        UUID caseId = caseHub.startCase(initialCtx).toCompletableFuture().get(10, SECONDS);
        assertThat(caseId).as("Case should start successfully").isNotNull();

        // Fire PlanItemCompletedEvent directly via CDI — simulating what the engine
        // does when a worker completes. Context signals don't trigger PlanItemCompletedEvent.
        planItemEvents.fireAsync(new PlanItemCompletedEvent(
                caseId, "style-check", "style-agent-1", TenancyConstants.DEFAULT_TENANT_ID));

        // Wait for ReviewCompletedEvent to be captured
        await().atMost(10, SECONDS).pollInterval(200, MILLISECONDS).untilAsserted(() ->
            assertThat(capture.getEvents()).as("ReviewCompletedEvent should be fired").isNotEmpty());

        // Wait for async emission chain to complete.
        // ReviewOutcomeObserver → ReviewCompletedEvent → CaseMemoryEmitter → InMemoryMemoryStore
        await().atMost(20, SECONDS).pollInterval(500, MILLISECONDS).untilAsserted(() -> {
            var memories = store.query(
                MemoryQuery.forEntity(DevtownMemoryDomain.CONTRIBUTOR_PREFIX + "roundtrip-user",
                    DevtownMemoryDomain.SOFTWARE_REVIEW, TenancyConstants.DEFAULT_TENANT_ID)
                .withLimit(10)
                .withOrder(MemoryOrder.CHRONOLOGICAL));
            assertThat(memories).as("contributor facts from emission").isNotEmpty();
        });

        // Recall for a second PR by the same contributor.
        var pr2 = new PrPayload("casehubio/devtown", 901, "sha2", "main", 200,
            "roundtrip-user", List.of("app/src/main/java/Bar.java"));
        MemoryContext recalled = recaller.recall(pr2);

        // Verify recall returns stored facts.
        assertThat(recalled.contributorHistory())
            .as("recall returns facts from emission")
            .isNotEmpty();
        assertThat(recalled.contributorHistory().get(0).entityId())
            .isEqualTo(DevtownMemoryDomain.CONTRIBUTOR_PREFIX + "roundtrip-user");
        assertThat(recalled.contributorHistory().get(0).attributes().get(DevtownMemoryKeys.CAPABILITY))
            .isEqualTo("style-review");
        assertThat(recalled.contributorHistory().get(0).attributes().get(DevtownMemoryKeys.OUTCOME_DETAIL))
            .isEqualTo("APPROVED");

        // Verify code-area recall also works.
        assertThat(recalled.codeAreaHistory())
            .as("code area facts from emission")
            .isNotEmpty();
        assertThat(recalled.codeAreaHistory().get(0).entityId())
            .isEqualTo(DevtownMemoryDomain.MODULE_PREFIX + "casehubio/devtown/app");
    }
}
