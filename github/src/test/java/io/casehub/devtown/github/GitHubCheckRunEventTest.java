package io.casehub.devtown.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class GitHubCheckRunEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String COMPLETED_EVENT = """
            {
              "action": "completed",
              "check_run": {
                "name": "lint",
                "status": "completed",
                "conclusion": "success",
                "completed_at": "2026-06-21T12:00:00Z",
                "head_sha": "abc123",
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
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckRunEvent.class);
        assertThat(event.action()).isEqualTo("completed");
    }

    @Test
    void parsesCheckRunName() throws Exception {
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckRunEvent.class);
        assertThat(event.check_run().name()).isEqualTo("lint");
    }

    @Test
    void parsesConclusion() throws Exception {
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckRunEvent.class);
        assertThat(event.check_run().conclusion()).isEqualTo("success");
    }

    @Test
    void parsesCompletedAt() throws Exception {
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckRunEvent.class);
        assertThat(event.check_run().completed_at()).isEqualTo("2026-06-21T12:00:00Z");
    }

    @Test
    void parsesHeadSha() throws Exception {
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckRunEvent.class);
        assertThat(event.check_run().head_sha()).isEqualTo("abc123");
    }

    @Test
    void parsesPullRequests() throws Exception {
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckRunEvent.class);
        assertThat(event.check_run().pull_requests()).hasSize(1);
        assertThat(event.check_run().pull_requests().get(0).number()).isEqualTo(42);
    }

    @Test
    void ignoresUnknownFields() throws Exception {
        var event = MAPPER.readValue(COMPLETED_EVENT, GitHubCheckRunEvent.class);
        assertThat(event).isNotNull();
    }
}
