package app.dispofold.desktop;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import app.dispofold.desktop.update.BundleMaker;
import app.dispofold.desktop.update.BundleVerifierTest;
import app.dispofold.desktop.update.SignTool;
import app.dispofold.desktop.update.Version;

/** Quit, update settings and bundle upload; the exit is recorded instead of performed. */
@QuarkusTest
@TestProfile(DesktopProfile.class)
class DesktopResourceTest {

    @Inject
    RecordingExit exit;

    @BeforeEach
    void clearExits() {
        exit.codes().clear();
    }

    static RequestSpecification local() {
        return DesktopProfile.local();
    }

    @Test
    void quitAnswers204ThenExits() {
        local().post("/api/desktop/quit").then().statusCode(204);
        assertThat(exit.codes()).containsExactly(0);
    }

    @Test
    void updateSettingsArePersisted() throws Exception {
        Files.deleteIfExists(Path.of(DesktopProfile.BASE, "daten/einstellungen.json"));
        local().get("/api/desktop/update").then().statusCode(200).body("current", equalTo("0.1.0"))
                .body("source", equalTo("")).body("available", nullValue())
                .body("error", nullValue());
        // No source by default: a check answers "no source" without contacting anything
        local().get("/api/desktop/update?check=true").then().statusCode(200).body("available", nullValue())
                .body("errorCode", equalTo("desktop.update.noSource"));
        local().post("/api/desktop/update/install").then().statusCode(400)
                .body("code", equalTo("desktop.update.noSource"));
        assertThat(exit.codes()).isEmpty();
        Path folder = Path.of(DesktopProfile.BASE, "quelle").toAbsolutePath();
        local().contentType(ContentType.JSON).body("{\"source\":\"" + folder + "\",\"checkOnStart\":false}")
                .put("/api/desktop/update/settings").then().statusCode(200);
        assertThat(Path.of(DesktopProfile.BASE, "daten/einstellungen.json")).content().contains("quelle");
        local().get("/api/desktop/update?check=true").then().statusCode(200).body("checkOnStart", is(false))
                .body("error", containsString("nicht erreichbar"))
                .body("errorCode", equalTo("desktop.update.folderUnreachable"));
        local().contentType(ContentType.JSON).body("{\"source\":\"ftp://x\",\"checkOnStart\":true}")
                .put("/api/desktop/update/settings").then().statusCode(400)
                .body("code", equalTo("desktop.update.sourceUnsupported"));
    }

    @Test
    void folderSourceOffersOnlyNewerBundles() throws Exception {
        Path folder = Files.createDirectories(Path.of(DesktopProfile.BASE, "quelle2").toAbsolutePath());
        Path app = BundleVerifierTest.fakeApp(folder.resolve("build"));
        var key = SignTool.keyPair().getPrivate();
        BundleMaker.make(app, Version.parse("0.0.9"), key, folder);
        local().contentType(ContentType.JSON).body("{\"source\":\"" + folder + "\",\"checkOnStart\":true}")
                .put("/api/desktop/update/settings").then().statusCode(200);
        local().get("/api/desktop/update?check=true").then().body("available", nullValue()).body("error", nullValue());
        BundleMaker.make(app, Version.parse("0.2.0"), key, folder);
        local().get("/api/desktop/update?check=true").then().body("available.version", equalTo("0.2.0"));
        // A version that failed its trial start is not offered again
        Path state = Path.of(DesktopProfile.BASE, "versionen/state.json");
        Files.createDirectories(state.getParent());
        Files.writeString(state, "{\"bad\": [\"0.2.0\"]}");
        local().get("/api/desktop/update?check=true").then().body("available", nullValue());
        Files.delete(state);
        // Signed with a key that is not the release key: refused, nothing staged, no exit
        local().post("/api/desktop/update/install").then().statusCode(400).body("error", containsString("Signatur"))
                .body("code", equalTo("desktop.bundle.signature"));
        assertThat(exit.codes()).isEmpty();
    }

    @Test
    void uploadedBundleIsVerified() {
        local().contentType("application/octet-stream").body("not a bundle".getBytes())
                .post("/api/desktop/update/file").then().statusCode(400).body("error", containsString("ZIP"))
                .body("code", equalTo("desktop.bundle.notZip"));
        assertThat(exit.codes()).isEmpty();
        assertThat(Path.of(DesktopProfile.BASE, "versionen/eingang")).isEmptyDirectory();
    }

    @Test
    void desktopEndpointsNeedTheCookie() {
        given().post("/api/desktop/quit").then().statusCode(401);
        assertThat(exit.codes()).isEmpty();
    }
}
