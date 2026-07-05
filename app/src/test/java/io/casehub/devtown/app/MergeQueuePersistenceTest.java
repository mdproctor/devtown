package io.casehub.devtown.app;

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
 * Persistence integration test for the merge queue.
 *
 * <p>Verifies that queue operations survive across transactions:
 * <ul>
 *   <li>Enqueue/dequeue persisted and retrievable
 *   <li>Batch dispatch recorded and findable by caseId
 *   <li>Completion callback correctly locates batch and entries
 *   <li>markPrioritized flag persisted
 *   <li>markQueued compensating action restores QUEUED state
 * </ul>
 */
@QuarkusTest
class MergeQueuePersistenceTest {

    @Inject MergeQueueStore store;

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanAll() {
        em.createQuery("DELETE FROM QueuedPrEntity").executeUpdate();
        em.createQuery("DELETE FROM BatchEntity").executeUpdate();
    }

    @Test
    void enqueue_persists_and_queued_retrieves() {
        QueuedPr pr = makePr(501, "casehubio/devtown");
        UUID workItemId = UUID.randomUUID();

        store.enqueue(pr, workItemId);

        List<QueueEntry> queued = store.queued();
        assertThat(queued).hasSize(1);
        QueueEntry entry = queued.get(0);
        assertThat(entry.pr().number()).isEqualTo(501);
        assertThat(entry.pr().repository()).isEqualTo("casehubio/devtown");
        assertThat(entry.workItemId()).isEqualTo(workItemId);
        assertThat(entry.status()).isEqualTo(QueueEntryStatus.QUEUED);
        assertThat(entry.prioritized()).isFalse();
        assertThat(entry.batchId()).isNull();
    }

    @Test
    void dequeue_changes_status_and_removes_from_queued() {
        QueuedPr pr = makePr(502, "casehubio/devtown");
        store.enqueue(pr, UUID.randomUUID());

        Optional<QueueEntry> removed = store.dequeue(502, "casehubio/devtown");
        assertThat(removed).isPresent();

        List<QueueEntry> queued = store.queued();
        assertThat(queued).isEmpty();
    }

    @Test
    void dequeue_returns_false_for_nonexistent() {
        Optional<QueueEntry> removed = store.dequeue(9999, "casehubio/devtown");
        assertThat(removed).isEmpty();
    }

    @Test
    void markInBatch_and_recordBatch_persists_batch_lifecycle() {
        QueuedPr pr1 = makePr(510, "casehubio/devtown");
        QueuedPr pr2 = makePr(511, "casehubio/devtown");
        store.enqueue(pr1, UUID.randomUUID());
        store.enqueue(pr2, UUID.randomUUID());

        String batchId = "batch-persist-test-1";
        store.markInBatch(List.of(510, 511), "casehubio/devtown", batchId);

        // Entries should no longer appear in queued()
        assertThat(store.queued()).isEmpty();

        // Record the batch
        UUID caseId = UUID.randomUUID();
        store.recordBatch(batchId, caseId, List.of(510, 511), "casehubio/devtown");

        // Verify findBatchByCaseId
        Optional<BatchRecord> found = store.findBatchByCaseId(caseId);
        assertThat(found).isPresent();
        assertThat(found.get().batchId()).isEqualTo(batchId);
        assertThat(found.get().prNumbers()).containsExactlyInAnyOrder(510, 511);
        assertThat(found.get().repository()).isEqualTo("casehubio/devtown");

        // Verify findEntriesByBatchId
        List<QueueEntry> batchEntries = store.findEntriesByBatchId(batchId);
        assertThat(batchEntries).hasSize(2);
        assertThat(batchEntries).allSatisfy(e ->
            assertThat(e.status()).isEqualTo(QueueEntryStatus.IN_BATCH));
    }

    @Test
    void markCompleted_transitions_to_terminal_state() {
        QueuedPr pr = makePr(520, "casehubio/devtown");
        store.enqueue(pr, UUID.randomUUID());
        store.markInBatch(List.of(520), "casehubio/devtown", "batch-complete-test");

        store.markCompleted(520, "casehubio/devtown", "MERGED");

        List<QueueEntry> entries = store.findEntriesByBatchId("batch-complete-test");
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status().name()).isEqualTo("MERGED");
    }

    @Test
    void markPrioritized_sets_flag() {
        QueuedPr pr = makePr(530, "casehubio/devtown");
        store.enqueue(pr, UUID.randomUUID());

        store.markPrioritized(530, "casehubio/devtown");

        List<QueueEntry> queued = store.queued();
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).prioritized()).isTrue();
    }

    @Test
    void markQueued_reverses_markInBatch() {
        QueuedPr pr = makePr(540, "casehubio/devtown");
        store.enqueue(pr, UUID.randomUUID());
        store.markInBatch(List.of(540), "casehubio/devtown", "batch-compensate-test");

        // Verify IN_BATCH
        assertThat(store.queued()).isEmpty();

        // Compensate — return to QUEUED
        store.markQueued(List.of(540), "casehubio/devtown");

        List<QueueEntry> queued = store.queued();
        assertThat(queued).hasSize(1);
        assertThat(queued.get(0).pr().number()).isEqualTo(540);
        assertThat(queued.get(0).status()).isEqualTo(QueueEntryStatus.QUEUED);
    }

    @Test
    void activeBatches_returns_all_recorded_batches() {
        QueuedPr pr1 = makePr(550, "casehubio/devtown");
        QueuedPr pr2 = makePr(551, "casehubio/engine");
        store.enqueue(pr1, UUID.randomUUID());
        store.enqueue(pr2, UUID.randomUUID());

        store.markInBatch(List.of(550), "casehubio/devtown", "batch-a");
        store.markInBatch(List.of(551), "casehubio/engine", "batch-b");

        UUID caseA = UUID.randomUUID();
        UUID caseB = UUID.randomUUID();
        store.recordBatch("batch-a", caseA, List.of(550), "casehubio/devtown");
        store.recordBatch("batch-b", caseB, List.of(551), "casehubio/engine");

        Map<String, BatchRecord> active = store.activeBatches();
        assertThat(active).containsKeys("batch-a", "batch-b");
    }

    @Test
    void findBatchByCaseId_returns_empty_for_unknown_case() {
        Optional<BatchRecord> found = store.findBatchByCaseId(UUID.randomUUID());
        assertThat(found).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static QueuedPr makePr(int number, String repository) {
        return new QueuedPr(number, repository, "sha-" + number, "author-" + number,
            0.7, PriorityLane.NORMAL, Instant.now(), Set.of());
    }
}
