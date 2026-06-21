package io.casehub.devtown.review;

import java.time.Instant;

public interface PrReviewApplicationService {
    PrReviewOutcome startReview(PrPayload pr);
    LifecycleResult revisePr(String repo, int prNumber, String newHeadSha, int linesChanged);
    LifecycleResult closePr(String repo, int prNumber, boolean merged);
    LifecycleResult signalCiStatus(String repo, int prNumber, String headSha, long suiteId, String conclusion);
    LifecycleResult signalCheckRun(String repo, int prNumber, String headSha, String checkName, String conclusion, Instant completedAt);
}
