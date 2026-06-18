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

    public boolean isStalled(long thresholdMinutes) {
        return !status.isTerminal()
            && Instant.now().isAfter(lastEventAt.plusSeconds(thresholdMinutes * 60));
    }
}
