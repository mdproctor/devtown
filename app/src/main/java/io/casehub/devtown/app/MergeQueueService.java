package io.casehub.devtown.app;

import io.casehub.devtown.app.governance.MergeQueueStateEvent;
import io.casehub.devtown.domain.queue.MergeQueuePreferenceKeys;
import io.casehub.devtown.domain.queue.PriorityLane;
import io.casehub.devtown.merge.AdmissionResult;
import io.casehub.devtown.merge.BatchRecord;
import io.casehub.devtown.merge.MergeQueueAdmissionPort;
import io.casehub.devtown.merge.MergeQueueStore;
import io.casehub.devtown.merge.QueueEntry;
import io.casehub.devtown.merge.QueueEntryStatus;
import io.casehub.devtown.queue.Batch;
import io.casehub.devtown.queue.BatchCompositionPolicy;
import io.casehub.devtown.queue.BatchFormationContext;
import io.casehub.devtown.queue.QueuedPr;
import io.casehub.platform.api.preferences.PreferenceProvider;
import io.casehub.platform.api.preferences.Preferences;
import io.casehub.platform.api.preferences.SettingsScope;
import io.casehub.work.runtime.service.WorkItemService;
import io.casehub.work.api.WorkItemCreateRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/**
 * Merge queue lifecycle orchestration.
 *
 * <p>Stateless service — all queue state persisted via {@link MergeQueueStore}.
 * Configuration resolved via {@link PreferenceProvider} at scope
 * {@code casehubio/devtown/merge-queue}.
 *
 * <p>Repository-aware: batch formation groups queued entries by repository.
 * A batch never mixes PRs from different repositories.
 */
@ApplicationScoped
public class MergeQueueService implements MergeQueueAdmissionPort {

    private static final Logger LOG = Logger.getLogger(MergeQueueService.class);

    @Inject
    MergeQueueStore store;

    @Inject
    MergeBatchCaseHub mergeBatchCaseHub;

    @Inject
    BatchCompositionPolicy compositionPolicy;

    @Inject
    PreferenceProvider preferenceProvider;

    @Inject
    Instance<WorkItemService> workItemServiceInstance;

    @Inject
    Event<MergeQueueStateEvent> queueEvent;

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Hexagonal port implementation: minimal admission with neutral defaults.
     *
     * <p>Neutral defaults: trust=0.5, lane=NORMAL, capabilities=empty,
     * enqueued_at=now. The webhook boundary provides only prNumber, repository,
     * headSha, and author.
     */
    @Override
    public AdmissionResult admit(int prNumber, String repository, String headSha, String author) {
        QueuedPr pr = new QueuedPr(prNumber, repository, headSha, author,
            0.5, PriorityLane.NORMAL, Instant.now(), Set.of());
        boolean inserted = enqueue(pr);
        return inserted ? AdmissionResult.ENQUEUED : AdmissionResult.ALREADY_QUEUED;
    }

    /**
     * Enqueue a PR: resolve preferences, create WorkItem (if available),
     * persist queue entry, trigger batch formation.
     *
     * <p>Idempotent: duplicate enqueue for same (prNumber, repository) is silently ignored.
     *
     * @return true if a new entry was created; false if already queued (no-op)
     */
    public boolean enqueue(QueuedPr pr) {
        boolean inserted = store.enqueue(pr, null);
        if (!inserted) {
            LOG.debugf("Duplicate enqueue for PR #%d from %s — no-op",
                pr.number(), pr.repository());
            return false;
        }

        if (workItemServiceInstance.isResolvable()) {
            WorkItemService workItemService = workItemServiceInstance.get();
            Preferences prefs = resolvePreferences();
            PriorityLane lane = pr.lane();
            String slaDuration = prefs.getOrDefault(MergeQueuePreferenceKeys.slaKeyFor(lane)).value();
            Duration expiry = Duration.parse(slaDuration);

            var request = WorkItemCreateRequest.builder()
                .title("Merge queue wait: " + pr.repository() + "#" + pr.number())
                .description("PR waiting in merge queue")
                .createdBy("system")
                .callerRef(pr.repository() + "#" + pr.number())
                .scope("casehubio/devtown/merge-queue")
                .expiresAt(Instant.now().plus(expiry))
                .build();

            var workItem = workItemService.create(request);
            store.updateWorkItemId(pr.number(), pr.repository(), workItem.id);
            LOG.infof("Created WorkItem %s for PR %s#%d (SLA: %s)",
                workItem.id, pr.repository(), pr.number(), slaDuration);
        }

        LOG.infof("Enqueued PR #%d from %s (trust=%.2f, lane=%s)",
            pr.number(), pr.repository(), pr.trustScore(), pr.lane());
        queueEvent.fireAsync(MergeQueueStateEvent.enqueue(pr.repository(), pr.number()));
        formAndDispatchBatches();
        return true;
    }

