package io.casehub.devtown.app;

import io.casehub.devtown.review.PrPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NaivePrReviewServiceTest {

    private final NaivePrReviewService service = new NaivePrReviewService();
    private PrPayload pr;

    @BeforeEach
    void setUp() {
        pr = new PrPayload("casehubio/devtown", 42, "abc123", "main", 150);
    }

    @Test
    void review_returnsNonNullOutcome() {
        assertThat(service.review(pr)).isNotNull();
    }

    @Test
    void review_verdictIsNonBlank() {
        assertThat(service.review(pr).verdict()).isNotBlank();
    }

    @Test
    void review_findingsIsNonNull() {
        assertThat(service.review(pr).findings()).isNotNull();
    }
}
