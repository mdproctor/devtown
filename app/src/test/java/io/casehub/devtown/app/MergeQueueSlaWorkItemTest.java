package io.casehub.devtown.app;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.devtown.domain.queue.PriorityLane;
import io.casehub.devtown.queue.QueuedPr;
import io.casehub.work.api.WorkItemStatus;
import io.casehub.work.runtime.model.WorkItem;
import io.casehub.work.runtime.model.WorkItemTemplate;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MergeQueueSlaWorkItemTest {

    @Inject MergeQueueService mergeQueueService;
    @Inject WorkItemQueries workItemQueries;

    @BeforeEach
    @Transactional
    void seedTemplate() {
        if (WorkItemTemplate.find("name", "merge-queue-wait").count() == 0) {
            WorkItemTemplate t = new WorkItemTemplate();
            t.id = UUID.randomUUID();
            t.tenancyId = "default";
            t.name = "merge-queue-wait";
            t.description = "PR waiting in merge queue";
            t.scope = "casehubio/devtown/merge-queue";
            t.createdBy = "system";
            t.createdAt = Instant.now();
            t.persist();
        }
    }

    @Test
    void enqueue_createsWorkItemWithCorrectSla() {
        var pr = new QueuedPr(
            500, "casehubio/devtown", "abc500", "alice", 0.75,
            PriorityLane.NORMAL, Instant.now(), Set.of()
        );

        mergeQueueService.enqueue(pr);

        await().atMost(5, SECONDS).pollInterval(200, MILLISECONDS).untilAsserted(() -> {
            var items = scanMergeQueueWorkItems(500);
            assertThat(items).hasSize(1);
            WorkItem item = items.iterator().next();
            assertThat(item.status).isEqualTo(WorkItemStatus.PENDING);
            assertThat(item.callerRef).isEqualTo("casehubio/devtown#500");
            Duration actualExpiry = Duration.between(item.createdAt, item.expiresAt);
            assertThat(actualExpiry).isBetween(
                Duration.ofHours(8).minusSeconds(10),
                Duration.ofHours(8).plusSeconds(10)
            );
        });
    }

    @Test
    void duplicateEnqueue_doesNotCreateOrphanedWorkItem() {
        var pr = new QueuedPr(
            502, "casehubio/devtown", "ghi502", "carol", 0.60,
            PriorityLane.NORMAL, Instant.now(), Set.of()
        );

        mergeQueueService.enqueue(pr);

        await().atMost(5, SECONDS).pollInterval(200, MILLISECONDS)
            .until(() -> !scanMergeQueueWorkItems(502).isEmpty());

        boolean secondResult = mergeQueueService.enqueue(pr);
        assertThat(secondResult).isFalse();

        await().atMost(2, SECONDS).pollInterval(200, MILLISECONDS).untilAsserted(() -> {
            var items = scanMergeQueueWorkItems(502);
            assertThat(items).as("duplicate enqueue must not create a second WorkItem").hasSize(1);
        });
    }

    @Test
    void dequeue_obsoletesWorkItem() {
        var pr = new QueuedPr(
            501, "casehubio/devtown", "def501", "bob", 0.80,
            PriorityLane.HIGH, Instant.now(), Set.of()
        );

        mergeQueueService.enqueue(pr);

        await().atMost(5, SECONDS).pollInterval(200, MILLISECONDS)
            .until(() -> !scanMergeQueueWorkItems(501).isEmpty());

        mergeQueueService.dequeue(501, "casehubio/devtown");

        await().atMost(5, SECONDS).pollInterval(200, MILLISECONDS).untilAsserted(() -> {
            var items = scanMergeQueueWorkItems(501);
            assertThat(items).hasSize(1);
            WorkItem item = items.iterator().next();
            assertThat(item.status).isIn(WorkItemStatus.OBSOLETE, WorkItemStatus.EXPIRED);
        });
    }

    private Set<WorkItem> scanMergeQueueWorkItems(int prNumber) {
        var all = workItemQueries.scanAll();
        Set<WorkItem> result = new HashSet<>();
        for (WorkItem item : all) {
            if (item.callerRef != null && item.callerRef.endsWith("#" + prNumber)) {
                result.add(item);
            }
        }
        return result;
    }
}
