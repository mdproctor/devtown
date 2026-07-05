package io.casehub.devtown.review.compliance;

import io.casehub.blocks.routing.RequirementStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReviewSlaRequirement(
    String requirementId,
    String citation,
    String mechanism,
    RequirementStatus status,
    UUID taskId,
    Instant claimDeadline,
    Instant completedAt,
    Boolean slaMet,
    List<String> candidateGroups
) {
    public static final String REQUIREMENT_ID = "review-sla";
    public static final String CITATION = "Internal Engineering SLA — Human Oversight";
    public static final String MECHANISM = "casehub-work WorkItem lifecycle with SLA breach policy";
}
