package app.dispofold.desktop;

import static app.dispofold.desktop.DesktopProfile.anonymous;
import static app.dispofold.desktop.DesktopProfile.local;
import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;

import app.dispofold.desktop.DesktopAuth.Decision;
import app.dispofold.desktop.update.SessionKeys;

/** The desktop login: session header, Host, Origin and Sec-Fetch-Site checks, challenge and ready line. */
@QuarkusTest
@TestProfile(DesktopProfile.class)
class DesktopAuthTest {

    static final String CHALLENGE = "yMnKy8zNzs/Q0dLT1NXW1w==";

    @Test
    void missingSessionIs401() {
        anonymous().redirects().follow(false).get("/api/me").then().statusCode(401)
                .body("error", containsString("Programm")).body("code", equalTo("security.desktopLogin"));
        anonymous().redirects().follow(false).get("/").then().statusCode(401)
                .body(containsString("Bitte Dispofold über das Programm öffnen"));
        anonymous().redirects().follow(false).get("/login.html").then().statusCode(401);
    }

    @Test
    void wrongSessionIs401() {
        anonymous().header(DesktopAuth.HEADER, "wrong").get("/api/me").then().statusCode(401);
        anonymous().header(DesktopAuth.HEADER, DesktopProfile.session() + "x").get("/api/me").then().statusCode(401);
        String nonce = (String) ReadyLine.last.get("nonce");
        anonymous().header(DesktopAuth.HEADER, SessionKeys.session(new byte[32], nonce)).get("/api/me").then()
                .statusCode(401);
        // The value of an earlier start (another nonce) is refused.
        anonymous().header(DesktopAuth.HEADER, SessionKeys.session(SessionKeys.secret(DesktopProfile.SECRET),
                SessionKeys.nonce())).get("/api/me").then().statusCode(401);
    }

    @Test
    void cookieAndTokenAreGone() {
        anonymous().redirects().follow(false).cookie("dispofold_k", DesktopProfile.session()).get("/api/me").then()
                .statusCode(401);
        anonymous().redirects().follow(false).get("/?k=" + DesktopProfile.session()).then().statusCode(401)
                .header("Set-Cookie", nullValue());
    }

    @Test
    void sessionGivesThePlannerIdentity() {
        local().get("/api/me").then().statusCode(200)
                .body("username", equalTo("lokal")).body("role", equalTo("planner")).body("desktop", is(true));
        local().get("/").then().statusCode(200);
    }

    @Test
    void onlyTheOwnHostIsAccepted() {
        local().header("Host", "evil.example:" + RestAssured.port).get("/api/me").then()
                .statusCode(anyOf(is(400), is(403)));
        local().header("Host", "127.0.0.1:1").get("/api/me").then().statusCode(403);
        // The proxy always names 127.0.0.1; "localhost" is refused as well.
        local().header("Host", "localhost:" + RestAssured.port).get("/api/me").then().statusCode(403);
    }

    @Test
    void onlyTheWindowsOriginsAreAccepted() {
        for (String origin : new String[] { "dispofold://localhost", "http://dispofold.localhost" }) {
            local().header("Origin", origin).get("/api/me").then().statusCode(200);
        }
        for (String origin : new String[] { "http://evil.example", "null", "http://127.0.0.1:" + RestAssured.port,
                "dispofold://localhost.evil", "https://dispofold.localhost" }) {
            local().header("Origin", origin).get("/api/me").then().statusCode(403);
            local().header("Origin", origin).post("/api/desktop/update/install").then().statusCode(403);
        }
        local().header("Sec-Fetch-Site", "cross-site").get("/api/me").then().statusCode(403);
        local().header("Sec-Fetch-Site", "same-site").get("/api/me").then().statusCode(403);
        local().header("Sec-Fetch-Site", "same-origin").get("/api/me").then().statusCode(200);
    }

