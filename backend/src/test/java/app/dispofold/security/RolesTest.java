package app.dispofold.security;

import static io.restassured.RestAssured.given;

import org.junit.jupiter.api.Test;

import app.dispofold.api.FakeFacadeProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/** All GET need a login, all other methods the role planner (except logout). */
@QuarkusTest
@TestProfile(FakeFacadeProfile.class)
class RolesTest {

    static final String[] GETS = { "/api/me", "/api/rules", "/api/constraints", "/api/months",
            "/api/periods/2032-01", "/api/plans/2032-01", "/api/dataset" };

    static final String[][] WRITES = { { "PUT", "/api/rules" }, { "PUT", "/api/periods/2032-01" },
            { "PUT", "/api/dataset/people/P0001" }, { "POST", "/api/import/demo" }, { "POST", "/api/import/dataset" },
            { "POST", "/api/plans/2032-01/solve" }, { "POST", "/api/plans/2032-01/stop" },
            { "PUT", "/api/plans/2032-01/cells" }, { "POST", "/api/plans/2032-01/unpin" },
            { "POST", "/api/plans/2032-01/finalize" }, { "POST", "/api/plans/2032-01/import" } };

    @Test
    void anonymousGets401() {
        for (String path : GETS) {
            given().get(path).then().statusCode(401);
        }
        for (String[] w : WRITES) {
            given().contentType(ContentType.JSON).body("{}").request(w[0], w[1]).then().statusCode(401);
        }
    }

    @Test
    @TestSecurity(user = "leser", roles = "viewer")
    void viewerReadsOnly() {
        for (String path : GETS) {
            given().get(path).then().statusCode(200);
        }
        for (String[] w : WRITES) {
            given().contentType(ContentType.JSON).body("{}").request(w[0], w[1]).then().statusCode(403);
        }
        given().post("/api/logout").then().statusCode(204);
    }

    @Test
    @TestSecurity(user = "planer", roles = "planner")
    void plannerWrites() {
        for (String path : GETS) {
            given().get(path).then().statusCode(200);
        }
        String rules = given().get("/api/rules").then().extract().asString();
        given().contentType(ContentType.JSON).body(rules).put("/api/rules").then().statusCode(200);
        given().post("/api/plans/2032-01/stop").then().statusCode(200);
    }
}
