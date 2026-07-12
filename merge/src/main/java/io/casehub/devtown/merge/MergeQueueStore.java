package io.casehub.devtown.merge;

import io.casehub.devtown.queue.QueuedPr;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent store for the merge queue.
 *
 * <p>All mutating operations use {@code (prNumber, repository)} as the composite identifier.
 * PR numbers are not globally unique — they are scoped to a repository.
 *
 * <p>{@link #enqueue(QueuedPr, UUID)} is idempotent: if a {@code (prNumber, repository)} entry
 * already exists in state {@code QUEUED} or {@code IN_BATCH}, the call returns silently.
 * This prevents duplicate enqueue from any admission path (CasePlanModel binding, MCP tool, webhook).
 */
public interface MergeQueueStore {

    /**
     * Enqueue a PR with its associated SLA WorkItem ID.
     *
     * <p>Idempotent: if an entry for {@code (pr.number(), pr.repository())} already exists
     * in state {@code QUEUED} or {@code IN_BATCH}, this is a no-op and returns {@code false}.
     *
     * @param pr the PR to enqueue
     * @param workItemId the SLA WorkItem tracking queue wait time
     * @return true if a new entry was created; false if already queued (no-op)
     */
    boolean enqueue(QueuedPr pr, UUID workItemId);

    /**
     * Associate a WorkItem ID with an already-enqueued PR.
     *
     * <p>Called after {@link #enqueue} returns {@code true} and the WorkItem has been
     * created. This two-step pattern prevents orphaned WorkItems on duplicate enqueue.
     *
     * @param prNumber PR number
     * @param repository repository name
     * @param workItemId the SLA WorkItem ID to associate
     */
    void updateWorkItemId(int prNumber, String repository, UUID workItemId);

    /**
     * Remove a PR from the queue (sets status to DEQUEUED).
     *
     * @param prNumber PR number
     * @param repository repository name
     * @return the dequeued entry if found in QUEUED state; empty if absent or not QUEUED
     */
    Optional<QueueEntry> dequeue(int prNumber, String repository);

    /**
     * Returns all entries in QUEUED status.
     *
     * <p>Does not acquire locks. Use {@link #queuedForUpdate()} for batch formation.
     *
     * @return list of queued entries
     */
    List<QueueEntry> queued();

    /**
     * Returns all entries in QUEUED status with SELECT FOR UPDATE lock.
     *
     * <p>Used exclusively by batch formation to serialize concurrent batch creation
     * at the database level. The first caller transitions PRs to IN_BATCH; subsequent
     * concurrent callers block until the first completes, then find no QUEUED rows.
     *
     * @return list of queued entries, locked for update
     */
    List<QueueEntry> queuedForUpdate();

    /**
     * Mark multiple PRs from the same repository as IN_BATCH.
     *
     * @param prNumbers PR numbers to mark
     * @param repository repository name (must be the same for all PRs)
     * @param batchId the batch identifier
     */
    void markInBatch(List<Integer> prNumbers, String repository, String batchId);

    /**
     * Mark a PR as completed (MERGED or REJECTED).
     *
     * @param prNumber PR number
     * @param repository repository name
     * @param outcome "MERGED" or "REJECTED"
     */
    void markCompleted(int prNumber, String repository, String outcome);

    /**
     * Mark a PR as prioritized for immediate dispatch.
     *
     * <p>No-op if the PR is not in QUEUED state (covers IN_BATCH, MERGED, REJECTED, DEQUEUED, and absent).
     *
     * @param prNumber PR number
     * @param repository repository name
     */
    void markPrioritized(int prNumber, String repository);

    /**
     * Mark multiple PRs from the same repository as QUEUED (used after bisection rejects a subset).
     *
     * @param prNumbers PR numbers to mark
     * @param repository repository name (must be the same for all PRs)
     */
    void markQueued(List<Integer> prNumbers, String repository);

    /**
     * Record a dispatched batch.
     *
     * @param batchId batch identifier
     * @param caseId the CaseHub case ID for this batch
     * @param prNumbers PR numbers in the batch
     * @param repository repository name
     */
    void recordBatch(String batchId, UUID caseId, List<Integer> prNumbers, String repository);

    /**
     * Find a batch record by case ID.
     *
     * @param caseId the case ID
     * @return batch record if found
     */
    Optional<BatchRecord> findBatchByCaseId(UUID caseId);

    /**
     * Find all queue entries associated with a batch.
     *
     * @param batchId batch identifier
     * @return list of entries (may include MERGED, REJECTED, or DEQUEUED entries if batch lifecycle has progressed)
     */
    List<QueueEntry> findEntriesByBatchId(String batchId);

    /**
     * Returns all active batches (dispatched but not yet completed).
     *
     * @return map of batchId → BatchRecord
     */
    Map<String, BatchRecord> activeBatches();

    /**
     * Mark a batch as completed.
     *
     * @param batchId batch identifier
     * @param succeeded true if batch succeeded (all PRs merged), false if failed
     */
    void completeBatch(String batchId, boolean succeeded);

    /**
     * Compute the failure rate of recent batches for a repository.
     *
     * @param repository repository name
     * @param window maximum number of recent completed batches to consider
     * @return failure rate [0.0, 1.0]; 0.0 if no completed batches exist
     */
    double recentBatchFailureRate(String repository, int window);

    /**
     * Returns all batches completed since the given instant.
     *
     * @param since cutoff instant (inclusive)
     * @return completed batches ordered by completedAt descending
     */
    List<BatchRecord> completedBatchesSince(Instant since);

    /**
     * Aggregate failure rate across all repositories.
     *
     * <p>Overload of {@link #recentBatchFailureRate(String, int)} without the
     * repository filter — for cross-repo aggregate metrics.
     *
     * @param window maximum number of recent completed batches to consider
     * @return failure rate [0.0, 1.0]; 0.0 if no completed batches exist
     */
    double recentBatchFailureRate(int window);

    /**
     * Deletes completed batch records older than the given cutoff.
     *
     * @return number of records expunged
     */
    int expungeCompletedBefore(Instant cutoff);

}
