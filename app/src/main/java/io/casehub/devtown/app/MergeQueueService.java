package io.casehub.devtown.app;

import io.casehub.devtown.queue.Batch;
import io.casehub.devtown.queue.BatchCompositionPolicy;
import io.casehub.devtown.queue.BatchFormationContext;
import io.casehub.devtown.queue.QueuedPr;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MergeQueueService {

    private static final Logger LOG = Logger.getLogger(MergeQueueService.class);

    private static final int DEFAULT_MAX_BATCH_SIZE = 10;
    private static final int DEFAULT_MIN_BATCH_SIZE = 1;
    private static final double DEFAULT_DECAY_RATE = 0.1;
    private static final String DEFAULT_TARGET_BRANCH = "main";
    private static final String DEFAULT_RISK_LEVEL = "ROUTINE";
    private static final String DEFAULT_BISECTION_STRATEGY = "trust-weighted";

    private final List<QueuedPr> queue = new CopyOnWriteArrayList<>();
    private final Map<UUID, Batch> activeBatches = new ConcurrentHashMap<>();

    @Inject
    MergeBatchCaseHub mergeBatchCaseHub;

    @Inject
    BatchCompositionPolicy compositionPolicy;

    public void enqueue(QueuedPr pr) {
        queue.add(pr);
        LOG.infof("Enqueued PR #%d (trust=%.2f, lane=%s)", pr.number(), pr.trustScore(), pr.lane());
    }

    public List<UUID> formAndDispatchBatches() {
        if (queue.isEmpty()) {
            return List.of();
        }

        var ctx = new BatchFormationContext(
            Instant.now(),
            DEFAULT_MAX_BATCH_SIZE,
            DEFAULT_MIN_BATCH_SIZE,
            DEFAULT_DECAY_RATE,
            0.0,
            DEFAULT_TARGET_BRANCH,
            DEFAULT_RISK_LEVEL,
            DEFAULT_BISECTION_STRATEGY,
            new AtomicInteger(0)
        );

        List<Batch> batches = compositionPolicy.formBatches(new ArrayList<>(queue), ctx);
        queue.clear();

        List<UUID> caseIds = new ArrayList<>();
        for (Batch batch : batches) {
            UUID caseId = dispatchBatch(batch);
            activeBatches.put(caseId, batch);
            caseIds.add(caseId);
            LOG.infof("Dispatched batch %s (%d PRs) as case %s", batch.id(), batch.size(), caseId);
        }
        return caseIds;
    }

    public void handleBatchCompletion(UUID caseId) {
        Batch batch = activeBatches.remove(caseId);
        if (batch == null) {
            LOG.warnf("No active batch found for case %s", caseId);
            return;
        }
        LOG.infof("Batch %s completed (case %s)", batch.id(), caseId);
    }

    public int queueSize() {
        return queue.size();
    }

    public int activeBatchCount() {
        return activeBatches.size();
    }

    public List<QueuedPr> queuedPrs() {
        return List.copyOf(queue);
    }

    public Map<UUID, Batch> activeBatches() {
        return Map.copyOf(activeBatches);
    }

    public boolean dequeue(int prNumber) {
        return queue.removeIf(pr -> pr.number() == prNumber);
    }

    private UUID dispatchBatch(Batch batch) {
        var batchContext = new LinkedHashMap<String, Object>();
        var batchMap = new LinkedHashMap<String, Object>();
        batchMap.put("id", batch.id());
        batchMap.put("targetBranch", batch.targetBranch());
        batchMap.put("size", batch.size());
        batchMap.put("riskLevel", batch.riskLevel());
        batchMap.put("bisectionStrategy", batch.bisectionStrategy());
        batchMap.put("bisectionDepth", 0);

        List<Map<String, Object>> prMaps = new ArrayList<>();
        for (var pr : batch.prs()) {
            var prMap = new LinkedHashMap<String, Object>();
            prMap.put("number", pr.number());
            prMap.put("headSha", pr.headSha());
            prMap.put("author", pr.author());
            prMap.put("trustScore", pr.trustScore());
            prMap.put("lane", pr.lane().name());
            prMaps.add(prMap);
        }
        batchMap.put("prs", prMaps);

        batchContext.put("batch", batchMap);

        return mergeBatchCaseHub.startCase(batchContext).toCompletableFuture().join();
    }
}
