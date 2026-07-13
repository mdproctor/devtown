package io.casehub.devtown.app;

import io.casehub.devtown.domain.ReviewDomain;
import io.casehub.devtown.review.PrPayload;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.api.message.MessageTypeViolationException;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.api.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.api.store.CommitmentStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

import static io.casehub.qhorus.api.message.MessageType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@QuarkusTest
class PrReviewQhorusLifecycleTest {

    // Injected by concrete type — CDI selects exactly QhorusPrReviewService regardless
    // of @Priority ordering. PrReviewCaseService (@Priority(2)) is selected for the
    // PrReviewApplicationService injection point but not for this direct-type injection.
    @Inject QhorusPrReviewService service;

    @Inject ChannelService  channelService;
    @Inject MessageService  messageService;
    @Inject CommitmentStore commitmentStore;

    // Each test uses a distinct PR number to prevent cross-test contamination:
    // InMemoryMessageStore accumulates across test methods in the same @QuarkusTest session.

    @Test
    void channelsCreated() {
        var pr = new PrPayload("casehubio/devtown", 201, "sha201", "main", 100, "test-contributor", List.of());

        service.startReview(pr);

        assertThat(channelService.findByName("pr-review-201/work")).isPresent();
        assertThat(channelService.findByName("pr-review-201/observe")).isPresent();
        assertThat(channelService.findByName("pr-review-201/oversight")).isPresent();
    }

    @Test
    void commandsDispatched() {
        var pr = new PrPayload("casehubio/devtown", 202, "sha202", "main", 100, "test-contributor", List.of());

        service.startReview(pr);

        var work = channelService.findByName("pr-review-202/work").orElseThrow();
        List<Message> messages = messageService.pollAfter(work.id(), 0L, 100);
        List<Message> commands = messages.stream()
                .filter(m -> m.messageType() == MessageType.COMMAND)
                .toList();

        assertThat(commands).hasSize(4);
        assertThat(commands).extracting(m -> m.target())
                .containsExactlyInAnyOrder(
                        ReviewDomain.SECURITY_REVIEW,
                        ReviewDomain.ARCHITECTURE_REVIEW,
                        ReviewDomain.TEST_COVERAGE,
                        ReviewDomain.PERFORMANCE_ANALYSIS);
    }

    @Test
    void doneDischargesCommitment() {
        var pr = new PrPayload("casehubio/devtown", 203, "sha203", "main", 100, "test-contributor", List.of());

        service.startReview(pr);

        var work = channelService.findByName("pr-review-203/work").orElseThrow();

        // DONE from security and test-coverage agents discharges their commitments (FULFILLED)
        assertThat(commitmentStore.findOpenByObligor(ReviewDomain.SECURITY_REVIEW, work.id()))
                .as("security-review commitment should be discharged after DONE")
                .isEmpty();
        assertThat(commitmentStore.findOpenByObligor(ReviewDomain.TEST_COVERAGE, work.id()))
                .as("test-coverage commitment should be discharged after DONE")
                .isEmpty();
    }

    @Test
    void declineRecorded() {
        var pr = new PrPayload("casehubio/devtown", 204, "sha204", "main", 100, "test-contributor", List.of());

        service.startReview(pr);

        var work = channelService.findByName("pr-review-204/work").orElseThrow();
        List<Message> declines = messageService.pollAfter(work.id(), 0L, 100).stream()
                .filter(m -> m.messageType() == MessageType.DECLINE)
                .toList();

        assertThat(declines).as("exactly one DECLINE from ArchitectureReviewAgent").hasSize(1);

        // DECLINE also closes the commitment (state = DECLINED, not OPEN)
        assertThat(commitmentStore.findOpenByObligor(ReviewDomain.ARCHITECTURE_REVIEW, work.id()))
                .as("architecture-review commitment should be closed after DECLINE")
                .isEmpty();
    }

