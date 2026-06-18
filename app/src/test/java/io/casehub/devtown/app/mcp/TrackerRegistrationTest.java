package io.casehub.devtown.app.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.devtown.review.PrPayload;
import io.casehub.devtown.review.PrReviewApplicationService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class TrackerRegistrationTest {

    @Inject PrReviewApplicationService reviewService;
    @Inject PrReviewCaseTracker tracker;

    @Test
    void review_registersCaseInTracker() {
        var pr = new PrPayload("casehubio/devtown", 99, "sha123", "main", 50, "bob", List.of("src/Foo.java"));
        reviewService.review(pr);

        var active = tracker.activeCases();
        assertThat(active).anyMatch(c ->
            c.payload().prNumber() == 99 && c.payload().repo().equals("casehubio/devtown"));
    }
}
