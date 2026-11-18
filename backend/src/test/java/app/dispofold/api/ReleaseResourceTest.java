package app.dispofold.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

import app.dispofold.api.ReleaseCheck.Release;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

/** GET /api/release: planners see a newer release, viewers get nothing. The tests never contact GitHub. */
@QuarkusTest
class ReleaseResourceTest {

    private static void offer(Release release) {
        QuarkusMock.installMockForType(new ReleaseCheck() {
            @Override
            public Release latest() {
                return release;
            }

            @Override
            public String current() {
                return "0.12.0";
            }
        }, ReleaseCheck.class);
    }

    @Test
    @TestSecurity(user = "planer", roles = "planner")
    void plannersSeeANewerRelease() {
        offer(null);
        given().get("/api/release").then().statusCode(200).body("current", equalTo("0.12.0"))
                .body("available", nullValue());
        offer(new Release("0.13.0", "Dispofold 0.13.0", "Neu", "https://github.com/mschabhuettl/dispofold/releases/tag/v0.13.0"));
        given().get("/api/release").then().statusCode(200).body("available.version", equalTo("0.13.0"))
                .body("available.title", equalTo("Dispofold 0.13.0")).body("available.notes", equalTo("Neu"))
                .body("available.url", equalTo("https://github.com/mschabhuettl/dispofold/releases/tag/v0.13.0"));
    }

    @Test
    @TestSecurity(user = "leser", roles = "viewer")
    void viewersGetNoReleaseInformation() {
        offer(new Release("0.13.0", null, null, null));
        given().get("/api/release").then().statusCode(403);
    }

    @Test
    void anonymousRequestsAreRefused() {
        given().get("/api/release").then().statusCode(401);
    }
}
