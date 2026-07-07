-- V102: add LedgerEntry @MappedSuperclass base columns to merge_decision_ledger_entry
-- These columns were added to LedgerEntry after V2002 was created.
-- MergeDecisionLedgerEntry extends LedgerEntry (@MappedSuperclass), so all
-- base columns must be on the concrete entity's table.

ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS subject_id UUID;
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS sequence_number INT NOT NULL DEFAULT 0;
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS entry_type VARCHAR(50);
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS actor_id VARCHAR(255);
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS actor_type VARCHAR(50);
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS actor_role VARCHAR(100);
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS occurred_at TIMESTAMP;
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS digest VARCHAR(128);
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS trace_id VARCHAR(64);
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS caused_by_entry_id UUID;
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS agent_signature VARBINARY(256);
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS agent_public_key VARBINARY(256);
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS agent_key_ref VARCHAR(255);
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS actor_did TEXT;
ALTER TABLE merge_decision_ledger_entry ADD COLUMN IF NOT EXISTS supplement_json TEXT;
