package io.casehub.devtown.github;

import io.casehub.devtown.domain.CombinedCiStatus;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GitHubCiStatusClientTest {

    private GitHubChecksApi api;
    private GitHubCiStatusClient client;

    @BeforeEach
    void setUp() {
        api = mock(GitHubChecksApi.class);
        client = new GitHubCiStatusClient(api);
    }

    @Test
    void allSuitesSuccess_returnsPassing() {
        when(api.listCheckSuites(eq("casehubio"), eq("devtown"), eq("sha123"), eq(100)))
            .thenReturn(response(2,
                suite(1, "completed", "success"),
                suite(2, "completed", "success")));

        var result = client.getCombinedStatus("casehubio", "devtown", "sha123");

        assertThat(result).isInstanceOf(CombinedCiStatus.Passing.class);
    }

    @Test
    void successAndNeutral_returnsPassing() {
        when(api.listCheckSuites(eq("casehubio"), eq("devtown"), eq("sha123"), eq(100)))
            .thenReturn(response(2,
                suite(1, "completed", "success"),
                suite(2, "completed", "neutral")));

        var result = client.getCombinedStatus("casehubio", "devtown", "sha123");

        assertThat(result).isInstanceOf(CombinedCiStatus.Passing.class);
    }

    @Test
    void successAndSkipped_returnsPassing() {
        when(api.listCheckSuites(eq("casehubio"), eq("devtown"), eq("sha123"), eq(100)))
            .thenReturn(response(2,
                suite(1, "completed", "success"),
                suite(2, "completed", "skipped")));

        var result = client.getCombinedStatus("casehubio", "devtown", "sha123");

        assertThat(result).isInstanceOf(CombinedCiStatus.Passing.class);
    }

    @Test
    void oneFailure_returnsFailing() {
        when(api.listCheckSuites(eq("casehubio"), eq("devtown"), eq("sha123"), eq(100)))
            .thenReturn(response(3,
                suite(1, "completed", "success"),
                suite(2, "completed", "failure"),
                suite(3, "completed", "success")));

        var result = client.getCombinedStatus("casehubio", "devtown", "sha123");

        assertThat(result).isInstanceOf(CombinedCiStatus.Failing.class);
        assertThat(((CombinedCiStatus.Failing) result).summary()).contains("1");
    }

    @Test
    void cancelled_isBlocking() {
        when(api.listCheckSuites(eq("casehubio"), eq("devtown"), eq("sha123"), eq(100)))
            .thenReturn(response(1,
                suite(1, "completed", "cancelled")));

        var result = client.getCombinedStatus("casehubio", "devtown", "sha123");

        assertThat(result).isInstanceOf(CombinedCiStatus.Failing.class);
    }

    @Test
    void timedOut_isBlocking() {
        when(api.listCheckSuites(eq("casehubio"), eq("devtown"), eq("sha123"), eq(100)))
            .thenReturn(response(1,
                suite(1, "completed", "timed_out")));

        var result = client.getCombinedStatus("casehubio", "devtown", "sha123");

        assertThat(result).isInstanceOf(CombinedCiStatus.Failing.class);
    }

    @Test
    void someInProgress_returnsPending() {
        when(api.listCheckSuites(eq("casehubio"), eq("devtown"), eq("sha123"), eq(100)))
            .thenReturn(response(3,
                suite(1, "completed", "success"),
                suite(2, "in_progress", null),
                suite(3, "queued", null)));

        var result = client.getCombinedStatus("casehubio", "devtown", "sha123");

        assertThat(result).isInstanceOf(CombinedCiStatus.Pending.class);
        var pending = (CombinedCiStatus.Pending) result;
        assertThat(pending.completed()).isEqualTo(1);
        assertThat(pending.total()).isEqualTo(3);
    }

    @Test
    void zeroSuites_returnsPending() {
        when(api.listCheckSuites(eq("casehubio"), eq("devtown"), eq("sha123"), eq(100)))
            .thenReturn(response(0));

        var result = client.getCombinedStatus("casehubio", "devtown", "sha123");

        assertThat(result).isInstanceOf(CombinedCiStatus.Pending.class);
        var pending = (CombinedCiStatus.Pending) result;
        assertThat(pending.completed()).isEqualTo(0);
        assertThat(pending.total()).isEqualTo(0);
    }

    @Test
    void totalCountExceedsReturnedList_returnsPending() {
        when(api.listCheckSuites(eq("casehubio"), eq("devtown"), eq("sha123"), eq(100)))
            .thenReturn(new CheckSuitesResponse(105, List.of(
                suite(1, "completed", "success"))));

        var result = client.getCombinedStatus("casehubio", "devtown", "sha123");

        assertThat(result).isInstanceOf(CombinedCiStatus.Pending.class);
    }

    @Test
    void webApplicationException_returnsUnavailable() {
        when(api.listCheckSuites(anyString(), anyString(), anyString(), anyInt()))
            .thenThrow(new WebApplicationException(Response.status(403).build()));

        var result = client.getCombinedStatus("casehubio", "devtown", "sha123");

        assertThat(result).isInstanceOf(CombinedCiStatus.Unavailable.class);
        assertThat(((CombinedCiStatus.Unavailable) result).reason()).contains("403");
    }

    @Test
    void processingException_returnsUnavailable() {
        when(api.listCheckSuites(anyString(), anyString(), anyString(), anyInt()))
            .thenThrow(new ProcessingException("connection reset"));

        var result = client.getCombinedStatus("casehubio", "devtown", "sha123");

        assertThat(result).isInstanceOf(CombinedCiStatus.Unavailable.class);
        assertThat(((CombinedCiStatus.Unavailable) result).reason()).contains("connection reset");
    }

    private static CheckSuitesResponse response(int totalCount, CheckSuitesResponse.CheckSuite... suites) {
        return new CheckSuitesResponse(totalCount, List.of(suites));
    }

    private static CheckSuitesResponse.CheckSuite suite(long id, String status, String conclusion) {
        return new CheckSuitesResponse.CheckSuite(id, status, conclusion);
    }
}
