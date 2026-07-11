package io.casehub.devtown.app.spi;

import io.casehub.platform.api.identity.CurrentPrincipal;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Set;

@ApplicationScoped
public class DevCurrentPrincipal implements CurrentPrincipal {

    @Override
    public String actorId() {
        return "dev-operator";
    }

    @Override
    public Set<String> groups() {
        return Set.of("admin");
    }

    @Override
    public String tenancyId() {
        return "dev-tenant";
    }

    @Override
    public boolean isCrossTenantAdmin() {
        return false;
    }
}
