package io.casehub.devtown.app.mcp;

import io.casehub.platform.api.mcp.McpDomain;
import io.casehub.platform.api.mcp.ModelEnricher;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;

@McpDomain(value = "devtown/model", app = "devtown", summary = "DevTown domain model enrichment and state")
@ApplicationScoped
public class DevtownModelEnricher implements ModelEnricher {

    @Override
    public String summary() {
        return "Software engineering coordination — PR review, merge queue, "
                + "trust routing, reasoning traces";
    }

    @Override
    public Map<String, Object> state() {
        return Map.of("reasoningDomain", io.casehub.devtown.domain.memory.DevtownMemoryDomain.WORKER_REASONING.name());
    }
}
