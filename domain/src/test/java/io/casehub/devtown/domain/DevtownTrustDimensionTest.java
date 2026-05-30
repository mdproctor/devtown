package io.casehub.devtown.domain;

import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class DevtownTrustDimensionTest {

    @Test
    void allConstantsNonBlank() {
        assertThat(DevtownTrustDimension.REVIEW_THOROUGHNESS).isNotBlank();
        assertThat(DevtownTrustDimension.PRECISION).isNotBlank();
        assertThat(DevtownTrustDimension.SCOPE_CALIBRATION).isNotBlank();
    }

    @Test
    void allConstantsUnique() {
        assertThat(Set.of(
            DevtownTrustDimension.REVIEW_THOROUGHNESS,
            DevtownTrustDimension.PRECISION,
            DevtownTrustDimension.SCOPE_CALIBRATION
        )).hasSize(3);
    }

    @Test
    void valuesMatchSpec() {
        assertThat(DevtownTrustDimension.REVIEW_THOROUGHNESS).isEqualTo("review-thoroughness");
        assertThat(DevtownTrustDimension.PRECISION).isEqualTo("precision");
        assertThat(DevtownTrustDimension.SCOPE_CALIBRATION).isEqualTo("scope-calibration");
    }
}
