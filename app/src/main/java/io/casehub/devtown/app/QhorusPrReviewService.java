package io.casehub.devtown.app;

import io.casehub.devtown.domain.ReviewFinding;
import io.casehub.devtown.review.LifecycleResult;
import io.casehub.devtown.review.PrDiff;
import io.casehub.devtown.review.PrDiffCache;
import io.casehub.devtown.review.PrPayload;
import io.casehub.devtown.review.PrReviewApplicationService;
import io.casehub.devtown.review.PrReviewOutcome;
import io.casehub.devtown.review.ReviewContext;
import io.casehub.devtown.review.ReviewerAgent;
import io.casehub.devtown.review.ReviewerAgentRegistry;
import io.casehub.devtown.review.ReviewerOutcome;
import io.casehub.devtown.review.SupersedeResult;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.qhorus.api.channel.Channel;
import io.casehub.qhorus.api.channel.ChannelCreateRequest;
import io.casehub.qhorus.api.channel.ChannelSemantic;
import io.casehub.qhorus.api.channel.EnforcementMode;
import io.casehub.qhorus.api.message.MessageDispatch;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.casehub.qhorus.api.message.MessageType.COMMAND;
import static io.casehub.qhorus.api.message.MessageType.DECLINE;
import static io.casehub.qhorus.api.message.MessageType.DONE;
import static io.casehub.qhorus.api.message.MessageType.EVENT;
import static io.casehub.qhorus.api.message.MessageType.FAILURE;
import static io.casehub.qhorus.api.message.MessageType.STATUS;

/**
 * Layer 3: replaces direct specialist invocation (Layer 1) with typed speech-act messaging.
 * Each specialist review request becomes a COMMAND that creates a Commitment; DONE fulfils it;
 * DECLINE is a formal scope boundary with a recorded reason.
 * <p>
 * CDI priority: @Priority(1) — present in full build for tutorial reading; inactive at runtime
 * (PrReviewCaseService @Priority(2) wins). PrReviewService @DefaultBean is the Layer 1 fallback.
 */
@ApplicationScoped
@Alternative
@Priority(1)
public class QhorusPrReviewService implements PrReviewApplicationService {

    static final         Set<MessageType> WORK_ALLOWED_TYPES      =
            Set.copyOf(EnumSet.of(COMMAND, STATUS, DONE, DECLINE, FAILURE));
    static final         Set<MessageType> OBSERVE_ALLOWED_TYPES   =
            Set.copyOf(EnumSet.of(EVENT));
    static final         Set<MessageType> OVERSIGHT_ALLOWED_TYPES =
            Set.copyOf(EnumSet.of(COMMAND, DONE, DECLINE));
    private static final String           ORCHESTRATOR            = "pr-orchestrator";
    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    @Inject
    ReviewerAgentRegistry registry;

    @Inject
    PrDiffCache diffCache;

    private static Channel requireContract(final Channel ch, final Set<MessageType> expectedTypes,
                                           final String expectedWriter) {
        Set<MessageType> actual = ch.allowedTypes() != null ? ch.allowedTypes() : Set.of();
        if (!actual.equals(expectedTypes)) {
            throw new IllegalStateException(
                    "Channel '" + ch.name() + "' has allowedTypes=" + actual
                    + " but expected " + expectedTypes + ".");
        }
        if (!ch.allowedWriters().contains(expectedWriter)) {
            throw new IllegalStateException(
                    "Channel '" + ch.name() + "' has allowedWriters=" + ch.allowedWriters()
                    + " but expected to contain '" + expectedWriter + "'.");
        }
        return ch;
    }

