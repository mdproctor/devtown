package io.casehub.devtown.app;

import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.devtown.domain.MergeClient;
import io.casehub.devtown.domain.MergeOutcome;
import io.casehub.devtown.domain.RevertClient;
import io.casehub.devtown.domain.RevertOutcome;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class CoordinatedChangeCaseHub extends YamlCaseHub {

    @Inject
    MergeClient mergeClient;
    @Inject
    RevertClient revertClient;


    public CoordinatedChangeCaseHub() {
        super("casehub/devtown/coordinated-change.yaml");
    }

    @Override
    protected void augment(CaseDefinition definition) {
        definition.getWorkers().add(Worker.builder()
                                          .name("coordinated-merge")
                                          .capabilityName("coordinated-merge")
                                          .function(this::adaptCoordinatedMerge)
                                          .build());
        definition.getWorkers().add(Worker.builder()
                                          .name("coordinated-rollback")
                                          .capabilityName("coordinated-rollback")
                                          .function(this::adaptCoordinatedRollback)
                                          .build());
    }

    @SuppressWarnings("unchecked")
    WorkerResult adaptCoordinatedMerge(Map<String, Object> input) {
        List<Map<String, Object>> repos = (List<Map<String, Object>>) input.get("repos");
        List<Map<String, Object>> mergeResults = new ArrayList<>();

        for (Map<String, Object> repo : repos) {
            String owner = (String) repo.get("owner");
            String repoName = (String) repo.get("repo");
            int prNumber = ((Number) repo.get("prNumber")).intValue();
            String headSha = (String) repo.get("headSha");

            var result = new LinkedHashMap<String, Object>();
            result.put("repo", owner + "/" + repoName);

            switch (mergeClient.merge(owner, repoName, prNumber, headSha)) {
                case MergeOutcome.Success s -> {
                    result.put("status", "success");
                    result.put("mergeSha", s.mergeSha());
                }
                case MergeOutcome.Failure f -> {
                    result.put("status", "failed");
                    result.put("reason", f.reason());
                    mergeResults.add(result);
                    return WorkerResult.of(Map.of("mergeResults", mergeResults));
                }
            }
            mergeResults.add(result);
        }
        return WorkerResult.of(Map.of("mergeResults", mergeResults));
    }

    @SuppressWarnings("unchecked")
    WorkerResult adaptCoordinatedRollback(Map<String, Object> input) {
        List<Map<String, Object>> repos           = (List<Map<String, Object>>) input.get("repos");
        List<Map<String, Object>> mergeResults    = (List<Map<String, Object>>) input.get("mergeResults");
        List<Map<String, Object>> rollbackResults = new ArrayList<>();

        String failedRepo = mergeResults.stream()
                                        .filter(r -> "failed".equals(r.get("status")))
                                        .map(r -> (String) r.get("repo"))
                                        .findFirst()
                                        .orElse("unknown");

        Map<String, Map<String, Object>> repoIndex = new LinkedHashMap<>();
        for (Map<String, Object> repo : repos) {
            String key = repo.get("owner") + "/" + repo.get("repo");
            repoIndex.put(key, repo);
        }

        for (Map<String, Object> merge : mergeResults) {
            if (!"success".equals(merge.get("status"))) {continue;}

            String              repoKey   = (String) merge.get("repo");
            String              mergeSha  = (String) merge.get("mergeSha");
            Map<String, Object> repoEntry = repoIndex.get(repoKey);

            String owner        = (String) repoEntry.get("owner");
            String repoName     = (String) repoEntry.get("repo");
            String targetBranch = (String) repoEntry.get("targetBranch");
            int    prNumber     = ((Number) repoEntry.get("prNumber")).intValue();

            String commitMessage = "Revert " + repoKey + "#" + prNumber
                                   + " — coordinated rollback (merge failure in " + failedRepo + ")";

            var result = new LinkedHashMap<String, Object>();
            result.put("repo", repoKey);

            switch (revertClient.revert(owner, repoName, targetBranch, mergeSha, commitMessage)) {
                case RevertOutcome.Success s -> {
                    result.put("status", "success");
                    result.put("revertPrNumber", s.revertPrNumber());
                    result.put("revertSha", s.revertSha());
                }
                case RevertOutcome.MergeConflict c -> {
                    result.put("status", "conflict");
                    result.put("revertPrNumber", c.revertPrNumber());
                    result.put("reason", c.reason());
                }
                case RevertOutcome.Failure f -> {
                    result.put("status", "failed");
                    result.put("reason", f.reason());
                }
            }
            rollbackResults.add(result);
        }
        return WorkerResult.of(Map.of("rollbackResults", rollbackResults));
    }

}
