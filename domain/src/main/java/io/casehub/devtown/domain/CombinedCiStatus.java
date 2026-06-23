package io.casehub.devtown.domain;

public sealed interface CombinedCiStatus {
    record Passing() implements CombinedCiStatus {}
    record Failing(String summary) implements CombinedCiStatus {}
    record Pending(int completed, int total) implements CombinedCiStatus {}
    record Unavailable(String reason) implements CombinedCiStatus {}
}
