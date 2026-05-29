package io.casehub.devtown.app.agents;

import io.casehub.devtown.domain.ReviewDomain;
import io.casehub.devtown.review.PrPayload;
import io.casehub.devtown.review.ReviewerAgent;
import io.casehub.devtown.review.ReviewerOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

@ApplicationScoped
public class TestCoverageReviewAgent implements ReviewerAgent {

    @Override
    public String capability() {
        return ReviewDomain.TEST_COVERAGE;
    }

    @Override
    public ReviewerOutcome handle(PrPayload pr) {
        return new ReviewerOutcome.Completed(List.of("coverage 67%; payment path untested"));
    }
}
