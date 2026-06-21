package io.casehub.devtown.app.mcp;

import io.casehub.devtown.review.PrPayload;
import io.casehub.engine.common.spi.event.CaseLifecycleEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.ObservesAsync;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PrReviewCaseTracker {

    private static final Logger LOG = Logger.getLogger(PrReviewCaseTracker.class);
    private static final int DEFAULT_BUFFER_SIZE = 200;

    private final ConcurrentHashMap<UUID, CaseInfo> cases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> prIndex = new ConcurrentHashMap<>();
    private final Deque<TrackedEvent> eventBuffer;
    private final int maxBufferSize;

    public PrReviewCaseTracker() {
        this(DEFAULT_BUFFER_SIZE);
    }

    PrReviewCaseTracker(int maxBufferSize) {
        this.maxBufferSize = maxBufferSize;
        this.eventBuffer = new ArrayDeque<>(maxBufferSize);
    }

    public void register(UUID caseId, String tenancyId, PrPayload payload) {
        Instant now = Instant.now();
        cases.put(caseId, new CaseInfo(caseId, tenancyId, payload, now, now, CaseTrackingStatus.RUNNING));
        prIndex.put(payload.repo() + "#" + payload.prNumber(), caseId);
        LOG.infof("Tracking PR review case=%s repo=%s pr=#%d", caseId, payload.repo(), payload.prNumber());
    }

    public CaseInfo getCase(UUID caseId) {
        return cases.get(caseId);
    }

    public Optional<CaseInfo> findActiveCaseByPr(String repo, int prNumber) {
        UUID caseId = prIndex.get(repo + "#" + prNumber);
        if (caseId == null) return Optional.empty();
        CaseInfo info = cases.get(caseId);
        if (info == null || info.status().isTerminal()) return Optional.empty();
        return Optional.of(info);
    }

    public List<CaseInfo> activeCases() {
        return cases.values().stream()
            .filter(c -> !c.status().isTerminal())
            .toList();
    }

    public List<CaseInfo> stalledCases(long thresholdMinutes) {
        return cases.values().stream()
            .filter(c -> c.isStalled(thresholdMinutes))
            .toList();
    }

    public void updateStatus(UUID caseId, String caseStatus, Instant eventTime) {
        cases.computeIfPresent(caseId, (id, existing) ->
            existing.withStatus(CaseTrackingStatus.fromCaseStatus(caseStatus), eventTime));
    }

    public void updateHeadSha(UUID caseId, String newSha) {
        cases.computeIfPresent(caseId, (id, existing) -> existing.withHeadSha(newSha));
    }

    public void addEvent(TrackedEvent event) {
        synchronized (eventBuffer) {
            if (eventBuffer.size() >= maxBufferSize) {
                eventBuffer.pollFirst();
            }
            eventBuffer.addLast(event);
        }
    }

    public List<TrackedEvent> recentEvents(int limit, Instant since) {
        synchronized (eventBuffer) {
            var stream = eventBuffer.stream();
            if (since != null) {
                stream = stream.filter(e -> e.timestamp().isAfter(since));
            }
            return stream
                .toList()
                .reversed()
                .stream()
                .limit(limit)
                .toList()
                .reversed();
        }
    }

    void onCaseLifecycle(@ObservesAsync CaseLifecycleEvent event) {
        updateStatus(event.caseId(), event.caseStatus(), Instant.now());

        if (CaseTrackingStatus.fromCaseStatus(event.caseStatus()).isTerminal()) {
            CaseInfo terminated = cases.get(event.caseId());
            if (terminated != null) {
                prIndex.remove(terminated.payload().repo() + "#" + terminated.payload().prNumber(), event.caseId());
            }
        }

        CaseInfo info = cases.get(event.caseId());
        String repo = info != null ? info.payload().repo() : "unknown";
        int prNumber = info != null ? info.payload().prNumber() : 0;

        addEvent(new TrackedEvent(
            Instant.now(),
            event.caseId(),
            repo,
            prNumber,
            event.eventType(),
            event.caseStatus(),
            event.actorId()
        ));
    }
}
