package io.casehub.devtown.app.ledger;

import io.casehub.ledger.api.model.LedgerEntry;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Entity
@Table(name = "merge_decision_ledger_entry", indexes = {
    @Index(name = "idx_merge_decision_entry_case_id", columnList = "case_id")
})
@DiscriminatorValue("MERGE_DECISION")
@NamedQuery(
    name = "MergeDecisionLedgerEntry.findApprovedByRepoAndPr",
    query = "SELECT m FROM MergeDecisionLedgerEntry m WHERE m.repository = :repo AND m.prNumber = :prNumber AND m.decision = 'APPROVED'"
)
public class MergeDecisionLedgerEntry extends LedgerEntry {

    @Column(name = "pr_number", nullable = false)
    public int prNumber;

    @Column(name = "repository", nullable = false, length = 255)
    public String repository;

    @Column(name = "commit_sha", length = 40)
    public String commitSha;

    @Column(name = "decision", nullable = false, length = 20)
    public String decision;

    @Column(name = "case_id", nullable = false)
    public UUID caseId;

    @Column(name = "batch_id", length = 64)
    public String batchId;

    @Column(name = "batch_size")
    public Integer batchSize;

    @Column(name = "bisection_occurred")
    public Boolean bisectionOccurred;

    @Column(name = "bisection_strategy", length = 30)
    public String bisectionStrategy;

    @Column(name = "batch_context_json", columnDefinition = "TEXT")
    public String batchContextJson;

    @Override
    protected byte[] domainContentBytes() {
        return String.join("|",
                String.valueOf(prNumber),
                LedgerContentUtils.escapePipe(repository),
                LedgerContentUtils.escapePipe(commitSha),
                LedgerContentUtils.escapePipe(decision),
                caseId != null ? caseId.toString() : "",
                LedgerContentUtils.escapePipe(batchId),
                batchSize != null ? String.valueOf(batchSize) : "",
                bisectionOccurred != null ? String.valueOf(bisectionOccurred) : "",
                LedgerContentUtils.escapePipe(bisectionStrategy)
        ).getBytes(StandardCharsets.UTF_8);
    }
}
