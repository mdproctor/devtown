package io.casehub.devtown.domain;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MergeOutcomeTest {

    @Test
    void success_carriesMergeSha() {
        var outcome = new MergeOutcome.Success("abc123def");
        assertThat(outcome.mergeSha()).isEqualTo("abc123def");
        assertThat(outcome).isInstanceOf(MergeOutcome.class);
    }

    @Test
    void failure_carriesReason() {
        var outcome = new MergeOutcome.Failure("merge conflict");
        assertThat(outcome.reason()).isEqualTo("merge conflict");
        assertThat(outcome).isInstanceOf(MergeOutcome.class);
    }

    @Test
    void switchExpression_exhaustive() {
        MergeOutcome outcome = new MergeOutcome.Success("sha");
        String result = switch (outcome) {
            case MergeOutcome.Success s -> s.mergeSha();
            case MergeOutcome.Failure f -> f.reason();
        };
        assertThat(result).isEqualTo("sha");
    }
}
