package io.casehub.devtown.review.compliance;

import io.casehub.blocks.routing.TrustRoutingRequirement;

import java.time.Instant;
import java.util.UUID;

public record CodeReviewComplianceEvidence(
    UUID caseId,
    Instant generatedAt,
    AuditChainRequirement auditChain,
    ReviewSlaRequirement reviewSla,
    TrustRoutingRequirement trustRouting,
    GdprRequirement gdpr
) {}
