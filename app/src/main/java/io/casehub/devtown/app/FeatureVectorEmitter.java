package io.casehub.devtown.app;

import io.casehub.devtown.domain.cbr.PrFeatureVector;
import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.DevtownMemoryKeys;
import io.casehub.memory.runtime.MemoryEmitter;
import io.casehub.neocortex.memory.MemoryInput;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.HashMap;
import java.util.UUID;

@ApplicationScoped
public class FeatureVectorEmitter {

    @Inject
    MemoryEmitter memoryEmitter;

    public void emit(UUID caseId, String tenantId, PrFeatureVector vector) {
        var entityId = DevtownMemoryDomain.CASE_VECTOR_PREFIX + vector.repo() + ":" + caseId;

        var attributes = new HashMap<>(vector.toAttributes());
        attributes.put(DevtownMemoryKeys.ENTITY_TYPE, "case-vector");
        attributes.put(DevtownMemoryKeys.PR_REPO, vector.repo());

        var text = String.format("PR #%d in %s: %d lines, %d modules, %s",
                                 vector.prNumber(), vector.repo(), vector.linesChanged(),
                                 vector.modules().size(),
                                 vector.languages().isEmpty() ? "no languages detected" : String.join(", ", vector.languages()));

        memoryEmitter.emit(new MemoryInput(
                entityId,
                DevtownMemoryDomain.SOFTWARE_REVIEW,
                tenantId,
                caseId.toString(),
                text,
                attributes
        ));}
}
