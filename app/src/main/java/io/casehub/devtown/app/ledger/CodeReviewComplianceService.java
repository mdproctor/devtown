package io.casehub.devtown.app.ledger;

import io.casehub.blocks.routing.RequirementStatus;
import io.casehub.blocks.routing.RoutingDecisionRecord;
import io.casehub.blocks.routing.TrustRoutingRequirement;
import io.casehub.devtown.review.compliance.AuditChainRequirement;
import io.casehub.devtown.review.compliance.CodeReviewComplianceEvidence;
import io.casehub.devtown.review.compliance.GdprRequirement;
import io.casehub.devtown.review.compliance.InclusionProofRecord;
import io.casehub.devtown.review.compliance.LedgerEventRecord;
import io.casehub.devtown.review.compliance.ReviewSlaRequirement;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.casehub.ledger.model.WorkerDecisionEntry;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.ledger.runtime.service.LedgerVerificationService;
import io.casehub.ledger.runtime.service.model.InclusionProof;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * Assembles per-case compliance evidence from ledger entries.
 *
 * <p>Queries the tamper-evident audit trail for a given case ID and builds
 * a {@link CodeReviewComplianceEvidence} report covering four requirement
 * areas: audit chain integrity, review SLA, trust-based routing, and GDPR
 * capability.
 *
 * <p><strong>Layer 4 scope:</strong> The review SLA requirement is always
 * {@link RequirementStatus#GAP} because there is no direct ledger-to-WorkItem
 * link in this layer. That linkage requires case context propagation, which
 * is a future enhancement.
 */
@ApplicationScoped
public class CodeReviewComplianceService {

    private static final Logger LOG = Logger.getLogger(CodeReviewComplianceService.class);

    @Inject LedgerEntryRepository ledgerRepo;
    @Inject LedgerVerificationService verificationService;
    @Inject @LedgerPersistenceUnit EntityManager em;

    /**
     * Assembles compliance evidence for a case from its ledger entries.
     *
     * <p>Uses a transactional boundary for the initial ledger query, then
     * runs verification calls in isolated transactions to prevent exceptions
     * from the Merkle verification service from poisoning the caller's
     * transaction (the verification service methods are {@code @Transactional(REQUIRED)}
     * and mark the joining transaction as rollback-only on failure).
     *
     * @param caseId the case identifier (used as subjectId in the ledger)
     * @param tenancyId the tenancy identifier for the case
     * @return evidence if any ledger entries exist, empty otherwise
     */
    @Transactional
    public Optional<CodeReviewComplianceEvidence> findEvidence(UUID caseId, String tenancyId) {
        List<LedgerEntry> entries = ledgerRepo.findBySubjectId(caseId, tenancyId);
        if (entries.isEmpty()) {
            return Optional.empty();
        }

        // Detach entry data we need before leaving the transaction scope.
        // The entries are managed JPA entities; extract their fields now.
        List<EntrySnapshot> snapshots = entries.stream()
                .map(this::snapshot)
                .toList();

        boolean hasMergeDecision = entries.stream()
                .anyMatch(MergeDecisionLedgerEntry.class::isInstance);
        boolean mergeDecisionLinked = entries.stream()
                .filter(MergeDecisionLedgerEntry.class::isInstance)
                .anyMatch(e -> e.causedByEntryId != null);

        // Verification calls run in isolated transactions to avoid rollback
        // contamination when the Merkle frontier is absent or entries are missing.
        boolean chainVerified = verifyChainIsolated(caseId, tenancyId);
        String treeRoot = resolveTreeRootIsolated(caseId, tenancyId);

        List<LedgerEventRecord> events = snapshots.stream()
                .map(s -> new LedgerEventRecord(
                        s.id, s.eventType, s.actorId, s.actorRole,
                        s.occurredAt, s.causedByEntryId, s.digest,
                        resolveInclusionProofIsolated(s.id, tenancyId)))
                .toList();

        RequirementStatus auditStatus;
        if (chainVerified && hasMergeDecision && mergeDecisionLinked) {
            auditStatus = RequirementStatus.CLOSED;
        } else {
            auditStatus = RequirementStatus.PARTIAL;
        }

        AuditChainRequirement auditChain = new AuditChainRequirement(
                AuditChainRequirement.REQUIREMENT_ID,
                AuditChainRequirement.CITATION,
                AuditChainRequirement.MECHANISM,
                auditStatus,
                treeRoot,
                chainVerified,
                events);

        return Optional.of(new CodeReviewComplianceEvidence(
                caseId,
                Instant.now(),
                auditChain,
                buildReviewSla(),
                buildTrustRouting(snapshots),
                buildGdpr(snapshots, tenancyId)));
    }

    private ReviewSlaRequirement buildReviewSla() {
        // Layer 4: no direct ledger-to-WorkItem link. Return GAP.
        return new ReviewSlaRequirement(
                ReviewSlaRequirement.REQUIREMENT_ID,
                ReviewSlaRequirement.CITATION,
                ReviewSlaRequirement.MECHANISM,
                RequirementStatus.GAP,
                null,   // taskId
                null,   // claimDeadline
                null,   // completedAt
                null,   // slaMet — unknown when status is GAP
                List.of());
    }

    private static final String TRUST_ROUTING_REQUIREMENT_ID = "trust-routing";
    private static final String TRUST_ROUTING_CITATION =
        "EU AI Act Art.14 — Human Oversight of AI Routing";
    private static final String TRUST_ROUTING_MECHANISM =
        "TrustWeightedAgentStrategy with capability-scoped thresholds";

    private TrustRoutingRequirement buildTrustRouting(List<EntrySnapshot> snapshots) {
        List<RoutingDecisionRecord> decisions = snapshots.stream()
                .filter(s -> s.workerDecision != null)
                .map(s -> s.workerDecision)
                .toList();

        RequirementStatus status = decisions.isEmpty()
                ? RequirementStatus.GAP
                : RequirementStatus.CLOSED;

        return new TrustRoutingRequirement(
                TRUST_ROUTING_REQUIREMENT_ID,
                TRUST_ROUTING_CITATION,
                TRUST_ROUTING_MECHANISM,
                status,
                decisions);
    }

    private GdprRequirement buildGdpr(List<EntrySnapshot> snapshots, String tenancyId) {
        List<UUID> receiptIds = List.of();
        if (!snapshots.isEmpty()) {
            try {
                receiptIds = QuarkusTransaction.requiringNew().call(() ->
                        em.createQuery(
                                "SELECT e.id FROM ErasureReceiptLedgerEntry e WHERE e.tenancyId = :tenancyId",
                                UUID.class)
                            .setParameter("tenancyId", tenancyId)
                            .getResultList());
            } catch (PersistenceException e) {
                LOG.warnf("Erasure receipt query failed for compliance report: %s", e.getMessage());
            }
        }

        return new GdprRequirement(
                GdprRequirement.REQUIREMENT_ID,
                GdprRequirement.CITATION,
                GdprRequirement.MECHANISM,
                RequirementStatus.CLOSED,
                true,
                true,
                !receiptIds.isEmpty(),
                receiptIds);
    }

    /**
     * Captures the fields we need from a managed JPA entity into a plain
     * value object, so the data survives after the loading transaction commits.
     */
    private EntrySnapshot snapshot(LedgerEntry entry) {
        String eventType = resolveEventType(entry);
        RoutingDecisionRecord workerDecision = null;
        if (entry instanceof WorkerDecisionEntry wde) {
            workerDecision = new RoutingDecisionRecord(
                    wde.capabilityTag, wde.workerId,
                    wde.trustScoreAtRouting, wde.thresholdApplied, wde.id);
        }
        return new EntrySnapshot(
                entry.id, eventType, entry.actorId, entry.actorRole,
                entry.occurredAt, entry.causedByEntryId, entry.digest,
                workerDecision);
    }

    private String resolveEventType(LedgerEntry entry) {
        if (entry instanceof MergeDecisionLedgerEntry mde) {
            return "MERGE_DECISION:" + mde.decision;
        }
        if (entry instanceof CaseLedgerEntry cle) {
            return cle.eventType;
        }
        if (entry instanceof WorkerDecisionEntry wde) {
            return "WORKER_DECISION:" + wde.capabilityTag;
        }
        return entry.entryType.name();
    }

    private boolean verifyChainIsolated(UUID caseId, String tenancyId) {
        try {
            return QuarkusTransaction.requiringNew().call(
                    () -> verificationService.verify(caseId, tenancyId));
        } catch (Exception e) {
            LOG.debugf("Chain verification failed for caseId=%s: %s", caseId, e.getMessage());
            return false;
        }
    }

    private String resolveTreeRootIsolated(UUID caseId, String tenancyId) {
        try {
            return QuarkusTransaction.requiringNew().call(
                    () -> verificationService.treeRoot(caseId, tenancyId));
        } catch (Exception e) {
            LOG.debugf("Cannot resolve tree root for caseId=%s: %s", caseId, e.getMessage());
            return null;
        }
    }

    private InclusionProofRecord resolveInclusionProofIsolated(UUID entryId, String tenancyId) {
        try {
            return QuarkusTransaction.requiringNew().call(() -> {
                InclusionProof proof = verificationService.inclusionProof(entryId, tenancyId);
                return new InclusionProofRecord(
                        proof.entryIndex(),
                        proof.treeSize(),
                        proof.leafHash(),
                        proof.siblings().stream()
                                .map(s -> new InclusionProofRecord.ProofStepRecord(
                                        s.hash(), s.side().name()))
                                .toList(),
                        proof.treeRoot());
            });
        } catch (Exception e) {
            LOG.debugf("Cannot resolve inclusion proof for entryId=%s: %s",
                    entryId, e.getMessage());
            return null;
        }
    }

    /**
     * Immutable snapshot of a {@link LedgerEntry}'s fields needed for
     * compliance report assembly, extracted while the JPA entity is managed.
     */
    private record EntrySnapshot(
            UUID id,
            String eventType,
            String actorId,
            String actorRole,
            Instant occurredAt,
            UUID causedByEntryId,
            String digest,
            RoutingDecisionRecord workerDecision
    ) {}
}
