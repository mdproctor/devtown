package io.casehub.devtown.app.persistence;

import io.casehub.devtown.domain.queue.PriorityLane;
import io.casehub.devtown.merge.BatchRecord;
import io.casehub.devtown.merge.MergeQueueStore;
import io.casehub.devtown.merge.QueueEntry;
import io.casehub.devtown.merge.QueueEntryStatus;
import io.casehub.devtown.queue.QueuedPr;
import io.quarkus.hibernate.orm.PersistenceUnit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JPA implementation of {@link MergeQueueStore}.
 *
 * <p>Uses composite key {@code (prNumber, repository)} for all PR lookups.
 * {@link #queuedForUpdate()} uses {@code SELECT FOR UPDATE} to serialize concurrent batch formation.
 */
@ApplicationScoped
public class JpaMergeQueueStore implements MergeQueueStore {

    @Inject
    @PersistenceUnit("qhorus")
    EntityManager em;

    @Override
    @Transactional
    public boolean enqueue(QueuedPr pr, UUID workItemId) {
        // Idempotent: check for existing entry
        QueuedPrEntity existing = em.find(QueuedPrEntity.class,
            new QueuedPrEntity.QueuedPrId(pr.number(), pr.repository()));

        if (existing != null) {
            String status = existing.status;
            if ("QUEUED".equals(status) || "IN_BATCH".equals(status)) {
                // Already queued or in batch, no-op
                return false;
            }
        }

        QueuedPrEntity entity = new QueuedPrEntity();
        entity.prNumber = pr.number();
        entity.repository = pr.repository();
        entity.headSha = pr.headSha();
        entity.author = pr.author();
        entity.trustScore = pr.trustScore();
        entity.lane = pr.lane().name();
        entity.enqueuedAt = pr.enqueuedAt();
        entity.dependsOn = serializeDependencies(pr.dependsOn());
        entity.workItemId = workItemId;
        entity.status = QueueEntryStatus.QUEUED.name();
        entity.prioritized = false;
        entity.batchId = null;

        if (existing == null) {
            em.persist(entity);
        } else {
            em.merge(entity);
        }
        return true;
    }

    @Override
    @Transactional
    public void updateWorkItemId(int prNumber, String repository, UUID workItemId) {
        QueuedPrEntity entity = em.find(QueuedPrEntity.class,
            new QueuedPrEntity.QueuedPrId(prNumber, repository));
        if (entity != null) {
            entity.workItemId = workItemId;
            em.merge(entity);
        }
    }

    @Override
    @Transactional
    public Optional<QueueEntry> dequeue(int prNumber, String repository) {
        QueuedPrEntity entity = em.find(QueuedPrEntity.class,
            new QueuedPrEntity.QueuedPrId(prNumber, repository));

        if (entity == null || !"QUEUED".equals(entity.status)) {
            return Optional.empty();
        }

        entity.status = QueueEntryStatus.DEQUEUED.name();
        em.merge(entity);
        return Optional.of(toQueueEntry(entity));
    }

    @Override
    public List<QueueEntry> queued() {
        return em.createQuery(
            "SELECT e FROM QueuedPrEntity e WHERE e.status = :status",
            QueuedPrEntity.class)
            .setParameter("status", QueueEntryStatus.QUEUED.name())
            .getResultList()
            .stream()
            .map(this::toQueueEntry)
            .toList();
    }

    @Override
    @Transactional
    public List<QueueEntry> queuedForUpdate() {
        // Use native query for SELECT FOR UPDATE
        @SuppressWarnings("unchecked")
        List<Object[]> results = em.createNativeQuery(
            "SELECT pr_number, repository, head_sha, author, trust_score, lane, " +
            "enqueued_at, depends_on, work_item_id, status, prioritized, batch_id " +
            "FROM merge_queue_entry WHERE status = :status FOR UPDATE",
            Object[].class)
            .setParameter("status", QueueEntryStatus.QUEUED.name())
            .getResultList();

        return results.stream()
            .map(this::toQueueEntryFromNative)
            .toList();
    }

    @Override
    @Transactional
    public void markInBatch(List<Integer> prNumbers, String repository, String batchId) {
        for (Integer prNumber : prNumbers) {
            QueuedPrEntity entity = em.find(QueuedPrEntity.class,
                new QueuedPrEntity.QueuedPrId(prNumber, repository));
            if (entity != null && "QUEUED".equals(entity.status)) {
                entity.status = QueueEntryStatus.IN_BATCH.name();
                entity.batchId = batchId;
                em.merge(entity);
            }
        }
    }

    @Override
    @Transactional
    public void markCompleted(int prNumber, String repository, String outcome) {
        QueuedPrEntity entity = em.find(QueuedPrEntity.class,
            new QueuedPrEntity.QueuedPrId(prNumber, repository));

        if (entity != null) {
            entity.status = outcome;
            em.merge(entity);
        }
    }

    @Override
    @Transactional
    public void markPrioritized(int prNumber, String repository) {
        QueuedPrEntity entity = em.find(QueuedPrEntity.class,
            new QueuedPrEntity.QueuedPrId(prNumber, repository));

        if (entity != null && "QUEUED".equals(entity.status)) {
            entity.prioritized = true;
            em.merge(entity);
        }
    }

    @Override
    @Transactional
    public void markQueued(List<Integer> prNumbers, String repository) {
        for (Integer prNumber : prNumbers) {
            QueuedPrEntity entity = em.find(QueuedPrEntity.class,
                new QueuedPrEntity.QueuedPrId(prNumber, repository));
            if (entity != null) {
                entity.status = QueueEntryStatus.QUEUED.name();
                entity.batchId = null;
                em.merge(entity);
            }
        }
    }

    @Override
    @Transactional
    public void recordBatch(String batchId, UUID caseId, List<Integer> prNumbers, String repository) {
        BatchEntity entity = new BatchEntity();
        entity.batchId = batchId;
        entity.caseId = caseId;
        entity.prNumbers = serializePrNumbers(prNumbers);
        entity.repository = repository;
        entity.dispatchedAt = Instant.now();
        em.persist(entity);
    }

    @Override
    public Optional<BatchRecord> findBatchByCaseId(UUID caseId) {
        try {
            BatchEntity entity = em.createQuery(
                "SELECT b FROM BatchEntity b WHERE b.caseId = :caseId",
                BatchEntity.class)
                .setParameter("caseId", caseId)
                .getSingleResult();
            return Optional.of(toBatchRecord(entity));
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<QueueEntry> findEntriesByBatchId(String batchId) {
        return em.createQuery(
            "SELECT e FROM QueuedPrEntity e WHERE e.batchId = :batchId",
            QueuedPrEntity.class)
            .setParameter("batchId", batchId)
            .getResultList()
            .stream()
            .map(this::toQueueEntry)
            .toList();
    }

    @Override
    public Map<String, BatchRecord> activeBatches() {
        List<BatchEntity> entities = em.createQuery(
            "SELECT b FROM BatchEntity b WHERE b.completedAt IS NULL",
            BatchEntity.class)
            .getResultList();

        Map<String, BatchRecord> result = new HashMap<>();
        for (BatchEntity entity : entities) {
            result.put(entity.batchId, toBatchRecord(entity));
        }
        return result;
    }

    @Override
    @Transactional
    public void completeBatch(String batchId, boolean succeeded) {
        BatchEntity entity = em.find(BatchEntity.class, batchId);
        if (entity == null || entity.completedAt != null) return;
        entity.completedAt = Instant.now();
        entity.succeeded = succeeded;
        em.merge(entity);
    }

    @Override
    public double recentBatchFailureRate(String repository, int window) {
        List<BatchEntity> recent = em.createQuery(
            "SELECT b FROM BatchEntity b WHERE b.repository = :repo " +
            "AND b.completedAt IS NOT NULL ORDER BY b.completedAt DESC",
            BatchEntity.class)
            .setParameter("repo", repository)
            .setMaxResults(window)
            .getResultList();

        if (recent.isEmpty()) return 0.0;
        long failed = recent.stream().filter(b -> Boolean.FALSE.equals(b.succeeded)).count();
        return (double) failed / recent.size();
    }

    @Override
    public List<BatchRecord> completedBatchesSince(Instant since) {
        return em.createQuery(
            "SELECT b FROM BatchEntity b WHERE b.completedAt IS NOT NULL " +
            "AND b.completedAt >= :since ORDER BY b.completedAt DESC",
            BatchEntity.class)
            .setParameter("since", since)
            .getResultList()
            .stream()
            .map(this::toBatchRecord)
            .toList();
    }

    @Override
    public double recentBatchFailureRate(int window) {
        List<BatchEntity> recent = em.createQuery(
            "SELECT b FROM BatchEntity b WHERE b.completedAt IS NOT NULL " +
            "ORDER BY b.completedAt DESC",
            BatchEntity.class)
            .setMaxResults(window)
            .getResultList();

        if (recent.isEmpty()) return 0.0;
        long failed = recent.stream().filter(b -> Boolean.FALSE.equals(b.succeeded)).count();
        return (double) failed / recent.size();
    }

    @Override
    @Transactional
    public int expungeCompletedBefore(Instant cutoff) {
        return em.createQuery("DELETE FROM BatchEntity b WHERE b.completedAt IS NOT NULL AND b.completedAt < :cutoff")
                 .setParameter("cutoff", cutoff)
                 .executeUpdate();
    }


    // ── Converters ─────────────────────────────────────────────────────────

    private QueueEntry toQueueEntry(QueuedPrEntity entity) {
        QueuedPr pr = new QueuedPr(
            entity.prNumber,
            entity.repository,
            entity.headSha,
            entity.author,
            entity.trustScore,
            PriorityLane.valueOf(entity.lane),
            entity.enqueuedAt,
            deserializeDependencies(entity.dependsOn)
        );
        return new QueueEntry(
            pr,
            entity.workItemId,
            QueueEntryStatus.valueOf(entity.status),
            entity.prioritized,
            entity.batchId
        );
    }

    private QueueEntry toQueueEntryFromNative(Object[] row) {
        int prNumber = ((Number) row[0]).intValue();
        String repository = (String) row[1];
        String headSha = (String) row[2];
        String author = (String) row[3];
        double trustScore = ((Number) row[4]).doubleValue();
        String lane = (String) row[5];
        Instant enqueuedAt = toInstant(row[6]);
        String dependsOn = (String) row[7];
        UUID workItemId = toUuid(row[8]);
        String status = (String) row[9];
        boolean prioritized = (Boolean) row[10];
        String batchId = (String) row[11];

        QueuedPr pr = new QueuedPr(
            prNumber,
            repository,
            headSha,
            author,
            trustScore,
            PriorityLane.valueOf(lane),
            enqueuedAt,
            deserializeDependencies(dependsOn)
        );
        return new QueueEntry(
            pr,
            workItemId,
            QueueEntryStatus.valueOf(status),
            prioritized,
            batchId
        );
    }

    private BatchRecord toBatchRecord(BatchEntity entity) {
        return new BatchRecord(
            entity.batchId,
            entity.caseId,
            deserializePrNumbers(entity.prNumbers),
            entity.repository,
            entity.dispatchedAt,
            entity.completedAt,
            entity.succeeded
        );
    }

    private String serializeDependencies(Set<Integer> dependsOn) {
        if (dependsOn == null || dependsOn.isEmpty()) {
            return null;
        }
        return dependsOn.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
    }

    private Set<Integer> deserializeDependencies(String dependsOn) {
        if (dependsOn == null || dependsOn.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(dependsOn.split(","))
            .map(Integer::parseInt)
            .collect(Collectors.toSet());
    }

    private String serializePrNumbers(List<Integer> prNumbers) {
        return prNumbers.stream()
            .map(String::valueOf)
            .collect(Collectors.joining(","));
    }

    private List<Integer> deserializePrNumbers(String prNumbers) {
        return Arrays.stream(prNumbers.split(","))
            .map(Integer::parseInt)
            .toList();
    }

    private UUID toUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        } else if (value instanceof byte[] bytes) {
            // H2 returns UUID columns as byte[] in native queries
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(bytes);
            return new UUID(bb.getLong(), bb.getLong());
        } else {
            throw new IllegalArgumentException("Unsupported UUID type: " + value.getClass());
        }
    }

    private Instant toInstant(Object timestamp) {
        if (timestamp instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        } else if (timestamp instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        } else if (timestamp instanceof java.time.LocalDateTime ldt) {
            return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant();
        } else {
            throw new IllegalArgumentException("Unsupported timestamp type: " + timestamp.getClass());
        }
    }
}
