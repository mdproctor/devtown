package io.casehub.devtown.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.devtown.domain.queue.PriorityLane;
import io.casehub.devtown.merge.BatchRecord;
import io.casehub.devtown.merge.MergeQueueStore;
import io.casehub.devtown.merge.QueueEntry;
import io.casehub.devtown.merge.QueueEntryStatus;
import io.casehub.devtown.queue.QueuedPr;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration test for {@link JpaMergeQueueStore}.
 *
 * <p>Verifies persistence, idempotence, SELECT FOR UPDATE locking, and batch lifecycle.
 */
@QuarkusTest
class JpaMergeQueueStoreTest {

    @Inject
    MergeQueueStore store;

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    @BeforeEach
    @Transactional
    void setUp() {
        // Clean tables before each test for isolation
        em.createNativeQuery("DELETE FROM merge_queue_batch").executeUpdate();
        em.createNativeQuery("DELETE FROM merge_queue_entry").executeUpdate();
    }

    @Test
    @Transactional
    void enqueue_persistsPr() {
        QueuedPr pr = new QueuedPr(
            100,
            "casehubio/devtown",
            "abc123",
            "author1",
            0.75,
            PriorityLane.NORMAL,
            Instant.now(),
            Set.of()
        );
        UUID workItemId = UUID.randomUUID();

        store.enqueue(pr, workItemId);

        List<QueueEntry> queued = store.queued();
        assertThat(queued).hasSize(1);
        QueueEntry entry = queued.get(0);
        assertThat(entry.pr().number()).isEqualTo(100);
        assertThat(entry.pr().repository()).isEqualTo("casehubio/devtown");
        assertThat(entry.workItemId()).isEqualTo(workItemId);
        assertThat(entry.status()).isEqualTo(QueueEntryStatus.QUEUED);
        assertThat(entry.prioritized()).isFalse();
        assertThat(entry.batchId()).isNull();
    }

