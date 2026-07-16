package io.casehub.devtown.github;

import io.casehub.devtown.domain.RevertClient;
import io.casehub.devtown.domain.RevertOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class GitHubRevertClient implements RevertClient {

    private static final Logger LOG = Logger.getLogger(GitHubRevertClient.class);

    private final GitHubGitApi gitApi;
    private final GitHubPullRequestApi prApi;
    private final GitHubMergeApi mergeApi;

    public GitHubRevertClient(@RestClient GitHubGitApi gitApi,
                               @RestClient GitHubPullRequestApi prApi,
                               @RestClient GitHubMergeApi mergeApi) {
        this.gitApi = gitApi;
        this.prApi = prApi;
        this.mergeApi = mergeApi;
    }

    @Override
    public RevertOutcome revert(String owner, String repo, String targetBranch,
                                 String mergeSha, String commitMessage) {
        String shortSha = mergeSha.substring(0, 7);
        String tempBranch = "revert/" + shortSha;
        boolean tempBranchCreated = false;
        int prNumber = -1;

        try {
            GitCommit mergeCommit = gitApi.getCommit(owner, repo, mergeSha);
            if (mergeCommit.parents().size() < 2) {
                return new RevertOutcome.Failure(
                    "not a merge commit: expected ≥2 parents, got " + mergeCommit.parents().size());
            }

            String parentSha = mergeCommit.parents().getFirst().sha();
            GitCommit parentCommit = gitApi.getCommit(owner, repo, parentSha);

            GitCommit revertCommit = gitApi.createCommit(owner, repo, Map.of(
                "message", commitMessage,
                "tree", parentCommit.tree().sha(),
                "parents", List.of(mergeSha)
            ));

            try { gitApi.deleteRef(owner, repo, "heads/" + tempBranch); }
            catch (WebApplicationException ignored) {}

            gitApi.createRef(owner, repo, Map.of(
                "ref", "refs/heads/" + tempBranch,
                "sha", revertCommit.sha()
            ));
            tempBranchCreated = true;

            prNumber = findOrCreateRevertPr(owner, repo, targetBranch, tempBranch, commitMessage);

            var mergeResult = mergeApi.merge(owner, repo, prNumber,
                Map.of("merge_method", (Object) "merge"));
            String revertSha = (String) mergeResult.get("sha");

            cleanupTempBranch(owner, repo, tempBranch);
            return new RevertOutcome.Success(prNumber, revertSha);

        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            if (prNumber > 0 && (status == 409 || status == 405 || status == 422)) {
                return mergeConflictOutcome(prNumber, status);
            }
            if (tempBranchCreated) {
                cleanupTempBranch(owner, repo, tempBranch);
            }
            return new RevertOutcome.Failure("api error: HTTP " + status);
        } catch (Exception e) {
            if (tempBranchCreated) {
                cleanupTempBranch(owner, repo, tempBranch);
            }
            return new RevertOutcome.Failure("api error: " + e.getMessage());
        }
    }

    private int findOrCreateRevertPr(String owner, String repo,
                                      String targetBranch, String tempBranch,
                                      String commitMessage) {
        var existing = prApi.listPullRequests(owner, repo,
            owner + ":" + tempBranch, targetBranch, "open");
        if (existing != null && !existing.isEmpty()) {
            return ((Number) existing.getFirst().get("number")).intValue();
        }
        var pr = prApi.createPullRequest(owner, repo, Map.of(
            "title", commitMessage,
            "head", tempBranch,
            "base", targetBranch
        ));
        return ((Number) pr.get("number")).intValue();
    }

    private RevertOutcome.MergeConflict mergeConflictOutcome(int prNumber, int status) {
        String reason = switch (status) {
            case 409 -> "merge conflict or SHA mismatch";
            case 405 -> "branch protection prevents merge";
            case 422 -> "PR not mergeable";
            default -> "merge failed: HTTP " + status;
        };
        return new RevertOutcome.MergeConflict(prNumber, reason);
    }

    private void cleanupTempBranch(String owner, String repo, String tempBranch) {
        try {
            gitApi.deleteRef(owner, repo, "heads/" + tempBranch);
        } catch (Exception e) {
            LOG.warnf("Failed to clean up temp branch %s: %s", tempBranch, e.getMessage());
        }
    }
}
