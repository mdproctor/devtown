package io.casehub.devtown.app.mcp;

import io.casehub.devtown.review.PrPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrReviewCaseTrackerTest {

    private PrReviewCaseTracker tracker;
    private UUID caseId;
    private PrPayload payload;

    @BeforeEach
    void setUp() {
        tracker = new PrReviewCaseTracker();
        caseId = UUID.randomUUID();
        payload = new PrPayload("casehubio/devtown", 123, "abc123", "main", 50, "alice", List.of("src/Main.java"));
    }

    @Test
    void register_addsCase_retrievableViaGetCase() {
        tracker.register(caseId, "tenant-1", payload);

        CaseInfo info = tracker.getCase(caseId);
        assertThat(info).isNotNull();
        assertThat(info.caseId()).isEqualTo(caseId);
        assertThat(info.tenancyId()).isEqualTo("tenant-1");
        assertThat(info.payload()).isEqualTo(payload);
        assertThat(info.status()).isEqualTo(CaseTrackingStatus.RUNNING);
    }

    @Test
    void register_addsCase_appearsInActiveCases() {
        tracker.register(caseId, "tenant-1", payload);

        List<CaseInfo> active = tracker.activeCases();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).caseId()).isEqualTo(caseId);
    }

    @Test
    void updateStatus_toCompleted_movesToTerminal_excludedFromActiveCases() {
        tracker.register(caseId, "tenant-1", payload);

        tracker.updateStatus(caseId, "COMPLETED", Instant.now());

        CaseInfo info = tracker.getCase(caseId);
        assertThat(info.status()).isEqualTo(CaseTrackingStatus.COMPLETED);
        assertThat(info.status().isTerminal()).isTrue();
        assertThat(tracker.activeCases()).isEmpty();
    }

    @Test
    void updateStatus_forUnknownCase_silentlyIgnored() {
        UUID unknownId = UUID.randomUUID();

        // Should not throw
        tracker.updateStatus(unknownId, "COMPLETED", Instant.now());

        assertThat(tracker.getCase(unknownId)).isNull();
    }

    @Test
    void stalledCases_returnsOnlyCases_withLastEventOlderThanThreshold() throws InterruptedException {
        UUID stalledId = UUID.randomUUID();
        UUID recentId = UUID.randomUUID();

        PrPayload payload1 = new PrPayload("casehubio/devtown", 100, "aaa", "main", 10, "bob", List.of());
        PrPayload payload2 = new PrPayload("casehubio/devtown", 200, "bbb", "main", 20, "carol", List.of());

        tracker.register(stalledId, "tenant-1", payload1);
        Thread.sleep(100); // Ensure time difference
        tracker.register(recentId, "tenant-1", payload2);

        // Update stalled case with old timestamp
        Instant oldTime = Instant.now().minusSeconds(600); // 10 minutes ago
        tracker.updateStatus(stalledId, "RUNNING", oldTime);

        List<CaseInfo> stalled = tracker.stalledCases(5); // 5 minute threshold
        assertThat(stalled).hasSize(1);
        assertThat(stalled.get(0).caseId()).isEqualTo(stalledId);
    }

    @Test
    void addEvent_addsToRingBuffer_retrievableViaRecentEvents() {
        TrackedEvent event = new TrackedEvent(
            Instant.now(),
            caseId,
            "casehubio/devtown",
            123,
            "case.started",
            "RUNNING",
            "actor-1"
        );

        tracker.addEvent(event);

        List<TrackedEvent> events = tracker.recentEvents(10, null);
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).isEqualTo(event);
    }

    @Test
    void recentEvents_respectsLimit_returnsMostRecentN() {
        for (int i = 0; i < 10; i++) {
            tracker.addEvent(new TrackedEvent(
                Instant.now().plusSeconds(i),
                UUID.randomUUID(),
                "repo",
                i,
                "event-" + i,
                "RUNNING",
                "actor"
            ));
        }

        List<TrackedEvent> events = tracker.recentEvents(3, null);
        assertThat(events).hasSize(3);
        // Should be events 7, 8, 9 (most recent 3) in chronological order
        assertThat(events.get(0).prNumber()).isEqualTo(7);
        assertThat(events.get(1).prNumber()).isEqualTo(8);
        assertThat(events.get(2).prNumber()).isEqualTo(9);
    }

    @Test
    void recentEvents_filtersByTimestamp() throws InterruptedException {
        Instant t0 = Instant.now();

        tracker.addEvent(new TrackedEvent(t0, UUID.randomUUID(), "repo", 1, "old", "RUNNING", "actor"));
        Thread.sleep(10);
        Instant cutoff = Instant.now();
        Thread.sleep(10);
        tracker.addEvent(new TrackedEvent(Instant.now(), UUID.randomUUID(), "repo", 2, "new", "RUNNING", "actor"));

        List<TrackedEvent> events = tracker.recentEvents(10, cutoff);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).prNumber()).isEqualTo(2);
    }

    @Test
    void ringBuffer_evictsOldest_whenFull() {
        PrReviewCaseTracker smallTracker = new PrReviewCaseTracker(5);

        for (int i = 0; i < 10; i++) {
            smallTracker.addEvent(new TrackedEvent(
                Instant.now().plusSeconds(i),
                UUID.randomUUID(),
                "repo",
                i,
                "event-" + i,
                "RUNNING",
                "actor"
            ));
        }

        List<TrackedEvent> events = smallTracker.recentEvents(100, null);
        assertThat(events).hasSize(5);
        // Should have events 5, 6, 7, 8, 9 (oldest 0-4 were evicted)
        assertThat(events.get(0).prNumber()).isEqualTo(5);
        assertThat(events.get(4).prNumber()).isEqualTo(9);
    }

    @Test
    void recentEvents_returnsChronologicalOrder_oldestFirst() {
        Instant t0 = Instant.now();

        tracker.addEvent(new TrackedEvent(t0, UUID.randomUUID(), "repo", 1, "first", "RUNNING", "actor"));
        tracker.addEvent(new TrackedEvent(t0.plusSeconds(1), UUID.randomUUID(), "repo", 2, "second", "RUNNING", "actor"));
        tracker.addEvent(new TrackedEvent(t0.plusSeconds(2), UUID.randomUUID(), "repo", 3, "third", "RUNNING", "actor"));

        List<TrackedEvent> events = tracker.recentEvents(3, null);
        assertThat(events).hasSize(3);
        assertThat(events.get(0).prNumber()).isEqualTo(1);
        assertThat(events.get(1).prNumber()).isEqualTo(2);
        assertThat(events.get(2).prNumber()).isEqualTo(3);
    }
}
