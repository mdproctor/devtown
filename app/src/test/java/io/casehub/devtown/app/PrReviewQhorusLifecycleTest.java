package io.casehub.devtown.app;

import io.casehub.devtown.domain.ReviewDomain;
import io.casehub.devtown.review.PrPayload;
import io.casehub.qhorus.api.message.MessageType;
import io.casehub.qhorus.runtime.channel.ChannelService;
import io.casehub.qhorus.runtime.message.Message;
import io.casehub.qhorus.runtime.message.MessageService;
import io.casehub.qhorus.runtime.store.CommitmentStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

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
        var pr = new PrPayload("casehubio/devtown", 201, "sha201", "main", 100);

        service.review(pr);

        assertThat(channelService.findByName("pr-review-201/work")).isPresent();
        assertThat(channelService.findByName("pr-review-201/observe")).isPresent();
        assertThat(channelService.findByName("pr-review-201/oversight")).isPresent();
    }

    @Test
    void commandsDispatched() {
        var pr = new PrPayload("casehubio/devtown", 202, "sha202", "main", 100);

        service.review(pr);

        var work = channelService.findByName("pr-review-202/work").orElseThrow();
        List<Message> messages = messageService.pollAfter(work.id, 0L, 100);
        List<Message> commands = messages.stream()
                .filter(m -> m.messageType == MessageType.COMMAND)
                .toList();

        assertThat(commands).hasSize(3);
        assertThat(commands).extracting(m -> m.target)
                .containsExactlyInAnyOrder(
                        ReviewDomain.SECURITY_REVIEW,
                        ReviewDomain.ARCHITECTURE_REVIEW,
                        ReviewDomain.TEST_COVERAGE);
    }

    @Test
    void doneDischargesCommitment() {
        var pr = new PrPayload("casehubio/devtown", 203, "sha203", "main", 100);

        service.review(pr);

        var work = channelService.findByName("pr-review-203/work").orElseThrow();

        // DONE from security and test-coverage agents discharges their commitments (FULFILLED)
        assertThat(commitmentStore.findOpenByObligor(ReviewDomain.SECURITY_REVIEW, work.id))
                .as("security-review commitment should be discharged after DONE")
                .isEmpty();
        assertThat(commitmentStore.findOpenByObligor(ReviewDomain.TEST_COVERAGE, work.id))
                .as("test-coverage commitment should be discharged after DONE")
                .isEmpty();
    }

    @Test
    void declineRecorded() {
        var pr = new PrPayload("casehubio/devtown", 204, "sha204", "main", 100);

        service.review(pr);

        var work = channelService.findByName("pr-review-204/work").orElseThrow();
        List<Message> declines = messageService.pollAfter(work.id, 0L, 100).stream()
                .filter(m -> m.messageType == MessageType.DECLINE)
                .toList();

        assertThat(declines).as("exactly one DECLINE from ArchitectureReviewAgent").hasSize(1);

        // DECLINE also closes the commitment (state = DECLINED, not OPEN)
        assertThat(commitmentStore.findOpenByObligor(ReviewDomain.ARCHITECTURE_REVIEW, work.id))
                .as("architecture-review commitment should be closed after DECLINE")
                .isEmpty();
    }

    @Test
    void declineContentRecorded() {
        var pr = new PrPayload("casehubio/devtown", 205, "sha205", "main", 100);

        service.review(pr);

        var work = channelService.findByName("pr-review-205/work").orElseThrow();
        Message decline = messageService.pollAfter(work.id, 0L, 100).stream()
                .filter(m -> m.messageType == MessageType.DECLINE)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No DECLINE message found on /work"));

        assertThat(decline.content)
                .isEqualTo("distributed transaction outside scope");
    }
}
