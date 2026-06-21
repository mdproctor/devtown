package io.casehub.devtown.github;

import io.casehub.devtown.domain.MergeOutcome;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GitHubMergeClientTest {

    private GitHubMergeApi api;
    private GitHubMergeClient client;

    @BeforeEach
    void setUp() {
        api = mock(GitHubMergeApi.class);
        client = new GitHubMergeClient(api, "squash");
    }

    @Test
    void merge_success_returnsSuccessWithSha() {
        when(api.merge(eq("casehubio"), eq("devtown"), eq(42), any()))
            .thenReturn(Map.of("sha", "merge-sha-abc"));

        var result = client.merge("casehubio", "devtown", 42, "head-sha-123");

        assertThat(result).isInstanceOf(MergeOutcome.Success.class);
        assertThat(((MergeOutcome.Success) result).mergeSha()).isEqualTo("merge-sha-abc");
    }

    @Test
    void merge_success_passesMethodAndSha() {
        when(api.merge(anyString(), anyString(), anyInt(), any()))
            .thenReturn(Map.of("sha", "abc"));

        client.merge("casehubio", "devtown", 42, "head-sha-123");

        verify(api).merge(eq("casehubio"), eq("devtown"), eq(42), argThat(body ->
            "squash".equals(body.get("merge_method")) &&
            "head-sha-123".equals(body.get("sha"))
        ));
    }

    @Test
    void merge_409_returnsConflictFailure() {
        when(api.merge(anyString(), anyString(), anyInt(), any()))
            .thenThrow(new WebApplicationException(Response.status(409).build()));

        var result = client.merge("casehubio", "devtown", 42, "sha");

        assertThat(result).isInstanceOf(MergeOutcome.Failure.class);
        assertThat(((MergeOutcome.Failure) result).reason()).contains("conflict");
    }

    @Test
    void merge_405_returnsBranchProtectionFailure() {
        when(api.merge(anyString(), anyString(), anyInt(), any()))
            .thenThrow(new WebApplicationException(Response.status(405).build()));

        var result = client.merge("casehubio", "devtown", 42, "sha");

        assertThat(result).isInstanceOf(MergeOutcome.Failure.class);
        assertThat(((MergeOutcome.Failure) result).reason()).contains("branch protection");
    }

    @Test
    void merge_422_returnsNotMergeableFailure() {
        when(api.merge(anyString(), anyString(), anyInt(), any()))
            .thenThrow(new WebApplicationException(Response.status(422).build()));

        var result = client.merge("casehubio", "devtown", 42, "sha");

        assertThat(result).isInstanceOf(MergeOutcome.Failure.class);
        assertThat(((MergeOutcome.Failure) result).reason()).contains("not mergeable");
    }

    @Test
    void merge_runtimeException_returnsApiErrorFailure() {
        when(api.merge(anyString(), anyString(), anyInt(), any()))
            .thenThrow(new RuntimeException("connection reset"));

        var result = client.merge("casehubio", "devtown", 42, "sha");

        assertThat(result).isInstanceOf(MergeOutcome.Failure.class);
        assertThat(((MergeOutcome.Failure) result).reason()).contains("api error");
    }

    @Test
    void merge_customMethod_passesConfiguredMethod() {
        var rebaseClient = new GitHubMergeClient(api, "rebase");
        when(api.merge(anyString(), anyString(), anyInt(), any()))
            .thenReturn(Map.of("sha", "abc"));

        rebaseClient.merge("casehubio", "devtown", 42, "sha");

        verify(api).merge(anyString(), anyString(), anyInt(), argThat(body ->
            "rebase".equals(body.get("merge_method"))
        ));
    }
}
