package io.casehub.devtown.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GitHubCheckSuiteEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COMPLETED_EVENT = """
            {
              "action": "completed",
              "check_suite": {
                "id": 12345,
                "head_sha": "abc123",
                "status": "completed",
                "conclusion": "success",
                "pull_requests": [
                  { "number": 42, "head": { "sha": "abc123" } }
                ]
              },
              "repository": { "full_name": "casehubio/devtown" },
              "sender": { "login": "github-actions[bot]" }
            }
            """;

    @Test
    void parsesAction() throws Exception {
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckSuiteEvent.class);
        assertThat(event.action()).isEqualTo("completed");
    }

    @Test
    void parsesSuiteId() throws Exception {
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckSuiteEvent.class);
        assertThat(event.check_suite().id()).isEqualTo(12345);
    }

    @Test
    void parsesConclusion() throws Exception {
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckSuiteEvent.class);
        assertThat(event.check_suite().conclusion()).isEqualTo("success");
    }

    @Test
    void parsesHeadSha() throws Exception {
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckSuiteEvent.class);
        assertThat(event.check_suite().head_sha()).isEqualTo("abc123");
    }

    @Test
    void parsesPullRequests() throws Exception {
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckSuiteEvent.class);
        assertThat(event.check_suite().pull_requests()).hasSize(1);
        assertThat(event.check_suite().pull_requests().get(0).number()).isEqualTo(42);
    }

    @Test
    void parsesRepository() throws Exception {
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckSuiteEvent.class);
        assertThat(event.repository().full_name()).isEqualTo("casehubio/devtown");
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckSuiteEvent.class);
        assertThat(event).isNotNull();
    }

    @Test
    void parsesEmptyPullRequests() throws Exception {
        var noPrs = """
            {
              "action": "completed",
              "check_suite": {
                "id": 12345,
                "head_sha": "abc123",
                "status": "completed",
                "conclusion": "success",
                "pull_requests": []
              },
              "repository": { "full_name": "casehubio/devtown" }
            }
            """;
        var event = MAPPER.readValue(noPrs, GitHubCheckSuiteEvent.class);
        assertThat(event.check_suite().pull_requests()).isEmpty();
    }
}
