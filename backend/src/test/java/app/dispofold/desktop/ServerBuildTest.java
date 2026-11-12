package app.dispofold.desktop;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;

/** The server build has no desktop login and no desktop endpoints, whatever the request carries. */
@QuarkusTest
class ServerBuildTest {

    @Test
    void desktopTokenAndEndpointsDoNotExist() {
        given().redirects().follow(false).get("/").then().statusCode(302);
        given().header(DesktopAuth.HEADER, "any-value").get("/api/me").then().statusCode(401);
        given().contentType("application/json").body("{\"challenge\":\"yMnKy8zNzs/Q0dLT1NXW1w==\"}")
                .post("/api/desktop/challenge").then().statusCode(404);
        Cookie login = given().redirects().follow(false).formParam("j_username", "planer")
                .formParam("j_password", "planpw").post("/j_security_check").then().statusCode(302).extract()
                .detailedCookie("quarkus-credential");
        given().cookie(login).get("/api/me").then().statusCode(200).body("desktop", is(false));
        given().cookie(login).post("/api/desktop/quit").then().statusCode(404);
    }

    @Test
    void bodiesOver50MegabytesAre413() throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("http://localhost:" + io.restassured.RestAssured.port + "/api/import/dataset"))
                .header("Content-Type", "application/json").expectContinue(true)
                .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(new byte[(50 << 20) + 1])).build();
        org.assertj.core.api.Assertions.assertThat(DesktopBodyLimitTest.send(request)).isEqualTo(413);
    }

    @Test
    void loginPageAssetsArePublic() {
        for (String path : new String[] { "/theme-TESTTEST.js", "/favicon.svg", "/favicon.ico",
                "/js/login-TESTTEST.js", "/login.html" }) {
            given().redirects().follow(false).get(path).then().statusCode(200);
        }
        // The application script itself needs a login.
        given().redirects().follow(false).get("/js/main-TESTTEST.js").then().statusCode(302);
    }
}
