package io.casehub.devtown.app;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.engine.common.spi.event.PlanItemCompletedEvent;
import io.casehub.devtown.domain.memory.ReviewOutcome;
import io.casehub.devtown.review.ReviewCompletedEvent;
import io.casehub.engine.common.spi.CrossTenantCaseInstanceRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ReviewOutcomeObserverTest {

    @Inject PrReviewCaseHub caseHub;
    @Inject CrossTenantCaseInstanceRepository caseInstanceRepository;
    @Inject Event<PlanItemCompletedEvent> planItemCompletedEvents;
    @Inject ReviewCompletedEventCapture capture;

    @BeforeEach
    void clearCapture() {
        capture.clear();
    }

    // Pre-seeded context: all parallel check keys non-null to suppress bindings (PP-20260521-134c38).
    // linesChanged=100 < humanApprovalThreshold=500 so human-approval binding does not fire.
    private static Map<String, Object> minimalContext() {
        return Map.of(
                "pr", Map.of("id", "42", "repo", "casehubio/devtown", "linesChanged", 100,
                             "baseRef", "main", "headSha", "abc123",
                             "contributor", "testuser",
                             "changedPaths", List.of("app/src/main/java/Foo.java")),
                "policy", Map.of("humanApprovalThreshold", 500,
                                 "securityReviewRequired", false,
                                 "requireSeniorApproval", false),
                "codeAnalysis", Map.of("complete", true, "securitySensitive", false,
                                       "architectureCrossing", false),
                "styleCheck", Map.of("outcome", "PENDING"),
                "testCoverage", Map.of("outcome", "PENDING"),
                "performanceAnalysis", Map.of("outcome", "PENDING"),
                "ci", Map.of("status", "passing"));
    }

    @Test
    void observerExtractsReviewOutcome_fromStyleCheck() throws Exception {
        // Start a case so we have a CaseInstance with context
        UUID caseId = caseHub.startCase(minimalContext())
                .toCompletableFuture().get(5, SECONDS);
        assertThat(caseId).isNotNull();

        // Signal the styleCheck outcome into the case context
        caseHub.signal(caseId, "styleCheck", Map.of("outcome", "APPROVED"));

        // Wait for context to be updated
        await().atMost(3, SECONDS).pollInterval(50, MILLISECONDS).untilAsserted(() -> {
            var instance = caseInstanceRepository.findByUuid(caseId)
                    ;
            assertThat(instance.getCaseContext().getPathAsString("styleCheck.outcome"))
                    .isEqualTo("APPROVED");
        });

        // Fire PlanItemCompletedEvent directly — simulating what the engine does
        // when a worker finishes. Context signals don't go through PlanItemCompletionHandler,
        // so we fire the CDI event manually.
        planItemCompletedEvents.fireAsync(
                new PlanItemCompletedEvent(caseId, "style-check", "style-bot", "test-tenant"));

        // Wait for the ReviewCompletedEvent to be captured
        await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() ->
                assertThat(capture.getEvents()).as("ReviewCompletedEvent should be fired").isNotEmpty());

        ReviewCompletedEvent event = capture.getEvents().get(0);
        assertThat(event.caseId()).isEqualTo(caseId);
        assertThat(event.capability()).isEqualTo("style-review"); // style-check -> style-review
        assertThat(event.reviewerId()).isEqualTo("style-bot");
        assertThat(event.outcome()).isEqualTo(ReviewOutcome.COMPLETED);
        assertThat(event.outcomeDetail()).isEqualTo("APPROVED");

        // PR metadata extraction
        assertThat(event.pr()).isNotNull();
        assertThat(event.pr().repo()).isEqualTo("casehubio/devtown");
        assertThat(event.pr().prNumber()).isEqualTo(42);
        assertThat(event.pr().headSha()).isEqualTo("abc123");
        assertThat(event.pr().baseRef()).isEqualTo("main");
        assertThat(event.pr().linesChanged()).isEqualTo(100);
        assertThat(event.pr().contributor()).isEqualTo("testuser");
        assertThat(event.pr().changedPaths()).containsExactly("app/src/main/java/Foo.java");
    }

    @Test
    void observerIgnoresInfrastructureBindings() throws Exception {
        UUID caseId = caseHub.startCase(minimalContext())
                .toCompletableFuture().get(5, SECONDS);

        // "initial-analysis" is an infrastructure binding — not in the planItem-to-context map
        planItemCompletedEvents.fireAsync(
                new PlanItemCompletedEvent(caseId, "initial-analysis", "analysis-bot", "test-tenant"));

        // "run-ci" is another infrastructure binding
        planItemCompletedEvents.fireAsync(
                new PlanItemCompletedEvent(caseId, "run-ci", "ci-runner", "test-tenant"));

        // "merge-direct" is an infrastructure binding (renamed from "merge" when enqueue-for-merge was added)
        planItemCompletedEvents.fireAsync(
                new PlanItemCompletedEvent(caseId, "merge-direct", "merge-bot", "test-tenant"));

        // Give enough time for any event to propagate
        Thread.sleep(500);

        assertThat(capture.getEvents())
                .as("No ReviewCompletedEvent for infrastructure bindings")
                .isEmpty();
    }

    @Test
    void observerUsesUnknown_whenTrackingKeyNull() throws Exception {
        UUID caseId = caseHub.startCase(minimalContext())
                .toCompletableFuture().get(5, SECONDS);

        caseHub.signal(caseId, "testCoverage", Map.of("outcome", "APPROVED"));
        await().atMost(3, SECONDS).pollInterval(50, MILLISECONDS).untilAsserted(() -> {
            var instance = caseInstanceRepository.findByUuid(caseId)
                    ;
            assertThat(instance.getCaseContext().getPathAsString("testCoverage.outcome"))
                    .isEqualTo("APPROVED");
        });

        planItemCompletedEvents.fireAsync(
                new PlanItemCompletedEvent(caseId, "test-coverage", null, "test-tenant"));

        await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() ->
                assertThat(capture.getEvents()).isNotEmpty());

        assertThat(capture.getEvents().get(0).reviewerId()).isEqualTo("unknown");
        assertThat(capture.getEvents().get(0).capability()).isEqualTo("test-coverage");
    }

    @Test
    void observerHandlesHumanApproval() throws Exception {
        UUID caseId = caseHub.startCase(minimalContext())
                .toCompletableFuture().get(5, SECONDS);

        // Manually set humanApproval.status in context
        caseHub.signal(caseId, "humanApproval", Map.of("status", "approved"));
        await().atMost(3, SECONDS).pollInterval(50, MILLISECONDS).untilAsserted(() -> {
            var instance = caseInstanceRepository.findByUuid(caseId)
                    ;
            assertThat(instance.getCaseContext().getPathAsString("humanApproval.status"))
                    .isEqualTo("approved");
        });

        planItemCompletedEvents.fireAsync(
                new PlanItemCompletedEvent(caseId, "human-approval", "reviewer-alice", "test-tenant"));

        await().atMost(5, SECONDS).pollInterval(100, MILLISECONDS).untilAsserted(() ->
                assertThat(capture.getEvents()).isNotEmpty());

        ReviewCompletedEvent event = capture.getEvents().get(0);
        assertThat(event.capability()).isEqualTo("human-decision:pr-approval");
        assertThat(event.outcomeDetail()).isEqualTo("approved");
        assertThat(event.reviewerId()).isEqualTo("reviewer-alice");
    }

    @Test
    void observerSkipsWhenCaseNotFound() throws Exception {
        // Non-existent caseId — observer should log WARN and not fire event
        UUID fakeCaseId = UUID.randomUUID();
        planItemCompletedEvents.fireAsync(
                new PlanItemCompletedEvent(fakeCaseId, "style-check", "bot", "test-tenant"));

        Thread.sleep(500);

        assertThat(capture.getEvents())
                .as("No event when case not found")
                .isEmpty();
    }
}
