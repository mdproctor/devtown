package io.casehub.devtown.app.governance;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MergeQueueStateEventTest {

    @Test
    void enqueue_createsCorrectEvent() {
        var event = MergeQueueStateEvent.enqueue("casehubio/devtown", 42);
        assertThat(event.action()).isEqualTo("enqueue");
        assertThat(event.repository()).isEqualTo("casehubio/devtown");
        assertThat(event.prNumber()).isEqualTo(42);
        assertThat(event.batchId()).isNull();
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void dequeue_createsCorrectEvent() {
        var event = MergeQueueStateEvent.dequeue("casehubio/devtown", 42);
        assertThat(event.action()).isEqualTo("dequeue");
        assertThat(event.repository()).isEqualTo("casehubio/devtown");
        assertThat(event.prNumber()).isEqualTo(42);
        assertThat(event.batchId()).isNull();
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void batchFormed_createsCorrectEvent() {
        var event = MergeQueueStateEvent.batchFormed("casehubio/devtown", "batch-abc");
        assertThat(event.action()).isEqualTo("batch_formed");
        assertThat(event.repository()).isEqualTo("casehubio/devtown");
        assertThat(event.batchId()).isEqualTo("batch-abc");
        assertThat(event.timestamp()).isNotNull();
    }

    @Test
    void batchCompleted_createsCorrectEvent() {
        var event = MergeQueueStateEvent.batchCompleted("casehubio/devtown", "batch-xyz");
        assertThat(event.action()).isEqualTo("batch_completed");
        assertThat(event.repository()).isEqualTo("casehubio/devtown");
        assertThat(event.batchId()).isEqualTo("batch-xyz");
        assertThat(event.timestamp()).isNotNull();
    }
}
