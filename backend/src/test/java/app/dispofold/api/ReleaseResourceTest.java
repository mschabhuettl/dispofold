package app.dispofold.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;

/**
 * GET /api/release with the check switched on against a local stand-in for GitHub: the check on start finds the
 * release, planners see it, viewers get nothing. The tests never contact GitHub.
 */
@QuarkusTest
@TestProfile(ReleaseResourceTest.LocalGitHub.class)
class ReleaseResourceTest {

    /** The check on, its URL on a local server that offers release 99.0.0. */
    public static class LocalGitHub implements QuarkusTestProfile {
        private static HttpServer server;

        @Override
        public synchronized Map<String, String> getConfigOverrides() {
            if (server == null) {
                try {
                    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                server.createContext("/repos/mschabhuettl/dispofold/releases/latest", ex -> {
                    byte[] b = ("{\"tag_name\":\"v99.0.0\",\"name\":\"Dispofold 99.0.0\",\"prerelease\":false,"
                            + "\"body\":\"Neu\",\"html_url\":\"https://github.com/mschabhuettl/dispofold/releases/tag/v99.0.0\"}")
                            .getBytes(StandardCharsets.UTF_8);
                    ex.sendResponseHeaders(200, b.length);
                    ex.getResponseBody().write(b);
                    ex.close();
                });
                server.start();
            }
            return Map.of("dispofold.update-check.enabled", "true", "dispofold.update-check.url",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/repos/mschabhuettl/dispofold/releases/latest");
        }
    }

    @Test
    @TestSecurity(user = "planer", roles = "planner")
    void plannersSeeTheReleaseFoundOnStart() {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> "99.0.0".equals(
                given().get("/api/release").then().statusCode(200).extract().path("available.version")));
        given().get("/api/release").then().body("current", equalTo("0.15.0"))
                .body("available.title", equalTo("Dispofold 99.0.0")).body("available.notes", equalTo("Neu"))
                .body("available.url", equalTo("https://github.com/mschabhuettl/dispofold/releases/tag/v99.0.0"));
    }

    @Test
    @TestSecurity(user = "leser", roles = "viewer")
    void viewersGetNoReleaseInformation() {
        given().get("/api/release").then().statusCode(403);
    }

    @Test
    void anonymousRequestsAreRefused() {
        given().get("/api/release").then().statusCode(401);
    }
}