    @Test
    void challengeIsAnsweredWithoutSession() {
        String expected = SessionKeys.response(SessionKeys.secret(DesktopProfile.SECRET), CHALLENGE,
                RestAssured.port);
        anonymous().contentType("application/json").body("{\"challenge\":\"" + CHALLENGE + "\"}")
                .post("/api/desktop/challenge").then().statusCode(200).body("response", equalTo(expected));
        // The other checks still apply.
        anonymous().header("Host", "127.0.0.1:1").contentType("application/json")
                .body("{\"challenge\":\"" + CHALLENGE + "\"}").post("/api/desktop/challenge").then().statusCode(403);
        anonymous().header("Origin", "http://evil.example").contentType("application/json")
                .body("{\"challenge\":\"" + CHALLENGE + "\"}").post("/api/desktop/challenge").then().statusCode(403);
        for (String bad : new String[] { "{}", "{\"challenge\":\"AAEC\"}", "{\"challenge\":\"%%%\"}" }) {
            anonymous().contentType("application/json").body(bad).post("/api/desktop/challenge").then()
                    .statusCode(400).body("code", equalTo("desktop.challenge.invalid"));
        }
        // Only POST is open.
        anonymous().get("/api/desktop/challenge").then().statusCode(401);
    }

    @Test
    void readyLineProvesTheSecretForTheListeningPort() {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).until(() -> ReadyLine.last != null);
        var line = ReadyLine.last;
        int port = ((Number) line.get("port")).intValue();
        long pid = ((Number) line.get("pid")).longValue();
        String nonce = (String) line.get("nonce");
        assertThat(line.get("type")).isEqualTo("ready");
        assertThat(port).isPositive().isEqualTo(RestAssured.port);
        assertThat(pid).isEqualTo(ProcessHandle.current().pid());
        assertThat(SessionKeys.isNonce(nonce)).isTrue();
        assertThat(line.get("proof"))
                .isEqualTo(SessionKeys.proof(SessionKeys.secret(DesktopProfile.SECRET), nonce, port, pid));
        // The port listens and answers.
        anonymous().get("/q/health/ready").then().statusCode(200);
    }

    @Test
    void healthIsOpen() {
        anonymous().get("/q/health/ready").then().statusCode(200);
        given().header("Host", "evil.example").get("/q/health/live").then().statusCode(200);
    }

    @Test
    void decisionRules() {
        String nonce = "ZGVmZ2hpamtsbW5vcHFycw==";
        DesktopAuth auth = new DesktopAuth(DesktopProfile.SECRET, nonce);
        String s = "HlTrRK+7tRifwGl3ub6w2wHw7WUuIeQnomq4q3SD4OQ=";
        assertThat(auth.decide("/api/x", "GET", "127.0.0.1:5", 5, null, null,
                "jmVcnKTTvbihIjiQiwWuqos2IqxqnPowQXDW5JYRWEg=")).isEqualTo(Decision.UNAUTHORIZED);
        assertThat(auth.decide("/api/x", "POST", "127.0.0.1:5", 5, "dispofold://localhost", null, s))
                .isEqualTo(Decision.ALLOW);
        assertThat(auth.decide("/api/x", "GET", "127.0.0.1:5", 5, null, null, s)).isEqualTo(Decision.ALLOW);
        assertThat(auth.decide("/api/x", "GET", "localhost:5", 5, null, null, s)).isEqualTo(Decision.FORBIDDEN);
        assertThat(auth.decide("/api/x", "GET", "127.0.0.1:6", 5, null, null, s)).isEqualTo(Decision.FORBIDDEN);
        assertThat(auth.decide("/api/x", "GET", null, 5, null, null, s)).isEqualTo(Decision.FORBIDDEN);
        assertThat(auth.decide("/api/x", "GET", "127.0.0.1:5", 5, "http://127.0.0.1:5", null, s))
                .isEqualTo(Decision.FORBIDDEN);
        assertThat(auth.decide("/api/x", "GET", "127.0.0.1:5", 5, null, null, null)).isEqualTo(Decision.UNAUTHORIZED);
        assertThat(auth.decide("/api/x", "GET", "127.0.0.1:5", 5, null, null, s.toLowerCase()))
                .isEqualTo(Decision.UNAUTHORIZED);
        assertThat(auth.decide("/api/desktop/challenge", "POST", "127.0.0.1:5", 5, null, null, null))
                .isEqualTo(Decision.OPEN);
        assertThat(auth.decide("/api/desktop/challenge", "POST", "evil:5", 5, null, null, null))
                .isEqualTo(Decision.FORBIDDEN);
        assertThat(auth.decide("/api/desktop/challenge", "GET", "127.0.0.1:5", 5, null, null, null))
                .isEqualTo(Decision.UNAUTHORIZED);
        assertThat(auth.decide("/q/health/ready", "GET", "evil", 5, null, "cross-site", null))
                .isEqualTo(Decision.OPEN);
        assertThat(auth.decide("/q/healthx", "GET", "evil", 5, null, null, null)).isEqualTo(Decision.FORBIDDEN);
    }
}
