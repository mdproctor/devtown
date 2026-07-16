package io.casehub.devtown.github;

import java.util.List;

public record GitCommit(String sha, GitCommitTree tree, List<GitCommitParent> parents) {
    public record GitCommitTree(String sha) {}
    public record GitCommitParent(String sha) {}
}
