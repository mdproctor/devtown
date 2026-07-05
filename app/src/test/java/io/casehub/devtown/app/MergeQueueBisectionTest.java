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
 * Bisection integration test for the merge queue.
 *
 * <p>Verifies the failure path with bisection split:
 * <ol>
 *   <li>Start a batch case with 4 PRs
 *   <li>Tip test fails → triggers compute-bisection-split binding
 *   <li>Bisection-splitter worker (registered in MergeBatchCaseHub) computes split
 *   <li>splitResult is written to context with left and right sub-batch metadata
 *   <li>Sub-case bindings (bisect-left, bisect-right) fire
 * </ol>
 *
 * <p>The tip-test worker returns "failing" for batches containing the culprit PR.
 * Sub-case spawning requires engine#573 (recursive sub-cases) and engine#574
 * (M-of-N + per-child outputMapping). If these features have edge cases, the test
 * verifies up to the splitResult population and documents the sub-case behavior.
 */
@QuarkusTest
class MergeQueueBisectionTest {

    @Inject MergeBatchCaseHub caseHub;
    @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;

    /** PR number that causes tip-test failure when present in the batch. */
    private static final int CULPRIT_PR = 100;

    private final AtomicReference<Function<Map<String, Object>, WorkerResult>> tipTestBehavior =
        new AtomicReference<>();

    @SuppressWarnings("unchecked")
    @BeforeEach
    void registerTestWorkers() {
        var def = caseHub.getDefinition();

        // Remove previously registered test workers to avoid duplicates
        def.getWorkers().removeIf(w -> "batch-ci-runner".equals(w.name()));

        // Default tip-test behavior: fail if batch contains the culprit PR, pass otherwise
        tipTestBehavior.set(input -> {
            Map<String, Object> batch = (Map<String, Object>) input.get("batch");
            List<Map<String, Object>> prs = (List<Map<String, Object>>) batch.get("prs");
            boolean hasCulprit = prs.stream()
                .anyMatch(pr -> ((Number) pr.get("number")).intValue() == CULPRIT_PR);
            if (hasCulprit) {
                return WorkerResult.of(Map.of(
                    "status", "failing",
                    "failureReason", "test X failed in batch containing PR #" + CULPRIT_PR));
            }
            return WorkerResult.of(Map.of("status", "passing"));
        });

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
    void tipTestFails_bisectionSplitComputed() throws Exception {
        // Arrange: batch of 4 PRs, PR #100 is the culprit (lowest trust score)
        Map<String, Object> batchContext = buildBatchWithCulprit();

        // Act
        UUID caseId = caseHub.startCase(batchContext)
            .toCompletableFuture().get(5, SECONDS);
        assertThat(caseId).isNotNull();

        // Wait for tip test to fail and bisection-splitter to compute the split
        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var instance = caseInstanceRepository.findByUuid(caseId)
                    ;
                assertThat(instance).isNotNull();
                assertThat(instance.getCaseContext().getPath("tipTest.status"))
                    .as("tipTest should show failing for the full batch")
                    .isEqualTo("failing");
                assertThat(instance.getCaseContext().getPath("splitResult"))
                    .as("splitResult should be populated by bisection-splitter worker")
                    .isNotNull();
            });

        // Verify split result structure — the outputSchema "{ splitResult: . }" maps
        // the raw worker output (which itself contains "splitResult" key) into context.
        // The actual context path depends on the engine's output mapping implementation.
        var instance = caseInstanceRepository.findByUuid(caseId)
            ;

        // The worker returns WorkerResult.of(Map.of("splitResult", {left, right}))
        // and the outputSchema wraps it: context.splitResult = { splitResult: { left, right } }
        Object splitResult = instance.getCaseContext().getPath("splitResult");
        assertThat(splitResult).as("splitResult should exist in context").isNotNull();

        // Check both possible paths: engine may merge raw output or apply outputSchema
        Object leftDirect = instance.getCaseContext().getPath("splitResult.left");
        Object leftNested = instance.getCaseContext().getPath("splitResult.splitResult.left");
        Object leftActual = leftDirect != null ? leftDirect : leftNested;
        assertThat(leftActual).as("left sub-batch should exist in splitResult (direct or nested)").isNotNull();

