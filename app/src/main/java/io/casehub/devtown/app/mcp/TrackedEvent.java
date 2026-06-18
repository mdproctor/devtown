package io.casehub.devtown.app.mcp;

import java.time.Instant;
import java.util.UUID;

public record TrackedEvent(
    Instant timestamp,
    UUID caseId,
    String repo,
    int prNumber,
    String eventType,
    String caseStatus,
    String actorId
) {}
