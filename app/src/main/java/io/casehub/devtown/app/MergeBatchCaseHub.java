package io.casehub.devtown.app;

import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.devtown.domain.BatchBranchClient;
import io.casehub.devtown.domain.BatchBranchOutcome;
import io.casehub.devtown.domain.PrRef;
import io.casehub.devtown.domain.queue.PriorityLane;
import io.casehub.devtown.queue.BisectionSplitStrategy;
import io.casehub.devtown.queue.QueuedPr;
import io.casehub.devtown.queue.SplitResult;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class MergeBatchCaseHub extends YamlCaseHub {

    @Inject
    BisectionSplitStrategy splitStrategy;
    @Inject
    BatchBranchClient      batchBranchClient;


    public MergeBatchCaseHub() {
        super("devtown/merge-batch.yaml");
    }

    @Override
    protected void augment(CaseDefinition definition) {
        definition.getWorkers().add(Worker.builder()
                                          .name("bisection-splitter")
                                          .capabilityName("bisection-splitter")
                                          .function(this::adaptBisectionSplit)
                                          .build());

        definition.getWorkers().add(Worker.builder()
                                          .name("pr-reject-and-notify")
                                          .capabilityName("pr-reject-and-notify")
                                          .function(this::adaptRejectAndNotify)
                                          .build());

        definition.getWorkers().add(Worker.builder()
                                          .name("batch-ci-runner")
                                          .capabilityName("batch-ci-runner")
                                          .function(this::adaptBatchCiRunner)
                                          .build());
    }

    @SuppressWarnings("unchecked")
    WorkerResult adaptBatchCiRunner(Map<String, Object> input) {
        Map<String, Object> batch      = (Map<String, Object>) input.get("batch");
        String              repository = (String) batch.get("repository");
        if (repository == null || !repository.contains("/")) {
            return WorkerResult.failed("batch context missing or invalid 'repository': " + repository);
        }
        String[] parts        = repository.split("/");
        String   targetBranch = (String) batch.get("targetBranch");
        String   batchId      = (String) batch.get("id");

        List<Map<String, Object>> prMaps = (List<Map<String, Object>>) batch.get("prs");
        List<PrRef> prs = prMaps.stream()
                                .map(m -> new PrRef(
                                        ((Number) m.get("number")).intValue(),
                                        (String) m.get("headSha")))
                                .toList();

        return switch (batchBranchClient.createBatchBranch(parts[0], parts[1], targetBranch, batchId, prs)) {
            case BatchBranchOutcome.Created c -> WorkerResult.of(Map.of(
                    "status", "branch-created",
                    "branch", c.branchName(),
                    "tipSha", c.tipSha()));
            case BatchBranchOutcome.MergeConflict mc -> WorkerResult.failed(
                    "merge conflict on PR #" + mc.conflictPrNumber(),
                    Map.of("status", "conflict",
                           "conflictPr", mc.conflictPrNumber(),
                           "branch", mc.branchName()));
            case BatchBranchOutcome.Failure f -> WorkerResult.failed(f.reason());
        };
    }


    @SuppressWarnings("unchecked")
    WorkerResult adaptBisectionSplit(Map<String, Object> input) {
        List<Map<String, Object>> prMaps   = (List<Map<String, Object>>) input.get("prs");
        String                    strategy = (String) input.getOrDefault("strategy", "trust-weighted");

        Map<String, Object> batch        = (Map<String, Object>) input.get("batch");
        String              batchId      = batch != null ? (String) batch.getOrDefault("id", "unknown") : "unknown";
        String              repository   = batch != null ? (String) batch.getOrDefault("repository", "unknown") : "unknown";
        String              targetBranch = batch != null ? (String) batch.getOrDefault("targetBranch", "main") : "main";
        int bisectionDepth = batch != null
                             ? ((Number) batch.getOrDefault("bisectionDepth", 0)).intValue()
                             : 0;
        String riskLevel = batch != null ? (String) batch.getOrDefault("riskLevel", "ROUTINE") : "ROUTINE";

        List<QueuedPr> prs = prMaps.stream()
                                   .map(MergeBatchCaseHub::mapToQueuedPr)
                                   .toList();

        SplitResult result = splitStrategy.split(
                prs, repository, batchId, targetBranch, bisectionDepth + 1, strategy, riskLevel);

        var output = new LinkedHashMap<String, Object>();
        output.put("left", sliceToMap(result.left()));
        output.put("right", sliceToMap(result.right()));
        return WorkerResult.of(Map.of("splitResult", output));}

    WorkerResult adaptRejectAndNotify(Map<String, Object> input) {
        @SuppressWarnings("unchecked")
        Map<String, Object> pr = (Map<String, Object>) input.get("pr");
        String reason = (String) input.get("reason");
        String batchId = (String) input.get("batchId");

        // In-memory rejection — connectors will handle actual GitHub notification
        var rejected = Map.of(
            "pr", pr,
            "reason", reason != null ? reason : "tip-test-failure",
            "batchId", batchId != null ? batchId : "unknown"
        );
        return WorkerResult.of(Map.of("rejectedPrs", List.of(rejected)));
    }

    private Map<String, Object> sliceToMap(io.casehub.devtown.queue.BatchSlice slice) {
        var map = new LinkedHashMap<String, Object>();
        map.put("id", slice.id());
        map.put("repository", slice.repository());
        map.put("targetBranch", slice.targetBranch());
        map.put("prs", slice.prs().stream().map(MergeBatchCaseHub::queuedPrToMap).toList());
        map.put("size", slice.size());
        map.put("parentBatchId", slice.parentBatchId());
        map.put("bisectionDepth", slice.bisectionDepth());
        map.put("bisectionStrategy", slice.bisectionStrategy());
        map.put("riskLevel", slice.riskLevel());
        return map;}

    private static QueuedPr mapToQueuedPr(Map<String, Object> m) {
        return new QueuedPr(
                ((Number) m.get("number")).intValue(),
                (String) m.getOrDefault("repository", "unknown"),
                (String) m.getOrDefault("headSha", ""),
                (String) m.getOrDefault("author", "unknown"),
                m.containsKey("trustScore") ? ((Number) m.get("trustScore")).doubleValue() : 0.5,
                PriorityLane.NORMAL,
                Instant.now(),
                Set.of(),
                m.containsKey("riskScore") ? ((Number) m.get("riskScore")).doubleValue() : 0.0
        );
    }

    private static Map<String, Object> queuedPrToMap(QueuedPr pr) {
        var map = new LinkedHashMap<String, Object>();
        map.put("number", pr.number());
        map.put("repository", pr.repository());
        map.put("headSha", pr.headSha());
        map.put("author", pr.author());
        map.put("trustScore", pr.trustScore());
        map.put("riskScore", pr.riskScore());
        return map;
    }

}
