package io.casehub.devtown.app.ledger;

import io.casehub.ledger.runtime.model.LedgerEntry;
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

    @Override
    protected byte[] domainContentBytes() {
        return String.join("|",
                String.valueOf(prNumber),
                LedgerContentUtils.escapePipe(repository),
                LedgerContentUtils.escapePipe(commitSha),
                LedgerContentUtils.escapePipe(decision),
                caseId != null ? caseId.toString() : ""
        ).getBytes(StandardCharsets.UTF_8);
    }
}
