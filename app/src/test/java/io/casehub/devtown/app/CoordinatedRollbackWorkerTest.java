package io.casehub.devtown.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import io.casehub.devtown.domain.RevertClient;
import io.casehub.devtown.domain.RevertOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class CoordinatedRollbackWorkerTest {

    CoordinatedChangeCaseHub hub;
    RevertClient revertClient;

    @BeforeEach
    void setUp() {
        revertClient = mock(RevertClient.class);
        hub = new CoordinatedChangeCaseHub();
        hub.revertClient = revertClient;
    }

    @Test
    @SuppressWarnings("unchecked")
    void revertsAllSuccessfulMerges() {
        when(revertClient.revert(eq("casehubio"), eq("engine"), eq("main"), eq("sha1"), anyString()))
            .thenReturn(new RevertOutcome.Success(101, "revertSha1"));
        when(revertClient.revert(eq("casehubio"), eq("work"), eq("main"), eq("sha2"), anyString()))
            .thenReturn(new RevertOutcome.Success(102, "revertSha2"));

        var input = Map.<String, Object>of(
            "repos", List.of(
                Map.of("owner", "casehubio", "repo", "engine", "prNumber", 42, "targetBranch", "main"),
                Map.of("owner", "casehubio", "repo", "platform", "prNumber", 99, "targetBranch", "main"),
                Map.of("owner", "casehubio", "repo", "work", "prNumber", 7, "targetBranch", "main")
            ),
            "mergeResults", List.of(
                Map.of("repo", "casehubio/engine", "status", "success", "mergeSha", "sha1"),
                Map.of("repo", "casehubio/platform", "status", "failed", "reason", "merge conflict"),
                Map.of("repo", "casehubio/work", "status", "success", "mergeSha", "sha2")
            )
        );

        var result = hub.adaptCoordinatedRollback(input);
        assertThat(result.outcome()).isInstanceOf(io.casehub.worker.api.WorkerOutcome.Success.class);

        var rollbackResults = (List<Map<String, Object>>) result.output().get("rollbackResults");
        assertThat(rollbackResults).hasSize(2);
        assertThat(rollbackResults.get(0).get("repo")).isEqualTo("casehubio/engine");
        assertThat(rollbackResults.get(0).get("status")).isEqualTo("success");
        assertThat(rollbackResults.get(0).get("revertPrNumber")).isEqualTo(101);
        assertThat(rollbackResults.get(0).get("revertSha")).isEqualTo("revertSha1");
        assertThat(rollbackResults.get(1).get("repo")).isEqualTo("casehubio/work");
        assertThat(rollbackResults.get(1).get("status")).isEqualTo("success");
    }

    @Test
    @SuppressWarnings("unchecked")
    void bestEffortOnConflict() {
        when(revertClient.revert(eq("casehubio"), eq("engine"), eq("main"), eq("sha1"), anyString()))
            .thenReturn(new RevertOutcome.MergeConflict(101, "branch protection prevents merge"));
        when(revertClient.revert(eq("casehubio"), eq("work"), eq("main"), eq("sha2"), anyString()))
            .thenReturn(new RevertOutcome.Success(102, "revertSha2"));

        var input = Map.<String, Object>of(
            "repos", List.of(
                Map.of("owner", "casehubio", "repo", "engine", "prNumber", 42, "targetBranch", "main"),
                Map.of("owner", "casehubio", "repo", "platform", "prNumber", 99, "targetBranch", "main"),
                Map.of("owner", "casehubio", "repo", "work", "prNumber", 7, "targetBranch", "main")
            ),
            "mergeResults", List.of(
                Map.of("repo", "casehubio/engine", "status", "success", "mergeSha", "sha1"),
                Map.of("repo", "casehubio/platform", "status", "failed", "reason", "merge conflict"),
                Map.of("repo", "casehubio/work", "status", "success", "mergeSha", "sha2")
            )
        );

        var result = hub.adaptCoordinatedRollback(input);
        assertThat(result.outcome()).isInstanceOf(io.casehub.worker.api.WorkerOutcome.Success.class);

        var rollbackResults = (List<Map<String, Object>>) result.output().get("rollbackResults");
        assertThat(rollbackResults).hasSize(2);
        assertThat(rollbackResults.get(0).get("status")).isEqualTo("conflict");
        assertThat(rollbackResults.get(0).get("revertPrNumber")).isEqualTo(101);
        assertThat(rollbackResults.get(0).get("reason")).isEqualTo("branch protection prevents merge");
        assertThat(rollbackResults.get(1).get("status")).isEqualTo("success");

        verify(revertClient).revert(eq("casehubio"), eq("engine"), eq("main"), eq("sha1"), anyString());
        verify(revertClient).revert(eq("casehubio"), eq("work"), eq("main"), eq("sha2"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allRevertsFail() {
        when(revertClient.revert(eq("casehubio"), eq("engine"), eq("main"), eq("sha1"), anyString()))
            .thenReturn(new RevertOutcome.Failure("api error: HTTP 500"));
        when(revertClient.revert(eq("casehubio"), eq("work"), eq("main"), eq("sha2"), anyString()))
            .thenReturn(new RevertOutcome.Failure("api error: HTTP 503"));

        var input = Map.<String, Object>of(
            "repos", List.of(
                Map.of("owner", "casehubio", "repo", "engine", "prNumber", 42, "targetBranch", "main"),
                Map.of("owner", "casehubio", "repo", "platform", "prNumber", 99, "targetBranch", "main"),
                Map.of("owner", "casehubio", "repo", "work", "prNumber", 7, "targetBranch", "main")
            ),
            "mergeResults", List.of(
                Map.of("repo", "casehubio/engine", "status", "success", "mergeSha", "sha1"),
                Map.of("repo", "casehubio/platform", "status", "failed", "reason", "merge conflict"),
                Map.of("repo", "casehubio/work", "status", "success", "mergeSha", "sha2")
            )
        );

        var result = hub.adaptCoordinatedRollback(input);
        assertThat(result.outcome()).isInstanceOf(io.casehub.worker.api.WorkerOutcome.Success.class);

        var rollbackResults = (List<Map<String, Object>>) result.output().get("rollbackResults");
        assertThat(rollbackResults).hasSize(2);
        assertThat(rollbackResults.get(0).get("status")).isEqualTo("failed");
        assertThat(rollbackResults.get(0).get("reason")).isEqualTo("api error: HTTP 500");
        assertThat(rollbackResults.get(1).get("status")).isEqualTo("failed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nothingToRevert() {
        var input = Map.<String, Object>of(
            "repos", List.of(
                Map.of("owner", "casehubio", "repo", "engine", "prNumber", 42, "targetBranch", "main")
            ),
            "mergeResults", List.of(
                Map.of("repo", "casehubio/engine", "status", "failed", "reason", "merge conflict")
            )
        );

        var result = hub.adaptCoordinatedRollback(input);
        assertThat(result.outcome()).isInstanceOf(io.casehub.worker.api.WorkerOutcome.Success.class);

        var rollbackResults = (List<Map<String, Object>>) result.output().get("rollbackResults");
        assertThat(rollbackResults).isEmpty();

        verifyNoInteractions(revertClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void singleRepoRevert() {
        when(revertClient.revert(eq("casehubio"), eq("engine"), eq("main"), eq("sha1"), anyString()))
            .thenReturn(new RevertOutcome.Success(101, "revertSha1"));

        var input = Map.<String, Object>of(
            "repos", List.of(
                Map.of("owner", "casehubio", "repo", "engine", "prNumber", 42, "targetBranch", "main"),
                Map.of("owner", "casehubio", "repo", "platform", "prNumber", 99, "targetBranch", "main")
            ),
            "mergeResults", List.of(
                Map.of("repo", "casehubio/engine", "status", "success", "mergeSha", "sha1"),
                Map.of("repo", "casehubio/platform", "status", "failed", "reason", "merge conflict")
            )
        );

        var result = hub.adaptCoordinatedRollback(input);
        assertThat(result.outcome()).isInstanceOf(io.casehub.worker.api.WorkerOutcome.Success.class);

        var rollbackResults = (List<Map<String, Object>>) result.output().get("rollbackResults");
        assertThat(rollbackResults).hasSize(1);
        assertThat(rollbackResults.get(0).get("repo")).isEqualTo("casehubio/engine");
        assertThat(rollbackResults.get(0).get("status")).isEqualTo("success");
    }

    @Test
    void commitMessageContainsFailedRepo() {
        when(revertClient.revert(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(new RevertOutcome.Success(101, "revertSha1"));

        var input = Map.<String, Object>of(
            "repos", List.of(
                Map.of("owner", "casehubio", "repo", "engine", "prNumber", 42, "targetBranch", "main"),
                Map.of("owner", "casehubio", "repo", "platform", "prNumber", 99, "targetBranch", "main")
            ),
            "mergeResults", List.of(
                Map.of("repo", "casehubio/engine", "status", "success", "mergeSha", "sha1"),
                Map.of("repo", "casehubio/platform", "status", "failed", "reason", "merge conflict")
            )
        );

        hub.adaptCoordinatedRollback(input);

        verify(revertClient).revert(
            eq("casehubio"), eq("engine"), eq("main"), eq("sha1"),
            argThat(msg -> msg.contains("casehubio/platform") && msg.contains("casehubio/engine#42"))
        );
    }
}
