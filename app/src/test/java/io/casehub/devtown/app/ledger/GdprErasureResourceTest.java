package io.casehub.devtown.app.ledger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

import io.casehub.ledger.api.model.LedgerEntryType;
import io.casehub.ledger.model.CaseLedgerEntry;
import io.casehub.ledger.runtime.privacy.ActorIdentityProvider;
import io.casehub.ledger.runtime.repository.LedgerEntryRepository;
import io.casehub.platform.api.identity.ActorType;
import io.casehub.platform.api.identity.CurrentPrincipal;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(TokenisationEnabledTestProfile.class)
class GdprErasureResourceTest {

    @Inject LedgerEntryRepository ledgerRepo;
    @Inject ActorIdentityProvider actorIdentityProvider;
    @Inject CurrentPrincipal principal;

    @Test
    void erasure_pseudonymisesActorAndReturnsReceipt() {
        final String rawActorId = "human-reviewer-gdpr-" + UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final String tenancyId = principal.tenancyId();

        seedHumanEntry(rawActorId, caseId, tenancyId);

        String token = QuarkusTransaction.requiringNew().call(
                () -> actorIdentityProvider.tokeniseForQuery(rawActorId));
        assertThat(token).isNotEqualTo(rawActorId);

        var response = given()
            .contentType("application/json")
            .queryParam("tenancyId", tenancyId)
            .body("{\"reason\": \"GDPR Art.17 request\"}")
            .when()
                .post("/api/actors/" + rawActorId + "/erasure")
            .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getString("erasedActorToken")).isEqualTo(token);
        assertThat(response.getLong("ledgerEntriesAffected")).isGreaterThan(0);
        assertThat(response.getString("receiptEntryId")).isNotNull();
        assertThat(response.getString("reason")).isEqualTo("GDPR Art.17 request");

        var resolved = QuarkusTransaction.requiringNew().call(
                () -> actorIdentityProvider.resolve(token));
        assertThat(resolved).isEmpty();
    }

    @Test
    void erasure_receiptPersistedWithTokenNotRawId() {
        final String rawActorId = "human-reviewer-receipt-" + UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final String tenancyId = principal.tenancyId();

        seedHumanEntry(rawActorId, caseId, tenancyId);

        String token = QuarkusTransaction.requiringNew().call(
                () -> actorIdentityProvider.tokeniseForQuery(rawActorId));

        var response = given()
            .contentType("application/json")
            .queryParam("tenancyId", tenancyId)
            .body("{\"reason\": \"GDPR Art.17 test\"}")
            .when()
                .post("/api/actors/" + rawActorId + "/erasure")
            .then()
                .statusCode(200)
                .extract().body().jsonPath();

        UUID receiptId = UUID.fromString(response.getString("receiptEntryId"));

        var receipt = QuarkusTransaction.requiringNew().call(() ->
                ledgerRepo.findEntryById(receiptId, tenancyId));
        assertThat(receipt).isPresent();
        assertThat(receipt.get()).isInstanceOf(ErasureReceiptLedgerEntry.class);

        var erasureReceipt = (ErasureReceiptLedgerEntry) receipt.get();
        assertThat(erasureReceipt.erasedActorToken).isEqualTo(token);
        assertThat(erasureReceipt.erasedActorToken).isNotEqualTo(rawActorId);
        assertThat(erasureReceipt.actorType).isEqualTo(ActorType.SYSTEM);
        assertThat(erasureReceipt.actorRole).isEqualTo("GDPR_COMPLIANCE");
    }

    @Test
    void erasure_idempotent_secondCallReturnsZeroCounts() {
        final String rawActorId = "human-reviewer-idempotent-" + UUID.randomUUID();
        final UUID caseId = UUID.randomUUID();
        final String tenancyId = principal.tenancyId();

        seedHumanEntry(rawActorId, caseId, tenancyId);

        given()
            .contentType("application/json")
            .queryParam("tenancyId", tenancyId)
            .body("{\"reason\": \"first erasure\"}")
            .when()
                .post("/api/actors/" + rawActorId + "/erasure")
            .then()
                .statusCode(200);

        var response = given()
            .contentType("application/json")
            .queryParam("tenancyId", tenancyId)
            .body("{\"reason\": \"second erasure\"}")
            .when()
                .post("/api/actors/" + rawActorId + "/erasure")
            .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getLong("ledgerEntriesAffected")).isEqualTo(0);
    }

    @Test
    void erasure_unknownActor_returns200WithZeroCounts() {
        final String tenancyId = principal.tenancyId();

        var response = given()
            .contentType("application/json")
            .queryParam("tenancyId", tenancyId)
            .body("{\"reason\": \"GDPR Art.17 request\"}")
            .when()
                .post("/api/actors/nonexistent-actor-" + UUID.randomUUID() + "/erasure")
            .then()
                .statusCode(200)
                .extract().body().jsonPath();

        assertThat(response.getLong("ledgerEntriesAffected")).isEqualTo(0);
        assertThat(response.getInt("memoryRecordsErased")).isEqualTo(0);
        assertThat(response.getString("receiptEntryId")).isNotNull();
    }

    private void seedHumanEntry(final String actorId, final UUID caseId, final String tenancyId) {
        QuarkusTransaction.requiringNew().run(() -> {
            CaseLedgerEntry entry = new CaseLedgerEntry();
            entry.subjectId = caseId;
            entry.caseId = caseId;
            entry.entryType = LedgerEntryType.EVENT;
            entry.eventType = "REVIEW_COMPLETED";
            entry.caseStatus = "IN_PROGRESS";
            entry.actorId = actorId;
            entry.actorType = ActorType.HUMAN;
            entry.actorRole = "REVIEWER";
            entry.occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
            ledgerRepo.save(entry, tenancyId);
        });
    }
}
