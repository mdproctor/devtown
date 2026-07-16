package io.casehub.devtown.github;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GitCommitTest {

    @Test
    void recordAccessors() {
        var commit = new GitCommit(
            "abc123",
            new GitCommit.GitCommitTree("tree-sha"),
            List.of(new GitCommit.GitCommitParent("parent1"), new GitCommit.GitCommitParent("parent2"))
        );
        assertThat(commit.sha()).isEqualTo("abc123");
        assertThat(commit.tree().sha()).isEqualTo("tree-sha");
        assertThat(commit.parents()).hasSize(2);
        assertThat(commit.parents().getFirst().sha()).isEqualTo("parent1");
    }

    @Test
    void singleParentCommit() {
        var commit = new GitCommit(
            "def456",
            new GitCommit.GitCommitTree("tree2"),
            List.of(new GitCommit.GitCommitParent("only-parent"))
        );
        assertThat(commit.parents()).hasSize(1);
        assertThat(commit.parents().getFirst().sha()).isEqualTo("only-parent");
    }

    @Test
    void emptyParentsList() {
        var commit = new GitCommit(
            "root",
            new GitCommit.GitCommitTree("tree3"),
            List.of()
        );
        assertThat(commit.parents()).isEmpty();
    }
}
