package app.dispofold.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.Cookie;

/** Real form login without test security shortcuts. */
@QuarkusTest
class LoginTest {

    static Cookie login(String user, String password) {
        Cookie cookie = given().redirects().follow(false)
                .formParam("j_username", user).formParam("j_password", password)
                .post("/j_security_check")
                .then().statusCode(302).header("Location", not(containsString("error")))
                .extract().detailedCookie("quarkus-credential");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    @Test
    void anonymousApiGets401AndPagesRedirect() {
        given().redirects().follow(false).get("/api/me").then().statusCode(401);
        given().redirects().follow(false).post("/api/logout").then().statusCode(401);
        given().redirects().follow(false).get("/").then().statusCode(302)
                .header("Location", containsString("/login.html"));
        given().redirects().follow(false).get("/q/health/live").then().statusCode(200);
        given().redirects().follow(false).get("/js/login-TESTTEST.js").then().statusCode(not(equalTo(302)));
        given().redirects().follow(false).get("/css/app.css").then().statusCode(not(equalTo(302)));
    }

    @Test
    void plainPasswordLogin() {
        Cookie cookie = login("planer", "planpw");
        given().cookie(cookie).get("/api/me").then().statusCode(200)
                .body("username", equalTo("planer")).body("role", equalTo("planner"));
    }

    @Test
    void bcryptLoginAndLogout() {
        Cookie cookie = login("leser", "geheim");
        given().cookie(cookie).get("/api/me").then().statusCode(200).body("role", equalTo("viewer"));
        given().cookie(cookie).redirects().follow(false).post("/api/logout").then().statusCode(204)
                .header("Set-Cookie", containsString("quarkus-credential="));
    }

    @Test
    void wrongPasswordGoesToErrorPage() {
        given().redirects().follow(false)
                .formParam("j_username", "leser").formParam("j_password", "falsch")
                .post("/j_security_check")
                .then().statusCode(302).header("Location", containsString("/login.html?error=1"));
    }
}
