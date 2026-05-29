package io.casehub.devtown.app;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.spi.CaseInstanceRepository;
import io.casehub.work.runtime.event.WorkItemLifecycleEvent;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.service.WorkItemService;
import io.casehub.workadapter.WorkItemLifecycleAdapter;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HumanApprovalLifecycleTest {

    @Inject PrReviewCaseHub          caseHub;
    @Inject WorkItemQueries          workItemQueries;
    @Inject WorkItemService          workItemService;
    @Inject CaseInstanceRepository   caseInstanceRepository;
    @Inject WorkItemCompletionCapture completionCapture;
    @Inject WorkItemLifecycleAdapter  lifecycleAdapter;

    @Test
    void humanApproval_fullLifecycle()
            throws ExecutionException, InterruptedException, TimeoutException {

        // Initial context: all parallel checks pre-seeded with non-APPROVED values.
        // - Suppresses all capability bindings (ci, style, test, perf are non-null →
        //   conditions ".x == null" are false → no tryProvision() calls → Vert.x free).
        // - Keeps pr-approved goal unsatisfied (PENDING ≠ APPROVED) so the case stays
        //   active while the human-approval WorkItem lifecycle plays out.
        var pr = Map.<String, Object>of(
                "id", "42",
                "repo", "casehubio/devtown",
                "linesChanged", 600,       // > humanApprovalThreshold of 500
                "baseRef", "main",
                "headSha", "abc123");
        var policy = Map.<String, Object>of(
                "humanApprovalThreshold", 500,
                "securityReviewRequired", false,
                "requireSeniorApproval", false);
        var codeAnalysis = Map.<String, Object>of(
                "complete", true,
                "securitySensitive", false,
                "architectureCrossing", false);

        var initialContext = Map.<String, Object>of(
                "pr", pr,
                "policy", policy,
                "codeAnalysis", codeAnalysis,
                "styleCheck",         Map.of("outcome", "PENDING"),
                "testCoverage",       Map.of("outcome", "PENDING"),
                "performanceAnalysis", Map.of("outcome", "PENDING"),
                "ci",                 Map.of("status", "passing"));

        // ── Checkpoint 1: start the case ──────────────────────────────────────
        UUID caseId = caseHub.startCase(initialContext)
                .toCompletableFuture()
                .get(5, SECONDS);
        assertThat(caseId).isNotNull();

        // ── Checkpoint 2: WorkItem created by the human-approval binding ───────
        // HumanTaskScheduleHandler (blocking=true) creates the WorkItem asynchronously.
        // engine#312: the binding may fire twice → accept ≥1 WorkItem.
        await().atMost(5, SECONDS)
                .pollInterval(100, MILLISECONDS)
                .untilAsserted(() -> {
                    var items = workItemQueries.scanAll().stream()
                            .filter(i -> isHumanApprovalFor(i, caseId))
                            .toList();
                    assertThat(items).as("human-approval WorkItem").isNotEmpty();
                    assertThat(items.get(0).title).isEqualTo("PR approval required");
                });

        // ── Checkpoint 3: complete WorkItems and drive the adapter ─────────────
        // Resolution {"status":"approved"} with outputMapping "{ humanApproval: . }"
        // (engine#314 — flat outputMapping required because nested {..} not supported).
        // → humanApproval = {status:"approved"} in case context.
        var toComplete = workItemQueries.scanAll().stream()
                .filter(i -> isHumanApprovalFor(i, caseId))
                .toList();

        toComplete.forEach(wi -> workItemService.completeFromSystem(
                wi.id, "system", "{\"status\": \"approved\"}"));

        // CDI @ObservesAsync delivery is verified via test-scope bean (not the external
        // adapter — engine#315 tracks @ObservesAsync for indexed external jar observers).
        await().atMost(3, SECONDS)
                .pollInterval(100, MILLISECONDS)
                .untilAsserted(() -> toComplete.forEach(wi ->
                        assertThat(completionCapture.wasCompleted(wi.id))
                                .as("@ObservesAsync CDI delivery for WorkItem " + wi.id)
                                .isTrue()));

        // Re-fetch completed WorkItems and invoke the adapter directly.
        // Tests the full outputMapping → case context → CONTEXT_CHANGED chain.
        var completedItems = workItemQueries.scanAll().stream()
                .filter(i -> isHumanApprovalFor(i, caseId))
                .toList();
        completedItems.forEach(wi -> lifecycleAdapter.onWorkItemLifecycle(
                WorkItemLifecycleEvent.of("COMPLETED", wi, "system", wi.resolution)));

        // ── Checkpoint 4: case context updated via outputMapping ──────────────
        await().atMost(5, SECONDS)
                .pollInterval(100, MILLISECONDS)
                .untilAsserted(() -> {
                    var instance = caseInstanceRepository
                            .findByUuid(caseId)
                            .await().atMost(Duration.ofSeconds(2));
                    assertThat(instance).isNotNull();
                    Object status = instance.getCaseContext().getPath("humanApproval.status");
                    assertThat(status)
                            .as("humanApproval.status should be 'approved' after outputMapping")
                            .isEqualTo("approved");
                });

        // ── Checkpoint 5: case completes when all goals are satisfied ──────────
        // Signal the APPROVED values that were intentionally absent (kept pr-approved
        // unsatisfied while the WorkItem was live).
        caseHub.signal(caseId, "styleCheck",          Map.of("outcome", "APPROVED"));
        caseHub.signal(caseId, "testCoverage",         Map.of("outcome", "APPROVED"));
        caseHub.signal(caseId, "performanceAnalysis",  Map.of("outcome", "APPROVED"));

        await().atMost(5, SECONDS)
                .pollInterval(100, MILLISECONDS)
                .untilAsserted(() -> {
                    var instance = caseInstanceRepository
                            .findByUuid(caseId)
                            .await().atMost(Duration.ofSeconds(2));
                    assertThat(instance).isNotNull();
                    assertThat(instance.getState()).isEqualTo(CaseStatus.COMPLETED);
                });
    }

    private static boolean isHumanApprovalFor(final WorkItem item, final UUID caseId) {
        return item.callerRef != null && item.callerRef.contains(caseId.toString());
    }
}
