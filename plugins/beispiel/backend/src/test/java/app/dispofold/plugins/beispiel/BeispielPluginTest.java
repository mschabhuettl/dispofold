package app.dispofold.plugins.beispiel;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import app.dispofold.plugin.FileRoots;
import app.dispofold.store.Store;

/** Every extension point of the plugin system, through the example plugin. */
@QuarkusTest
@TestProfile(BeispielProfile.class)
@TestSecurity(user = "planer", roles = "planner")
class BeispielPluginTest {

    static final String ROOT = BeispielProfile.ROOT.toString();

    @Inject
    Store store;

    @Inject
    FileRoots files;

    @BeforeAll
    static void folders() throws IOException {
        Files.createDirectories(BeispielProfile.ROOT.resolve("unter"));
        Files.createDirectories(BeispielProfile.OUTSIDE);
        Files.copy(Path.of("../tests/fixtures/synthetic/small-dataset-2027-01.synthetic.json"),
                BeispielProfile.ROOT.resolve("unter/daten.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(BeispielProfile.OUTSIDE.resolve("geheim.json"), "{}");
        Path out = BeispielProfile.ROOT.resolve("nach-aussen");
        Path in = BeispielProfile.ROOT.resolve("nach-innen");
        Files.deleteIfExists(out);
        Files.deleteIfExists(in);
        Files.createSymbolicLink(out, BeispielProfile.OUTSIDE);
        Files.createSymbolicLink(in, BeispielProfile.ROOT.resolve("unter"));
    }

    @BeforeEach
    void off() {
        store.setPluginEnabled("beispiel", false);
    }

    static void switchTo(boolean on) {
        given().contentType(ContentType.JSON).body("{\"enabled\":" + on + "}").put("/api/extensions/beispiel").then()
                .statusCode(200).body("enabled", equalTo(on));
    }

    static RequestSpecification json() {
        return given().contentType(ContentType.JSON);
    }

    static String request(String file, boolean corrections) {
        return "{\"root\":\"" + ROOT + "\",\"file\":\"" + file + "\",\"corrections\":" + corrections + "}";
    }

    @Test
    void listedWithItsManifestAndOffByDefault() {
        given().get("/api/extensions").then().statusCode(200).body("find { it.id == 'beispiel' }.name",
                equalTo("Beispiel")).body("find { it.id == 'beispiel' }.version", equalTo("1.0.0"))
                .body("find { it.id == 'beispiel' }.enabled", equalTo(false))
                .body("find { it.id == 'beispiel' }.importSource", equalTo(true));
        given().get("/api/plugins/beispiel/gruss").then().statusCode(404).body("code", equalTo("plugin.unknown"));
        given().get("/api/plugins/beispiel/gibtsnicht").then().statusCode(404);
        json().body(request("unter/daten.json", false)).post("/api/import/plugin/beispiel/preview").then()
                .statusCode(404);
        given().contentType(ContentType.JSON).body("{\"enabled\":true}").put("/api/extensions/gibtsnicht").then()
                .statusCode(404);
    }

    @Test
    void routesFollowTheRolesOnceOn() {
        switchTo(true);
        given().get("/api/plugins/beispiel/gruss").then().statusCode(200);
        given().get("/api/plugins/beispiel/einstellungen").then().statusCode(200);
        given().get("/api/plugins/beispiel/gibtsnicht").then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "leser", roles = "viewer")
    void readersSeeOnlyViewerRoutesAndNothingToSwitch() {
        // the planner's switch is made through the store: readers cannot switch
        store.setPluginEnabled("beispiel", false);
        given().get("/api/extensions").then().statusCode(200).body("id", not(hasItem("beispiel")));
        given().get("/api/plugins/beispiel/gruss").then().statusCode(404);
        store.setPluginEnabled("beispiel", true);
        given().get("/api/extensions").then().statusCode(200).body("id", hasItem("beispiel"));
        given().get("/api/plugins/beispiel/gruss").then().statusCode(200);
        given().get("/api/plugins/beispiel/einstellungen").then().statusCode(403);
        json().body("{\"enabled\":false}").put("/api/extensions/beispiel").then().statusCode(403);
        json().body(request("unter/daten.json", false)).post("/api/import/plugin/beispiel/preview").then()
                .statusCode(403);
        given().get("/api/files/roots").then().statusCode(403);
        store.setPluginEnabled("beispiel", false);
    }

    @Test
    @TestSecurity
    void anonymousGets401EvenWhenOff() {
        given().get("/api/plugins/beispiel/gruss").then().statusCode(401);
        given().get("/api/extensions").then().statusCode(401);
        given().contentType(ContentType.JSON).body("{}").post("/api/import/plugin/beispiel").then().statusCode(401);
        given().get("/api/files/roots").then().statusCode(401);
    }

    @Test
    void settingsAreMigratedValidatedAndKeptWhenSwitchedOff() {
        store.savePluginSettings("beispiel", 1, "{\"text\":\"Servus\"}");
        switchTo(true);
        given().get("/api/plugins/beispiel/einstellungen").then().statusCode(200).body("gruss", equalTo("Servus"))
                .body("text", equalTo(null));
        assertThat(store.plugin("beispiel").orElseThrow().settingsVersion()).isEqualTo(2);
        json().body("{\"gruss\":\"\"}").put("/api/plugins/beispiel/einstellungen").then().statusCode(400)
                .body("code", equalTo("plugin.beispiel.greeting")).body("params.max", equalTo(40));
        json().body("{\"gruss\":\"Guten Tag\"}").put("/api/plugins/beispiel/einstellungen").then().statusCode(200)
                .body("gruss", equalTo("Guten Tag"));
        switchTo(false);
        switchTo(true);
        given().get("/api/plugins/beispiel/gruss").then().statusCode(200).body("gruss", equalTo("Guten Tag"));
    }

    @Test
    void importSourceGoesThroughTheImportPath() {
        switchTo(true);
        json().body(request("unter/daten.json", false)).post("/api/import/plugin/beispiel?replace=true").then()
                .statusCode(200).body("people", equalTo(20)).body("months", equalTo(List.of("2027-01")));
        // the baseline is stored: a change since is kept by keep=true and shown by the preview
        long revision = given().get("/api/dataset").then().extract().jsonPath().getLong("revision");
        json().header("If-Match", revision).body("true").put("/api/dataset/people/P0004/excluded").then()
                .statusCode(200);
        json().body(request("unter/daten.json", false)).post("/api/import/plugin/beispiel/preview").then()
                .statusCode(200).body("changed", equalTo(true)).body("kept", equalTo(1));
        json().body(request("unter/daten.json", false)).post("/api/import/plugin/beispiel").then()
                .statusCode(409).body("code", equalTo("import.masterChanged"));
        json().body(request("unter/daten.json", false)).post("/api/import/plugin/beispiel?keep=true").then()
                .statusCode(200).body("kept", equalTo(1));
        // the source asks for the cumulative corrections and lists them as applied: the next file is complete
        json().body(request("unter/daten.json", true)).post("/api/import/plugin/beispiel?keep=true").then()
                .statusCode(200);
        assertThat(store.appliedCorrections()).isPresent();
        assertThat(store.appliedCorrections().get().path("people").path("P0004").path("exclude").asBoolean())
                .isTrue();
        given().get("/api/data/corrections").then().statusCode(200).body("complete", equalTo(true))
                .body("count", equalTo(1));
        // a file that is no dataset: the plugin's own error
        json().body(request("nach-innen", false)).post("/api/import/plugin/beispiel/preview").then()
                .statusCode(400).body("code", equalTo("plugin.beispiel.unreadable"));
    }

    @Test
    void filesStayInsideTheAllowedFolders() {
        switchTo(true);
        // other tests sharing the database may have added a picked folder
        given().get("/api/files/roots").then().statusCode(200).body("id", hasItem(ROOT));
        given().queryParam("root", ROOT).get("/api/files/list").then().statusCode(200)
                .body("name", equalTo(List.of("nach-innen", "unter")));
        given().queryParam("root", ROOT).queryParam("path", "unter").get("/api/files/list").then().statusCode(200)
                .body("name", equalTo(List.of("daten.json"))).body("dir", equalTo(List.of(false)));
        for (String bad : List.of("../geheim", "unter/../../geheim", "nach-aussen", "nach-aussen/geheim.json",
                BeispielProfile.OUTSIDE.toString(), "/etc", "fehlt")) {
            given().queryParam("root", ROOT).queryParam("path", bad).get("/api/files/list").then().statusCode(404)
                    .body("code", equalTo("files.notFound")).body(not(org.hamcrest.Matchers.containsString("geheim")));
        }
        given().queryParam("root", BeispielProfile.OUTSIDE.toString()).get("/api/files/list").then().statusCode(404);
        given().queryParam("root", "/").get("/api/files/list").then().statusCode(404);
        // the plugin reads only through the same check
        for (String bad : List.of("../geheim/geheim.json", "nach-aussen/geheim.json",
                BeispielProfile.OUTSIDE.resolve("geheim.json").toString())) {
            json().body(request(bad, false)).post("/api/import/plugin/beispiel/preview").then().statusCode(404)
                    .body("code", equalTo("files.notFound"));
        }
        assertThat(files.resolve(ROOT, "nach-innen/daten.json")).isEqualTo(
                files.resolve(ROOT, "unter/daten.json"));
        // only the desktop window adds folders
        json().header("X-Dispofold-Picked", "1").body("{\"path\":\"/\"}").post("/api/files/roots").then()
                .statusCode(403).body("code", equalTo("files.pickInWindow"));
    }
}
