package io.casehub.devtown.app.spi;

import io.casehub.devtown.domain.RevertClient;
import io.casehub.devtown.domain.RevertOutcome;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class NoOpRevertClient implements RevertClient {

    @Override
    public RevertOutcome revert(String owner, String repo,
                                String targetBranch, String mergeSha,
                                String commitMessage) {
        return new RevertOutcome.Failure("no revert client configured");
    }
}
