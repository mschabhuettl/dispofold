package app.dispofold.desktop;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import io.quarkus.arc.Arc;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import app.dispofold.api.ReleaseCheck;
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
        // Nothing saved yet: the interface asks once, offering the project's releases API
        local().get("/api/desktop/update").then().statusCode(200).body("current", equalTo("0.14.0"))
                .body("source", equalTo("")).body("available", nullValue())
                .body("error", nullValue()).body("ask", is(true))
                .body("releases", equalTo("https://api.github.com/repos/mschabhuettl/dispofold/releases/latest"));
        // No source by default: a check answers "no source" without contacting anything
        local().get("/api/desktop/update?check=true").then().statusCode(200).body("available", nullValue())
                .body("errorCode", equalTo("desktop.update.noSource"));
        local().post("/api/desktop/update/install").then().statusCode(400)
                .body("code", equalTo("desktop.update.noSource"));
        assertThat(exit.codes()).isEmpty();
        // "Nein": an empty source is a stored choice, not asked again, and still no source
        local().contentType(ContentType.JSON).body("{\"source\":\"\",\"checkOnStart\":true}")
                .put("/api/desktop/update/settings").then().statusCode(200);
        local().get("/api/desktop/update?check=true").then().statusCode(200).body("ask", is(false))
                .body("source", equalTo("")).body("errorCode", equalTo("desktop.update.noSource"));
        Path folder = Path.of(DesktopProfile.BASE, "quelle").toAbsolutePath();
        local().contentType(ContentType.JSON).body("{\"source\":\"" + folder + "\",\"checkOnStart\":false}")
                .put("/api/desktop/update/settings").then().statusCode(200);
        assertThat(Path.of(DesktopProfile.BASE, "daten/einstellungen.json")).content().contains("quelle");
        // A source set by hand (for example a folder on a share) is kept and not asked about
        local().get("/api/desktop/update").then().body("ask", is(false)).body("source", equalTo(folder.toString()));
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
        BundleMaker.make(app, Version.parse("9.9.9"), key, folder);
        local().get("/api/desktop/update?check=true").then().body("available.version", equalTo("9.9.9"));
        // A version that failed its trial start is not offered again
        Path state = Path.of(DesktopProfile.BASE, "versionen/state.json");
        Files.createDirectories(state.getParent());
        Files.writeString(state, "{\"bad\": [\"9.9.9\"]}");
        local().get("/api/desktop/update?check=true").then().body("available", nullValue());
        Files.delete(state);
        // Signed with a key that is not the release key: refused, nothing staged, no exit
        local().post("/api/desktop/update/install").then().statusCode(400).body("error", containsString("Signatur"))
                .body("code", equalTo("desktop.bundle.signature"));
        assertThat(exit.codes()).isEmpty();
    }

    /** "Ja" sets the GitHub releases API: its release object and the update.zip asset are read as they are. */
    @Test
    void githubReleasesAreReadDirectly() throws Exception {
        Path folder = Files.createDirectories(Path.of(DesktopProfile.BASE, "github").toAbsolutePath());
        Path zip = BundleMaker.make(BundleVerifierTest.fakeApp(folder.resolve("build")), Version.parse("9.9.9"),
                SignTool.keyPair().getPrivate(), folder);
        HttpServer github = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger downloads = new AtomicInteger();
        String base = "http://127.0.0.1:" + github.getAddress().getPort();
        github.createContext("/repos/mschabhuettl/dispofold/releases/latest", ex -> {
            byte[] b = ("{\"tag_name\":\"v9.9.9\",\"name\":\"Dispofold 9.9.9\",\"draft\":false,\"prerelease\":false,"
                    + "\"body\":\"Neu\",\"html_url\":\"https://github.com/mschabhuettl/dispofold/releases/tag/v9.9.9\","
                    + "\"assets\":[{\"name\":\"dispofold-9.9.9-SHA256SUMS.txt\",\"browser_download_url\":\"" + base
                    + "/sums\"},{\"name\":\"dispofold-9.9.9-update.zip\",\"browser_download_url\":\"" + base
                    + "/update.zip\"}]}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        github.createContext("/update.zip", ex -> {
            downloads.incrementAndGet();
            byte[] b = Files.readAllBytes(zip);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        github.start();
        try {
            local().contentType(ContentType.JSON).body("{\"source\":\"" + base
                    + "/repos/mschabhuettl/dispofold/releases/latest\",\"checkOnStart\":true}")
                    .put("/api/desktop/update/settings").then().statusCode(200);
            local().get("/api/desktop/update?check=true").then().statusCode(200).body("error", nullValue())
                    .body("available.version", equalTo("9.9.9")).body("available.title", equalTo("Dispofold 9.9.9"))
                    .body("available.notes", equalTo("Neu"))
                    .body("available.url", equalTo("https://github.com/mschabhuettl/dispofold/releases/tag/v9.9.9"));
            assertThat(downloads.get()).isZero();
            // The package is downloaded and verified; it is signed with a test key, so it is refused
            local().post("/api/desktop/update/install").then().statusCode(400)
                    .body("code", equalTo("desktop.bundle.signature"));
            assertThat(downloads.get()).isEqualTo(1);
            assertThat(exit.codes()).isEmpty();
        } finally {
            github.stop(0);
        }
    }

    @Test
    void theServerReleaseCheckIsNotInTheDesktopBuild() {
        local().get("/api/release").then().statusCode(404);
        assertThat(Arc.container().instance(ReleaseCheck.class).isAvailable()).isFalse();
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