    @Test
    @Transactional
    void enqueue_idempotent_whenQueued() {
        QueuedPr pr = new QueuedPr(
            101,
            "casehubio/devtown",
            "abc123",
            "author1",
            0.75,
            PriorityLane.NORMAL,
            Instant.now(),
            Set.of()
        );
        UUID workItemId1 = UUID.randomUUID();
        UUID workItemId2 = UUID.randomUUID();

        store.enqueue(pr, workItemId1);
        store.enqueue(pr, workItemId2);  // second enqueue should be no-op

        List<QueueEntry> queued = store.queued();
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).workItemId()).isEqualTo(workItemId1);
    }

    @Test
    @Transactional
    void enqueue_idempotent_whenInBatch() {
        QueuedPr pr = new QueuedPr(
            102,
            "casehubio/devtown",
            "abc123",
            "author1",
            0.75,
            PriorityLane.NORMAL,
            Instant.now(),
            Set.of()
        );
        UUID workItemId = UUID.randomUUID();

        store.enqueue(pr, workItemId);
        store.markInBatch(List.of(102), "casehubio/devtown", "batch-001");

        // Second enqueue should be no-op
        store.enqueue(pr, UUID.randomUUID());

        List<QueueEntry> entries = store.findEntriesByBatchId("batch-001");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).workItemId()).isEqualTo(workItemId);
    }

    @Test
    @Transactional
    void dequeue_removesQueuedPr() {
        QueuedPr pr = new QueuedPr(
            103,
            "casehubio/devtown",
            "abc123",
            "author1",
            0.75,
            PriorityLane.NORMAL,
            Instant.now(),
            Set.of()
        );

        store.enqueue(pr, UUID.randomUUID());
        Optional<QueueEntry> dequeued = store.dequeue(103, "casehubio/devtown");

        assertThat(dequeued).isPresent();
        assertThat(dequeued.get().status()).isEqualTo(QueueEntryStatus.DEQUEUED);
        assertThat(store.queued()).isEmpty();
    }

    @Test
    @Transactional
    void dequeue_returnsFalse_whenNotQueued() {
        Optional<QueueEntry> dequeued = store.dequeue(999, "casehubio/nonexistent");
        assertThat(dequeued).isEmpty();
    }

    @Test
    @Transactional
    void markInBatch_updatesStatusAndBatchId() {
        QueuedPr pr1 = new QueuedPr(
            104,
            "casehubio/devtown",
            "abc123",
            "author1",
            0.75,
            PriorityLane.NORMAL,
            Instant.now(),
            Set.of()
        );
        QueuedPr pr2 = new QueuedPr(
            105,
            "casehubio/devtown",
            "def456",
            "author2",
            0.80,
            PriorityLane.HIGH,
            Instant.now(),
            Set.of()
        );

        store.enqueue(pr1, UUID.randomUUID());
        store.enqueue(pr2, UUID.randomUUID());

        store.markInBatch(List.of(104, 105), "casehubio/devtown", "batch-002");

        assertThat(store.queued()).isEmpty();

        List<QueueEntry> batchEntries = store.findEntriesByBatchId("batch-002");
        assertThat(batchEntries).hasSize(2);
        assertThat(batchEntries).allMatch(e -> e.status() == QueueEntryStatus.IN_BATCH);
        assertThat(batchEntries).allMatch(e -> "batch-002".equals(e.batchId()));
    }

    @Test
    @Transactional
    void markCompleted_setsOutcome() {
        QueuedPr pr = new QueuedPr(
            106,
            "casehubio/devtown",
            "abc123",
            "author1",
            0.75,
            PriorityLane.NORMAL,
            Instant.now(),
            Set.of()
        );

        store.enqueue(pr, UUID.randomUUID());
        store.markInBatch(List.of(106), "casehubio/devtown", "batch-003");
        store.markCompleted(106, "casehubio/devtown", "MERGED");

        List<QueueEntry> entries = store.findEntriesByBatchId("batch-003");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo(QueueEntryStatus.MERGED);
    }

    @Test
    @Transactional
    void markPrioritized_setsFlag() {
        QueuedPr pr = new QueuedPr(
            107,
            "casehubio/devtown",
            "abc123",
            "author1",
            0.75,
            PriorityLane.NORMAL,
            Instant.now(),
            Set.of()
        );

        store.enqueue(pr, UUID.randomUUID());
        store.markPrioritized(107, "casehubio/devtown");

        List<QueueEntry> queued = store.queued();
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).prioritized()).isTrue();
    }

    @Test
    @Transactional
    void markQueued_resetsStatusAndBatchId() {
        QueuedPr pr = new QueuedPr(
            108,
            "casehubio/devtown",
            "abc123",
            "author1",
            0.75,
            PriorityLane.NORMAL,
            Instant.now(),
            Set.of()
        );

        store.enqueue(pr, UUID.randomUUID());
        store.markInBatch(List.of(108), "casehubio/devtown", "batch-004");
        store.markQueued(List.of(108), "casehubio/devtown");

        List<QueueEntry> queued = store.queued();
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).status()).isEqualTo(QueueEntryStatus.QUEUED);
        assertThat(queued.get(0).batchId()).isNull();
    }

    @Test
    @Transactional
    void recordBatch_persistsBatch() {
        UUID caseId = UUID.randomUUID();
        List<Integer> prNumbers = List.of(109, 110, 111);

        store.recordBatch("batch-005", caseId, prNumbers, "casehubio/devtown");

        Optional<BatchRecord> found = store.findBatchByCaseId(caseId);
        assertThat(found).isPresent();
        BatchRecord batch = found.get();
        assertThat(batch.batchId()).isEqualTo("batch-005");
        assertThat(batch.caseId()).isEqualTo(caseId);
        assertThat(batch.prNumbers()).containsExactly(109, 110, 111);
        assertThat(batch.repository()).isEqualTo("casehubio/devtown");
    }

    @Test
    @Transactional
    void findBatchByCaseId_returnsEmpty_whenNotFound() {
        Optional<BatchRecord> found = store.findBatchByCaseId(UUID.randomUUID());
        assertThat(found).isEmpty();
    }

    @Test
    @Transactional
    void activeBatches_returnsAllBatches() {
        UUID caseId1 = UUID.randomUUID();
        UUID caseId2 = UUID.randomUUID();

        store.recordBatch("batch-006", caseId1, List.of(112), "casehubio/devtown");
        store.recordBatch("batch-007", caseId2, List.of(113, 114), "casehubio/engine");

        Map<String, BatchRecord> active = store.activeBatches();
        assertThat(active).hasSize(2);
        assertThat(active).containsKeys("batch-006", "batch-007");
    }

    @Test
    @Transactional
    void queuedForUpdate_returnsQueuedEntries() {
        QueuedPr pr1 = new QueuedPr(
            115,
            "casehubio/devtown",
            "abc123",
            "author1",
            0.75,
            PriorityLane.NORMAL,
            Instant.now(),
            Set.of()
        );
        QueuedPr pr2 = new QueuedPr(
            116,
            "casehubio/devtown",
            "def456",
            "author2",
            0.80,
            PriorityLane.HIGH,
            Instant.now(),
            Set.of()
        );

        store.enqueue(pr1, UUID.randomUUID());
        store.enqueue(pr2, UUID.randomUUID());

        List<QueueEntry> locked = store.queuedForUpdate();

        assertThat(locked).hasSize(2);
        assertThat(locked).allMatch(e -> e.status() == QueueEntryStatus.QUEUED);
    }

    @Test
    @Transactional
    void dependsOn_roundTrips() {
        QueuedPr pr = new QueuedPr(
            117,
            "casehubio/devtown",
            "abc123",
            "author1",
            0.75,
            PriorityLane.NORMAL,
            Instant.now(),
            Set.of(100, 101)
        );

        store.enqueue(pr, UUID.randomUUID());

        List<QueueEntry> queued = store.queued();
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).pr().dependsOn()).containsExactlyInAnyOrder(100, 101);
    }

    @Test
    @Transactional
    void completeBatch_setsOutcome() {
        UUID caseId = UUID.randomUUID();
        store.recordBatch("batch-complete-1", caseId, List.of(200), "casehubio/devtown");

        store.completeBatch("batch-complete-1", true);

        Optional<BatchRecord> found = store.findBatchByCaseId(caseId);
        assertThat(found).isPresent();
        assertThat(found.get().completedAt()).isNotNull();
        assertThat(found.get().succeeded()).isTrue();
        assertThat(found.get().isActive()).isFalse();
    }

    @Test
    @Transactional
    void completeBatch_idempotent_preservesOriginalValues() {
        UUID caseId = UUID.randomUUID();
        store.recordBatch("batch-complete-2", caseId, List.of(201), "casehubio/devtown");
        store.completeBatch("batch-complete-2", true);

        Instant firstCompletedAt = store.findBatchByCaseId(caseId).get().completedAt();

        store.completeBatch("batch-complete-2", false);

        BatchRecord after = store.findBatchByCaseId(caseId).get();
        assertThat(after.completedAt()).isEqualTo(firstCompletedAt);
        assertThat(after.succeeded()).isTrue();
    }

    @Test
    @Transactional
    void activeBatches_excludesCompleted() {
        store.recordBatch("batch-active", UUID.randomUUID(), List.of(202), "casehubio/devtown");
        store.recordBatch("batch-done", UUID.randomUUID(), List.of(203), "casehubio/devtown");
        store.completeBatch("batch-done", true);

        Map<String, BatchRecord> active = store.activeBatches();
        assertThat(active).hasSize(1);
        assertThat(active).containsKey("batch-active");
    }

    @Test
    @Transactional
    void findBatchByCaseId_returnsCompletedBatch() {
        UUID caseId = UUID.randomUUID();
        store.recordBatch("batch-find-completed", caseId, List.of(204), "casehubio/devtown");
        store.completeBatch("batch-find-completed", false);

        Optional<BatchRecord> found = store.findBatchByCaseId(caseId);
        assertThat(found).isPresent();
        assertThat(found.get().succeeded()).isFalse();
    }

    @Test
    @Transactional
    void recentBatchFailureRate_returnsZero_withNoHistory() {
        double rate = store.recentBatchFailureRate("casehubio/devtown", 20);
        assertThat(rate).isEqualTo(0.0);
    }

    @Test
    @Transactional
    void recentBatchFailureRate_computesCorrectly() {
        for (int i = 0; i < 5; i++) {
            store.recordBatch("batch-rate-" + i, UUID.randomUUID(), List.of(300 + i), "casehubio/devtown");
            store.completeBatch("batch-rate-" + i, i < 3);
        }

        double rate = store.recentBatchFailureRate("casehubio/devtown", 20);
        assertThat(rate).isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @Transactional
    void recentBatchFailureRate_respectsWindow() {
        for (int i = 0; i < 10; i++) {
            store.recordBatch("batch-window-" + i, UUID.randomUUID(), List.of(400 + i), "casehubio/devtown");
            store.completeBatch("batch-window-" + i, i < 8);
        }

        double rateAll = store.recentBatchFailureRate("casehubio/devtown", 20);
        double rateWindow5 = store.recentBatchFailureRate("casehubio/devtown", 5);
        assertThat(rateAll).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.001));
        assertThat(rateWindow5).isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @Transactional
    void recentBatchFailureRate_isPerRepository() {
        store.recordBatch("batch-repoA", UUID.randomUUID(), List.of(500), "casehubio/devtown");
        store.completeBatch("batch-repoA", false);

        store.recordBatch("batch-repoB", UUID.randomUUID(), List.of(501), "casehubio/engine");
        store.completeBatch("batch-repoB", true);

        assertThat(store.recentBatchFailureRate("casehubio/devtown", 20)).isEqualTo(1.0);
        assertThat(store.recentBatchFailureRate("casehubio/engine", 20)).isEqualTo(0.0);
    }

    @Test
    @Transactional
    void enqueue_returnsTrue_whenNewEntry() {
        QueuedPr pr = new QueuedPr(600, "casehubio/devtown", "abc", "author", 0.5,
            PriorityLane.NORMAL, Instant.now(), Set.of());
        boolean result = store.enqueue(pr, UUID.randomUUID());
        assertThat(result).isTrue();
    }

    @Test
    @Transactional
    void enqueue_returnsFalse_whenDuplicate() {
        QueuedPr pr = new QueuedPr(601, "casehubio/devtown", "abc", "author", 0.5,
            PriorityLane.NORMAL, Instant.now(), Set.of());
        store.enqueue(pr, UUID.randomUUID());
        boolean result = store.enqueue(pr, UUID.randomUUID());
        assertThat(result).isFalse();
    }

    @Test
    @Transactional
    void completedBatchesSince_filtersCorrectly() {
        // Batch completed 2 hours ago (backdated via SQL)
        store.recordBatch("batch-old", UUID.randomUUID(), List.of(700), "casehubio/devtown");
        store.completeBatch("batch-old", true);
        em.createQuery("UPDATE BatchEntity b SET b.completedAt = :past WHERE b.batchId = 'batch-old'")
            .setParameter("past", Instant.now().minus(2, java.time.temporal.ChronoUnit.HOURS))
            .executeUpdate();

        // Active batch (not completed)
        store.recordBatch("batch-active", UUID.randomUUID(), List.of(701), "casehubio/devtown");

        // Batch completed now
        store.recordBatch("batch-recent", UUID.randomUUID(), List.of(702), "casehubio/devtown");
        store.completeBatch("batch-recent", false);

        // 1-hour window should exclude batch-old, include batch-recent
        List<BatchRecord> recent = store.completedBatchesSince(Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).batchId()).isEqualTo("batch-recent");

        // 24-hour window should include both completed batches
        List<BatchRecord> all = store.completedBatchesSince(Instant.now().minus(24, java.time.temporal.ChronoUnit.HOURS));
        assertThat(all).hasSize(2);
        assertThat(all).allSatisfy(b -> assertThat(b.completedAt()).isNotNull());
    }

    @Test
    @Transactional
    void recentBatchFailureRate_aggregate_crossRepo() {
        store.recordBatch("batch-a1", UUID.randomUUID(), List.of(710), "casehubio/devtown");
        store.completeBatch("batch-a1", false);  // failed
        store.recordBatch("batch-b1", UUID.randomUUID(), List.of(711), "casehubio/engine");
        store.completeBatch("batch-b1", true);  // succeeded

        // Per-repo: devtown=1.0, engine=0.0
        // Aggregate: 1 failed / 2 total = 0.5
        double aggregate = store.recentBatchFailureRate(20);
        assertThat(aggregate).isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    @Transactional
    void recentBatchFailureRate_aggregate_respectsWindow() {
        for (int i = 0; i < 10; i++) {
            store.recordBatch("batch-agg-" + i, UUID.randomUUID(), List.of(720 + i), "casehubio/devtown");
            store.completeBatch("batch-agg-" + i, i < 8);  // 8 pass, 2 fail
        }
        double rateAll = store.recentBatchFailureRate(20);
        double rate5 = store.recentBatchFailureRate(5);
        assertThat(rateAll).isCloseTo(0.2, org.assertj.core.data.Offset.offset(0.001));
        // Window of 5 gets the 5 most recent: indices 5,6,7,8,9 → 7=pass, 8=fail, 9=fail → 2/5 = 0.4
        assertThat(rate5).isCloseTo(0.4, org.assertj.core.data.Offset.offset(0.001));
    }
}
