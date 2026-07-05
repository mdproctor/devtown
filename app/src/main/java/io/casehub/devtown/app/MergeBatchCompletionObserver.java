package io.casehub.devtown.app;

import io.casehub.api.context.CaseContext;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Observes terminal {@link CaseLifecycleEvent} transitions for merge batch cases
 * and drives queue cleanup and WorkItem obsolescence.
 *
 * <p>Maps case status to batch outcome:
 * <ul>
 *   <li>{@code COMPLETED} → batchSucceeded = true → entries reach {@code MERGED}
 *   <li>{@code CANCELLED} → batchSucceeded = false → entries reach {@code DEQUEUED}
 *   <li>{@code FAULTED} → skipped (no cleanup)
 * </ul>
 *
 * <p>Filters out PR review cases (context has {@code pr.*}) and sub-case lifecycle
 * events (no {@code batch.*} context). Only root batch cases with {@code batch.*}
 * trigger queue cleanup.
 *
 * <p><strong>Tech debt:</strong> Uses {@link CrossTenantCaseInstanceRepository}
 * because there is no request-scoped tenant in the async observer context.
 * The repository's contract says "for startup recovery services only" — this
 * is accepted tech debt, identical to {@code MergeDecisionObserver} and
 * {@code ReviewOutcomeObserver}. Resolution: when {@code CaseLifecycleEvent}
 * carries case metadata directly, the lookup becomes unnecessary.
 */
@ApplicationScoped
public class MergeBatchCompletionObserver {

    private static final Logger LOG = Logger.getLogger(MergeBatchCompletionObserver.class);

    @Inject CrossTenantCaseInstanceRepository caseInstanceRepo;
    @Inject MergeQueueService mergeQueueService;

    void onCaseLifecycle(@ObservesAsync CaseLifecycleEvent event) {
        try {
            handleEvent(event);
        } catch (Exception e) {
            LOG.warnf(e, "MergeBatchCompletionObserver failed for caseId=%s", event.caseId());
        }
    }

    private void handleEvent(CaseLifecycleEvent event) {
        // Filter: only act on terminal states (COMPLETED or CANCELLED)
        Boolean batchSucceeded = switch (event.caseStatus()) {
            case "COMPLETED" -> true;
            case "CANCELLED" -> false;
            default -> {
                LOG.debugf("Ignoring non-terminal case status %s for caseId=%s",
                        event.caseStatus(), event.caseId());
                yield null;
            }
        };
        if (batchSucceeded == null) return;

        // Lookup CaseInstance
        CaseInstance ci;
        try {
            ci = caseInstanceRepo.findByUuid(event.caseId());
        } catch (Exception e) {
            LOG.warnf(e, "Failed to lookup CaseInstance for caseId=%s", event.caseId());
            return;
        }
        if (ci == null) {
            LOG.warnf("CaseInstance not found for caseId=%s", event.caseId());
            return;
        }

        CaseContext ctx = ci.getCaseContext();
        if (ctx == null) {
            LOG.warnf("CaseContext is null for caseId=%s", event.caseId());
            return;
        }

        // Filter: only act on batch cases (context has batch.*, NOT pr.*)
        String batchId = ctx.getPathAsString("batch.id");
        String prRepo = ctx.getPathAsString("pr.repo");
        if (batchId == null || prRepo != null) {
            // Either this is a PR review case (prRepo != null), or not a batch case at all
            return;
        }

        // Extract rejectedPrs from case context (may be empty list)
        Set<Integer> rejectedPrs = extractRejectedPrs(ctx);

        // Call service handler
        mergeQueueService.handleBatchCompletion(event.caseId(), batchSucceeded, rejectedPrs);
        LOG.debugf("Batch completion handled: caseId=%s succeeded=%s rejected=%s",
                event.caseId(), batchSucceeded, rejectedPrs);
    }

    @SuppressWarnings("unchecked")
    private static Set<Integer> extractRejectedPrs(CaseContext ctx) {
        Object rejectedObj = ctx.getPath("batch.rejectedPrs");
        if (rejectedObj instanceof List<?> list) {
            Set<Integer> rejected = new HashSet<>();
            for (Object item : list) {
                if (item instanceof Number n) {
                    rejected.add(n.intValue());
                } else if (item instanceof String s) {
                    try {
                        rejected.add(Integer.parseInt(s));
                    } catch (NumberFormatException ignored) {
                        // Skip malformed entries
                    }
                }
            }
            return rejected;
        }
        return Set.of();
    }
}
