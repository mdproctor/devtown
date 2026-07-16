package io.casehub.devtown.domain;

public sealed interface RevertOutcome {
    record Success(int revertPrNumber, String revertSha) implements RevertOutcome {}
    record MergeConflict(int revertPrNumber, String reason) implements RevertOutcome {}
    record Failure(String reason) implements RevertOutcome {}
}