    @Test
    void declineContentRecorded() {
        var pr = new PrPayload("casehubio/devtown", 205, "sha205", "main", 100, "test-contributor", List.of());

        service.startReview(pr);

        var work = channelService.findByName("pr-review-205/work").orElseThrow();
        Message decline = messageService.pollAfter(work.id(), 0L, 100).stream()
                .filter(m -> m.messageType() == MessageType.DECLINE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No DECLINE message found on /work"));

        assertThat(decline.content())
                .isEqualTo("distributed transaction outside scope");
    }

    @Test
    void failureRecorded() {
        var pr = new PrPayload("casehubio/devtown", 206, "sha206", "main", 100, "test-contributor", List.of());

        service.startReview(pr);

        var work = channelService.findByName("pr-review-206/work").orElseThrow();
        List<Message> failures = messageService.pollAfter(work.id(), 0L, 100).stream()
                .filter(m -> m.messageType() == MessageType.FAILURE)
                .toList();

        assertThat(failures).as("exactly one FAILURE from PerformanceAnalysisAgent").hasSize(1);
    }

    @Test
    void failureContentRecorded() {
        var pr = new PrPayload("casehubio/devtown", 207, "sha207", "main", 100, "test-contributor", List.of());

        service.startReview(pr);

        var work = channelService.findByName("pr-review-207/work").orElseThrow();
        Message failure = messageService.pollAfter(work.id(), 0L, 100).stream()
                .filter(m -> m.messageType() == MessageType.FAILURE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No FAILURE message found on /work"));

        assertThat(failure.content())
                .isEqualTo("analysis timed out on large diff");
    }

    @Test
    void failureClosesCommitment() {
        var pr = new PrPayload("casehubio/devtown", 208, "sha208", "main", 100, "test-contributor", List.of());

        service.startReview(pr);

        var work = channelService.findByName("pr-review-208/work").orElseThrow();
        assertThat(commitmentStore.findOpenByObligor(ReviewDomain.PERFORMANCE_ANALYSIS, work.id()))
                .as("performance-analysis commitment should be closed after FAILURE")
                .isEmpty();
    }

    // --- allowedTypes enforcement (devtown#54) ---
    // PR numbers 210–220 for type-enforcement tests; 299 for migration guard.

    @Test
    void workChannel_allowedTypes_containsExpectedSet() {
        service.startReview(new PrPayload("casehubio/devtown", 210, "sha210", "main", 100, "test-contributor", List.of()));
        var work = channelService.findByName("pr-review-210/work").orElseThrow();
        assertThat(work.allowedTypes())
                .containsExactlyInAnyOrder(COMMAND, STATUS, DONE, DECLINE, FAILURE);
    }

    @Test
    void observeChannel_allowedTypes_isEventOnly() {
        service.startReview(new PrPayload("casehubio/devtown", 211, "sha211", "main", 100, "test-contributor", List.of()));
        var observe = channelService.findByName("pr-review-211/observe").orElseThrow();
        assertThat(observe.allowedTypes())
                .containsExactly(EVENT);
    }

    @Test
    void oversightChannel_allowedTypes_containsExpectedSet() {
        service.startReview(new PrPayload("casehubio/devtown", 212, "sha212", "main", 100, "test-contributor", List.of()));
        var oversight = channelService.findByName("pr-review-212/oversight").orElseThrow();
        assertThat(oversight.allowedTypes())
                .containsExactlyInAnyOrder(COMMAND, DONE, DECLINE);
    }

    @Test
    void workChannel_acceptsStatusDispatch() {
        service.startReview(new PrPayload("casehubio/devtown", 213, "sha213", "main", 100, "test-contributor", List.of()));
        var work = channelService.findByName("pr-review-213/work").orElseThrow();
        assertThatNoException().isThrownBy(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(work.id())
                        .sender(ORCHESTRATOR)
                        .type(STATUS)
                        .content("analysing large diff")
                        .actorType(ActorType.SYSTEM)
                        .build()));
    }

    @Test
    void workChannel_acceptsFailureDispatch() {
        service.startReview(new PrPayload("casehubio/devtown", 214, "sha214", "main", 100, "test-contributor", List.of()));
        var work = channelService.findByName("pr-review-214/work").orElseThrow();
        // FAILURE requires inReplyTo (builder validation) — open a COMMAND first
        final String corrId = UUID.randomUUID().toString();
        final var commandResult = messageService.dispatch(MessageDispatch.builder()
                .channelId(work.id())
                .sender(ORCHESTRATOR)
                .type(COMMAND)
                .content("review this")
                .correlationId(corrId)
                .actorType(ActorType.SYSTEM)
                .build());
        assertThatNoException().isThrownBy(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(work.id())
                        .sender(ORCHESTRATOR)
                        .type(FAILURE)
                        .content("agent process crashed")
                        .correlationId(corrId)
                        .inReplyTo(commandResult.messageId())
                        .actorType(ActorType.SYSTEM)
                        .build()));
    }

