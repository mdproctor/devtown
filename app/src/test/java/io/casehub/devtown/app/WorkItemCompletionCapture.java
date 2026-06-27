package io.casehub.devtown.app;

import io.casehub.work.runtime.event.WorkItemLifecycleEvent;
import io.casehub.work.api.WorkItemStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Test-scope bean that captures async WorkItem lifecycle events.
 * Used by HumanApprovalLifecycleTest to verify @ObservesAsync delivery.
 */
@ApplicationScoped
class WorkItemCompletionCapture {

    private final ConcurrentMap<UUID, WorkItemLifecycleEvent> completed = new ConcurrentHashMap<>();

    void onCompleted(@ObservesAsync WorkItemLifecycleEvent event) {
        if (event.status() == WorkItemStatus.COMPLETED) {
            completed.put(event.workItemId(), event);
        }
    }

    boolean wasCompleted(final UUID workItemId) {
        return completed.containsKey(workItemId);
    }
}