    /**
     * Form batches from queued entries and dispatch them as merge-batch cases.
     *
     * <p>Repository-aware: groups queued entries by repository, applies dispatch
     * threshold per group, forms batches per group via composition policy.
     *
     * <p>Two-phase execution:
     * <ol>
     *   <li><b>Batch formation (transactional):</b> {@code queuedForUpdate()} acquires
     *       SELECT FOR UPDATE lock, composition policy forms batches, {@code markInBatch()}
     *       transitions entries — all atomic.
     *   <li><b>Case dispatch (non-transactional):</b> {@code startCase()} runs in the
     *       engine's own transaction. If it fails, compensating action returns entries
     *       to QUEUED. If it succeeds, {@code recordBatch()} links batch to case.
     * </ol>
     *
     * @return list of case IDs for dispatched batches
     */
    public List<UUID> formAndDispatchBatches() {
        // Phase 1: form batches (transactional — holds SELECT FOR UPDATE lock)
        List<FormedBatch> formedBatches = formBatchesTransactional();
        if (formedBatches.isEmpty()) {
            return List.of();
        }

        // Phase 2: dispatch cases (non-transactional — engine manages its own tx)
        List<UUID> caseIds = new ArrayList<>();
        for (FormedBatch formed : formedBatches) {
            UUID caseId;
            try {
                caseId = dispatchBatch(formed.batch());
            } catch (Exception e) {
                LOG.errorf(e, "Case start failed for batch %s — compensating: returning %d PRs to QUEUED",
                    formed.batch().id(), formed.prNumbers().size());
                store.markQueued(formed.prNumbers(), formed.repository());
                continue;
            }

            store.recordBatch(formed.batch().id(), caseId, formed.prNumbers(), formed.repository());
            caseIds.add(caseId);
            queueEvent.fireAsync(MergeQueueStateEvent.batchFormed(formed.repository(), formed.batch().id()));
            LOG.infof("Dispatched batch %s (%d PRs from %s) as case %s",
                formed.batch().id(), formed.batch().size(), formed.repository(), caseId);
        }

        return caseIds;
    }

    /**
     * Prioritize a PR for immediate dispatch.
     *
     * <p>No-op if the PR is not in QUEUED state.
     * Triggers {@link #formAndDispatchBatches()} which bypasses the dispatch threshold
     * for groups containing prioritized entries.
     *
     * @param prNumber PR number
     * @param repository repository name
     */
    public void prioritize(int prNumber, String repository) {
        store.markPrioritized(prNumber, repository);
        LOG.infof("Prioritized PR #%d from %s — triggering batch formation", prNumber, repository);
        formAndDispatchBatches();
    }

