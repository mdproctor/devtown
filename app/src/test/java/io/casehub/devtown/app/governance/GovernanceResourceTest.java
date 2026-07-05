package io.casehub.devtown.app.governance;

import io.casehub.devtown.app.mcp.TrackedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for GovernanceResource.
 *
 * Note: Using plain unit tests instead of @QuarkusTest due to CDI deployment
 * issues (30 ambiguous bean problems). These tests verify delegation logic
 * by mocking GovernanceQueryService.
 */
class GovernanceResourceTest {

    private GovernanceQueryService mockService;
    private GovernanceResource resource;

    @BeforeEach
    void setup() {
        mockService = Mockito.mock(GovernanceQueryService.class);
        resource = new GovernanceResource();
        resource.queryService = mockService;
    }

    @Test
    void queueStatus_delegatesToService() {
        var expected = new GovernanceQueryService.QueueStatus(5, Map.of("ACTIVE", 3, "WAITING", 2), List.of());
        when(mockService.queueStatus()).thenReturn(expected);

        var result = resource.queueStatus();

        assertSame(expected, result);
        verify(mockService).queueStatus();
    }

    @Test
    void recentEvents_delegatesToService() {
        Instant now = Instant.now();
        var expected = List.of(
            new TrackedEvent(now, UUID.randomUUID(), "repo", 1, "CASE_CREATED", "ACTIVE", "actor")
        );
        when(mockService.recentEvents(50, null)).thenReturn(expected);

        var result = resource.recentEvents(50, null);

        assertSame(expected, result);
        verify(mockService).recentEvents(50, null);
    }

    @Test
    void recentEvents_parsesSinceParam() {
        Instant since = Instant.parse("2026-06-30T10:00:00Z");
        Instant now = Instant.now();
        var expected = List.of(
            new TrackedEvent(now, UUID.randomUUID(), "repo", 1, "CASE_CREATED", "ACTIVE", "actor")
        );
        when(mockService.recentEvents(100, since)).thenReturn(expected);

        var result = resource.recentEvents(100, "2026-06-30T10:00:00Z");

        assertSame(expected, result);
        verify(mockService).recentEvents(100, since);
    }

    @Test
    void systemHealth_delegatesToService() {
        var expected = new GovernanceQueryService.SystemHealth(10, 5, Map.of("code-analysis", 0.8), 3, 2);
        when(mockService.systemHealth()).thenReturn(expected);

        var result = resource.systemHealth();

        assertSame(expected, result);
        verify(mockService).systemHealth();
    }

    @Test
    void problems_delegatesToService() {
        var expected = List.of(
            new GovernanceQueryService.Problem("stalled_case", "warning", "PR stalled", UUID.randomUUID(), null, Instant.now())
        );
        when(mockService.problems(60)).thenReturn(expected);

        var result = resource.problems(60);

        assertSame(expected, result);
        verify(mockService).problems(60);
    }

    @Test
    void reviewsList_delegatesToService() {
        var now = Instant.now();
        var expected = List.of(
            new GovernanceQueryService.ReviewListEntry(UUID.randomUUID(), "repo", 1, "contributor", "ACTIVE", now, now)
        );
        when(mockService.reviewsList()).thenReturn(expected);

        var result = resource.reviewsList();

        assertSame(expected, result);
        verify(mockService).reviewsList();
    }

    @Test
    void reviewDetail_delegatesToService() {
        UUID caseId = UUID.randomUUID();
        var expected = new GovernanceQueryService.ReviewDetail(caseId, null, List.of(), List.of());
        when(mockService.reviewDetail(caseId)).thenReturn(expected);

        var result = resource.reviewDetail(caseId);

        assertSame(expected, result);
        verify(mockService).reviewDetail(caseId);
    }

    @Test
    void reviewerFleet_delegatesToService() {
        var expected = List.of(
            new GovernanceQueryService.ReviewerFleetEntry("actor-1", Map.of("code-analysis", 0.7), "active", 2, 10)
        );
        when(mockService.reviewerFleet()).thenReturn(expected);

        var result = resource.reviewerFleet();

        assertSame(expected, result);
        verify(mockService).reviewerFleet();
    }

    @Test
    void reviewerHealth_delegatesToService() {
        var expected = new GovernanceQueryService.ReviewerHealth("actor-1", 2, Map.of(), Map.of(), 10, List.of());
        when(mockService.reviewerHealth("actor-1")).thenReturn(expected);

        var result = resource.reviewerHealth("actor-1");

        assertSame(expected, result);
        verify(mockService).reviewerHealth("actor-1");
    }

    @Test
    void mergeQueue_delegatesToService() {
        var expected = new GovernanceQueryService.MergeQueueStatus(5, 1, List.of(), List.of());
        when(mockService.mergeQueue()).thenReturn(expected);

        var result = resource.mergeQueue();

        assertSame(expected, result);
        verify(mockService).mergeQueue();
    }

    @Test
    void mergeQueueMetrics_delegatesToService() {
        var expected = new GovernanceQueryService.MergeQueueMetrics(5, 1, 120, 60, 0.85, Map.of(), 10, 0.05, Map.of());
        when(mockService.mergeQueueMetrics()).thenReturn(expected);

        var result = resource.mergeQueueMetrics();

        assertSame(expected, result);
        verify(mockService).mergeQueueMetrics();
    }

    @Test
    void batchStatus_delegatesToService() {
        UUID batchId = UUID.randomUUID();
        var expected = new GovernanceQueryService.BatchStatus("batch-1", batchId, List.of(), "ROUTINE", "trust-weighted");
        when(mockService.batchStatus(batchId)).thenReturn(expected);

        var result = resource.batchStatus(batchId);

        assertSame(expected, result);
        verify(mockService).batchStatus(batchId);
    }

    @Test
    void triageItems_delegatesToService() {
        var expected = List.of(
            new GovernanceQueryService.TriageItem(UUID.randomUUID(), "repo#1", "pr-approval", "humans", Instant.now(), "L1", Instant.now(), UUID.randomUUID())
        );
        when(mockService.triageItems()).thenReturn(expected);

        var result = resource.triageItems();

        assertSame(expected, result);
        verify(mockService).triageItems();
    }
}
