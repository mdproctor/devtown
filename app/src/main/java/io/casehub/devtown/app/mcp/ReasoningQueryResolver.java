package io.casehub.devtown.app.mcp;

import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.Memory;
import io.casehub.neocortex.memory.MemoryOrder;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.casehub.platform.api.mcp.McpDomain;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.DefaultValue;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@McpDomain(value = "devtown", app = "devtown")
@GraphQLApi
@ApplicationScoped
public class ReasoningQueryResolver {

    @Inject
    Instance<CaseMemoryStore> memoryStore;

    @Inject
    CurrentPrincipal principal;

    @Query
    @Description("Get worker reasoning traces for a specific case")
    public List<ReasoningTrace> caseReasoning(
            @Name("caseId") @Description("Case UUID") UUID caseId,
            @Name("limit") @Description("Max results (default 20)") @DefaultValue("20") int limit) {
        if (!memoryStore.isResolvable()) return List.of();
        var memories = memoryStore.get().query(
                MemoryQuery.forEntity("case:" + caseId,
                                DevtownMemoryDomain.WORKER_REASONING, principal.tenancyId())
                        .withLimit(limit)
                        .withOrder(MemoryOrder.CHRONOLOGICAL));
        return memories.stream().map(ReasoningTrace::from).toList();
    }

    @Query
    @Description("Get reasoning traces for a contributor's PRs across cases — "
            + "looks up the contributor's review cases, then queries reasoning per case")
    public List<ReasoningTrace> contributorReasoning(
            @Name("contributor") @Description("GitHub username") String contributor,
            @Name("limit") @Description("Max results (default 20)") @DefaultValue("20") int limit) {
        if (!memoryStore.isResolvable()) return List.of();
        String tenantId = principal.tenancyId();
        var contributorFacts = memoryStore.get().query(
                MemoryQuery.forEntity(
                                DevtownMemoryDomain.CONTRIBUTOR_PREFIX + contributor,
                                DevtownMemoryDomain.SOFTWARE_REVIEW, tenantId)
                        .withLimit(limit)
                        .withOrder(MemoryOrder.CHRONOLOGICAL));

        List<String> caseIds = contributorFacts.stream()
                .map(Memory::caseId).filter(Objects::nonNull).distinct()
                .limit(MemoryQuery.MAX_ENTITY_IDS).toList();

        if (caseIds.isEmpty()) return List.of();

        List<String> entityIds = caseIds.stream()
                .map(id -> "case:" + id).toList();
        var reasoning = memoryStore.get().query(
                MemoryQuery.forEntities(entityIds,
                                DevtownMemoryDomain.WORKER_REASONING, tenantId)
                        .withLimit(limit)
                        .withOrder(MemoryOrder.CHRONOLOGICAL));
        return reasoning.stream().map(ReasoningTrace::from).toList();
    }

    @Query
    @Description("Get reasoning traces for a specific module across cases — "
            + "finds reviews that touched the module, then retrieves reasoning per case")
    public List<ReasoningTrace> moduleReasoning(
            @Name("repo") @Description("Repository name (e.g. casehubio/devtown)") String repo,
            @Name("module") @Description("Module path (e.g. app, domain, review)") String module,
            @Name("limit") @Description("Max results (default 20)") @DefaultValue("20") int limit) {
        if (!memoryStore.isResolvable()) return List.of();
        String tenantId = principal.tenancyId();

        String entityId = DevtownMemoryDomain.MODULE_PREFIX + repo + "/" + module;
        var moduleFacts = memoryStore.get().query(
                MemoryQuery.forEntity(entityId,
                                DevtownMemoryDomain.SOFTWARE_REVIEW, tenantId)
                        .withLimit(limit)
                        .withOrder(MemoryOrder.CHRONOLOGICAL));

        List<String> caseIds = moduleFacts.stream()
                .map(Memory::caseId).filter(Objects::nonNull).distinct()
                .limit(MemoryQuery.MAX_ENTITY_IDS).toList();

        if (caseIds.isEmpty()) return List.of();

        List<String> entityIds = caseIds.stream()
                .map(id -> "case:" + id).toList();
        var reasoning = memoryStore.get().query(
                MemoryQuery.forEntities(entityIds,
                                DevtownMemoryDomain.WORKER_REASONING, tenantId)
                        .withLimit(limit)
                        .withOrder(MemoryOrder.CHRONOLOGICAL));
        return reasoning.stream()
                .map(r -> ReasoningTrace.from(r).withModuleContext(module, repo))
                .toList();
    }
}
