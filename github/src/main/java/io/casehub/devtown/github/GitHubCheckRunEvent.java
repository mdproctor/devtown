package io.casehub.devtown.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubCheckRunEvent(
    String action,
    CheckRun check_run,
    Repository repository
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckRun(
        String name, String status, String conclusion, String completed_at,
        String head_sha, List<PullRequest> pull_requests
    ) {}
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PullRequest(int number, Head head) {
        public record Head(String sha) {}
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(String full_name) {}
}
