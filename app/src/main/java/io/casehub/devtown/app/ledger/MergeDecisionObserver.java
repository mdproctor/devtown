package io.casehub.devtown.app.ledger;

import io.casehub.api.context.CaseContext;
import io.casehub.engine.common.internal.model.CaseInstance;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.casehub.ledger.runtime.config.LedgerConfig;
import io.casehub.ledger.runtime.model.supplement.ComplianceSupplement;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jboss.logging.Logger;

/**
 * Observes terminal {@link CaseLifecycleEvent} transitions and writes a
 * {@link MergeDecisionLedgerEntry} to the tamper-evident audit trail.
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

    @Transactional
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
            ci = caseInstanceRepo.findByUuid(event.caseId())
                    .await().atMost(Duration.ofSeconds(5));
        } catch (Exception e) {
            LOG.warnf(e, "Failed to lookup CaseInstance for caseId=%s", event.caseId());
            return;
        }
        if (ci == null) return;

        CaseContext ctx = ci.getCaseContext();
        if (ctx == null) return;

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

        ComplianceSupplement cs = new ComplianceSupplement();
        cs.algorithmRef = "casehub-devtown:pr-review-v1";
        cs.humanOverrideAvailable = true;
        cs.contestationUri = "/api/reviews/" + prNumber + "/contest";
        entry.attach(cs);

        ledgerRepo.save(entry, event.tenancyId());
        LOG.debugf("Merge decision written: caseId=%s decision=%s pr=%s#%d",
                event.caseId(), decision, repo, prNumber);
    }
}
