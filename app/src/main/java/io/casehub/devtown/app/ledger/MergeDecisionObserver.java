package io.casehub.devtown.app.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.casehub.api.context.CaseContext;
import io.casehub.devtown.domain.DeterministicUuid;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.ledger.api.model.supplement.ComplianceSupplement;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Observes terminal {@link CaseLifecycleEvent} transitions and writes a
 * {@link MergeDecisionLedgerEntry} to the tamper-evident audit trail.
 *
 * <p>Handles two distinct paths, distinguished by case context shape:
 *
 * <p><strong>PR review path</strong> (existing): Case context has {@code pr.repo},
 * {@code pr.id}. Batch columns stay null. Single entry per case.
 *
 * <p><strong>Merge batch path</strong> (new): Case context has {@code batch.*}
 * AND {@code batch.isRootBatch == true}. The observer skips sub-case completions
 * during bisection — sub-cases receive {@code { batch: .splitResult.left }} from
 * the splitter, which does not include {@code isRootBatch}. Only root batch cases
 * set {@code batch.isRootBatch = true} at dispatch. Without this guard, bisection
 * sub-case completions write duplicate ledger entries. For root batches: iterates
 * {@code batch.prs}, writes one entry per PR with shared batch metadata.
 *
 * <p>Decision semantics:
 * <ul>
 *   <li>{@code COMPLETED} → {@code APPROVED} — all goals met
 *   <li>{@code CANCELLED} → {@code REJECTED} — case explicitly aborted
 *   <li>{@code FAULTED} → no entry — infrastructure error, not a merge decision
 * </ul>
 *
 * <p><strong>Tech debt:</strong> Uses {@link CrossTenantCaseInstanceRepository}
 * because there is no request-scoped tenant in the async observer context.
 * The repository's contract says "for startup recovery services only" — this
 * is accepted tech debt, identical to {@code ReviewOutcomeObserver}. Resolution:
 * when {@code CaseLifecycleEvent} carries PR metadata directly, the lookup
 * becomes unnecessary.
 */
@ApplicationScoped
public class MergeDecisionObserver {

    private static final Logger LOG = Logger.getLogger(MergeDecisionObserver.class);

    @Inject CrossTenantCaseInstanceRepository caseInstanceRepo;
    @Inject LedgerEntryRepository ledgerRepo;
    @Inject LedgerConfig ledgerConfig;
    @Inject ObjectMapper objectMapper;

    void onCaseLifecycle(@ObservesAsync CaseLifecycleEvent event) {
        if (!ledgerConfig.enabled()) return;

        String decision = switch (event.caseStatus()) {
            case "COMPLETED" -> "APPROVED";
            case "CANCELLED" -> "REJECTED";
            default -> null;
        };
        if (decision == null) return;

        CaseInstance ci;
        try {
            ci = caseInstanceRepo.findByUuid(event.caseId());
        } catch (Exception e) {
            LOG.warnf(e, "Failed to lookup CaseInstance for caseId=%s", event.caseId());
            return;
        }
        if (ci == null) return;

        CaseContext ctx = ci.getCaseContext();
        if (ctx == null) return;

        // Path detection: PR review vs merge batch
        String prRepo = ctx.getPathAsString("pr.repo");
        String batchIdStr = ctx.getPathAsString("batch.id");

        try {
            QuarkusTransaction.requiringNew().run(() -> {
                if (prRepo != null) {
                    handlePrReviewPath(event, ctx, decision);
                } else if (batchIdStr != null) {
                    handleMergeBatchPath(event, ctx, decision);
                }
            });
        } catch (Exception e) {
            LOG.errorf(e, "Failed to write merge decision for caseId=%s decision=%s",
                    event.caseId(), decision);
        }
    }

    private void handlePrReviewPath(CaseLifecycleEvent event, CaseContext ctx, String decision) {
        String repo = ctx.getPathAsString("pr.repo");
        String prIdStr = ctx.getPathAsString("pr.id");
        String headSha = ctx.getPathAsString("pr.headSha");
        String mergeSha = ctx.getPathAsString("merge_sha");
        if (repo == null || prIdStr == null) return;

        int prNumber;
        try {
            prNumber = Integer.parseInt(prIdStr);
        } catch (NumberFormatException e) {
            return;
        }

        MergeDecisionLedgerEntry entry = new MergeDecisionLedgerEntry();
        entry.subjectId = event.caseId();
        entry.caseId = event.caseId();
        entry.tenancyId = event.tenancyId();
        entry.entryType = LedgerEntryType.EVENT;
        entry.prNumber = prNumber;
        entry.repository = repo;
        entry.commitSha = mergeSha != null ? mergeSha : headSha;
        entry.decision = decision;
        entry.actorId = "system";
        entry.actorType = ActorType.SYSTEM;
        entry.actorRole = "ORCHESTRATOR";
        entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        // Best-effort causal link to the CaseLedgerEntry for the terminal transition.
        // Uses findLatestBySubjectId() (correct @LedgerPersistenceUnit) — NOT
        // findLatestByCaseId() which uses an unqualified EntityManager (engine#450).
        ledgerRepo.findLatestBySubjectId(event.caseId(), event.tenancyId())
                .filter(latest -> latest instanceof CaseLedgerEntry cle
                        && event.caseStatus().equals(cle.caseStatus))
                .ifPresent(latest -> entry.causedByEntryId = latest.id);

        ComplianceSupplement cs = new DevtownComplianceSupplement();
        cs.algorithmRef = "casehub-devtown:pr-review-v1";
        cs.humanOverrideAvailable = true;
        cs.contestationUri = "/api/reviews/" + prNumber + "/contest";
        entry.attach(cs);

        ledgerRepo.save(entry, event.tenancyId());
        LOG.debugf("Merge decision written (PR review path): caseId=%s decision=%s pr=%s#%d",
                event.caseId(), decision, repo, prNumber);
    }

