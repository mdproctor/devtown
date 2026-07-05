package io.casehub.devtown.app;

import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.devtown.queue.BisectionSplitStrategy;
import io.casehub.devtown.queue.SplitResult;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class MergeBatchCaseHub extends YamlCaseHub {

    @Inject
    BisectionSplitStrategy splitStrategy;

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
    }

    @SuppressWarnings("unchecked")
    WorkerResult adaptBisectionSplit(Map<String, Object> input) {
        List<Map<String, Object>> prs = (List<Map<String, Object>>) input.get("prs");
        String strategy = (String) input.getOrDefault("strategy", "trust-weighted");

        // Extract batch context from input for split metadata
        Map<String, Object> batch = (Map<String, Object>) input.get("batch");
        String batchId = batch != null ? (String) batch.getOrDefault("id", "unknown") : "unknown";
        String targetBranch = batch != null ? (String) batch.getOrDefault("targetBranch", "main") : "main";
        int bisectionDepth = batch != null
            ? ((Number) batch.getOrDefault("bisectionDepth", 0)).intValue()
            : 0;
        String riskLevel = batch != null ? (String) batch.getOrDefault("riskLevel", "ROUTINE") : "ROUTINE";

        SplitResult result = splitStrategy.split(
            prs, batchId, targetBranch, bisectionDepth + 1, strategy, riskLevel);

        var output = new LinkedHashMap<String, Object>();
        output.put("left", sliceToMap(result.left()));
        output.put("right", sliceToMap(result.right()));
        return WorkerResult.of(Map.of("splitResult", output));
    }

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
        map.put("targetBranch", slice.targetBranch());
        map.put("prs", slice.prs());
        map.put("size", slice.size());
        map.put("parentBatchId", slice.parentBatchId());
        map.put("bisectionDepth", slice.bisectionDepth());
        map.put("bisectionStrategy", slice.bisectionStrategy());
        map.put("riskLevel", slice.riskLevel());
        return map;
    }
}
