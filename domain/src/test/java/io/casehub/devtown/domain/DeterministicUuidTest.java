package io.casehub.devtown.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeterministicUuidTest {

    private static final UUID DNS_NS = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    @Test
    void v5_deterministic() {
        UUID a = DeterministicUuid.v5(DNS_NS, "test.example.com");
        UUID b = DeterministicUuid.v5(DNS_NS, "test.example.com");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void v5_namespace_isolation() {
        UUID ns2 = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
        UUID a = DeterministicUuid.v5(DNS_NS, "same-name");
        UUID b = DeterministicUuid.v5(ns2, "same-name");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void v5_version_bits() {
        UUID result = DeterministicUuid.v5(DNS_NS, "anything");
        assertThat(result.version()).isEqualTo(5);
    }

    @Test
    void v5_variant_bits() {
        UUID result = DeterministicUuid.v5(DNS_NS, "anything");
        assertThat(result.variant()).isEqualTo(2); // IETF variant = 2 in Java's UUID
    }

    @Test
    void v5_known_vector() {
        // RFC 4122 Appendix B: v5 of DNS namespace + "python.org"
        UUID expected = UUID.fromString("886313e1-3b8a-5372-9b90-0c9aee199e5d");
        UUID actual = DeterministicUuid.v5(DNS_NS, "python.org");
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void MERGE_DECISION_NS_stable() {
        assertThat(DeterministicUuid.MERGE_DECISION_NS.version()).isEqualTo(5);
        // Verify it's deterministic — re-derive and compare
        UUID rederived = DeterministicUuid.v5(DNS_NS, "casehub.io/devtown/merge-decision");
        assertThat(DeterministicUuid.MERGE_DECISION_NS).isEqualTo(rederived);
    }
}
