package io.casehub.devtown.app;

import io.casehub.devtown.domain.memory.DevtownMemoryDomain;
import io.casehub.platform.api.identity.TenancyConstants;
import io.casehub.platform.api.memory.CaseMemoryStore;
import io.casehub.platform.api.memory.MemoryInput;
import io.casehub.platform.api.memory.MemoryQuery;
import io.casehub.platform.testing.FixedCurrentPrincipal;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class MemoryAdminResourceTest {

    @Inject CaseMemoryStore store;
    @Inject FixedCurrentPrincipal principal;

    @BeforeEach
    void setUp() {
        principal.reset();
        principal.setTenancyId(TenancyConstants.DEFAULT_TENANT_ID);
    }

    @Test
    void eraseContributor_removes_facts_and_returns_204() {
        String tenantId = principal.tenancyId();

        store.store(new MemoryInput(
            "contributor:alice",
            DevtownMemoryDomain.SOFTWARE_REVIEW,
            tenantId,
            UUID.randomUUID().toString(),
            "Style review by alice",
            Map.of()
        ));

        assertThat(store.query(
            MemoryQuery.forEntity("contributor:alice", DevtownMemoryDomain.SOFTWARE_REVIEW, tenantId)
                .withLimit(5)
        )).isNotEmpty();

        given()
            .contentType("application/json")
            .body("{\"login\": \"alice\"}")
        .when()
            .post("/api/admin/memory/erase/contributor")
        .then()
            .statusCode(204);

        assertThat(store.query(
            MemoryQuery.forEntity("contributor:alice", DevtownMemoryDomain.SOFTWARE_REVIEW, tenantId)
                .withLimit(5)
        )).isEmpty();
    }

    @Test
    void eraseContributor_nullLogin_returns_400() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/api/admin/memory/erase/contributor")
        .then()
            .statusCode(400);
    }

    @Test
    void eraseContributor_blankLogin_returns_400() {
        given()
            .contentType("application/json")
            .body("{\"login\": \"  \"}")
        .when()
            .post("/api/admin/memory/erase/contributor")
        .then()
            .statusCode(400);
    }

    @Test
    void eraseContributor_nonExistent_returns_204() {
        given()
            .contentType("application/json")
            .body("{\"login\": \"nonexistent\"}")
        .when()
            .post("/api/admin/memory/erase/contributor")
        .then()
            .statusCode(204);
    }

    @Test
    void eraseContributor_logs_before_and_after_erasure() {
        String tenantId = principal.tenancyId();

        store.store(new MemoryInput(
            "contributor:bob",
            DevtownMemoryDomain.SOFTWARE_REVIEW,
            tenantId,
            UUID.randomUUID().toString(),
            "Review by bob",
            Map.of()
        ));

        var logs = new ArrayList<LogRecord>();
        var handler = new Handler() {
            @Override public void publish(LogRecord record) { logs.add(record); }
            @Override public void flush() {}
            @Override public void close() {}
        };

        var julLogger = java.util.logging.Logger.getLogger("io.casehub.devtown.app.MemoryAdminResource");
        julLogger.addHandler(handler);
        try {
            given()
                .contentType("application/json")
                .body("{\"login\": \"bob\"}")
            .when()
                .post("/api/admin/memory/erase/contributor")
            .then()
                .statusCode(204);

            assertThat(logs).hasSizeGreaterThanOrEqualTo(2);
            assertThat(logs.get(0).getMessage()).contains("GDPR erasure requested");
            assertThat(logs.get(0).getMessage()).contains("contributor:bob");
            assertThat(logs.get(1).getMessage()).contains("GDPR erasure completed");
            assertThat(logs.get(1).getMessage()).contains("contributor:bob");
        } finally {
            julLogger.removeHandler(handler);
        }
    }
}