    /**
     * Handle batch completion: mark entries as terminal and obsolete WorkItems.
     *
     * <p>Returns early for sub-case lifecycle events (no batch record found).
     *
     * @param caseId batch case ID
     * @param batchSucceeded true if batch merged cleanly
     * @param rejectedPrNumbers PR numbers rejected during bisection
     */
    @Transactional
    public void handleBatchCompletion(UUID caseId, boolean batchSucceeded,
                                       Set<Integer> rejectedPrNumbers) {
        var batchOpt = store.findBatchByCaseId(caseId);
        if (batchOpt.isEmpty()) {
            LOG.debugf("No batch record for case %s — sub-case lifecycle event, ignoring", caseId);
            return;
        }

        BatchRecord batch = batchOpt.get();
        if (!batch.isActive()) {
            LOG.debugf("Batch %s already completed — idempotent no-op", batch.batchId());
            return;
        }

        List<QueueEntry> entries = store.findEntriesByBatchId(batch.batchId());

        for (QueueEntry entry : entries) {
            String outcome;
            if (rejectedPrNumbers.contains(entry.pr().number())) {
                outcome = QueueEntryStatus.REJECTED.name();
            } else if (batchSucceeded) {
                outcome = QueueEntryStatus.MERGED.name();
            } else {
                outcome = QueueEntryStatus.DEQUEUED.name();
            }

            store.markCompleted(entry.pr().number(), entry.pr().repository(), outcome);

            if (entry.workItemId() != null && workItemServiceInstance.isResolvable()) {
                try {
                    workItemServiceInstance.get().obsoleteFromSystem(
                        entry.workItemId(), "system", "batch-" + outcome.toLowerCase());
                } catch (Exception e) {
                    LOG.warnf(e, "Failed to obsolete WorkItem %s for PR #%d",
                        entry.workItemId(), entry.pr().number());
                }
            }
        }

        LOG.infof("Batch %s completed (case %s): %d entries processed, succeeded=%s, rejected=%s",
            batch.batchId(), caseId, entries.size(), batchSucceeded, rejectedPrNumbers);

        store.completeBatch(batch.batchId(), batchSucceeded);
        queueEvent.fireAsync(MergeQueueStateEvent.batchCompleted(batch.repository(), batch.batchId()));
    }

    /**
     * Dequeue a PR by composite identifier (prNumber, repository).
     *
     * <p>Atomically transitions the entry from QUEUED to DEQUEUED and returns
     * the entry (including its WorkItem ID) from the same transaction. If the
     * entry is not in QUEUED state (absent, IN_BATCH, or already terminal),
     * returns false without side effects.
     *
     * @return true if a QUEUED entry was dequeued
     */
    public boolean dequeue(int prNumber, String repository) {
        Optional<QueueEntry> dequeued = store.dequeue(prNumber, repository);
        if (dequeued.isEmpty()) return false;

        UUID workItemId = dequeued.get().workItemId();
        if (workItemId != null && workItemServiceInstance.isResolvable()) {
            try {
                workItemServiceInstance.get().obsoleteFromSystem(
                    workItemId, "system", "dequeued");
            } catch (Exception e) {
                LOG.warnf(e, "Failed to obsolete WorkItem %s for dequeued PR #%d",
                    workItemId, prNumber);
            }
        }

        queueEvent.fireAsync(MergeQueueStateEvent.dequeue(repository, prNumber));
        return true;
    }

    // ── Read accessors (delegated to store) ─────────────────────────────────

    public int queueSize() {
        return store.queued().size();
    }

    public int activeBatchCount() {
        return store.activeBatches().size();
    }

    public List<QueuedPr> queuedPrs() {
        return store.queued().stream()
            .map(QueueEntry::pr)
            .toList();
    }

    public Map<String, BatchRecord> activeBatches() {
        return store.activeBatches();
    }

    public record SlaBreach(QueuedPr pr, Duration waited, Duration sla) {}

    public List<SlaBreach> detectSlaBreaches() {
        Preferences prefs = resolvePreferences();
        Instant now = Instant.now();
        List<SlaBreach> breaches = new ArrayList<>();
        for (QueueEntry entry : store.queued()) {
            QueuedPr pr = entry.pr();
            String slaDuration = prefs.getOrDefault(
                MergeQueuePreferenceKeys.slaKeyFor(pr.lane())).value();
            Duration sla = Duration.parse(slaDuration);
            Duration waited = Duration.between(pr.enqueuedAt(), now);
            if (waited.compareTo(sla) > 0) {
                breaches.add(new SlaBreach(pr, waited, sla));
            }
        }
        return breaches;
    }

    public List<BatchRecord> completedBatches(Duration window) {
        return store.completedBatchesSince(Instant.now().minus(window));
    }

    public double aggregateFailureRate() {
        Preferences prefs = resolvePreferences();
        int window = prefs.getOrDefault(MergeQueuePreferenceKeys.FAILURE_RATE_WINDOW).value();
        return store.recentBatchFailureRate(window);
    }

    // ── Internal ────────────────────────────────────────────────────────────

