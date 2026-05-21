package io.casehub.devtown.app;

import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.repository.WorkItemStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;

@ApplicationScoped
class WorkItemQueries {

    @Inject WorkItemStore store;

    @Transactional
    List<WorkItem> scanAll() {
        return store.scanAll();
    }
}
