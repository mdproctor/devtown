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
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.MessageService;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    @Inject
    ChannelService channelService;

    @Inject
    MessageService messageService;

    @Inject
    Instance<ReviewerAgent> agents;

    @Override
    public PrReviewOutcome review(PrPayload pr) {
        String prefix = "pr-review-" + pr.prNumber();
        Channel work = findOrCreate(prefix + "/work");
        findOrCreate(prefix + "/observe");
        findOrCreate(prefix + "/oversight");

        List<String> allFindings = new ArrayList<>();

        for (ReviewerAgent agent : agents) {
            String correlationId = UUID.randomUUID().toString();

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
                            .sender(agent.capability())
                            .type(MessageType.DONE)
                            .content(String.join("; ", completed.findings()))
                            .correlationId(correlationId)
                            .inReplyTo(commandResult.messageId())
                            .actorType(ActorType.AGENT)
                            .build());
                    allFindings.addAll(completed.findings());
                }
                case ReviewerOutcome.Declined declined ->
                    messageService.dispatch(MessageDispatch.builder()
                            .channelId(work.id)
                            .sender(agent.capability())
                            .type(MessageType.DECLINE)
                            .content(declined.reason())
                            .correlationId(correlationId)
                            .inReplyTo(commandResult.messageId())
                            .actorType(ActorType.AGENT)
                            .build());
            }
        }

        return new PrReviewOutcome("qhorus-reviewed", allFindings);
    }

    private Channel findOrCreate(String name) {
        return channelService.findByName(name)
                .orElseGet(() -> channelService.create(name, null, ChannelSemantic.APPEND, ORCHESTRATOR));
    }
}
