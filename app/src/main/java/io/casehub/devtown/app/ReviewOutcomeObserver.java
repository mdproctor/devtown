package io.casehub.devtown.app;

import io.casehub.api.context.CaseContext;
import io.casehub.engine.common.spi.event.PlanItemCompletedEvent;
import io.casehub.devtown.domain.memory.ReviewOutcome;
import io.casehub.devtown.review.PrPayload;
import io.casehub.devtown.review.ReviewCompletedEvent;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Observes {@link PlanItemCompletedEvent} from the engine blackboard, extracts
 * structured review data from the case context, and fires a typed
 * {@link ReviewCompletedEvent}.
 *
 * <p>This is the single component that touches engine internals. It bridges
 * the engine's plan-item completion model to devtown's typed event model.
 *
 * <p><strong>Tech debt:</strong> Uses {@link CrossTenantCaseInstanceRepository}
 * because there is no request-scoped tenant in the async observer context.
 * The repository's contract says "for startup recovery services only" — this
 * is accepted tech debt until {@code PlanItemCompletedEvent} carries tenantId.
 */
@ApplicationScoped
public class ReviewOutcomeObserver {

    private static final Logger LOG = Logger.getLogger(ReviewOutcomeObserver.class);

    /**
     * Hard-coded mapping from YAML binding name (planItemId) to the context key
     * path where the outcome is stored. The YAML case definition is static —
     * bindings and outputMappings are known at compile time.
     *
     * <p>Note: humanApproval uses {@code .status}, others use {@code .outcome} —
     * this inconsistency was established in Layer 2 and is intentional.
     */
    private static final Map<String, String> PLAN_ITEM_TO_CONTEXT_KEY = Map.of(
            "security-review",       "securityReview.outcome",
            "architecture-review",   "architectureReview.outcome",
            "style-check",           "styleCheck.outcome",
            "test-coverage",         "testCoverage.outcome",
            "performance-analysis",  "performanceAnalysis.outcome",
            "human-approval",        "humanApproval.status"
    );

    @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;
    @Inject Event<ReviewCompletedEvent> reviewCompletedEvents;

    void onPlanItemCompleted(@ObservesAsync PlanItemCompletedEvent event) {
        try {
            handleEvent(event);
        } catch (Exception e) {
            LOG.warnf(e, "ReviewOutcomeObserver failed for caseId=%s planItemId=%s",
                    event.caseId(), event.planItemId());
        }
    }

    private void handleEvent(PlanItemCompletedEvent event) {
        // Filter: skip infrastructure bindings not in the map
        String contextKeyPath = PLAN_ITEM_TO_CONTEXT_KEY.get(event.planItemId());
        if (contextKeyPath == null) {
            return;
        }

        // Lookup CaseInstance
        CaseInstance caseInstance;
        try {
            caseInstance = caseInstanceRepository.findByUuid(event.caseId());
        } catch (Exception e) {
            LOG.warnf(e, "Failed to lookup CaseInstance for caseId=%s", event.caseId());
            return;
        }
        if (caseInstance == null) {
            LOG.warnf("CaseInstance not found for caseId=%s", event.caseId());
            return;
        }

        String tenantId = caseInstance.tenancyId;
        CaseContext ctx = caseInstance.getCaseContext();
        if (ctx == null) {
            LOG.warnf("CaseContext is null for caseId=%s", event.caseId());
            return;
        }

        // Extract PR metadata
        PrPayload pr = extractPrPayload(ctx);
        if (pr == null) {
            LOG.warnf("Missing PR metadata in case context for caseId=%s", event.caseId());
            return;
        }

        // Extract outcome
        String rawOutcome = ctx.getPathAsString(contextKeyPath);
        ReviewOutcome outcome = ReviewOutcome.COMPLETED; // MVP: all completions map to COMPLETED
        String outcomeDetail = rawOutcome;

        // Resolve capability (binding name != capability name in some cases)
        String capability = resolveCapability(event.planItemId());

        // Resolve reviewer identity
        String reviewerId = event.trackingKey() != null ? event.trackingKey() : "unknown";

        // Fire typed event
        reviewCompletedEvents.fireAsync(new ReviewCompletedEvent(
                event.caseId(), tenantId, capability, reviewerId, outcome, outcomeDetail, pr));
    }

    @SuppressWarnings("unchecked")
    private static PrPayload extractPrPayload(CaseContext ctx) {
        String repo = ctx.getPathAsString("pr.repo");
        String prIdStr = ctx.getPathAsString("pr.id");
        String headSha = ctx.getPathAsString("pr.headSha");
        String baseRef = ctx.getPathAsString("pr.baseRef");

        if (repo == null || prIdStr == null || headSha == null || baseRef == null) {
            return null;
        }

        int prNumber;
        try {
            prNumber = Integer.parseInt(prIdStr);
        } catch (NumberFormatException e) {
            return null;
        }

        // linesChanged may be Integer or null
        Object linesObj = ctx.getPath("pr.linesChanged");
        int linesChanged = linesObj instanceof Number n ? n.intValue() : 0;

        String contributor = ctx.getPathAsString("pr.contributor");

        // changedPaths may be List or null
        Object pathsObj = ctx.getPath("pr.changedPaths");
        List<String> changedPaths = pathsObj instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of();

        return new PrPayload(repo, prNumber, headSha, baseRef, linesChanged, contributor, changedPaths);
    }

    /**
     * Maps binding name (planItemId) to capability name. Most are identical,
     * but two diverge due to the YAML definition structure.
     */
    private static String resolveCapability(String planItemId) {
        return switch (planItemId) {
            case "style-check" -> "style-review";
            case "human-approval" -> "human-decision:pr-approval";
            default -> planItemId;
        };
    }
}
