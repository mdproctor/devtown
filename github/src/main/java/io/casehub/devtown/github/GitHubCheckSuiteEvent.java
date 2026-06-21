package io.casehub.devtown.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCheckSuiteEvent(
    String action,
    CheckSuite check_suite,
    Repository repository
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckSuite(
        long id, String conclusion, String head_sha, String status,
        List<PullRequest> pull_requests
    ) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(int number, Head head) {
        public record Head(String sha) {}
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(String full_name) {}
}
