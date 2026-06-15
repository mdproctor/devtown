package io.casehub.devtown.app.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErasureReceiptLedgerEntryTest {

    @Test
    void domainContentBytes_includesAllFields() {
        var entry = new ErasureReceiptLedgerEntry();
        entry.erasedActorToken = "tok_abc123";
        entry.reason = "GDPR Art.17 request";
        entry.ledgerEntriesAffected = 12;
        entry.memoryRecordsErased = 3;

        byte[] bytes = entry.domainContentBytes();

        String content = new String(bytes);
        assertThat(content).contains("tok_abc123");
        assertThat(content).contains("GDPR Art.17 request");
        assertThat(content).contains("12");
        assertThat(content).contains("3");
    }

    @Test
    void domainContentBytes_handlesNullReason() {
        var entry = new ErasureReceiptLedgerEntry();
        entry.erasedActorToken = "tok_xyz";
        entry.reason = null;
        entry.ledgerEntriesAffected = 0;
        entry.memoryRecordsErased = 0;

        byte[] bytes = entry.domainContentBytes();

        String content = new String(bytes);
        assertThat(content).contains("tok_xyz");
        assertThat(content).contains("0");
    }

    @Test
    void domainContentBytes_escapesPipesInFieldValues() {
        var entry = new ErasureReceiptLedgerEntry();
        entry.erasedActorToken = "tok_abc";
        entry.reason = "reason|with|pipes";
        entry.ledgerEntriesAffected = 1;
        entry.memoryRecordsErased = 0;

        String content = new String(entry.domainContentBytes());
        assertThat(content).isEqualTo("tok_abc|reason\\|with\\|pipes|1|0");
    }

    @Test
    void domainContentBytes_nonEmpty() {
        var entry = new ErasureReceiptLedgerEntry();
        entry.erasedActorToken = "tok_test";
        entry.reason = "test";
        entry.ledgerEntriesAffected = 1;
        entry.memoryRecordsErased = 0;

        assertThat(entry.domainContentBytes()).isNotEmpty();
    }
}
