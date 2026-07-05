package io.casehub.devtown.app;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.api.model.CaseStatus;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Happy-path integration test for the merge queue batch lifecycle.
 *
 * <p>Verifies the full batch-then-merge path through the engine's CasePlanModel:
 * <ol>
 *   <li>Start a batch case with 3 PRs
 *   <li>Tip test worker executes and returns passing (via registered test worker)
 *   <li>Merge result signalled directly into context (merge-executor is trust-routed;
 *       in production it dispatches to a trusted worker — here we verify goal evaluation)
 *   <li>{@code batch-merged} goal fires and case reaches COMPLETED
 * </ol>
 *
 * <p>The {@code batch-ci-runner} worker is registered in {@code @BeforeEach} because it
 * has no trust routing policy. The {@code merge-executor} capability IS trust-routed
 * (trust-routing.yaml blend-factor 0.80, threshold 0.80, min observations 15) and
 * requires a pre-seeded trust profile to pass the routing gate. Tests signal the merge
 * result directly to verify goal conditions and completion semantics without requiring
 * ledger-seeded trust observations.
 *
 * <p>To prevent the engine from attempting merge-executor dispatch (which races with
 * the test signal and produces FAULTED PlanItems), the initial context pre-seeds
 * {@code mergeResult} with a placeholder value. The test then signals the real value.
 */
@QuarkusTest
class MergeQueueBatchLifecycleTest {

    @Inject MergeBatchCaseHub caseHub;
    @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;

    private final AtomicReference<Function<Map<String, Object>, WorkerResult>> tipTestBehavior =
        new AtomicReference<>(input -> WorkerResult.of(Map.of("status", "passing")));

    @BeforeEach
    void registerTestWorkers() {
        var def = caseHub.getDefinition();

        // Remove previously registered test workers to avoid duplicates across test runs
        def.getWorkers().removeIf(w -> "batch-ci-runner".equals(w.name()));

        var ciCap = def.getCapabilities().stream()
            .filter(c -> "batch-ci-runner".equals(c.name()))
            .findFirst().orElseThrow();
        def.getWorkers().add(Worker.builder()
            .name("batch-ci-runner")
            .capabilityName(ciCap.name())
            .function(input -> tipTestBehavior.get().apply(input))
            .build());
    }

    @Test
    void happyPath_tipPasses_mergeSucceeds_caseCompleted() throws Exception {
        tipTestBehavior.set(input -> WorkerResult.of(Map.of("status", "passing")));

        // Pre-seed mergeResult with placeholder to suppress merge-batch binding dispatch.
        // The binding condition includes ".mergeResult == null" — a non-null value prevents firing.
        Map<String, Object> batchContext = buildBatchContext(3, "ROUTINE");
        batchContext.put("mergeResult", Map.of("status", "pending"));

        UUID caseId = caseHub.startCase(batchContext)
            .toCompletableFuture().get(5, SECONDS);
        assertThat(caseId).isNotNull();

        // Wait for tip test to complete
        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var instance = caseInstanceRepository.findByUuid(caseId)
                    ;
                assertThat(instance).isNotNull();
                assertThat(instance.getCaseContext().getPath("tipTest.status"))
                    .as("tipTest.status should be 'passing' after batch-ci-runner worker")
                    .isEqualTo("passing");
            });

        // Signal merge result — this overwrites the placeholder and fires batch-merged goal
        caseHub.signal(caseId, "mergeResult", Map.of("status", "success"));

        // Verify case reaches COMPLETED via batch-merged goal
        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var instance = caseInstanceRepository.findByUuid(caseId)
                    ;
                assertThat(instance).isNotNull();
                assertThat(instance.getState())
                    .as("Case should complete via batch-merged goal")
                    .isEqualTo(CaseStatus.COMPLETED);
            });

        var instance = caseInstanceRepository.findByUuid(caseId)
            ;
        assertThat(instance.getCaseContext().getPath("tipTest.status"))
            .isEqualTo("passing");
        assertThat(instance.getCaseContext().getPath("mergeResult.status"))
            .isEqualTo("success");
    }

    @Test
    void tipTestFails_batchOfOne_rejectsSinglePr() throws Exception {
        // Tip test fails for single-PR batch — triggers reject-single-pr binding
        tipTestBehavior.set(input ->
            WorkerResult.of(Map.of("status", "failing", "failureReason", "compilation error")));

        Map<String, Object> batchContext = buildBatchContext(1, "ROUTINE");

        UUID caseId = caseHub.startCase(batchContext)
            .toCompletableFuture().get(5, SECONDS);
        assertThat(caseId).isNotNull();

        // Case should reach COMPLETED via single-pr-rejected goal
        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var instance = caseInstanceRepository.findByUuid(caseId)
                    ;
                assertThat(instance).isNotNull();
                assertThat(instance.getState())
                    .as("Case should complete via single-pr-rejected goal")
                    .isEqualTo(CaseStatus.COMPLETED);
            });

        var instance = caseInstanceRepository.findByUuid(caseId)
            ;
        assertThat(instance.getCaseContext().getPath("rejectedPrs"))
            .as("rejectedPrs should be populated")
            .isNotNull();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static Map<String, Object> buildBatchContext(int prCount, String riskLevel) {
        var batchMap = new LinkedHashMap<String, Object>();
        batchMap.put("id", "test-batch-" + UUID.randomUUID().toString().substring(0, 8));
        batchMap.put("repository", "casehubio/devtown");
        batchMap.put("targetBranch", "main");
        batchMap.put("size", prCount);
        batchMap.put("riskLevel", riskLevel);
        batchMap.put("bisectionStrategy", "trust-weighted");
        batchMap.put("bisectionDepth", 0);

        List<Map<String, Object>> prMaps = new ArrayList<>();
        for (int i = 0; i < prCount; i++) {
            var prMap = new LinkedHashMap<String, Object>();
            prMap.put("number", 100 + i);
            prMap.put("repository", "casehubio/devtown");
            prMap.put("headSha", "sha-" + (100 + i));
            prMap.put("author", "author-" + i);
            prMap.put("trustScore", 0.5 + (i * 0.1));
            prMap.put("lane", "NORMAL");
            prMaps.add(prMap);
        }
        batchMap.put("prs", prMaps);

        var context = new LinkedHashMap<String, Object>();
        context.put("batch", batchMap);
        return context;
    }
}
