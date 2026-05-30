package io.casehub.devtown.domain;

import org.junit.jupiter.api.Test;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingPolicyTest {

    private static final RoutingPolicy SECURITY_POLICY = new RoutingPolicy(
        OptionalDouble.of(0.70),
        OptionalInt.of(10),
        OptionalDouble.of(0.05),
        Optional.of(HumanOversight.ROUTING_REVIEW),
        "Security mistakes reach production; 10 observations required for credible score"
    );

    private static final RoutingPolicy NO_GATE_POLICY = new RoutingPolicy(
        OptionalDouble.of(0.50),
        OptionalInt.empty(),
        OptionalDouble.empty(),
        Optional.empty(),
        "No observation gate"
    );

    @Test
    void isBootstrapReturnsTrueWhenBelowMinimumObservations() {
        assertThat(SECURITY_POLICY.isBootstrap(4)).isTrue();
        assertThat(SECURITY_POLICY.isBootstrap(9)).isTrue();
    }

    @Test
    void isBootstrapReturnsFalseWhenAtMinimumObservations() {
        assertThat(SECURITY_POLICY.isBootstrap(10)).isFalse();
    }

    @Test
    void isBootstrapReturnsFalseWhenAboveMinimumObservations() {
        assertThat(SECURITY_POLICY.isBootstrap(100)).isFalse();
    }

    @Test
    void isBootstrapReturnsFalseWhenNoMinimumObservationsConfigured() {
        assertThat(NO_GATE_POLICY.isBootstrap(0)).isFalse();
        assertThat(NO_GATE_POLICY.isBootstrap(1)).isFalse();
    }

    @SuppressWarnings("deprecation")
    @Test
    void isBorderlineReturnsTrueWhenWithinMarginAboveThreshold() {
        assertThat(SECURITY_POLICY.isBorderline(0.70)).isTrue();
        assertThat(SECURITY_POLICY.isBorderline(0.72)).isTrue();
        assertThat(SECURITY_POLICY.isBorderline(0.7499)).isTrue();
    }

    @SuppressWarnings("deprecation")
    @Test
    void isBorderlineReturnsFalseWhenBelowThreshold() {
        assertThat(SECURITY_POLICY.isBorderline(0.69)).isFalse();
        assertThat(SECURITY_POLICY.isBorderline(0.50)).isFalse();
    }

    @SuppressWarnings("deprecation")
    @Test
    void isBorderlineReturnsFalseWhenAtOrAboveThresholdPlusMargin() {
        assertThat(SECURITY_POLICY.isBorderline(0.75)).isFalse();
        assertThat(SECURITY_POLICY.isBorderline(0.90)).isFalse();
    }

    @SuppressWarnings("deprecation")
    @Test
    void isBorderlineReturnsFalseWhenNoMarginConfigured() {
        assertThat(NO_GATE_POLICY.isBorderline(0.51)).isFalse();
        assertThat(NO_GATE_POLICY.isBorderline(0.70)).isFalse();
    }

    @SuppressWarnings("deprecation")
    @Test
    void agentWithSufficientObservationsCanBeBorderline() {
        assertThat(SECURITY_POLICY.isBootstrap(15)).isFalse();
        assertThat(SECURITY_POLICY.isBorderline(0.72)).isTrue();
    }

    @Test
    void recordEquality() {
        RoutingPolicy a = new RoutingPolicy(
            OptionalDouble.of(0.70), OptionalInt.of(10),
            OptionalDouble.of(0.05), Optional.of(HumanOversight.ROUTING_REVIEW),
            "test"
        );
        RoutingPolicy b = new RoutingPolicy(
            OptionalDouble.of(0.70), OptionalInt.of(10),
            OptionalDouble.of(0.05), Optional.of(HumanOversight.ROUTING_REVIEW),
            "test"
        );
        assertThat(a).isEqualTo(b);
    }

    @Test
    void nullRationaleThrowsNullPointerException() {
        assertThatThrownBy(() -> new RoutingPolicy(
            OptionalDouble.of(0.70), OptionalInt.of(10),
            OptionalDouble.of(0.05), Optional.empty(), null
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("rationale");
    }

    @Test
    void nullThresholdThrowsNullPointerException() {
        assertThatThrownBy(() -> new RoutingPolicy(
            null, OptionalInt.of(10),
            OptionalDouble.of(0.05), Optional.empty(),
            "test rationale"
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("threshold");
    }

    @Test
    void nullMinimumObservationsThrowsNullPointerException() {
        assertThatThrownBy(() -> new RoutingPolicy(
            OptionalDouble.of(0.70), null,
            OptionalDouble.of(0.05), Optional.empty(),
            "test rationale"
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("minimumObservations");
    }

    @Test
    void nullBorderlineMarginThrowsNullPointerException() {
        assertThatThrownBy(() -> new RoutingPolicy(
            OptionalDouble.of(0.70), OptionalInt.of(10),
            null, Optional.empty(),
            "test rationale"
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("borderlineMargin");
    }

    @Test
    void nullFallbackTypeThrowsNullPointerException() {
        assertThatThrownBy(() -> new RoutingPolicy(
            OptionalDouble.of(0.70), OptionalInt.of(10),
            OptionalDouble.of(0.05), null,
            "test rationale"
        )).isInstanceOf(NullPointerException.class)
          .hasMessageContaining("fallbackType");
    }
}
