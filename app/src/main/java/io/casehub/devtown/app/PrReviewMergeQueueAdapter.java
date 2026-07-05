package io.casehub.devtown.app;

import io.casehub.api.model.CaseDefinition;
import io.casehub.devtown.domain.queue.PriorityLane;
import io.casehub.devtown.queue.QueuedPr;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

/**
 * Registers the {@code merge-queue-enqueue} worker on the PR review case definition.
 *
 * <p>When the PR review case reaches merge-ready state and {@code policy.mergeQueueEnabled == true},
 * the {@code enqueue-for-merge} binding fires and delegates to this worker. The worker constructs
 * a {@link QueuedPr} from the case context and enqueues it via {@link MergeQueueService}.
 *
 * <p>This adapter lives in {@code app/} rather than {@code merge/} to avoid a circular dependency:
 * it must inject {@link PrReviewCaseHub} (also in {@code app/}).
 */
@ApplicationScoped
public class PrReviewMergeQueueAdapter {

    @Inject
    PrReviewCaseHub prReviewCaseHub;

    @Inject
    MergeQueueService mergeQueueService;

    @PostConstruct
    void registerWorker() {
        CaseDefinition def = prReviewCaseHub.getDefinition();
        var enqueueCapability = def.getCapabilities().stream()
            .filter(c -> "merge-queue-enqueue".equals(c.name()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "merge-queue-enqueue capability not found in pr-review.yaml"));

        def.getWorkers().add(Worker.builder()
            .name("merge-queue-enqueue")
            .capabilityName(enqueueCapability.name())
            .function(this::enqueue)
            .build());
    }

    WorkerResult enqueue(Map<String, Object> input) {
        @SuppressWarnings("unchecked")
        Map<String, Object> pr = (Map<String, Object>) input.get("pr");

        int prNumber = Integer.parseInt((String) pr.get("id"));
        String repository = (String) pr.get("repo");
        String headSha = (String) pr.get("headSha");
        String author = (String) pr.get("contributor");

        // Construct QueuedPr
        QueuedPr queuedPr = new QueuedPr(
            prNumber,
            repository,
            headSha,
            author,
            0.5,  // trustScore — neutral default (spec §6.3)
            PriorityLane.NORMAL,  // lane — automated enqueues always use NORMAL
            Instant.now(),  // enqueuedAt
            Set.of()  // dependsOn — empty (dependency extraction is devtown#101)
        );

        boolean inserted = mergeQueueService.enqueue(queuedPr);
        return WorkerResult.of(Map.of(
            "status", inserted ? "enqueued" : "already-queued",
            "prNumber", prNumber,
            "repository", repository
        ));
    }
}
