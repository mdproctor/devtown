package io.casehub.devtown.app;

import static org.assertj.core.api.Assertions.assertThat;
import io.casehub.devtown.domain.queue.PriorityLane;
import io.casehub.devtown.merge.MergeQueueStore;
import io.casehub.devtown.queue.QueuedPr;
import io.quarkus.hibernate.orm.PersistenceUnit;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class MergeQueueSlaDetectionTest {

    @Inject MergeQueueService mergeQueueService;
    @Inject MergeQueueStore store;

    @Inject @PersistenceUnit("qhorus") EntityManager em;

    @BeforeEach
    @Transactional
    void cleanAll() {
        em.createQuery("DELETE FROM QueuedPrEntity").executeUpdate();
        em.createQuery("DELETE FROM BatchEntity").executeUpdate();
    }

    @Test
    void detectSlaBreaches_criticalPrFlaggedAtCorrectThreshold() {
        // CRITICAL SLA = PT1H (60 min) — PR queued 90 min ago should breach
        QueuedPr critical = new QueuedPr(701, "casehubio/devtown", "sha-701", "alice",
            0.8, PriorityLane.CRITICAL, Instant.now().minus(90, ChronoUnit.MINUTES), Set.of());
        store.enqueue(critical, UUID.randomUUID());

        // NORMAL SLA = PT8H (480 min) — PR queued 120 min ago should NOT breach
        QueuedPr normal = new QueuedPr(702, "casehubio/devtown", "sha-702", "bob",
            0.7, PriorityLane.NORMAL, Instant.now().minus(120, ChronoUnit.MINUTES), Set.of());
        store.enqueue(normal, UUID.randomUUID());

        var breaches = mergeQueueService.detectSlaBreaches();

        assertThat(breaches).hasSize(1);
        assertThat(breaches.get(0).pr().number()).isEqualTo(701);
        assertThat(breaches.get(0).pr().lane()).isEqualTo(PriorityLane.CRITICAL);
        assertThat(breaches.get(0).waited().toMinutes()).isGreaterThanOrEqualTo(89);
        assertThat(breaches.get(0).sla().toHours()).isEqualTo(1);
    }

    @Test
    void detectSlaBreaches_emptyQueue_returnsEmpty() {
        assertThat(mergeQueueService.detectSlaBreaches()).isEmpty();
    }
}
