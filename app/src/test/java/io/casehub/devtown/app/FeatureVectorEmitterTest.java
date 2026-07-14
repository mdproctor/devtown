package io.casehub.devtown.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import io.casehub.devtown.domain.cbr.PrFeatureVector;
import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.devtown.domain.memory.DevtownMemoryKeys;
import io.casehub.memory.runtime.MemoryEmitter;
import io.casehub.neocortex.memory.MemoryInput;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeatureVectorEmitterTest {

    @Test
    void emitsFactWithCorrectEntityIdAndAttributes() {
        var memoryEmitter = mock(MemoryEmitter.class);

        var emitter = new FeatureVectorEmitter();
        emitter.memoryEmitter = memoryEmitter;

        var caseId = UUID.randomUUID();
        var vector = PrFeatureVector.from("casehubio/devtown", 42, "alice", 150,
            List.of("core/src/main/java/Foo.java", "core/src/test/java/FooTest.java"));

        emitter.emit(caseId, "tenant-1", vector);

        var captor = ArgumentCaptor.forClass(MemoryInput.class);
        verify(memoryEmitter).emit(captor.capture());
        var input = captor.getValue();

        assertThat(input.entityId()).startsWith("case-vector:casehubio/devtown:");
        assertThat(input.entityId()).contains(caseId.toString());
        assertThat(input.domain()).isEqualTo(DevtownMemoryDomain.SOFTWARE_REVIEW);
        assertThat(input.tenantId()).isEqualTo("tenant-1");
        assertThat(input.caseId()).isEqualTo(caseId.toString());
        assertThat(input.attributes().get(DevtownMemoryKeys.ENTITY_TYPE)).isEqualTo("case-vector");
        assertThat(input.attributes().get(DevtownMemoryKeys.PR_REPO)).isEqualTo("casehubio/devtown");
    }

    @Test
    void emitsHumanReadableText() {
        var memoryEmitter = mock(MemoryEmitter.class);

        var emitter = new FeatureVectorEmitter();
        emitter.memoryEmitter = memoryEmitter;

        var vector = PrFeatureVector.from("casehubio/devtown", 42, "alice", 150,
            List.of("core/src/main/java/Foo.java", "core/src/test/java/FooTest.java"));

        emitter.emit(UUID.randomUUID(), "tenant-1", vector);

        var captor = ArgumentCaptor.forClass(MemoryInput.class);
        verify(memoryEmitter).emit(captor.capture());
        assertThat(captor.getValue().text()).contains("PR #42", "casehubio/devtown", "150 lines");
    }

    @Test
    void attributesContainAllVectorFields() {
        var memoryEmitter = mock(MemoryEmitter.class);

        var emitter = new FeatureVectorEmitter();
        emitter.memoryEmitter = memoryEmitter;

        var vector = PrFeatureVector.from("repo", 1, "alice", 100,
            List.of("core/src/main/java/Foo.java"));

        emitter.emit(UUID.randomUUID(), "t", vector);

        var captor = ArgumentCaptor.forClass(MemoryInput.class);
        verify(memoryEmitter).emit(captor.capture());
        var attrs = captor.getValue().attributes();

        assertThat(attrs).containsKeys(
            "repo", "pr-number", "contributor", "lines-changed",
            "changed-paths", "modules", "languages",
            "has-tests", "touched-configs",
            DevtownMemoryKeys.ENTITY_TYPE, DevtownMemoryKeys.PR_REPO);
    }
}
