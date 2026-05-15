package io.casehub.devtown.app;

public record PrPayload(String repo, int prNumber, String headSha, int linesChanged) {}
