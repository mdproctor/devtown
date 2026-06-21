package io.casehub.devtown.app;

import io.casehub.devtown.review.LifecycleResult;
import io.casehub.devtown.review.PrPayload;
import io.casehub.devtown.review.PrReviewApplicationService;
import io.casehub.devtown.review.PrReviewOutcome;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@DefaultBean
public class PrReviewService implements PrReviewApplicationService {

    @Override
    public PrReviewOutcome startReview(PrPayload pr) {
        var securityFindings = analyzeSecurityDirectly(pr);
        var architectureFindings = reviewArchitectureDirectly(pr);
        var allFindings = new ArrayList<String>(securityFindings);
        allFindings.addAll(architectureFindings);
        return new PrReviewOutcome("reviewed", allFindings);
    }

    @Override
    public LifecycleResult revisePr(String repo, int prNumber, String newHeadSha, int linesChanged) {
        return LifecycleResult.NO_ACTIVE_CASE;
    }

    @Override
    public LifecycleResult closePr(String repo, int prNumber, boolean merged) {
        return LifecycleResult.NO_ACTIVE_CASE;
    }

    @Override
    public LifecycleResult signalCiStatus(String repo, int prNumber, String headSha, long suiteId, String conclusion) {
        return LifecycleResult.NO_ACTIVE_CASE;
    }

    @Override
    public LifecycleResult signalCheckRun(String repo, int prNumber, String headSha, String checkName, String conclusion, Instant completedAt) {
        return LifecycleResult.NO_ACTIVE_CASE;
    }

    private List<String> analyzeSecurityDirectly(PrPayload pr) {
        return List.of("security-analysis-complete");
    }

    private List<String> reviewArchitectureDirectly(PrPayload pr) {
        return List.of("architecture-review-complete");
    }
}
