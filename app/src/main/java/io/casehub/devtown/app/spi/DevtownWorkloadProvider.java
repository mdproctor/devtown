package io.casehub.devtown.app.spi;

import io.casehub.work.api.WorkloadProvider;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Provides a no-op WorkloadProvider implementation for devtown.
 *
 * JpaWorkloadProvider (casehub-work) is excluded via quarkus.arc.exclude-types because it
 * requires a full JPA datasource wired to the work schema.
 * Satisfies the CDI injection point for WorkItemAssignmentService. Stand-in until devtown#34 adds production JPA wiring.
 */
@ApplicationScoped
public class DevtownWorkloadProvider implements WorkloadProvider {

    @Override
    public int getActiveWorkCount(String agentId) {
        return 0;
    }
}
