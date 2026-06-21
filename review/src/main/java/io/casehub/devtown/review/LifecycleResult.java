package io.casehub.devtown.review;

public enum LifecycleResult {
    UPDATED,
    NO_ACTIVE_CASE,
    ALREADY_COMPLETED,
    ALREADY_ABANDONED,
    STALE_EVENT
}
