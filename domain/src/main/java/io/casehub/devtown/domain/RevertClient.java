package io.casehub.devtown.domain;

public interface RevertClient {
    RevertOutcome revert(String owner, String repo, String targetBranch, String mergeSha, String commitMessage);
}
