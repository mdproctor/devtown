package io.casehub.devtown.app;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.neocortex.memory.CaseMemoryStore;
import io.casehub.neocortex.memory.MemoryInput;
import io.casehub.neocortex.memory.MemoryQuery;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Minimal reproduction test: verifies that {@code InMemoryMemoryStore.assertTenant()}
 * works correctly when called from an {@code @ObservesAsync} handler.
 *
 * <p>{@link FixedCurrentPrincipal} is {@code @ApplicationScoped} — a CDI singleton
 * accessible from any thread, including the async observer executor pool.
 */
@QuarkusTest
class AsyncTenantAssertionTest {

    @Inject Event<StoreRequest> storeRequests;
    @Inject CaseMemoryStore store;
    @Inject FixedCurrentPrincipal principal;
    @Inject AsyncStoreObserver observer;

    @BeforeEach
    void setUp() {
        principal.reset();
        principal.setTenancyId(TenancyConstants.DEFAULT_TENANT_ID);
        observer.clearError();
    }

    @Test
    void assertTenant_succeeds_in_async_observer_thread() {
        var input = new MemoryInput(
            "test-entity:" + UUID.randomUUID(),
            DevtownMemoryDomain.SOFTWARE_REVIEW,
            TenancyConstants.DEFAULT_TENANT_ID,
            UUID.randomUUID().toString(),
            "async tenant test",
            Map.of()
        );

        storeRequests.fireAsync(new StoreRequest(input));

        await().atMost(5, SECONDS).untilAsserted(() -> {
            var results = store.query(
                MemoryQuery.forEntity(input.entityId(),
                    DevtownMemoryDomain.SOFTWARE_REVIEW,
                    TenancyConstants.DEFAULT_TENANT_ID)
                .withLimit(1));
            assertThat(results).as("fact stored via async observer").hasSize(1);
            assertThat(results.get(0).text()).isEqualTo("async tenant test");
        });

        assertThat(observer.getError())
            .as("no SecurityException from assertTenant in async thread")
            .isNull();
    }

    record StoreRequest(MemoryInput input) {}

    @ApplicationScoped
    static class AsyncStoreObserver {
        @Inject CaseMemoryStore store;
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        void onStore(@ObservesAsync StoreRequest request) {
            try {
                store.store(request.input());
            } catch (Throwable t) {
                error.set(t);
            }
        }

        Throwable getError() { return error.get(); }
        void clearError() { error.set(null); }
    }
}