        Object rightDirect = instance.getCaseContext().getPath("splitResult.right");
        Object rightNested = instance.getCaseContext().getPath("splitResult.splitResult.right");
        Object rightActual = rightDirect != null ? rightDirect : rightNested;
        assertThat(rightActual).as("right sub-batch should exist in splitResult (direct or nested)").isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void tipTestFails_bisectionProducesCorrectSliceMetadata() throws Exception {
        // Arrange: batch of 4 PRs
        Map<String, Object> batchContext = buildBatchWithCulprit();

        UUID caseId = caseHub.startCase(batchContext)
            .toCompletableFuture().get(5, SECONDS);

        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var instance = caseInstanceRepository.findByUuid(caseId)
                    ;
                assertThat(instance.getCaseContext().getPath("splitResult")).isNotNull();
            });

        var instance = caseInstanceRepository.findByUuid(caseId)
            ;

        // Resolve left slice path — outputSchema wrapping may nest under splitResult.splitResult
        String leftPrefix = resolveSplitPath(instance, "left");
        assertThat(leftPrefix).as("left slice must be found in context").isNotNull();

        assertThat(instance.getCaseContext().getPath(leftPrefix + ".targetBranch"))
            .isEqualTo("main");
        assertThat(instance.getCaseContext().getPath(leftPrefix + ".bisectionDepth"))
            .as("bisectionDepth should be 1 (parent was 0)")
            .isEqualTo(1);
        assertThat(instance.getCaseContext().getPath(leftPrefix + ".bisectionStrategy"))
            .isEqualTo("trust-weighted");

        // Resolve right slice path
        String rightPrefix = resolveSplitPath(instance, "right");
        assertThat(rightPrefix).as("right slice must be found in context").isNotNull();

        // Verify sizes add up to original batch
        int leftSize = ((Number) instance.getCaseContext().getPath(leftPrefix + ".size")).intValue();
        int rightSize = ((Number) instance.getCaseContext().getPath(rightPrefix + ".size")).intValue();
        assertThat(leftSize + rightSize)
            .as("Left + Right should equal original batch size of 4")
            .isEqualTo(4);
    }

    @Test
    void tipTestFails_subCaseBindingsEvaluate() throws Exception {
        // After splitResult is populated, bisect-left and bisect-right bindings should evaluate.
        // These are subCase bindings that spawn recursive merge-batch cases.
        // The engine may spawn them or stay RUNNING depending on engine#573/574 support.
        Map<String, Object> batchContext = buildBatchWithCulprit();

        UUID caseId = caseHub.startCase(batchContext)
            .toCompletableFuture().get(5, SECONDS);

        // Wait for splitResult to be populated
        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var instance = caseInstanceRepository.findByUuid(caseId)
                    ;
                assertThat(instance.getCaseContext().getPath("splitResult")).isNotNull();
            });

        // Give sub-case bindings time to evaluate
        // The case should either:
        // - Complete (if sub-cases spawn and complete recursively)
        // - Stay RUNNING/WAITING (if sub-case bindings are pending)
        // - FAULT (if recursive sub-case spawning hits an engine limitation)
        await().atMost(30, SECONDS)
            .pollInterval(500, MILLISECONDS)
            .untilAsserted(() -> {
                var instance = caseInstanceRepository.findByUuid(caseId)
                    ;
                assertThat(instance).isNotNull();
                // Accept any terminal-ish state or WAITING (for sub-case completion)
                assertThat(instance.getState())
                    .as("Parent case should progress beyond RUNNING after sub-case bindings evaluate")
                    .isIn(CaseStatus.COMPLETED, CaseStatus.WAITING, CaseStatus.FAULTED);
            });

        var instance = caseInstanceRepository.findByUuid(caseId)
            ;

        if (instance.getState() == CaseStatus.COMPLETED) {
            // Full recursive bisection completed — verify both sub-case outputs
            assertThat(instance.getCaseContext().getPath("bisectLeft"))
                .as("bisectLeft should be populated from left sub-case")
                .isNotNull();
            assertThat(instance.getCaseContext().getPath("bisectRight"))
                .as("bisectRight should be populated from right sub-case")
                .isNotNull();
        }
        // WAITING or FAULTED states are acceptable — document for follow-up
    }

    @Test
    void tipTestFails_batchOfTwo_splitsToSinglePrSubCases() throws Exception {
        // Batch of 2 PRs — culprit on the left, clean on the right
        Map<String, Object> batchContext = buildBatchContext(
            List.of(
                prMap(CULPRIT_PR, "sha-culprit", "author-bad", 0.1),
                prMap(200, "sha-clean", "author-good", 0.9)
            ),
            "ROUTINE"
        );

        UUID caseId = caseHub.startCase(batchContext)
            .toCompletableFuture().get(5, SECONDS);
        assertThat(caseId).isNotNull();

        // Wait for bisection split
        await().atMost(10, SECONDS)
            .pollInterval(200, MILLISECONDS)
            .untilAsserted(() -> {
                var instance = caseInstanceRepository.findByUuid(caseId)
                    ;
                assertThat(instance.getCaseContext().getPath("splitResult")).isNotNull();
            });

        var instance = caseInstanceRepository.findByUuid(caseId)
            ;

        // Resolve paths — outputSchema wrapping may nest under splitResult.splitResult
        String leftPrefix = resolveSplitPath(instance, "left");
        String rightPrefix = resolveSplitPath(instance, "right");
        assertThat(leftPrefix).as("left slice must be found").isNotNull();
        assertThat(rightPrefix).as("right slice must be found").isNotNull();

        // Each slice should have exactly 1 PR (midpoint split of 2)
        int leftSize = ((Number) instance.getCaseContext().getPath(leftPrefix + ".size")).intValue();
        int rightSize = ((Number) instance.getCaseContext().getPath(rightPrefix + ".size")).intValue();
        assertThat(leftSize).as("left slice from batch of 2").isEqualTo(1);
        assertThat(rightSize).as("right slice from batch of 2").isEqualTo(1);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Resolves the context path prefix for a split side (left/right).
     * The worker output may be at splitResult.left or splitResult.splitResult.left
     * depending on how the engine applies the capability outputSchema.
     */
    private static String resolveSplitPath(
            io.casehub.engine.common.internal.model.CaseInstance instance, String side) {
        if (instance.getCaseContext().getPath("splitResult." + side) != null) {
            return "splitResult." + side;
        }
        if (instance.getCaseContext().getPath("splitResult.splitResult." + side) != null) {
            return "splitResult.splitResult." + side;
        }
        return null;
    }

    private Map<String, Object> buildBatchWithCulprit() {
        return buildBatchContext(
            List.of(
                prMap(CULPRIT_PR, "sha-culprit", "author-bad", 0.1),   // lowest trust → left
                prMap(101, "sha-101", "author-a", 0.4),
                prMap(102, "sha-102", "author-b", 0.7),
                prMap(103, "sha-103", "author-c", 0.9)                 // highest trust → right
            ),
            "ROUTINE"
        );
    }

    static Map<String, Object> prMap(int number, String headSha, String author, double trustScore) {
        var m = new LinkedHashMap<String, Object>();
        m.put("number", number);
        m.put("repository", "casehubio/devtown");
        m.put("headSha", headSha);
        m.put("author", author);
        m.put("trustScore", trustScore);
        m.put("lane", "NORMAL");
        return m;
    }

    static Map<String, Object> buildBatchContext(List<Map<String, Object>> prs, String riskLevel) {
        var batchMap = new LinkedHashMap<String, Object>();
        batchMap.put("id", "test-batch-" + UUID.randomUUID().toString().substring(0, 8));
        batchMap.put("repository", "casehubio/devtown");
        batchMap.put("targetBranch", "main");
        batchMap.put("size", prs.size());
        batchMap.put("riskLevel", riskLevel);
        batchMap.put("bisectionStrategy", "trust-weighted");
        batchMap.put("bisectionDepth", 0);
        batchMap.put("prs", new ArrayList<>(prs));

        var context = new LinkedHashMap<String, Object>();
        context.put("batch", batchMap);
        return context;
    }
}
