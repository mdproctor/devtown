package io.casehub.devtown.app.mcp;

import io.casehub.devtown.review.PrPayload;
import java.time.Instant;
import java.util.UUID;

public record CaseInfo(
    UUID caseId,
    String tenancyId,
    PrPayload payload,
    Instant startedAt,
    Instant lastEventAt,
    CaseTrackingStatus status
) {
    public CaseInfo withStatus(CaseTrackingStatus newStatus, Instant eventTime) {
        return new CaseInfo(caseId, tenancyId, payload, startedAt, eventTime, newStatus);
    }

    public CaseInfo withHeadSha(String newSha) {
        var updatedPayload = new PrPayload(
            payload.repo(), payload.prNumber(), newSha,
            payload.baseRef(), payload.linesChanged(),
            payload.contributor(), payload.changedPaths()
        );
        return new CaseInfo(caseId, tenancyId, updatedPayload, startedAt, lastEventAt, status);
    }

    public boolean isStalled(long thresholdMinutes) {
        return !status.isTerminal()
            && Instant.now().isAfter(lastEventAt.plusSeconds(thresholdMinutes * 60));
    }
}
