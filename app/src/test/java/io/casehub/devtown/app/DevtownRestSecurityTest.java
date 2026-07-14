package io.casehub.devtown.app;

import io.casehub.devtown.domain.DevtownRoles;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class DevtownRestSecurityTest {

    @Test
    void unauthenticated_adminEndpoints_return401() {
        given().contentType("application/json")
                .body("{\"login\":\"test\"}")
                .when().post("/api/admin/memory/erase/contributor")
                .then().statusCode(401);

        given().contentType("application/json")
                .body("{}")
                .when().post("/api/incident-feedback")
                .then().statusCode(401);

        given().contentType("application/json")
                .body("{\"reason\":\"test\"}")
                .when().post("/api/actors/test-actor/erasure")
                .then().statusCode(401);

        given().contentType("application/json")
                .body("{\"repo\":\"test/repo\",\"prNumber\":1,\"headSha\":\"abc\",\"linesChanged\":10}")
                .when().post("/api/reviews")
                .then().statusCode(401);

        given().when().get("/api/compliance/code-review/00000000-0000-0000-0000-000000000001")
                .then().statusCode(401);
    }

    @Test
    @TestSecurity(user = "admin-user", roles = {DevtownRoles.ADMIN})
    void authenticated_admin_notBlockedByAuth() {
        given().contentType("application/json")
                .body("{\"login\":\"test\"}")
                .when().post("/api/admin/memory/erase/contributor")
                .then().statusCode(204);

        given().contentType("application/json")
                .body("{\"repo\":\"test/repo\",\"prNumber\":1,\"headSha\":\"abc\",\"linesChanged\":10,\"baseRef\":\"main\",\"contributor\":\"dev\",\"changedPaths\":[]}")
                .when().post("/api/reviews")
                .then().statusCode(200);

        given().when().get("/api/compliance/code-review/00000000-0000-0000-0000-000000000001")
                .then().statusCode(404);
    }

    @Test
    void permitAll_webhook_reachesResourceCode() {
        given().contentType("application/json")
                .header("X-GitHub-Event", "ping")
                .header("X-Hub-Signature-256", "")
                .body("{}")
                .when().post("/api/github/webhook")
                .then().statusCode(401)
                .body("error", equalTo("invalid signature"));
    }

    @Test
    @TestSecurity(user = "engineer-user", roles = {DevtownRoles.ENGINEER})
    void engineer_canPostReviews() {
        given().contentType("application/json")
               .body("{\"repo\":\"test/repo\",\"prNumber\":1,\"headSha\":\"abc\",\"linesChanged\":10,\"baseRef\":\"main\",\"contributor\":\"dev\",\"changedPaths\":[]}")
               .when().post("/api/reviews")
               .then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "engineer-user", roles = {DevtownRoles.ENGINEER})
    void engineer_canReadCompliance() {
        given().when().get("/api/compliance/code-review/00000000-0000-0000-0000-000000000001")
               .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "engineer-user", roles = {DevtownRoles.ENGINEER})
    void engineer_canReadGovernance() {
        given().when().get("/api/governance/queue-status")
               .then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "engineer-user", roles = {DevtownRoles.ENGINEER})
    void engineer_cannotErase() {
        given().contentType("application/json")
               .body("{\"reason\":\"test\"}")
               .when().post("/api/actors/test-actor/erasure")
               .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "engineer-user", roles = {DevtownRoles.ENGINEER})
    void engineer_cannotRecordIncidentFeedback() {
        given().contentType("application/json")
               .body("{}")
               .when().post("/api/incident-feedback")
               .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "auditor-user", roles = {DevtownRoles.AUDITOR})
    void auditor_canReadCompliance() {
        given().when().get("/api/compliance/code-review/00000000-0000-0000-0000-000000000001")
               .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "auditor-user", roles = {DevtownRoles.AUDITOR})
    void auditor_canReadGovernance() {
        given().when().get("/api/governance/queue-status")
               .then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "auditor-user", roles = {DevtownRoles.AUDITOR})
    void auditor_cannotPostReviews() {
        given().contentType("application/json")
               .body("{\"repo\":\"test/repo\",\"prNumber\":1,\"headSha\":\"abc\",\"linesChanged\":10,\"baseRef\":\"main\",\"contributor\":\"dev\",\"changedPaths\":[]}")
               .when().post("/api/reviews")
               .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "auditor-user", roles = {DevtownRoles.AUDITOR})
    void auditor_cannotErase() {
        given().contentType("application/json")
               .body("{\"reason\":\"test\"}")
               .when().post("/api/actors/test-actor/erasure")
               .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "dc-user", roles = {DevtownRoles.DATA_CONTROLLER})
    void dataController_canErase() {
        int status = given().contentType("application/json")
               .body("{\"reason\":\"gdpr-request\"}")
               .when().post("/api/actors/test-actor/erasure")
               .then().extract().statusCode();
        assertThat(status).isNotIn(401, 403);
    }

    @Test
    @TestSecurity(user = "dc-user", roles = {DevtownRoles.DATA_CONTROLLER})
    void dataController_cannotPostReviews() {
        given().contentType("application/json")
               .body("{\"repo\":\"test/repo\",\"prNumber\":1,\"headSha\":\"abc\",\"linesChanged\":10,\"baseRef\":\"main\",\"contributor\":\"dev\",\"changedPaths\":[]}")
               .when().post("/api/reviews")
               .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "service-user", roles = {DevtownRoles.SERVICE})
    void service_canPostReviews() {
        given().contentType("application/json")
               .body("{\"repo\":\"test/repo\",\"prNumber\":1,\"headSha\":\"abc\",\"linesChanged\":10,\"baseRef\":\"main\",\"contributor\":\"dev\",\"changedPaths\":[]}")
               .when().post("/api/reviews")
               .then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "service-user", roles = {DevtownRoles.SERVICE})
    void service_cannotReadGovernance() {
        given().when().get("/api/governance/queue-status")
               .then().statusCode(403);
    }


}