    @Override
    public PrReviewOutcome startReview(PrPayload pr) {
        final String  prefix = "pr-review-" + pr.prNumber();
        final Channel work   = findOrCreateWorkChannel(prefix);
        findOrCreateObserveChannel(prefix);
        findOrCreateOversightChannel(prefix);

        PrDiff diff = null;
        try {
            diff = diffCache.get(pr.repo(), pr.prNumber(), pr.headSha());
        } catch (Exception e) {
            // diff fetch failure — agents will work with null diff
        }
        final ReviewContext context = new ReviewContext(pr, diff);

        final List<ReviewFinding> allFindings = new ArrayList<>();

        for (ReviewerAgent agent : registry.all()) {
            final String correlationId = UUID.randomUUID().toString();

            var commandResult = messageService.dispatch(MessageDispatch.builder()
                                                                       .channelId(work.id())
                                                                       .sender(ORCHESTRATOR)
                                                                       .type(MessageType.COMMAND)
                                                                       .content(pr.repo() + "#" + pr.prNumber())
                                                                       .correlationId(correlationId)
                                                                       .target(agent.capability())
                                                                       .actorType(ActorType.SYSTEM)
                                                                       .build());

            ReviewerOutcome outcome;
            try {
                outcome = agent.handle(context);
            } catch (Exception e) {
                outcome = new ReviewerOutcome.Failed("agent error: " + e.getMessage());
            }

            switch (outcome) {
                case ReviewerOutcome.Completed completed -> {
                    String findingsText = completed.findings().stream()
                                                   .map(f -> f.severity() + ": " + f.message())
                                                   .collect(Collectors.joining("; "));
                    messageService.dispatch(MessageDispatch.builder()
                                                           .channelId(work.id())
                                                           .sender(ORCHESTRATOR)
                                                           .type(MessageType.DONE)
                                                           .content(findingsText)
                                                           .correlationId(correlationId)
                                                           .inReplyTo(commandResult.messageId())
                                                           .actorType(ActorType.SYSTEM)
                                                           .build());
                    allFindings.addAll(completed.findings());
                }
                case ReviewerOutcome.Declined declined -> messageService.dispatch(MessageDispatch.builder()
                                                                                                 .channelId(work.id())
                                                                                                 .sender(ORCHESTRATOR)
                                                                                                 .type(MessageType.DECLINE)
                                                                                                 .content(declined.reason())
                                                                                                 .correlationId(correlationId)
                                                                                                 .inReplyTo(commandResult.messageId())
                                                                                                 .actorType(ActorType.SYSTEM)
                                                                                                 .build());
                case ReviewerOutcome.Failed failed -> messageService.dispatch(MessageDispatch.builder()
                                                                                             .channelId(work.id())
                                                                                             .sender(ORCHESTRATOR)
                                                                                             .type(MessageType.FAILURE)
                                                                                             .content(failed.reason())
                                                                                             .correlationId(correlationId)
                                                                                             .inReplyTo(commandResult.messageId())
                                                                                             .actorType(ActorType.SYSTEM)
                                                                                             .build());
            }
        }

        return new PrReviewOutcome("qhorus-reviewed", allFindings, null);
    }

    @Override
    public LifecycleResult revisePr(String repo, int prNumber, String newHeadSha, int linesChanged) {
        return LifecycleResult.NO_ACTIVE_CASE;
    }

    @Override
    public LifecycleResult closePr(io.casehub.devtown.review.PrClosePayload close) {
        return LifecycleResult.NO_ACTIVE_CASE;
    }

    @Override
    public LifecycleResult signalCiStatus(String repo, int prNumber, String headSha, long suiteId, String conclusion) {
        return LifecycleResult.NO_ACTIVE_CASE;
    }

    @Override
    public LifecycleResult signalCheckRun(String repo, int prNumber, String headSha, String checkName, String conclusion, Instant completedAt) {
        return LifecycleResult.NO_ACTIVE_CASE;
    }

    @Override
    public SupersedeResult supersedePr(String repo, int oldPrNumber, PrPayload replacement) {
        return SupersedeResult.noActiveCase();
    }

    private Channel findOrCreateWorkChannel(final String prefix) {
        final String name = prefix + "/work";
        return channelService.findByName(name)
                             .map(ch -> requireContract(ch, WORK_ALLOWED_TYPES, ORCHESTRATOR))
                             .orElseGet(() -> channelService.create(ChannelCreateRequest.builder(name)
                                                                                        .semantic(ChannelSemantic.APPEND)
                                                                                        .barrierContributors(List.of(ORCHESTRATOR))
                                                                                        .allowedWriters(List.of(ORCHESTRATOR))
                                                                                        .allowedTypes(WORK_ALLOWED_TYPES)
                                                                                        .enforcementMode(EnforcementMode.ADVISORY)
                                                                                        .build()));
    }

    private Channel findOrCreateObserveChannel(final String prefix) {
        final String name = prefix + "/observe";
        return channelService.findByName(name)
                             .map(ch -> requireContract(ch, OBSERVE_ALLOWED_TYPES, ORCHESTRATOR))
                             .orElseGet(() -> channelService.create(ChannelCreateRequest.builder(name)
                                                                                        .semantic(ChannelSemantic.APPEND)
                                                                                        .barrierContributors(List.of(ORCHESTRATOR))
                                                                                        .allowedWriters(List.of(ORCHESTRATOR))
                                                                                        .allowedTypes(OBSERVE_ALLOWED_TYPES)
                                                                                        .enforcementMode(EnforcementMode.ADVISORY)
                                                                                        .build()));
    }

    private Channel findOrCreateOversightChannel(final String prefix) {
        final String name = prefix + "/oversight";
        return channelService.findByName(name)
                             .map(ch -> requireContract(ch, OVERSIGHT_ALLOWED_TYPES, ORCHESTRATOR))
                             .orElseGet(() -> channelService.create(ChannelCreateRequest.builder(name)
                                                                                        .semantic(ChannelSemantic.APPEND)
                                                                                        .barrierContributors(List.of(ORCHESTRATOR))
                                                                                        .allowedWriters(List.of(ORCHESTRATOR))
                                                                                        .allowedTypes(OVERSIGHT_ALLOWED_TYPES)
                                                                                        .enforcementMode(EnforcementMode.ADVISORY)
                                                                                        .build()));
    }
}
