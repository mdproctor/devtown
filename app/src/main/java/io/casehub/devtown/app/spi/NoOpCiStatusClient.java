package io.casehub.devtown.app.spi;

import io.casehub.devtown.domain.CiStatusClient;
import io.casehub.devtown.domain.CombinedCiStatus;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;

@DefaultBean
@ApplicationScoped
public class NoOpCiStatusClient implements CiStatusClient {

    @Override
    public CombinedCiStatus getCombinedStatus(String owner, String repo, String headSha) {
        return new CombinedCiStatus.Unavailable("no CI status client configured");
    }
}
