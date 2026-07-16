package io.casehub.devtown.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RevertOutcomeTest {

    @Test
    void success_carriesPrNumberAndSha() {
        var outcome = new RevertOutcome.Success(42, "abc123def");
        assertThat(outcome.revertPrNumber()).isEqualTo(42);
        assertThat(outcome.revertSha()).isEqualTo("abc123def");
        assertThat(outcome).isInstanceOf(RevertOutcome.class);
    }

    @Test
    void mergeConflict_carriesPrNumberAndReason() {
        var outcome = new RevertOutcome.MergeConflict(7, "conflict with subsequent commits");
        assertThat(outcome.revertPrNumber()).isEqualTo(7);
        assertThat(outcome.reason()).isEqualTo("conflict with subsequent commits");
        assertThat(outcome).isInstanceOf(RevertOutcome.class);
    }

    @Test
    void failure_carriesReason() {
        var outcome = new RevertOutcome.Failure("api error: HTTP 500");
        assertThat(outcome.reason()).isEqualTo("api error: HTTP 500");
        assertThat(outcome).isInstanceOf(RevertOutcome.class);
    }

    @Test
    void exhaustiveSwitchCoversAllCases() {
        RevertOutcome outcome = new RevertOutcome.Success(1, "sha");
        String result = switch (outcome) {
            case RevertOutcome.Success s -> "success:" + s.revertPrNumber();
            case RevertOutcome.MergeConflict mc -> "conflict:" + mc.revertPrNumber();
            case RevertOutcome.Failure f -> "failure:" + f.reason();
        };
        assertThat(result).isEqualTo("success:1");
    }
}
