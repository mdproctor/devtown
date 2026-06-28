package io.casehub.devtown.app.spi;

import io.casehub.devtown.domain.MergeClient;
import io.casehub.devtown.domain.MergeOutcome;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class NoOpMergeClient implements MergeClient {

    @Override
    public MergeOutcome merge(String owner, String repo, int prNumber, String headSha) {
        return new MergeOutcome.Failure("no merge client configured");
    }
}
