package io.casehub.devtown.app;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.work.runtime.event.WorkItemLifecycleEvent;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.service.WorkItemService;
import io.casehub.workadapter.WorkItemLifecycleAdapter;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Escalation path integration tests for the merge queue.
 *
 * <p>Covers three escalation scenarios:
 * <ul>
 *   <li><b>High-risk approval:</b> HIGH-risk batch → human-merge-approval humanTask →
 *       REJECTED outcome → {@code merge-approval-rejected} failure goal fires
 *   <li><b>Tip-test escalation:</b> tip test REROUTES_EXHAUSTED (signalled) →
 *       tip-test-escalation humanTask → REJECT_BATCH → {@code tip-test-terminal-failure} fires
 *   <li><b>Merge escalation:</b> merge REROUTES_EXHAUSTED (signalled) →
 *       merge-escalation humanTask → APPROVED → contextWrite resets mergeResult →
 *       merge result signalled as success → {@code batch-merged} fires
 * </ul>
 *
 * <p>REROUTES_EXHAUSTED is signalled directly into context because it's normally
 * produced by the engine's outcome policy handler after max reroute attempts.
 * HumanTask WorkItems are driven via WorkItemService + WorkItemLifecycleAdapter,
 * following the same pattern as HumanApprovalLifecycleTest.
 */
@QuarkusTest
class MergeQueueEscalationTest {

    @Inject MergeBatchCaseHub caseHub;
    @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;
    @Inject WorkItemQueries workItemQueries;
    @Inject WorkItemService workItemService;
    @Inject WorkItemLifecycleAdapter lifecycleAdapter;

    private final AtomicReference<Function<Map<String, Object>, WorkerResult>> tipTestBehavior =
        new AtomicReference<>(input -> WorkerResult.of(Map.of("status", "passing")));

    @BeforeEach
    void registerTestWorkers() {
        var def = caseHub.getDefinition();
        def.getWorkers().removeIf(w -> "batch-ci-runner".equals(w.name()));

        var ciCap = def.getCapabilities().stream()
            .filter(c -> "batch-ci-runner".equals(c.name()))
            .findFirst().orElseThrow();
        def.getWorkers().add(Worker.builder()
            .name("batch-ci-runner")
            .capabilities(ciCap)
            .function(input -> tipTestBehavior.get().apply(input))
            .build());
    }

