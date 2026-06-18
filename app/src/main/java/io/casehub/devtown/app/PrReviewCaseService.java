package io.casehub.devtown.app;

import io.casehub.devtown.app.mcp.PrReviewCaseTracker;
import io.casehub.devtown.review.PrPayload;
import io.casehub.devtown.review.PrReviewApplicationService;
import io.casehub.devtown.review.PrReviewOutcome;
import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
@Alternative
@Priority(2)
public class PrReviewCaseService implements PrReviewApplicationService {

    private static final String VERDICT_CASE_OPENED = "case-opened";

    @Inject
    PrReviewCaseHub caseHub;

    @Inject
    CaseMemoryRecaller memoryRecaller;

    @Inject
    PrReviewCaseTracker caseTracker;

    @Inject
    CurrentPrincipal principal;

    @ConfigProperty(name = "devtown.policy.human-approval-threshold", defaultValue = "500")
    int humanApprovalThreshold;

    @ConfigProperty(name = "devtown.policy.security-review-required", defaultValue = "true")
    boolean securityReviewRequired;

    @ConfigProperty(name = "devtown.policy.require-senior-approval", defaultValue = "false")
    boolean requireSeniorApproval;

    @Override
    public PrReviewOutcome review(PrPayload pr) {
        var memoryContext = memoryRecaller.recall(pr);

        // TODO(parent#26): replace @ConfigProperty injection with PreferenceProvider.resolve(scope).asMap()
        var policy = Map.<String, Object>of(
            "humanApprovalThreshold", humanApprovalThreshold,
            "securityReviewRequired", securityReviewRequired,
            "requireSeniorApproval", requireSeniorApproval
        );
        var prContext = Map.<String, Object>of(
            "id", String.valueOf(pr.prNumber()),
            "repo", pr.repo(),
            "linesChanged", pr.linesChanged(),
            "baseRef", pr.baseRef(),
            "headSha", pr.headSha(),
            "contributor", pr.contributor(),
            "changedPaths", pr.changedPaths()
        );
        var initialContext = new HashMap<String, Object>();
        initialContext.put("pr", prContext);
        initialContext.put("policy", policy);
        initialContext.put("memory", memoryContext.toContextMap());

        // CompletionStage<UUID> case ID — not surfaced in PrReviewOutcome until Layer 6 adds case tracking (devtown#10)
        var caseId = caseHub.startCase(initialContext);
        caseId.thenAccept(id -> caseTracker.register(id, principal.tenancyId(), pr));
        return new PrReviewOutcome(VERDICT_CASE_OPENED, List.of());
    }
}
