package io.casehub.devtown.github;

import io.casehub.devtown.domain.CiStatusClient;
import io.casehub.devtown.domain.CombinedCiStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import java.util.Set;

@ApplicationScoped
public class GitHubCiStatusClient implements CiStatusClient {

    private static final Logger LOG = Logger.getLogger(GitHubCiStatusClient.class);
    private static final Set<String> NON_BLOCKING = Set.of("success", "neutral", "skipped");

    private final GitHubChecksApi api;

    public GitHubCiStatusClient(@RestClient GitHubChecksApi api) {
        this.api = api;
    }

    @Override
    public CombinedCiStatus getCombinedStatus(String owner, String repo, String headSha) {
        CheckSuitesResponse response;
        try {
            response = api.listCheckSuites(owner, repo, headSha, 100);
        } catch (WebApplicationException e) {
            int status = e.getResponse().getStatus();
            LOG.warnf("GitHub Checks API returned HTTP %d for %s/%s@%s", status, owner, repo, headSha);
            return new CombinedCiStatus.Unavailable("api error: HTTP " + status);
        } catch (Exception e) {
            LOG.warnf(e, "GitHub Checks API call failed for %s/%s@%s", owner, repo, headSha);
            return new CombinedCiStatus.Unavailable("api error: " + e.getMessage());
        }

        if (response.totalCount() == 0) {
            return new CombinedCiStatus.Pending(0, 0);
        }

        if (response.totalCount() > response.checkSuites().size()) {
            int completedInPage = (int) response.checkSuites().stream()
                .filter(s -> "completed".equals(s.status())).count();
            LOG.warnf("Incomplete check-suites page: %d of %d returned for %s/%s@%s",
                response.checkSuites().size(), response.totalCount(), owner, repo, headSha);
            return new CombinedCiStatus.Pending(completedInPage, response.totalCount());
        }

        int completedCount = 0;
        int blockingCount = 0;
        for (var suite : response.checkSuites()) {
            if (!"completed".equals(suite.status())) {
                return new CombinedCiStatus.Pending(completedCount, response.totalCount());
            }
            completedCount++;
            if (!NON_BLOCKING.contains(suite.conclusion())) {
                blockingCount++;
            }
        }

        if (blockingCount > 0) {
            return new CombinedCiStatus.Failing(
                blockingCount + " of " + response.totalCount() + " suites failed");
        }
        return new CombinedCiStatus.Passing();
    }
}
