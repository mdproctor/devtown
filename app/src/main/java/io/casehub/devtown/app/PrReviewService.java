package io.casehub.devtown.app;

import io.casehub.devtown.review.PrPayload;
import io.casehub.devtown.review.PrReviewApplicationService;
import io.casehub.devtown.review.PrReviewOutcome;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
@DefaultBean
public class PrReviewService implements PrReviewApplicationService {

    @Override
    public PrReviewOutcome review(PrPayload pr) {
        var securityFindings = analyzeSecurityDirectly(pr);
        var architectureFindings = reviewArchitectureDirectly(pr);
        var allFindings = new ArrayList<String>(securityFindings);
        allFindings.addAll(architectureFindings);
        return new PrReviewOutcome("reviewed", allFindings);
    }

    private List<String> analyzeSecurityDirectly(PrPayload pr) {
        return List.of("security-analysis-complete");
    }

    private List<String> reviewArchitectureDirectly(PrPayload pr) {
        return List.of("architecture-review-complete");
    }
}
