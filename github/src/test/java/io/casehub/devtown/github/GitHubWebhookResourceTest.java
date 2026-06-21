package io.casehub.devtown.github;

import io.casehub.devtown.review.LifecycleResult;
import io.casehub.devtown.review.PrPayload;
import io.casehub.devtown.review.PrReviewApplicationService;
import io.casehub.devtown.review.PrReviewOutcome;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWebhookResourceTest {

    private static final String SECRET = "test-secret";
    private RecordingService service;
    private GitHubWebhookResource resource;

    static class RecordingService implements PrReviewApplicationService {
        PrPayload lastStartReview;
        String lastReviseRepo;
        int lastRevisePr;
        String lastCloseRepo;
        boolean lastCloseMerged;
        LifecycleResult revisePrResult = LifecycleResult.UPDATED;
        LifecycleResult closePrResult = LifecycleResult.UPDATED;
        String lastCiRepo;
        int lastCiPr;
        String lastCiSha;
        long lastCiSuiteId;
        String lastCiConclusion;
        String lastCheckRunName;
        String lastCheckRunConclusion;
        java.time.Instant lastCheckRunCompletedAt;
        LifecycleResult signalCiResult = LifecycleResult.UPDATED;
        LifecycleResult signalCheckRunResult = LifecycleResult.UPDATED;

        @Override
        public PrReviewOutcome startReview(PrPayload pr) {
            lastStartReview = pr;
            return new PrReviewOutcome("case-opened", List.of());
        }

        @Override
        public LifecycleResult revisePr(String repo, int prNumber, String newHeadSha, int linesChanged) {
            lastReviseRepo = repo;
            lastRevisePr = prNumber;
            return revisePrResult;
        }

        @Override
        public LifecycleResult closePr(String repo, int prNumber, boolean merged) {
            lastCloseRepo = repo;
            lastCloseMerged = merged;
            return closePrResult;
        }

        @Override
        public LifecycleResult signalCiStatus(String repo, int prNumber, String headSha, long suiteId, String conclusion) {
            lastCiRepo = repo;
            lastCiPr = prNumber;
            lastCiSha = headSha;
            lastCiSuiteId = suiteId;
            lastCiConclusion = conclusion;
            return signalCiResult;
        }

        @Override
        public LifecycleResult signalCheckRun(String repo, int prNumber, String headSha, String checkName, String conclusion, java.time.Instant completedAt) {
            lastCheckRunName = checkName;
            lastCheckRunConclusion = conclusion;
            lastCheckRunCompletedAt = completedAt;
            return signalCheckRunResult;
        }
    }

    @BeforeEach
    void setUp() {
        service = new RecordingService();
        resource = new GitHubWebhookResource();
        resource.service = service;
        resource.webhookSecret = SECRET;
    }

    private String sign(String body) {
        try {
            var mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseBody(Response response) {
        return (Map<String, Object>) response.getEntity();
    }

    @Test
    void invalidSignature_returns401() {
        var response = resource.receive("{}", "pull_request", "sha256=bad", "delivery-1");
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void unknownEventType_returns200Ignored() {
        String body = "{}";
        var response = resource.receive(body, "push", sign(body), "delivery-1");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(responseBody(response)).containsEntry("status", "ignored");
    }

    @Test
    void openedNonDraft_callsStartReview() {
        String body = prEvent("opened", false, false);
        resource.receive(body, "pull_request", sign(body), "delivery-1");
        assertThat(service.lastStartReview).isNotNull();
        assertThat(service.lastStartReview.prNumber()).isEqualTo(42);
    }

    @Test
    void openedDraft_doesNotCallStartReview() {
        String body = prEvent("opened", true, false);
        var response = resource.receive(body, "pull_request", sign(body), "delivery-1");
        assertThat(service.lastStartReview).isNull();
        assertThat(responseBody(response)).containsEntry("reason", "draft");
    }

    @Test
    void synchronize_callsRevisePr() {
        String body = prEvent("synchronize", false, false);
        resource.receive(body, "pull_request", sign(body), "delivery-1");
        assertThat(service.lastReviseRepo).isEqualTo("casehubio/devtown");
        assertThat(service.lastRevisePr).isEqualTo(42);
    }

    @Test
    void closedMerged_callsClosePrWithTrue() {
        String body = prEvent("closed", false, true);
        resource.receive(body, "pull_request", sign(body), "delivery-1");
        assertThat(service.lastCloseRepo).isEqualTo("casehubio/devtown");
        assertThat(service.lastCloseMerged).isTrue();
    }

    @Test
    void closedNotMerged_callsClosePrWithFalse() {
        String body = prEvent("closed", false, false);
        resource.receive(body, "pull_request", sign(body), "delivery-1");
        assertThat(service.lastCloseMerged).isFalse();
    }

    @Test
    void readyForReview_callsStartReview() {
        String body = prEvent("ready_for_review", false, false);
        resource.receive(body, "pull_request", sign(body), "delivery-1");
        assertThat(service.lastStartReview).isNotNull();
    }

    @Test
    void reopened_callsStartReview() {
        String body = prEvent("reopened", false, false);
        resource.receive(body, "pull_request", sign(body), "delivery-1");
        assertThat(service.lastStartReview).isNotNull();
    }

    @Test
    void nullEventType_returns200Ignored() {
        String body = "{}";
        var response = resource.receive(body, null, sign(body), "delivery-1");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(responseBody(response)).containsEntry("reason", "missing-event-type");
    }

    @Test
    void malformedJson_returns500() {
        String body = "not json at all";
        var response = resource.receive(body, "pull_request", sign(body), "delivery-1");
        assertThat(response.getStatus()).isEqualTo(500);
    }

    @Test
    void unknownAction_returns200Ignored() {
        String body = prEvent("labeled", false, false);
        var response = resource.receive(body, "pull_request", sign(body), "delivery-1");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(responseBody(response)).containsEntry("status", "ignored");
    }

    @Test
    void checkSuite_completed_callsSignalCiStatus() {
        String body = checkSuiteEvent("completed", "success", 12345);
        resource.receive(body, "check_suite", sign(body), "delivery-1");
        assertThat(service.lastCiRepo).isEqualTo("casehubio/devtown");
        assertThat(service.lastCiPr).isEqualTo(42);
        assertThat(service.lastCiSha).isEqualTo("abc123");
        assertThat(service.lastCiSuiteId).isEqualTo(12345);
        assertThat(service.lastCiConclusion).isEqualTo("success");
    }

    @Test
    void checkSuite_requested_returnsIgnored() {
        String body = checkSuiteEvent("requested", null, 12345);
        var response = resource.receive(body, "check_suite", sign(body), "delivery-1");
        assertThat(responseBody(response)).containsEntry("status", "ignored");
        assertThat(service.lastCiRepo).isNull();
    }

    @Test
    void checkSuite_emptyPullRequests_returnsIgnoredNoPullRequests() {
        String body = checkSuiteEventNoPrs("completed", "success", 12345);
        var response = resource.receive(body, "check_suite", sign(body), "delivery-1");
        assertThat(responseBody(response)).containsEntry("status", "ignored");
        assertThat(responseBody(response)).containsEntry("reason", "no-pull-requests");
    }

    @Test
    void checkSuite_staleEvent_returnsIgnoredStaleSha() {
        service.signalCiResult = LifecycleResult.STALE_EVENT;
        String body = checkSuiteEvent("completed", "success", 12345);
        var response = resource.receive(body, "check_suite", sign(body), "delivery-1");
        assertThat(responseBody(response)).containsEntry("status", "ignored");
        assertThat(responseBody(response)).containsEntry("reason", "stale-sha");
    }

    @Test
    void checkRun_completed_callsSignalCheckRun() {
        String body = checkRunEvent("completed", "lint", "success");
        resource.receive(body, "check_run", sign(body), "delivery-1");
        assertThat(service.lastCheckRunName).isEqualTo("lint");
        assertThat(service.lastCheckRunConclusion).isEqualTo("success");
    }

    @Test
    void checkRun_created_returnsIgnored() {
        String body = checkRunEvent("created", "lint", null);
        var response = resource.receive(body, "check_run", sign(body), "delivery-1");
        assertThat(responseBody(response)).containsEntry("status", "ignored");
        assertThat(service.lastCheckRunName).isNull();
    }

    private String prEvent(String action, boolean draft, boolean merged) {
        return "{\"action\":\"%s\",\"number\":42,\"pull_request\":{\"head\":{\"sha\":\"abc\"},\"base\":{\"ref\":\"main\"},\"user\":{\"login\":\"octocat\"},\"draft\":%s,\"merged\":%s,\"additions\":10,\"deletions\":5,\"changed_files\":1},\"repository\":{\"full_name\":\"casehubio/devtown\"}}".formatted(action, draft, merged);
    }

    private String checkSuiteEvent(String action, String conclusion, long suiteId) {
        String conclusionField = conclusion != null ? "\"conclusion\":\"%s\",".formatted(conclusion) : "";
        return "{\"action\":\"%s\",\"check_suite\":{\"id\":%d,\"head_sha\":\"abc123\",\"status\":\"completed\",%s\"pull_requests\":[{\"number\":42,\"head\":{\"sha\":\"abc123\"}}]},\"repository\":{\"full_name\":\"casehubio/devtown\"}}".formatted(action, suiteId, conclusionField);
    }

    private String checkSuiteEventNoPrs(String action, String conclusion, long suiteId) {
        return "{\"action\":\"%s\",\"check_suite\":{\"id\":%d,\"head_sha\":\"abc123\",\"status\":\"completed\",\"conclusion\":\"%s\",\"pull_requests\":[]},\"repository\":{\"full_name\":\"casehubio/devtown\"}}".formatted(action, suiteId, conclusion);
    }

    private String checkRunEvent(String action, String name, String conclusion) {
        String conclusionField = conclusion != null ? "\"conclusion\":\"%s\",".formatted(conclusion) : "";
        return "{\"action\":\"%s\",\"check_run\":{\"name\":\"%s\",\"status\":\"completed\",%s\"completed_at\":\"2026-06-21T12:00:00Z\",\"head_sha\":\"abc123\",\"pull_requests\":[{\"number\":42,\"head\":{\"sha\":\"abc123\"}}]},\"repository\":{\"full_name\":\"casehubio/devtown\"}}".formatted(action, name, conclusionField);
    }
}