    /**
     * Phase 1 of batch formation — transactional.
     *
     * <p>Acquires SELECT FOR UPDATE lock, applies composition policy per repository
     * group, marks entries as IN_BATCH. Returns formed batches for dispatch in phase 2.
     */
    @Transactional
    List<FormedBatch> formBatchesTransactional() {
        List<QueueEntry> queued = store.queuedForUpdate();
        if (queued.isEmpty()) {
            return List.of();
        }

        Preferences prefs = resolvePreferences();
        int maxBatchSize = prefs.getOrDefault(MergeQueuePreferenceKeys.MAX_BATCH_SIZE).value();
        int minBatchSize = prefs.getOrDefault(MergeQueuePreferenceKeys.MIN_BATCH_SIZE).value();
        double decayRatePerHour = (double) prefs.getOrDefault(MergeQueuePreferenceKeys.DECAY_RATE_PER_HOUR).value();
        String targetBranch = prefs.getOrDefault(MergeQueuePreferenceKeys.TARGET_BRANCH).value();
        String bisectionStrategy = prefs.getOrDefault(MergeQueuePreferenceKeys.BISECTION_STRATEGY).value();
        int failureRateWindow = prefs.getOrDefault(MergeQueuePreferenceKeys.FAILURE_RATE_WINDOW).value();

        // Group by repository
        Map<String, List<QueueEntry>> byRepo = queued.stream()
            .collect(Collectors.groupingBy(e -> e.pr().repository()));

        List<FormedBatch> formedBatches = new ArrayList<>();
        AtomicInteger batchSequence = new AtomicInteger(0);

        for (var entry : byRepo.entrySet()) {
            String repository = entry.getKey();
            List<QueueEntry> repoEntries = entry.getValue();

            // Dispatch threshold: skip if size < minBatchSize AND no prioritized entries
            boolean hasPrioritized = repoEntries.stream().anyMatch(QueueEntry::prioritized);
            if (repoEntries.size() < minBatchSize && !hasPrioritized) {
                LOG.debugf("Skipping repo %s: %d entries < minBatchSize %d and no prioritized",
                    repository, repoEntries.size(), minBatchSize);
                continue;
            }

            List<QueuedPr> repoPrs = repoEntries.stream()
                .map(QueueEntry::pr)
                .toList();

            var ctx = new BatchFormationContext(
                Instant.now(),
                maxBatchSize,
                minBatchSize,
                decayRatePerHour,
                store.recentBatchFailureRate(repository, failureRateWindow),
                repository,
                targetBranch,
                "ROUTINE",
                bisectionStrategy,
                batchSequence
            );

            List<Batch> batches = compositionPolicy.formBatches(new ArrayList<>(repoPrs), ctx);

            for (Batch batch : batches) {
                List<Integer> prNumbers = batch.prs().stream()
                    .map(QueuedPr::number)
                    .toList();
                store.markInBatch(prNumbers, repository, batch.id());
                formedBatches.add(new FormedBatch(batch, prNumbers, repository));
            }
        }

        return formedBatches;
    }

    private Preferences resolvePreferences() {
        return preferenceProvider.resolve(
            SettingsScope.of("casehubio", "devtown", "merge-queue"));
    }

    private UUID dispatchBatch(Batch batch) {
        var batchContext = new LinkedHashMap<String, Object>();
        var batchMap = new LinkedHashMap<String, Object>();
        batchMap.put("id", batch.id());
        batchMap.put("repository", batch.repository());
        batchMap.put("targetBranch", batch.targetBranch());
        batchMap.put("size", batch.size());
        batchMap.put("riskLevel", batch.riskLevel());
        batchMap.put("bisectionStrategy", batch.bisectionStrategy());
        batchMap.put("bisectionDepth", 0);
        batchMap.put("isRootBatch", true);

        List<Map<String, Object>> prMaps = new ArrayList<>();
        for (var pr : batch.prs()) {
            var prMap = new LinkedHashMap<String, Object>();
            prMap.put("number", pr.number());
            prMap.put("repository", pr.repository());
            prMap.put("headSha", pr.headSha());
            prMap.put("author", pr.author());
            prMap.put("trustScore", pr.trustScore());
            prMap.put("lane", pr.lane().name());
            prMaps.add(prMap);
        }
        batchMap.put("prs", prMaps);

        batchContext.put("batch", batchMap);

        return mergeBatchCaseHub.startCase(batchContext).toCompletableFuture().join();
    }

    /**
     * Internal record for passing formed batch data between phase 1 (transactional)
     * and phase 2 (dispatch).
     */
    record FormedBatch(Batch batch, List<Integer> prNumbers, String repository) {}
}
