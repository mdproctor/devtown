package io.casehub.devtown.app.governance;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

@QuarkusTest
@TestSecurity(user = "test-user", roles = {"devtown-engineer"})
class GovernancePreferencesResourceTest {

    @Test
    void returnsDefaultPreferences() {
        given()
                .when()
                .get("/api/devtown/governance/preferences")
                .then()
                .statusCode(200)
                .body("refresh.operational", is("10second"))
                .body("refresh.metrics", is("30second"))
                .body("refresh.caseDetail", is("5second"));
    }
}
