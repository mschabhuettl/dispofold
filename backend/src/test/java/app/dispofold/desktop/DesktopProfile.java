package app.dispofold.desktop;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

import app.dispofold.desktop.update.SessionKeys;

/** The desktop build profile (encrypted H2 in a fresh folder, session header login) with a fixed test secret. */
public class DesktopProfile implements QuarkusTestProfile {

    /** The shared test vector's secret (bytes 0..31). */
    static final String SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    /** The session header of the running app: made from the nonce of its ready line, like the window does. */
    static String session() {
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(20)).until(() -> ReadyLine.last != null);
        return SessionKeys.session(SessionKeys.secret(SECRET), (String) ReadyLine.last.get("nonce"));
    }

    /** A request as the window's proxy sends it: own Host, session header. */
    static RequestSpecification local() {
        return RestAssured.given().baseUri("http://127.0.0.1").header(DesktopAuth.HEADER, session());
    }

    /** The same without the session header. */
    static RequestSpecification anonymous() {
        return RestAssured.given().baseUri("http://127.0.0.1");
    }
    /** Fresh per test run; a system property because the class is loaded by the test and by the app class loader. */
    static final String BASE = base();

    private static synchronized String base() {
        String b = System.getProperty("dispofold.test.desktop-base");
        if (b == null) {
            b = java.nio.file.Path.of("target/desktop-test", UUID.randomUUID().toString()).toAbsolutePath().toString();
            System.setProperty("dispofold.test.desktop-base", b);
        }
        return b;
    }

    @Override
    public String getConfigProfile() {
        return "desktop";
    }

    @Override
    public Set<Class<?>> getEnabledAlternatives() {
        return Set.of(RecordingExit.class);
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("dispofold.desktop.secret", SECRET,
                "dispofold.desktop.db-password", DatabaseKey.derive("test password", new byte[16]),
                "dispofold.data-dir", BASE + "/daten",
                "dispofold.versions-dir", BASE + "/versionen");
    }
}
