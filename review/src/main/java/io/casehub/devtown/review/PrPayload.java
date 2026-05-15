package io.casehub.devtown.review;

public record PrPayload(String repo, int prNumber, String headSha, int linesChanged) {}
