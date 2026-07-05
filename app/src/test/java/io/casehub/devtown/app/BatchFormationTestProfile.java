package io.casehub.devtown.app;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Test profile for tests that verify batch formation logic.
 *
 * <p>Overrides the min-batch-size to 1 so that enqueue() triggers immediate
 * batch formation, enabling integration tests to verify batch grouping,
 * dispatch thresholds, and repository isolation.
 */
public class BatchFormationTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "casehub.platform.preferences.defaults.\"devtown.merge-queue.min-batch-size\"", "1"
        );
    }
}