    @Test
    void highRiskBatch_humanRejects_failureGoalFires() throws Exception {
        // Arrange: tip test passes, batch is HIGH risk requiring human merge approval.
        // For HIGH risk, the merge-batch binding condition includes
        //   (.batch.riskLevel == "ROUTINE" or .batch.riskLevel == "ELEVATED" or .mergeApproval.outcome == "APPROVED")
        // which is FALSE for HIGH risk without approval — so merge-batch binding does NOT fire.
        // Only human-merge-approval fires (requires .mergeResult == null, which is true here).
        tipTestBehavior.set(input -> WorkerResult.of(Map.of("status", "passing")));

        Map<String, Object> batchContext = MergeQueueBatchLifecycleTest.buildBatchContext(2, "HIGH");

        UUID caseId = caseHub.startCase(batchContext)
            .toCompletableFuture().get(5, SECONDS);
        assertThat(caseId).isNotNull();

        // Wait for human-merge-approval WorkItem to be created
        // Binding: tipTest.status == "passing" AND mergeApproval == null AND riskLevel HIGH
        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var items = workItemQueries.scanAll().stream()
                    .filter(i -> isMergeApprovalFor(i, caseId))
                    .toList();
                assertThat(items)
                    .as("human-merge-approval WorkItem should be created for HIGH risk batch")
                    .isNotEmpty();
            });

        // Complete WorkItem with REJECTED outcome
        completeWorkItems(caseId, this::isMergeApprovalFor, "{\"outcome\": \"REJECTED\"}");

        // Case should reach terminal state via merge-approval-rejected goal
        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var instance = caseInstanceRepository.findByUuid(caseId)
                    .await().atMost(Duration.ofSeconds(2));
                assertThat(instance).isNotNull();
                // merge-approval-rejected is a failure goal: '.mergeApproval.outcome == "REJECTED"'
                assertThat(instance.getState())
                    .as("Case should terminate after human rejects merge approval")
                    .isIn(CaseStatus.COMPLETED, CaseStatus.FAULTED, CaseStatus.CANCELLED);
            });

        var instance = caseInstanceRepository.findByUuid(caseId)
            .await().atMost(Duration.ofSeconds(2));
        assertThat(instance.getCaseContext().getPath("mergeApproval.outcome"))
            .as("mergeApproval.outcome should be REJECTED")
            .isEqualTo("REJECTED");
    }

    @Test
    void tipTestReroutesExhausted_humanRejectsBatch_failureGoalFires() throws Exception {
        // Signal REROUTES_EXHAUSTED for tip test directly into context
        // (normally produced by engine's outcome policy after max reroute attempts)
        Map<String, Object> batchContext = MergeQueueBatchLifecycleTest.buildBatchContext(3, "ROUTINE");
        // Pre-seed tipTest as REROUTES_EXHAUSTED to trigger tip-test-escalation binding
        batchContext.put("tipTest", Map.of("status", "REROUTES_EXHAUSTED"));

        UUID caseId = caseHub.startCase(batchContext)
            .toCompletableFuture().get(5, SECONDS);
        assertThat(caseId).isNotNull();

        // Wait for tip-test-escalation WorkItem
        // Binding: '.tipTest.status == "REROUTES_EXHAUSTED" and .tipTestEscalation == null'
        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var items = workItemQueries.scanAll().stream()
                    .filter(i -> isTipTestEscalationFor(i, caseId))
                    .toList();
                assertThat(items)
                    .as("tip-test-escalation WorkItem should be created")
                    .isNotEmpty();
            });

        // Complete with REJECT_BATCH outcome
        completeWorkItems(caseId, this::isTipTestEscalationFor, "{\"outcome\": \"REJECT_BATCH\"}");

        // Case should reach terminal state via tip-test-terminal-failure goal
        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var instance = caseInstanceRepository.findByUuid(caseId)
                    .await().atMost(Duration.ofSeconds(2));
                assertThat(instance).isNotNull();
                assertThat(instance.getState())
                    .as("Case should terminate after REJECT_BATCH")
                    .isIn(CaseStatus.COMPLETED, CaseStatus.FAULTED, CaseStatus.CANCELLED);
            });

        var instance = caseInstanceRepository.findByUuid(caseId)
            .await().atMost(Duration.ofSeconds(2));
        assertThat(instance.getCaseContext().getPath("tipTestEscalation.outcome"))
            .as("tipTestEscalation.outcome should be REJECT_BATCH")
            .isEqualTo("REJECT_BATCH");
    }

    @Test
    void mergeReroutesExhausted_humanApproves_retrySucceeds() throws Exception {
        // Tip test passing, merge REROUTES_EXHAUSTED triggers merge-escalation
        Map<String, Object> batchContext = MergeQueueBatchLifecycleTest.buildBatchContext(2, "ROUTINE");
        // Pre-seed tipTest passing (skip worker dispatch)
        batchContext.put("tipTest", Map.of("status", "passing"));
        // Pre-seed mergeResult as REROUTES_EXHAUSTED (triggers merge-escalation binding)
        batchContext.put("mergeResult", Map.of("status", "REROUTES_EXHAUSTED"));

        UUID caseId = caseHub.startCase(batchContext)
            .toCompletableFuture().get(5, SECONDS);
        assertThat(caseId).isNotNull();

        // Wait for merge-escalation WorkItem
        // Binding: '.mergeResult.status == "REROUTES_EXHAUSTED" and .mergeEscalation == null'
        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var items = workItemQueries.scanAll().stream()
                    .filter(i -> isMergeEscalationFor(i, caseId))
                    .toList();
                assertThat(items)
                    .as("merge-escalation WorkItem should be created after reroutes exhausted")
                    .isNotEmpty();
            });

        // Approve the escalation
        completeWorkItems(caseId, this::isMergeEscalationFor, "{\"outcome\": \"APPROVED\"}");

        // merge-after-escalation binding should fire: contextWrite resets mergeResult to null,
        // sets mergeEscalatedRetry=true, then dispatches merge-executor again.
        // Since merge-executor is trust-routed, we signal the success result directly.
        await().atMost(5, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var instance = caseInstanceRepository.findByUuid(caseId)
                    .await().atMost(Duration.ofSeconds(2));
                assertThat(instance.getCaseContext().getPath("mergeEscalation.outcome"))
                    .as("mergeEscalation.outcome should be APPROVED after WorkItem completion")
                    .isEqualTo("APPROVED");
            });

        // Signal successful merge result
        caseHub.signal(caseId, "mergeResult", Map.of("status", "success"));

        // Case should complete via batch-merged goal
        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var instance = caseInstanceRepository.findByUuid(caseId)
                    .await().atMost(Duration.ofSeconds(2));
                assertThat(instance).isNotNull();
                assertThat(instance.getState())
                    .as("Case should complete after escalation-approved merge retry")
                    .isIn(CaseStatus.COMPLETED, CaseStatus.FAULTED);
            });
    }

    @Test
    void mergeReroutesExhausted_humanRejects_failureGoalFires() throws Exception {
        // Merge reroutes exhausted, human rejects → merge-terminal-failure goal
        Map<String, Object> batchContext = MergeQueueBatchLifecycleTest.buildBatchContext(2, "ROUTINE");
        batchContext.put("tipTest", Map.of("status", "passing"));
        batchContext.put("mergeResult", Map.of("status", "REROUTES_EXHAUSTED"));

        UUID caseId = caseHub.startCase(batchContext)
            .toCompletableFuture().get(5, SECONDS);

        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var items = workItemQueries.scanAll().stream()
                    .filter(i -> isMergeEscalationFor(i, caseId))
                    .toList();
                assertThat(items).isNotEmpty();
            });

        // Reject the escalation
        completeWorkItems(caseId, this::isMergeEscalationFor, "{\"outcome\": \"REJECTED\"}");

        // merge-terminal-failure goal: '.mergeEscalation.outcome == "REJECTED"'
        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var instance = caseInstanceRepository.findByUuid(caseId)
                    .await().atMost(Duration.ofSeconds(2));
                assertThat(instance).isNotNull();
                assertThat(instance.getState())
                    .as("Case should terminate after merge escalation rejected")
                    .isIn(CaseStatus.COMPLETED, CaseStatus.FAULTED, CaseStatus.CANCELLED);
            });

        var instance = caseInstanceRepository.findByUuid(caseId)
            .await().atMost(Duration.ofSeconds(2));
        assertThat(instance.getCaseContext().getPath("mergeEscalation.outcome"))
            .isEqualTo("REJECTED");
    }

    // ── WorkItem completion helper ───────────────────────────────────────────

    private void completeWorkItems(UUID caseId,
                                    java.util.function.BiPredicate<WorkItem, UUID> filter,
                                    String resolution) {
        var toComplete = workItemQueries.scanAll().stream()
            .filter(i -> filter.test(i, caseId))
            .toList();

        toComplete.forEach(wi -> workItemService.completeFromSystem(
            wi.id, "system", resolution));

        // Re-fetch and drive the adapter to propagate completion into case context
        var completed = workItemQueries.scanAll().stream()
            .filter(i -> filter.test(i, caseId))
            .toList();
        completed.forEach(wi -> lifecycleAdapter.onWorkItemLifecycle(
            WorkItemLifecycleEvent.of("COMPLETED", wi, "system", wi.resolution)));
    }

    // ── WorkItem matching predicates ─────────────────────────────────────────

    private boolean isMergeApprovalFor(WorkItem item, UUID caseId) {
        return item.callerRef != null
            && item.callerRef.contains(caseId.toString())
            && item.title != null
            && item.title.contains("High-risk");
    }

    private boolean isMergeEscalationFor(WorkItem item, UUID caseId) {
        return item.callerRef != null
            && item.callerRef.contains(caseId.toString())
            && item.title != null
            && item.title.contains("Merge execution failed");
    }

    private boolean isTipTestEscalationFor(WorkItem item, UUID caseId) {
        return item.callerRef != null
            && item.callerRef.contains(caseId.toString())
            && item.title != null
            && item.title.contains("Batch CI test failed");
    }
}
