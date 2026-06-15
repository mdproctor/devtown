package io.casehub.devtown.app.ledger;

import io.casehub.ledger.runtime.model.LedgerEntry;
import jakarta.persistence.Column;
import jakarta.persistence.DiscriminatorValue;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;
import java.nio.charset.StandardCharsets;

@Entity
@Table(name = "erasure_receipt_ledger_entry", indexes = {
    @Index(name = "idx_erasure_receipt_actor_token", columnList = "erased_actor_token")
})
@DiscriminatorValue("ERASURE_RECEIPT")
@NamedQuery(
    name = "ErasureReceiptLedgerEntry.findByTokens",
    query = "SELECT e FROM ErasureReceiptLedgerEntry e WHERE e.erasedActorToken IN :tokens"
)
public class ErasureReceiptLedgerEntry extends LedgerEntry {

    @Column(name = "erased_actor_token", nullable = false)
    public String erasedActorToken;

    @Column(name = "reason", length = 1000)
    public String reason;

    @Column(name = "ledger_entries_affected", nullable = false)
    public long ledgerEntriesAffected;

    @Column(name = "memory_records_erased", nullable = false)
    public int memoryRecordsErased;

    @Override
    protected byte[] domainContentBytes() {
        return String.join("|",
                LedgerContentUtils.escapePipe(erasedActorToken),
                LedgerContentUtils.escapePipe(reason),
                String.valueOf(ledgerEntriesAffected),
                String.valueOf(memoryRecordsErased)
        ).getBytes(StandardCharsets.UTF_8);
    }
}
