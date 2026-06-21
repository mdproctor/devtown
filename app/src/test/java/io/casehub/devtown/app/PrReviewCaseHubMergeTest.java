package io.casehub.devtown.app;

import io.casehub.api.model.WorkerResult;
import io.casehub.api.model.WorkerOutcome;
import io.casehub.devtown.domain.MergeClient;
import io.casehub.devtown.domain.MergeOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class PrReviewCaseHubMergeTest {

    private MergeClient mergeClient;
    private PrReviewCaseHub hub;

    @BeforeEach
    void setUp() {
        mergeClient = mock(MergeClient.class);
        hub = new PrReviewCaseHub();
        hub.mergeClient = mergeClient;
    }

    private Map<String, Object> prInput(String repo, String id, String headSha) {
        return Map.of("pr", Map.of("repo", repo, "id", id, "headSha", headSha));
    }

    @Test
    void adaptMerge_success_returnsWorkerResultWithMergeSha() {
        when(mergeClient.merge("casehubio", "devtown", 42, "sha123"))
            .thenReturn(new MergeOutcome.Success("merge-sha-abc"));

        WorkerResult result = hub.adaptMerge(prInput("casehubio/devtown", "42", "sha123"));

        assertThat(result.output()).containsEntry("merge_sha", "merge-sha-abc");
        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Success.class);
    }

    @Test
    void adaptMerge_failure_returnsFailedWorkerResult() {
        when(mergeClient.merge("casehubio", "devtown", 42, "sha123"))
            .thenReturn(new MergeOutcome.Failure("merge conflict"));

        WorkerResult result = hub.adaptMerge(prInput("casehubio/devtown", "42", "sha123"));

        assertThat(result.outcome()).isInstanceOf(WorkerOutcome.Failed.class);
    }

    @Test
    void adaptMerge_parsesRepoIntoOwnerAndName() {
        when(mergeClient.merge(anyString(), anyString(), anyInt(), anyString()))
            .thenReturn(new MergeOutcome.Success("sha"));

        hub.adaptMerge(prInput("myorg/myrepo", "7", "headsha"));

        verify(mergeClient).merge("myorg", "myrepo", 7, "headsha");
    }
}
