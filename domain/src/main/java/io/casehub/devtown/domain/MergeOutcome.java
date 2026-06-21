package io.casehub.devtown.domain;

public sealed interface MergeOutcome {
    record Success(String mergeSha) implements MergeOutcome {}
    record Failure(String reason) implements MergeOutcome {}
}
