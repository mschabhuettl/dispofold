package app.dispofold.plugins.beispiel;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/** A plugin the operator hides does not exist: not listed, cannot be switched on, every route 404. */
@QuarkusTest
@TestProfile(HiddenProfile.class)
@TestSecurity(user = "planer", roles = "planner")
class HiddenPluginTest {

    @Test
    void hiddenPluginIsGone() {
        given().get("/api/extensions").then().statusCode(200).body("id", not(hasItem("beispiel")));
        given().contentType(ContentType.JSON).body("{\"enabled\":true}").put("/api/extensions/beispiel").then()
                .statusCode(404).body("code", equalTo("plugin.unknown"));
        given().get("/api/plugins/beispiel/gruss").then().statusCode(404);
        given().contentType(ContentType.JSON).body("{}").post("/api/import/plugin/beispiel/preview").then()
                .statusCode(404);
    }
}