    private void handleMergeBatchPath(CaseLifecycleEvent event, CaseContext ctx, String decision) {
        // Root batch guard: only root batches write ledger entries
        Object isRootBatchObj = ctx.getPath("batch.isRootBatch");
        if (!(isRootBatchObj instanceof Boolean isRootBatch) || !isRootBatch) {
            LOG.debugf("Skipping batch sub-case completion: caseId=%s (not a root batch)", event.caseId());
            return;
        }

        // Extract batch metadata
        String batchId = ctx.getPathAsString("batch.id");
        String repository = ctx.getPathAsString("batch.repository");
        Object batchSizeObj = ctx.getPath("batch.size");
        Integer batchSize = batchSizeObj instanceof Number n ? n.intValue() : null;

        Object bisectionOccurredObj = ctx.getPath("batch.bisectionOccurred");
        Boolean bisectionOccurred = bisectionOccurredObj instanceof Boolean b ? b : null;

        String bisectionStrategy = ctx.getPathAsString("batch.bisectionStrategy");

        // batchContextJson: serialize the full batch context for audit purposes
        String batchContextJson = serializeBatchContext(ctx);

        // Extract PR list from batch.prs
        Object prsObj = ctx.getPath("batch.prs");
        if (!(prsObj instanceof List<?> prsList)) {
            LOG.warnf("batch.prs is not a List for caseId=%s", event.caseId());
            return;
        }

        int entriesWritten = 0;
        for (Object prItem : prsList) {
            if (!(prItem instanceof java.util.Map<?,?> prMap)) continue;

            Object prNumberObj = prMap.get("number");
            if (!(prNumberObj instanceof Number n)) continue;
            int prNumber = n.intValue();

            // Derive deterministic subjectId from caseId + prNumber (UUID v5)
            UUID subjectId = DeterministicUuid.v5(
                    DeterministicUuid.MERGE_DECISION_NS,
                    event.caseId() + ":" + prNumber);

            MergeDecisionLedgerEntry entry = new MergeDecisionLedgerEntry();
            entry.subjectId = subjectId;
            entry.caseId = event.caseId();
            entry.tenancyId = event.tenancyId();
            entry.entryType = LedgerEntryType.EVENT;
            entry.prNumber = prNumber;
            entry.repository = repository;
            entry.commitSha = null; // Batch merges don't track per-PR commit SHA
            entry.decision = decision;
            entry.actorId = "system";
            entry.actorType = ActorType.SYSTEM;
            entry.actorRole = "ORCHESTRATOR";
            entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);

            // Batch metadata
            entry.batchId = batchId;
            entry.batchSize = batchSize;
            entry.bisectionOccurred = bisectionOccurred;
            entry.bisectionStrategy = bisectionStrategy;
            entry.batchContextJson = batchContextJson;

            // Best-effort causal link
            ledgerRepo.findLatestBySubjectId(event.caseId(), event.tenancyId())
                    .filter(latest -> latest instanceof CaseLedgerEntry cle
                            && event.caseStatus().equals(cle.caseStatus))
                    .ifPresent(latest -> entry.causedByEntryId = latest.id);

            ComplianceSupplement cs = new DevtownComplianceSupplement();
            cs.algorithmRef = "casehub-devtown:merge-queue-v1";
            cs.humanOverrideAvailable = true;
            cs.contestationUri = "/api/merge-queue/batches/" + batchId + "/contest";
            entry.attach(cs);

            ledgerRepo.save(entry, event.tenancyId());
            entriesWritten++;
        }

        LOG.debugf("Merge decision written (batch path): caseId=%s decision=%s batchId=%s entries=%d",
                event.caseId(), decision, batchId, entriesWritten);
    }

    /**
     * Serialize batch context to JSON for audit purposes.
     * Includes: PR list, trust scores, CI run IDs, rejected PRs.
     */
    private String serializeBatchContext(CaseContext ctx) {
        Map<String, Object> batchContext = new LinkedHashMap<>();

        // prList
        Object prsObj = ctx.getPath("batch.prs");
        if (prsObj instanceof List<?> prsList) {
            batchContext.put("prList", prsList);
        }

        // trustScoresAtDecision
        Object trustScoresObj = ctx.getPath("batch.trustScoresAtDecision");
        if (trustScoresObj instanceof Map<?,?> trustScoresMap) {
            batchContext.put("trustScoresAtDecision", trustScoresMap);
        }

        // ciRunIds
        Object ciRunIdsObj = ctx.getPath("batch.ciRunIds");
        if (ciRunIdsObj instanceof List<?> ciRunIdsList) {
            batchContext.put("ciRunIds", ciRunIdsList);
        }

        // rejectedPrs
        Object rejectedPrsObj = ctx.getPath("batch.rejectedPrs");
        if (rejectedPrsObj instanceof List<?> rejectedPrsList) {
            batchContext.put("rejectedPrs", rejectedPrsList);
        }

        try {
            return objectMapper.writeValueAsString(batchContext);
        } catch (Exception e) {
            LOG.warnf(e, "Failed to serialize batch context to JSON — returning empty object");
            return "{}";
        }
    }
}
