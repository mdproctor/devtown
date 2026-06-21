package io.casehub.devtown.github;

import io.casehub.devtown.domain.MergeClient;
import io.casehub.devtown.domain.MergeOutcome;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import java.util.Map;

@ApplicationScoped
public class GitHubMergeClient implements MergeClient {

    private final GitHubMergeApi api;
    private final String mergeMethod;

    public GitHubMergeClient(@RestClient GitHubMergeApi api,
                              @ConfigProperty(name = "devtown.github.merge-method", defaultValue = "squash") String mergeMethod) {
        this.api = api;
        this.mergeMethod = mergeMethod;
    }

    @Override
    public MergeOutcome merge(String owner, String repo, int prNumber, String headSha) {
        try {
            var body = Map.<String, Object>of(
                "merge_method", mergeMethod,
                "sha", headSha
            );
            var response = api.merge(owner, repo, prNumber, body);
            String mergeSha = (String) response.get("sha");
            return new MergeOutcome.Success(mergeSha);
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            return switch (status) {
                case 409 -> new MergeOutcome.Failure("merge conflict or SHA mismatch");
                case 405 -> new MergeOutcome.Failure("branch protection prevents merge");
                case 422 -> new MergeOutcome.Failure("PR not mergeable");
                default -> new MergeOutcome.Failure("api error: HTTP " + status);
            };
        } catch (Exception e) {
            return new MergeOutcome.Failure("api error: " + e.getMessage());
        }
    }
}
