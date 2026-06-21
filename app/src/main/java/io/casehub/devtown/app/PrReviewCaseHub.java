package io.casehub.devtown.app;

import io.casehub.api.engine.YamlCaseHub;
import io.casehub.api.model.CaseDefinition;
import io.casehub.api.model.Worker;
import io.casehub.api.model.WorkerResult;
import io.casehub.devtown.domain.MergeClient;
import io.casehub.devtown.domain.MergeOutcome;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class PrReviewCaseHub extends YamlCaseHub {

    @Inject
    MergeClient mergeClient;

    public PrReviewCaseHub() {
        super("devtown/pr-review.yaml");
    }

    @PostConstruct
    void registerWorkers() {
        CaseDefinition def = super.getDefinition();
        var mergeCap = def.getCapabilities().stream()
            .filter(c -> "merge-executor".equals(c.getName()))
            .findFirst().orElseThrow();
        def.getWorkers().add(Worker.builder()
            .name("merge-executor")
            .capabilities(mergeCap)
            .function(this::adaptMerge)
            .build());
    }

    WorkerResult adaptMerge(Map<String, Object> input) {
        @SuppressWarnings("unchecked")
        Map<String, Object> pr = (Map<String, Object>) input.get("pr");
        String repo = (String) pr.get("repo");
        String[] parts = repo.split("/");
        int prNumber = Integer.parseInt((String) pr.get("id"));
        String headSha = (String) pr.get("headSha");

        return switch (mergeClient.merge(parts[0], parts[1], prNumber, headSha)) {
            case MergeOutcome.Success s -> WorkerResult.of(Map.of("merge_sha", s.mergeSha()));
            case MergeOutcome.Failure f -> WorkerResult.failed(f.reason());
        };
    }
}
