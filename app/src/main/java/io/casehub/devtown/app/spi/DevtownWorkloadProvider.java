package io.casehub.devtown.app.spi;

import io.casehub.work.api.WorkloadProvider;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Provides a no-op WorkloadProvider implementation for devtown.
 *
 * JpaWorkloadProvider (casehub-work) is excluded via quarkus.arc.exclude-types because it
 * requires a full JPA datasource wired to the work schema. In production, the engine's
 * CasehubWorkloadProvider bridges this; in devtown tests the engine jar does not include
 * that bridge class (moved in a later engine build). This @ApplicationScoped default satisfies
 * the CDI injection point for WorkItemAssignmentService without JPA overhead in tests.
 */
@ApplicationScoped
public class DevtownWorkloadProvider implements WorkloadProvider {

    @Override
    public int getActiveWorkCount(String agentId) {
        return 0;
    }
}
