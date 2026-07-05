package io.casehub.devtown.app;

import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.devtown.domain.queue.PriorityLane;
import io.casehub.devtown.merge.MergeQueueStore;
import io.casehub.devtown.merge.QueueEntry;
import io.casehub.devtown.merge.QueueEntryStatus;
import io.casehub.devtown.queue.QueuedPr;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Multi-repository batch formation test.
 *
 * <p>Verifies that batch formation groups queued entries by repository
 * and produces separate batches per repo. No cross-repo contamination.
 *
 * <p>Tests verify batch formation logic at the store level via
 * {@code formBatchesTransactional()} without dispatching actual cases.
 * Full case dispatch is covered by {@link MergeQueueBatchLifecycleTest}.
 * This isolates repository-aware grouping, dispatch threshold, and
 * prioritization logic from engine tenant/worker dependencies.
 */
@QuarkusTest
@TestProfile(BatchFormationTestProfile.class)
class MergeQueueMultiRepoBatchTest {

    @Inject MergeQueueStore store;
    @Inject MergeQueueService mergeQueueService;

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    @BeforeEach
    @Transactional
    void cleanAll() {
        // Clean ALL entries and batches — not just QUEUED — to avoid cross-test contamination
        em.createQuery("DELETE FROM QueuedPrEntity").executeUpdate();
        em.createQuery("DELETE FROM BatchEntity").executeUpdate();
    }

    @Test
    void prsFromDifferentRepos_formSeparateBatches() {
        store.enqueue(makePr(601, "casehubio/devtown"), UUID.randomUUID());
        store.enqueue(makePr(602, "casehubio/devtown"), UUID.randomUUID());
        store.enqueue(makePr(701, "casehubio/engine"), UUID.randomUUID());
        store.enqueue(makePr(702, "casehubio/engine"), UUID.randomUUID());

        assertThat(store.queued()).hasSize(4);

        List<MergeQueueService.FormedBatch> formedBatches =
            mergeQueueService.formBatchesTransactional();

        assertThat(formedBatches).hasSizeGreaterThanOrEqualTo(2);

        // Verify no cross-repo contamination
        for (MergeQueueService.FormedBatch formed : formedBatches) {
            List<QueueEntry> entries = store.findEntriesByBatchId(formed.batch().id());
            assertThat(entries)
                .as("All entries in batch %s should be from repo %s",
                    formed.batch().id(), formed.repository())
                .allSatisfy(e -> assertThat(e.pr().repository()).isEqualTo(formed.repository()));
        }

        // Verify both repos are represented
        Set<String> repos = formedBatches.stream()
            .map(MergeQueueService.FormedBatch::repository)
            .collect(Collectors.toSet());
        assertThat(repos).containsExactlyInAnyOrder("casehubio/devtown", "casehubio/engine");

        // All entries should be IN_BATCH
        assertThat(store.queued()).isEmpty();
    }

    @Test
    void singleRepoPrs_formSingleBatchGroup() {
        store.enqueue(makePr(611, "casehubio/devtown"), UUID.randomUUID());
        store.enqueue(makePr(612, "casehubio/devtown"), UUID.randomUUID());

        List<MergeQueueService.FormedBatch> formedBatches =
            mergeQueueService.formBatchesTransactional();

        assertThat(formedBatches).isNotEmpty();
        for (MergeQueueService.FormedBatch formed : formedBatches) {
            assertThat(formed.repository()).isEqualTo("casehubio/devtown");
        }
    }

    @Test
    void dispatchThreshold_minBatchSizeOne_dispatchesImmediately() {
        store.enqueue(makePr(620, "casehubio/devtown"), UUID.randomUUID());

        List<MergeQueueService.FormedBatch> formedBatches =
            mergeQueueService.formBatchesTransactional();

        assertThat(formedBatches).hasSize(1);
        assertThat(formedBatches.get(0).prNumbers()).containsExactly(620);
    }

    @Test
    void prioritizedEntry_includedInBatch() {
        store.enqueue(makePr(630, "casehubio/devtown"), UUID.randomUUID());
        store.markPrioritized(630, "casehubio/devtown");

        List<MergeQueueService.FormedBatch> formedBatches =
            mergeQueueService.formBatchesTransactional();

        assertThat(formedBatches).hasSize(1);
        assertThat(store.queued()).isEmpty();
    }

    @Test
    void batchFormation_marksEntriesAsInBatch_secondCallFindsNothing() {
        store.enqueue(makePr(640, "casehubio/devtown"), UUID.randomUUID());
        store.enqueue(makePr(641, "casehubio/devtown"), UUID.randomUUID());

        List<MergeQueueService.FormedBatch> first =
            mergeQueueService.formBatchesTransactional();
        assertThat(first).isNotEmpty();
        assertThat(store.queued()).isEmpty();

        List<MergeQueueService.FormedBatch> second =
            mergeQueueService.formBatchesTransactional();
        assertThat(second).isEmpty();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    static QueuedPr makePr(int number, String repository) {
        return new QueuedPr(number, repository, "sha-" + number, "author-" + number,
            0.7, PriorityLane.NORMAL, Instant.now(), Set.of());
    }
}
