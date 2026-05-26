package io.casehub.devtown.app;

import io.casehub.devtown.review.PrPayload;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PrReviewServiceTest {

    private final PrReviewService service = new PrReviewService();

    @Test
    void review_returnsNonNullOutcome() {
        var pr = new PrPayload("casehubio/devtown", 42, "abc123", "main", 150);
        var outcome = service.review(pr);
        assertThat(outcome).isNotNull();
    }

    @Test
    void review_verdictIsNonBlank() {
        var pr = new PrPayload("casehubio/devtown", 42, "abc123", "main", 150);
        var outcome = service.review(pr);
        assertThat(outcome.verdict()).isNotBlank();
    }

    @Test
    void review_findingsIsNonNull() {
        var pr = new PrPayload("casehubio/devtown", 42, "abc123", "main", 150);
        var outcome = service.review(pr);
        assertThat(outcome.findings()).isNotNull();
    }
}
