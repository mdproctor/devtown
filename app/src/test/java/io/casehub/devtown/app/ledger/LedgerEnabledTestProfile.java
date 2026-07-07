package io.casehub.devtown.app.ledger;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

public class LedgerEnabledTestProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
            "casehub.ledger.enabled", "true",
            "quarkus.flyway.qhorus.migrate-at-start", "true",
            "quarkus.flyway.qhorus.clean-at-start", "true",
            "quarkus.datasource.qhorus.jdbc.url",
                "jdbc:h2:mem:devtown-ledger-test;MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE",
            "quarkus.flyway.qhorus.locations",
                "classpath:db/ledger/migration,classpath:db/engine-ledger/migration,classpath:db/devtown/migration,classpath:db/devtown-test/migration"
        );
    }
}
