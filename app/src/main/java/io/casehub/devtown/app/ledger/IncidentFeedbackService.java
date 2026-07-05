package io.casehub.devtown.app.ledger;

import io.casehub.devtown.domain.DevtownTrustDimension;
import io.casehub.devtown.domain.FlaggedAgent;
import io.casehub.devtown.domain.IncidentFeedback;
import io.casehub.devtown.domain.IncidentFeedbackResult;
import io.casehub.devtown.domain.ReviewDomain;
import io.casehub.ledger.api.model.AttestationVerdict;
import io.casehub.ledger.model.WorkerDecisionEntry;
import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.ledger.api.model.LedgerAttestation;
import io.casehub.ledger.api.model.LedgerEntry;
import io.casehub.ledger.runtime.persistence.LedgerPersistenceUnit;
import io.casehub.ledger.api.spi.LedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class IncidentFeedbackService {

    static final String ATTESTOR_ID = "devtown:incident-feedback";
    static final String ATTESTOR_ROLE = "INCIDENT_FEEDBACK";

    @Inject LedgerEntryRepository ledgerRepo;
    @Inject LedgerConfig ledgerConfig;
    @Inject @LedgerPersistenceUnit EntityManager em;

    @Transactional
    public IncidentFeedbackResult recordFeedback(IncidentFeedback feedback) {
        if (!ledgerConfig.enabled()) {
            throw new WebApplicationException("Ledger is disabled", Response.Status.SERVICE_UNAVAILABLE);
        }

        if (!ReviewDomain.REVIEW_CAPABILITIES.contains(feedback.reviewCapability())) {
            throw new BadRequestException("Unknown review capability: " + feedback.reviewCapability());
        }

        MergeDecisionLedgerEntry mergeDecision = resolveMergeDecision(feedback);
        UUID caseId = mergeDecision.caseId;
        String tenancyId = mergeDecision.tenancyId;

        List<WorkerDecisionEntry> targets = findWorkerDecisions(caseId, feedback.reviewCapability(), tenancyId);
        if (targets.isEmpty()) {
            throw new WebApplicationException(
                    "No agent performed " + feedback.reviewCapability() + " on case " + caseId,
                    Response.Status.NOT_FOUND);
        }

        List<LedgerAttestation> existingAttestations =
                ledgerRepo.findAttestationsByAttestorIdAndCapabilityTag(
                        ATTESTOR_ID, feedback.reviewCapability(), tenancyId);

        String evidencePrefix = "Incident " + feedback.incidentId() + ":";
        List<FlaggedAgent> flaggedAgents = new ArrayList<>();
        int newCount = 0;

        for (WorkerDecisionEntry wde : targets) {
            Optional<LedgerAttestation> existing = existingAttestations.stream()
                    .filter(a -> wde.id.equals(a.ledgerEntryId)
                            && a.evidence != null
                            && a.evidence.startsWith(evidencePrefix))
                    .findFirst();

            if (existing.isPresent()) {
                flaggedAgents.add(new FlaggedAgent(wde.workerId, wde.capabilityTag, existing.get().id));
            } else {
                LedgerAttestation attestation = buildAttestation(wde, feedback);
                ledgerRepo.saveAttestation(attestation, tenancyId);
                flaggedAgents.add(new FlaggedAgent(wde.workerId, wde.capabilityTag, attestation.id));
                newCount++;
            }
        }

        return new IncidentFeedbackResult(caseId, newCount, flaggedAgents);
    }

    private MergeDecisionLedgerEntry resolveMergeDecision(IncidentFeedback feedback) {
        List<MergeDecisionLedgerEntry> decisions = em
                .createNamedQuery("MergeDecisionLedgerEntry.findApprovedByRepoAndPr", MergeDecisionLedgerEntry.class)
                .setParameter("repo", feedback.repository())
                .setParameter("prNumber", feedback.prNumber())
                .getResultList();

        if (feedback.caseId() != null) {
            return decisions.stream()
                    .filter(m -> feedback.caseId().equals(m.caseId))
                    .findFirst()
                    .orElseThrow(() -> new WebApplicationException(
                            "No APPROVED merge decision for caseId " + feedback.caseId(),
                            Response.Status.NOT_FOUND));
        }

        if (decisions.isEmpty()) {
            throw new WebApplicationException(
                    "No APPROVED merge decision for " + feedback.repository() + "#" + feedback.prNumber(),
                    Response.Status.NOT_FOUND);
        }
        if (decisions.size() > 1) {
            List<UUID> candidates = decisions.stream().map(m -> m.caseId).toList();
            throw new WebApplicationException(
                    Response.status(Response.Status.CONFLICT)
                            .entity(candidates)
                            .build());
        }
        return decisions.get(0);
    }

    private List<WorkerDecisionEntry> findWorkerDecisions(UUID caseId, String capabilityTag, String tenancyId) {
        List<LedgerEntry> entries = ledgerRepo.findBySubjectId(caseId, tenancyId);
        return entries.stream()
                .filter(WorkerDecisionEntry.class::isInstance)
                .map(WorkerDecisionEntry.class::cast)
                .filter(w -> capabilityTag.equals(w.capabilityTag))
                .toList();
    }

    private LedgerAttestation buildAttestation(WorkerDecisionEntry wde, IncidentFeedback feedback) {
        LedgerAttestation att = new LedgerAttestation();
        att.ledgerEntryId = wde.id;
        att.subjectId = wde.subjectId;
        att.attestorId = ATTESTOR_ID;
        att.attestorType = ActorType.SYSTEM;
        att.attestorRole = ATTESTOR_ROLE;
        att.verdict = AttestationVerdict.FLAGGED;
        att.capabilityTag = feedback.reviewCapability();
        att.trustDimension = DevtownTrustDimension.REVIEW_THOROUGHNESS;
        att.confidence = feedback.severity().confidence();
        att.dimensionScore = null;
        att.evidence = "Incident " + feedback.incidentId() + ": " + feedback.description();
        return att;
    }
}
