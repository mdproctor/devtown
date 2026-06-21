package io.casehub.devtown.domain;

public interface MergeClient {
    MergeOutcome merge(String owner, String repo, int prNumber, String headSha);
}
