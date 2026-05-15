package io.casehub.devtown.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NaivePrReviewServiceTest {

    private final NaivePrReviewService service = new NaivePrReviewService();

    @Test
    void review_returnsNonNullOutcome() {
        var pr = new PrPayload("casehubio/devtown", 42, "abc123", 150);
        var outcome = service.review(pr);
        assertThat(outcome).isNotNull();
    }

    @Test
    void review_verdictIsNonBlank() {
        var pr = new PrPayload("casehubio/devtown", 42, "abc123", 150);
        var outcome = service.review(pr);
        assertThat(outcome.verdict()).isNotBlank();
    }

    @Test
    void review_findingsIsNonNull() {
        var pr = new PrPayload("casehubio/devtown", 42, "abc123", 150);
        var outcome = service.review(pr);
        assertThat(outcome.findings()).isNotNull();
    }
}
