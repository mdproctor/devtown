package io.casehub.devtown.review.compliance;

import io.casehub.blocks.routing.RequirementStatus;

import java.util.List;

public record AuditChainRequirement(
    String requirementId,
    String citation,
    String mechanism,
    RequirementStatus status,
    String treeRoot,
    boolean chainVerified,
    List<LedgerEventRecord> events
) {
    public static final String REQUIREMENT_ID = "audit-chain";
    public static final String CITATION = "EU AI Act Art.12 — Logging Requirements";
    public static final String MECHANISM = "Merkle Mountain Range append-only ledger (casehub-ledger)";
}