    @Test
    void observeChannel_acceptsEventDispatch() {
        service.startReview(new PrPayload("casehubio/devtown", 215, "sha215", "main", 100, "test-contributor", List.of()));
        var observe = channelService.findByName("pr-review-215/observe").orElseThrow();
        assertThatNoException().isThrownBy(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(observe.id())
                        .sender("ledger-audit")
                        .type(EVENT)
                        .actorType(ActorType.SYSTEM)
                        .build()));
    }

    @Test
    void oversightChannel_acceptsCommandAndDoneDispatch() {
        service.startReview(new PrPayload("casehubio/devtown", 216, "sha216", "main", 100, "test-contributor", List.of()));
        var oversight = channelService.findByName("pr-review-216/oversight").orElseThrow();
        // DONE requires inReplyTo (builder validation) — open a COMMAND first
        final String corrId = UUID.randomUUID().toString();
        final var commandResult = messageService.dispatch(MessageDispatch.builder()
                .channelId(oversight.id())
                .sender(ORCHESTRATOR)
                .type(COMMAND)
                .content("approve-or-reject?")
                .correlationId(corrId)
                .actorType(ActorType.SYSTEM)
                .build());
        assertThatNoException().isThrownBy(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(oversight.id())
                        .sender(ORCHESTRATOR)
                        .type(DONE)
                        .content("approved")
                        .correlationId(corrId)
                        .inReplyTo(commandResult.messageId())
                        .actorType(ActorType.SYSTEM)
                        .build()));
    }

    @Test
    void observeChannel_advisoryOnSpeechActDispatch() {
        service.startReview(new PrPayload("casehubio/devtown", 221, "sha221", "main", 100, "test-contributor", List.of()));
        var observe = channelService.findByName("pr-review-221/observe").orElseThrow();
        var result = messageService.dispatch(MessageDispatch.builder()
                .channelId(observe.id())
                .sender("pr-orchestrator")
                .type(STATUS)
                .content("progress note")
                .actorType(ActorType.SYSTEM)
                .build());
        assertThat(result.advisories()).isNotEmpty();
    }

    @Test
    void workChannel_advisoryOnEventDispatch() {
        service.startReview(new PrPayload("casehubio/devtown", 217, "sha217", "main", 100, "test-contributor", List.of()));
        var work = channelService.findByName("pr-review-217/work").orElseThrow();
        var result = messageService.dispatch(MessageDispatch.builder()
                .channelId(work.id())
                .sender("telemetry")
                .type(EVENT)
                .actorType(ActorType.SYSTEM)
                .build());
        assertThat(result.advisories()).isNotEmpty();
    }

    @Test
    void observeChannel_rejectsCommandDispatch() {
        service.startReview(new PrPayload("casehubio/devtown", 218, "sha218", "main", 100, "test-contributor", List.of()));
        var observe = channelService.findByName("pr-review-218/observe").orElseThrow();
        assertThatThrownBy(() ->
                messageService.dispatch(MessageDispatch.builder()
                        .channelId(observe.id())
                        .sender(ORCHESTRATOR)
                        .type(COMMAND)
                        .content("do something")
                        .correlationId(UUID.randomUUID().toString())
                        .actorType(ActorType.SYSTEM)
                        .build()))
                .isInstanceOf(MessageTypeViolationException.class);
    }

    @Test
    void oversightChannel_advisoryOnFailureDispatch() {
        service.startReview(new PrPayload("casehubio/devtown", 219, "sha219", "main", 100, "test-contributor", List.of()));
        var oversight = channelService.findByName("pr-review-219/oversight").orElseThrow();
        final String corrId = UUID.randomUUID().toString();
        final var commandResult = messageService.dispatch(MessageDispatch.builder()
                .channelId(oversight.id())
                .sender(ORCHESTRATOR)
                .type(COMMAND)
                .content("oversight gate")
                .correlationId(corrId)
                .actorType(ActorType.SYSTEM)
                .build());
        var result = messageService.dispatch(MessageDispatch.builder()
                .channelId(oversight.id())
                .sender(ORCHESTRATOR)
                .type(FAILURE)
                .content("crashed")
                .correlationId(corrId)
                .inReplyTo(commandResult.messageId())
                .actorType(ActorType.SYSTEM)
                .build());
        assertThat(result.advisories()).isNotEmpty();
    }

    @Test
    void oversightChannel_advisoryOnEventDispatch() {
        service.startReview(new PrPayload("casehubio/devtown", 220, "sha220", "main", 100, "test-contributor", List.of()));
        var oversight = channelService.findByName("pr-review-220/oversight").orElseThrow();
        var result = messageService.dispatch(MessageDispatch.builder()
                .channelId(oversight.id())
                .sender("telemetry")
                .type(EVENT)
                .actorType(ActorType.SYSTEM)
                .build());
        assertThat(result.advisories()).isNotEmpty();
    }

    @Test
    void existingPermissiveWorkChannel_throwsOnAllowedTypesMismatch() {
        // Simulate a pre-fix channel created without allowedTypes (null).
        // requireAllowedTypes must fail fast rather than silently operate with weaker enforcement.
        channelService.create(io.casehub.qhorus.api.channel.ChannelCreateRequest.builder("pr-review-299/work")
                .semantic(ChannelSemantic.APPEND)
                .adminInstances(List.of(ORCHESTRATOR))
                .build());

        assertThatThrownBy(() ->
                service.startReview(new PrPayload("casehubio/devtown", 299, "sha299", "main", 100, "test-contributor", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowedTypes");
    }

    // --- allowedWriters enforcement (devtown#64) ---
    // PR numbers 230–240 for writer-enforcement tests; 249 for migration guard.

    @Test
    void workChannel_allowedWriters_isOrchestrator() {
        service.startReview(new PrPayload("casehubio/devtown", 230, "sha230", "main", 100, "test-contributor", List.of()));
        var work = channelService.findByName("pr-review-230/work").orElseThrow();
        assertThat(work.allowedWriters()).containsExactly(ORCHESTRATOR);
    }

    @Test
    void observeChannel_allowedWriters_isOrchestrator() {
        service.startReview(new PrPayload("casehubio/devtown", 231, "sha231", "main", 100, "test-contributor", List.of()));
        var observe = channelService.findByName("pr-review-231/observe").orElseThrow();
        assertThat(observe.allowedWriters()).containsExactly(ORCHESTRATOR);
    }

    @Test
    void oversightChannel_allowedWriters_isOrchestrator() {
        service.startReview(new PrPayload("casehubio/devtown", 232, "sha232", "main", 100, "test-contributor", List.of()));
        var oversight = channelService.findByName("pr-review-232/oversight").orElseThrow();
        assertThat(oversight.allowedWriters()).containsExactly(ORCHESTRATOR);
    }

    @Test
    void existingPermissiveWorkChannel_throwsOnAllowedWritersMismatch() {
        channelService.create(new io.casehub.qhorus.api.channel.ChannelCreateRequest("pr-review-249/work", null, ChannelSemantic.APPEND, null, null, null, null, null, QhorusPrReviewService.WORK_ALLOWED_TYPES, null, null, null, null, null, null));

        assertThatThrownBy(() ->
                service.startReview(new PrPayload("casehubio/devtown", 249, "sha249", "main", 100, "test-contributor", List.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowedWriters");
    }

    private static final String ORCHESTRATOR = "pr-orchestrator";

    private static Set<MessageType> parseAllowedTypes(final String allowedTypes) {
        return Arrays.stream(allowedTypes.split(","))
                .map(MessageType::valueOf)
                .collect(Collectors.toUnmodifiableSet());
    }
}
