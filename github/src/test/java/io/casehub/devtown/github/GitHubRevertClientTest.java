package io.casehub.devtown.github;

import io.casehub.devtown.domain.RevertOutcome;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GitHubRevertClientTest {

    private GitHubGitApi gitApi;
    private GitHubPullRequestApi prApi;
    private GitHubMergeApi mergeApi;
    private GitHubRevertClient client;

    private static final String OWNER = "casehubio";
    private static final String REPO = "devtown";
    private static final String TARGET = "main";
    private static final String MERGE_SHA = "aabbccdd11223344";
    private static final String SHORT_SHA = MERGE_SHA.substring(0, 7);
    private static final String PARENT_SHA = "parent-sha-111";
    private static final String PARENT_TREE_SHA = "tree-sha-222";
    private static final String REVERT_COMMIT_SHA = "revert-sha-333";
    private static final String REVERT_MERGE_SHA = "revert-merge-sha-444";
    private static final String MESSAGE = "Revert merge aabbccd — coordinated rollback";
    private static final String TEMP_BRANCH = "revert/" + SHORT_SHA;

    @BeforeEach
    void setUp() {
        gitApi = mock(GitHubGitApi.class);
        prApi = mock(GitHubPullRequestApi.class);
        mergeApi = mock(GitHubMergeApi.class);
        client = new GitHubRevertClient(gitApi, prApi, mergeApi);
    }

    private void stubHappyPath() {
        var mergeCommit = new GitCommit(
            MERGE_SHA,
            new GitCommit.GitCommitTree("merge-tree"),
            List.of(new GitCommit.GitCommitParent(PARENT_SHA), new GitCommit.GitCommitParent("second-parent"))
        );
        var parentCommit = new GitCommit(
            PARENT_SHA,
            new GitCommit.GitCommitTree(PARENT_TREE_SHA),
            List.of()
        );
        var revertCommit = new GitCommit(
            REVERT_COMMIT_SHA,
            new GitCommit.GitCommitTree(PARENT_TREE_SHA),
            List.of(new GitCommit.GitCommitParent(MERGE_SHA))
        );

        when(gitApi.getCommit(OWNER, REPO, MERGE_SHA)).thenReturn(mergeCommit);
        when(gitApi.getCommit(OWNER, REPO, PARENT_SHA)).thenReturn(parentCommit);
        when(gitApi.createCommit(eq(OWNER), eq(REPO), any())).thenReturn(revertCommit);
        when(gitApi.createRef(eq(OWNER), eq(REPO), any())).thenReturn(new GitRef("refs/heads/" + TEMP_BRANCH, Map.of("sha", REVERT_COMMIT_SHA)));
        when(prApi.listPullRequests(eq(OWNER), eq(REPO), anyString(), eq(TARGET), eq("open"))).thenReturn(List.of());
        when(prApi.createPullRequest(eq(OWNER), eq(REPO), any())).thenReturn(Map.of("number", 99));
        when(mergeApi.merge(eq(OWNER), eq(REPO), eq(99), any())).thenReturn(Map.of("sha", REVERT_MERGE_SHA));
    }

    @Test
    void happyPath_returnsSuccessWithPrNumberAndSha() {
        stubHappyPath();

        var result = client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        assertThat(result).isInstanceOf(RevertOutcome.Success.class);
        var success = (RevertOutcome.Success) result;
        assertThat(success.revertPrNumber()).isEqualTo(99);
        assertThat(success.revertSha()).isEqualTo(REVERT_MERGE_SHA);
    }

    @Test
    void happyPath_verifiesApiCallSequence() {
        stubHappyPath();

        client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        var inOrder = inOrder(gitApi, prApi, mergeApi);
        inOrder.verify(gitApi).getCommit(OWNER, REPO, MERGE_SHA);
        inOrder.verify(gitApi).getCommit(OWNER, REPO, PARENT_SHA);
        inOrder.verify(gitApi).createCommit(eq(OWNER), eq(REPO), any());
        inOrder.verify(gitApi).deleteRef(OWNER, REPO, "heads/" + TEMP_BRANCH);
        inOrder.verify(gitApi).createRef(eq(OWNER), eq(REPO), any());
        inOrder.verify(prApi).listPullRequests(eq(OWNER), eq(REPO), anyString(), eq(TARGET), eq("open"));
        inOrder.verify(prApi).createPullRequest(eq(OWNER), eq(REPO), any());
        inOrder.verify(mergeApi).merge(eq(OWNER), eq(REPO), eq(99), any());
        inOrder.verify(gitApi).deleteRef(OWNER, REPO, "heads/" + TEMP_BRANCH);
    }

    @Test
    void happyPath_commitMessageUsedInCommitAndPrTitle() {
        stubHappyPath();

        client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        verify(gitApi).createCommit(eq(OWNER), eq(REPO), argThat(body ->
            MESSAGE.equals(body.get("message"))
        ));
        verify(prApi).createPullRequest(eq(OWNER), eq(REPO), argThat(body ->
            MESSAGE.equals(body.get("title"))
        ));
    }

    @Test
    void happyPath_revertCommitUsesParentTreeAndMergeAsParent() {
        stubHappyPath();

        client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        verify(gitApi).createCommit(eq(OWNER), eq(REPO), argThat(body ->
            PARENT_TREE_SHA.equals(body.get("tree")) &&
            body.get("parents") instanceof List<?> parents &&
            parents.size() == 1 &&
            MERGE_SHA.equals(parents.getFirst())
        ));
    }

    @Test
    void happyPath_mergeUsesMethodMerge() {
        stubHappyPath();

        client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        verify(mergeApi).merge(eq(OWNER), eq(REPO), eq(99), argThat(body ->
            "merge".equals(body.get("merge_method"))
        ));
    }

    @Test
    void mergeConflict409_returnsMergeConflictWithPrNumber() {
        stubHappyPath();
        when(mergeApi.merge(eq(OWNER), eq(REPO), eq(99), any()))
            .thenThrow(new WebApplicationException(Response.status(409).build()));

        var result = client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        assertThat(result).isInstanceOf(RevertOutcome.MergeConflict.class);
        var mc = (RevertOutcome.MergeConflict) result;
        assertThat(mc.revertPrNumber()).isEqualTo(99);
        assertThat(mc.reason()).contains("conflict");
    }

    @Test
    void mergeConflict409_tempBranchNotCleanedUp() {
        stubHappyPath();
        when(mergeApi.merge(eq(OWNER), eq(REPO), eq(99), any()))
            .thenThrow(new WebApplicationException(Response.status(409).build()));

        client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        verify(gitApi, times(1)).deleteRef(OWNER, REPO, "heads/" + TEMP_BRANCH);
    }

    @Test
    void branchProtection405_returnsMergeConflict() {
        stubHappyPath();
        when(mergeApi.merge(eq(OWNER), eq(REPO), eq(99), any()))
            .thenThrow(new WebApplicationException(Response.status(405).build()));

        var result = client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        assertThat(result).isInstanceOf(RevertOutcome.MergeConflict.class);
        var mc = (RevertOutcome.MergeConflict) result;
        assertThat(mc.revertPrNumber()).isEqualTo(99);
        assertThat(mc.reason()).contains("branch protection");
    }

    @Test
    void notMergeable422_returnsMergeConflict() {
        stubHappyPath();
        when(mergeApi.merge(eq(OWNER), eq(REPO), eq(99), any()))
            .thenThrow(new WebApplicationException(Response.status(422).build()));

        var result = client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        assertThat(result).isInstanceOf(RevertOutcome.MergeConflict.class);
        var mc = (RevertOutcome.MergeConflict) result;
        assertThat(mc.revertPrNumber()).isEqualTo(99);
        assertThat(mc.reason()).contains("not mergeable");
    }

    @Test
    void apiErrorOnGetCommit_returnsFailure() {
        when(gitApi.getCommit(OWNER, REPO, MERGE_SHA))
            .thenThrow(new WebApplicationException(Response.status(404).build()));

        var result = client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        assertThat(result).isInstanceOf(RevertOutcome.Failure.class);
        assertThat(((RevertOutcome.Failure) result).reason()).contains("HTTP 404");
    }

    @Test
    void apiErrorOnCreateCommit_returnsFailure() {
        stubHappyPath();
        when(gitApi.createCommit(eq(OWNER), eq(REPO), any()))
            .thenThrow(new WebApplicationException(Response.status(500).build()));

        var result = client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        assertThat(result).isInstanceOf(RevertOutcome.Failure.class);
        assertThat(((RevertOutcome.Failure) result).reason()).contains("HTTP 500");
    }

    @Test
    void apiErrorOnPrCreate_returnsFailureAndCleansTempBranch() {
        stubHappyPath();
        when(prApi.createPullRequest(eq(OWNER), eq(REPO), any()))
            .thenThrow(new WebApplicationException(Response.status(500).build()));

        var result = client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        assertThat(result).isInstanceOf(RevertOutcome.Failure.class);
        verify(gitApi, times(2)).deleteRef(OWNER, REPO, "heads/" + TEMP_BRANCH);
    }

    @Test
    void apiErrorOnMerge500_returnsFailureAndCleansTempBranch() {
        stubHappyPath();
        when(mergeApi.merge(eq(OWNER), eq(REPO), eq(99), any()))
            .thenThrow(new WebApplicationException(Response.status(500).build()));

        var result = client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        assertThat(result).isInstanceOf(RevertOutcome.Failure.class);
        verify(gitApi, times(2)).deleteRef(OWNER, REPO, "heads/" + TEMP_BRANCH);
    }

    @Test
    void cleanupFailureSwallowed_stillReturnsSuccess() {
        stubHappyPath();
        doNothing()
            .doThrow(new WebApplicationException(Response.status(500).build()))
            .when(gitApi).deleteRef(OWNER, REPO, "heads/" + TEMP_BRANCH);

        var result = client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        assertThat(result).isInstanceOf(RevertOutcome.Success.class);
    }

    @Test
    void idempotentTempBranch_deleteRef404Swallowed() {
        stubHappyPath();
        doThrow(new WebApplicationException(Response.status(422).build()))
            .doNothing()
            .when(gitApi).deleteRef(OWNER, REPO, "heads/" + TEMP_BRANCH);

        var result = client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        assertThat(result).isInstanceOf(RevertOutcome.Success.class);
    }

    @Test
    void nonMergeCommit_singleParent_returnsFailure() {
        var singleParentCommit = new GitCommit(
            MERGE_SHA,
            new GitCommit.GitCommitTree("tree"),
            List.of(new GitCommit.GitCommitParent("only-parent"))
        );
        when(gitApi.getCommit(OWNER, REPO, MERGE_SHA)).thenReturn(singleParentCommit);

        var result = client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        assertThat(result).isInstanceOf(RevertOutcome.Failure.class);
        assertThat(((RevertOutcome.Failure) result).reason())
            .contains("not a merge commit")
            .contains("expected")
            .contains("got 1");
    }

    @Test
    void prReuseOnRetry_existingOpenPrFound_noCreateCalled() {
        stubHappyPath();
        when(prApi.listPullRequests(eq(OWNER), eq(REPO), anyString(), eq(TARGET), eq("open")))
            .thenReturn(List.of(Map.of("number", 55)));
        when(mergeApi.merge(eq(OWNER), eq(REPO), eq(55), any()))
            .thenReturn(Map.of("sha", REVERT_MERGE_SHA));

        var result = client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        assertThat(result).isInstanceOf(RevertOutcome.Success.class);
        assertThat(((RevertOutcome.Success) result).revertPrNumber()).isEqualTo(55);
        verify(prApi, never()).createPullRequest(anyString(), anyString(), any());
    }

    @Test
    void runtimeException_returnsFailure() {
        when(gitApi.getCommit(OWNER, REPO, MERGE_SHA))
            .thenThrow(new RuntimeException("connection reset"));

        var result = client.revert(OWNER, REPO, TARGET, MERGE_SHA, MESSAGE);

        assertThat(result).isInstanceOf(RevertOutcome.Failure.class);
        assertThat(((RevertOutcome.Failure) result).reason()).contains("connection reset");
    }
}
