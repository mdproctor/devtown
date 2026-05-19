package io.casehub.devtown.app;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(PrReviewResource.class)
class PrReviewResourceTest {

    @Test
    void postReview_returns200WithOutcome() {
        given()
            .contentType(MediaType.APPLICATION_JSON)
            .body("""
                {"repo":"casehubio/devtown","prNumber":42,"headSha":"abc123","baseRef":"main","linesChanged":150}
                """)
        .when()
            .post()
        .then()
            .statusCode(200)
            .body("verdict", notNullValue())
            .body("findings", notNullValue());
    }
}
