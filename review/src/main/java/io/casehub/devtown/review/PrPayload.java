package io.casehub.devtown.review;

public record PrPayload(String repo, int prNumber, String headSha, String baseRef, int linesChanged) {}
