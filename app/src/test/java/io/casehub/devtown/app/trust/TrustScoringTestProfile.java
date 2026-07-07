package io.casehub.devtown.app.trust;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class TrustScoringTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.ofEntries(
            Map.entry("casehub.ledger.enabled", "true"),
            Map.entry("casehub.ledger.trust-score.enabled", "true"),
            Map.entry("casehub.ledger.trust-score.incremental.enabled", "true"),
            Map.entry("casehub.ledger.trust-score.materialization.enabled", "true"),
            Map.entry("quarkus.flyway.qhorus.migrate-at-start", "true"),
            Map.entry("quarkus.flyway.qhorus.clean-at-start", "true"),
            Map.entry("quarkus.datasource.qhorus.jdbc.url",
                "jdbc:h2:mem:devtown-trust-test;MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE"),
            Map.entry("quarkus.flyway.qhorus.locations",
                "classpath:db/ledger/migration,classpath:db/engine-ledger/migration,classpath:db/devtown/migration,classpath:db/devtown-test/migration")
        );
    }
}
