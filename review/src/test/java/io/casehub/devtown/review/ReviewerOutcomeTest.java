package io.casehub.devtown.review;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReviewerOutcomeTest {

    @Test
    void completed_holdsFindings() {
        var outcome = new ReviewerOutcome.Completed(List.of("finding-1", "finding-2"));
        assertThat(outcome.findings()).containsExactly("finding-1", "finding-2");
    }

    @Test
    void declined_holdsReason() {
        var outcome = new ReviewerOutcome.Declined("out of scope");
        assertThat(outcome.reason()).isEqualTo("out of scope");
    }

    @Test
    void patternMatch_coversAllPermits() {
        ReviewerOutcome completed = new ReviewerOutcome.Completed(List.of("f1"));
        ReviewerOutcome declined  = new ReviewerOutcome.Declined("reason");

        String c = switch (completed) {
            case ReviewerOutcome.Completed x -> "completed:" + x.findings().size();
            case ReviewerOutcome.Declined x  -> "declined";
        };
        String d = switch (declined) {
            case ReviewerOutcome.Completed x -> "completed";
            case ReviewerOutcome.Declined x  -> "declined:" + x.reason();
        };

        assertThat(c).isEqualTo("completed:1");
        assertThat(d).isEqualTo("declined:reason");
    }
}
