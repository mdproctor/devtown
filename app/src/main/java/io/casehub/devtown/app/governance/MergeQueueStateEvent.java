package io.casehub.devtown.app.governance;

import java.time.Instant;

public record MergeQueueStateEvent(
    String action,  // "enqueue", "dequeue", "batch_formed", "batch_completed"
    String repository,
    int prNumber,
    String batchId,   // null for enqueue/dequeue
    Instant timestamp
) {
    public static MergeQueueStateEvent enqueue(String repository, int prNumber) {
        return new MergeQueueStateEvent("enqueue", repository, prNumber, null, Instant.now());
    }

    public static MergeQueueStateEvent dequeue(String repository, int prNumber) {
        return new MergeQueueStateEvent("dequeue", repository, prNumber, null, Instant.now());
    }

    public static MergeQueueStateEvent batchFormed(String repository, String batchId) {
        return new MergeQueueStateEvent("batch_formed", repository, 0, batchId, Instant.now());
    }

    public static MergeQueueStateEvent batchCompleted(String repository, String batchId) {
        return new MergeQueueStateEvent("batch_completed", repository, 0, batchId, Instant.now());
    }
}
