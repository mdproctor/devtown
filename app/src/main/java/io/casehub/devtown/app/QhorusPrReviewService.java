package io.casehub.devtown.app;

import io.casehub.devtown.review.PrPayload;
import io.casehub.devtown.review.PrReviewApplicationService;
import io.casehub.devtown.review.PrReviewOutcome;
import io.casehub.devtown.review.ReviewerAgent;
import io.casehub.devtown.review.ReviewerOutcome;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.Channel;
import io.casehub.qhorus.runtime.channel.ChannelCreateRequest;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.casehub.qhorus.api.message.MessageType.*;

/**
 * Layer 3: replaces direct specialist invocation (Layer 1) with typed speech-act messaging.
 * Each specialist review request becomes a COMMAND that creates a Commitment; DONE fulfils it;
 * DECLINE is a formal scope boundary with a recorded reason.
 *
 * CDI priority: @Priority(1) — present in full build for tutorial reading; inactive at runtime
 * (PrReviewCaseService @Priority(2) wins). PrReviewService @DefaultBean is the Layer 1 fallback.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class QhorusPrReviewService implements PrReviewApplicationService {

    private static final String ORCHESTRATOR = "pr-orchestrator";

    static final String WORK_ALLOWED_TYPES =
            EnumSet.of(COMMAND, STATUS, DONE, DECLINE, FAILURE)
                   .stream().map(Enum::name).collect(Collectors.joining(","));

    static final String OBSERVE_ALLOWED_TYPES =
            EnumSet.of(EVENT)
                   .stream().map(Enum::name).collect(Collectors.joining(","));

    static final String OVERSIGHT_ALLOWED_TYPES =
            EnumSet.of(COMMAND, DONE, DECLINE)
                   .stream().map(Enum::name).collect(Collectors.joining(","));

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    @Inject
    Instance<ReviewerAgent> agents;

    @Override
    public PrReviewOutcome review(PrPayload pr) {
        final String prefix = "pr-review-" + pr.prNumber();
        final Channel work = findOrCreateWorkChannel(prefix);
        findOrCreateObserveChannel(prefix);
        findOrCreateOversightChannel(prefix);

        final List<String> allFindings = new ArrayList<>();

        for (ReviewerAgent agent : agents) {
            final String correlationId = UUID.randomUUID().toString();

            var commandResult = messageService.dispatch(MessageDispatch.builder()
                    .channelId(work.id)
                    .sender(ORCHESTRATOR)
                    .type(MessageType.COMMAND)
                    .content(pr.repo() + "#" + pr.prNumber())
                    .correlationId(correlationId)
                    .target(agent.capability())
                    .actorType(ActorType.SYSTEM)
                    .build());

            ReviewerOutcome outcome = agent.handle(pr);

            switch (outcome) {
                case ReviewerOutcome.Completed completed -> {
                    messageService.dispatch(MessageDispatch.builder()
                            .channelId(work.id)
                            .sender(ORCHESTRATOR)
                            .type(MessageType.DONE)
                            .content(String.join("; ", completed.findings()))
                            .correlationId(correlationId)
                            .inReplyTo(commandResult.messageId())
                            .actorType(ActorType.SYSTEM)
                            .build());
                    allFindings.addAll(completed.findings());
                }
                case ReviewerOutcome.Declined declined ->
                    messageService.dispatch(MessageDispatch.builder()
                            .channelId(work.id)
                            .sender(ORCHESTRATOR)
                            .type(MessageType.DECLINE)
                            .content(declined.reason())
                            .correlationId(correlationId)
                            .inReplyTo(commandResult.messageId())
                            .actorType(ActorType.SYSTEM)
                            .build());
            }
        }

        return new PrReviewOutcome("qhorus-reviewed", allFindings);
    }

    private Channel findOrCreateWorkChannel(final String prefix) {
        final String name = prefix + "/work";
        return channelService.findByName(name)
                .map(ch -> requireContract(ch, WORK_ALLOWED_TYPES, ORCHESTRATOR))
                .orElseGet(() -> channelService.create(new ChannelCreateRequest(name, null, ChannelSemantic.APPEND, ORCHESTRATOR, ORCHESTRATOR, null, null, null, parseTypes(WORK_ALLOWED_TYPES), null, null, null, null, null)));
    }

    private Channel findOrCreateObserveChannel(final String prefix) {
        final String name = prefix + "/observe";
        return channelService.findByName(name)
                .map(ch -> requireContract(ch, OBSERVE_ALLOWED_TYPES, ORCHESTRATOR))
                .orElseGet(() -> channelService.create(new ChannelCreateRequest(name, null, ChannelSemantic.APPEND, ORCHESTRATOR, ORCHESTRATOR, null, null, null, parseTypes(OBSERVE_ALLOWED_TYPES), null, null, null, null, null)));
    }

    private Channel findOrCreateOversightChannel(final String prefix) {
        final String name = prefix + "/oversight";
        return channelService.findByName(name)
                .map(ch -> requireContract(ch, OVERSIGHT_ALLOWED_TYPES, ORCHESTRATOR))
                .orElseGet(() -> channelService.create(new ChannelCreateRequest(name, null, ChannelSemantic.APPEND, ORCHESTRATOR, ORCHESTRATOR, null, null, null, parseTypes(OVERSIGHT_ALLOWED_TYPES), null, null, null, null, null)));
    }

    private static Set<MessageType> parseTypes(final String csv) {
        return Arrays.stream(csv.split(",")).map(MessageType::valueOf).collect(Collectors.toSet());
    }

    private static Channel requireContract(final Channel ch, final String expectedTypes,
            final String expectedWriters) {
        requireAllowedTypes(ch, expectedTypes);
        requireAllowedWriters(ch, expectedWriters);
        return ch;
    }

    private static void requireAllowedTypes(final Channel ch, final String expected) {
        Set<String> actual = ch.allowedTypes == null
                ? Set.of()
                : Set.of(ch.allowedTypes.split(","));
        Set<String> expectedSet = Set.of(expected.split(","));
        if (!actual.equals(expectedSet)) {
            throw new IllegalStateException(
                    "Channel '" + ch.name + "' has allowedTypes='" + ch.allowedTypes
                    + "' but expected '" + expected + "'. "
                    + "Delete and recreate, or wait for qhorus#246 (setAllowedTypes).");
        }
    }

    private static void requireAllowedWriters(final Channel ch, final String expected) {
        if (!expected.equals(ch.allowedWriters)) {
            throw new IllegalStateException(
                    "Channel '" + ch.name + "' has allowedWriters='" + ch.allowedWriters
                    + "' but expected '" + expected + "'.");
        }
    }
}
