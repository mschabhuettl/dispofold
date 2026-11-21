package app.dispofold.plugins.sp5;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import app.dispofold.plugins.sp5.port.Sp5ExportTest;
import app.dispofold.store.Store;

/** The plugin on a small synthetic folder: settings, the folder check, the import and the result to compare. */
@QuarkusTest
@TestProfile(Sp5Profile.class)
@TestSecurity(user = "planer", roles = "planner")
class Sp5PluginTest {

    static final String ROOT = Sp5Profile.ROOT.toString();

    @Inject
    Store store;

    @Inject
    ObjectMapper mapper;

    @BeforeAll
    static void folders() throws Exception {
        Path folder = Sp5Profile.ROOT.resolve("ordner");
        Path linked = Sp5Profile.ROOT.resolve("verlinkt");
        Files.createDirectories(folder);
        Files.createDirectories(linked);
        Files.createDirectories(Sp5Profile.OUTSIDE);
        Sp5ExportTest.folder(folder);
        Sp5ExportTest.folder(Sp5Profile.OUTSIDE);
        // the same folder, but one table is a link out of the allowed folder
        try (Stream<Path> files = Files.list(folder)) {
            for (Path f : files.toList()) {
                Files.copy(f, linked.resolve(f.getFileName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.delete(linked.resolve("5EMPL.DBF"));
        Files.createSymbolicLink(linked.resolve("5EMPL.DBF"), Sp5Profile.OUTSIDE.resolve("5EMPL.DBF"));
    }

    @BeforeEach
    void on() {
        store.setPluginEnabled(Sp5Plugin.ID, true);
        store.savePluginSettings(Sp5Plugin.ID, 1, "{}");
    }

    static RequestSpecification json() {
        return given().contentType(ContentType.JSON);
    }

    ObjectNode settings(String path) throws IOException {
        ObjectNode s = mapper.createObjectNode();
        s.put("root", ROOT);
        s.put("path", path);
        s.set("mapping", mapper.readTree(Sp5ExportTest.MAPPING));
        s.putObject("options").put("historyMonths", 1).put("mixMinDuties", 1);
        return s;
    }

    @Test
    void defaultsAreTheOptionsOfTheProgram() {
        given().get("/api/plugins/sp5/einstellungen").then().statusCode(200).body("root", equalTo(""))
                .body("mapping", equalTo(null)).body("options.historyMonths", equalTo(12))
                .body("options.areasFromHistory", equalTo(null)).body("options.shiftsFromHistory", equalTo(null))
                .body("options.demand", equalTo("history")).body("options.mixMinDuties", equalTo(10))
                .body("options.shiftMix", equalTo(true)).body("options.hiddenShiftsMonths", equalTo(3))
                .body("options.recentMonths", equalTo(3));
    }

    @Test
    void settingsAreChecked() throws IOException {
        for (String[] bad : List.of(new String[] { "historyMonths", "25" }, new String[] { "historyMonths", "1.5" },
                new String[] { "areasFromHistory", "0" }, new String[] { "areasFromHistory", "4294967297" },
                new String[] { "demand", "\"plan\"" }, new String[] { "mixMinDuties", "0" },
                new String[] { "recentMonths", "-1" }, new String[] { "shiftMix", "1" })) {
            ObjectNode s = settings("ordner");
            ((ObjectNode) s.get("options")).set(bad[0], mapper.readTree(bad[1]));
            json().body(s).put("/api/plugins/sp5/einstellungen").then().statusCode(400)
                    .body("code", equalTo("plugin.sp5.setting")).body("params.field", equalTo(bad[0]));
        }
        ObjectNode s = settings("ordner");
        ((ObjectNode) s.get("mapping")).putArray("ignoreGroups").add("re:*x");
        json().body(s).put("/api/plugins/sp5/einstellungen").then().statusCode(400)
                .body("code", equalTo("plugin.sp5.mapping")).body("error", equalTo("ignoreGroups, Eintrag 1: Muster "
                        + "'re:*x' ist kein gültiger regulärer Ausdruck (nothing to repeat, Position 0)"));
        json().body(settings("../sp5-geheim")).put("/api/plugins/sp5/einstellungen").then().statusCode(404)
                .body("code", equalTo("files.notFound"));
        json().body(settings("ordner")).put("/api/plugins/sp5/einstellungen").then().statusCode(200)
                .body("path", equalTo("ordner")).body("options.historyMonths", equalTo(1))
                .body("options.recentMonths", equalTo(3));
    }

    @Test
    void aTableLinkedOutOfTheFolderIsRefused() throws IOException {
        json().body(settings("verlinkt")).put("/api/plugins/sp5/einstellungen").then().statusCode(404)
                .body("code", equalTo("files.notFound"));
        store.savePluginSettings(Sp5Plugin.ID, 1, mapper.writeValueAsString(settings("verlinkt")));
        json().body("{\"month\":\"2027-01\"}").post("/api/import/plugin/sp5/preview").then().statusCode(404)
                .body("code", equalTo("files.notFound"));
    }

    @Test
    void importNeedsAFolderAndAMapping() throws IOException {
        json().body("{\"month\":\"2027-01\"}").post("/api/import/plugin/sp5/preview").then().statusCode(400)
                .body("code", equalTo("plugin.sp5.noMapping"));
        ObjectNode s = settings("ordner");
        s.put("root", "");
        json().body(s).put("/api/plugins/sp5/einstellungen").then().statusCode(200);
        json().body("{\"month\":\"2027-01\"}").post("/api/import/plugin/sp5/preview").then().statusCode(400)
                .body("code", equalTo("plugin.sp5.noFolder"));
        json().body(settings("ordner")).put("/api/plugins/sp5/einstellungen").then().statusCode(200);
        json().body("{\"month\":\"2027-13\"}").post("/api/import/plugin/sp5/preview").then().statusCode(400)
                .body("code", equalTo("plugin.sp5.failed"))
                .body("error", equalTo("ungültiger Monat '2027-13', erwartet YYYY-MM"));
    }

    /**
     * Imports the folder, excludes a person (a correction), then saves the result: the ZIP is what the command line
     * of the port writes with the options and files the ZIP names.
     */
    @Test
    void importThenTheResultIsWhatTheCommandLineWrites(@TempDir Path dir) throws Exception {
        json().body(settings("ordner")).put("/api/plugins/sp5/einstellungen").then().statusCode(200);
        json().body("{\"month\":\"2027-01\",\"to\":null}").post("/api/import/plugin/sp5?replace=true").then()
                .statusCode(200).body("months", equalTo(List.of("2027-01")));
        // the two people of the folder (an excluded person of an earlier dataset in this database stays as well)
        given().get("/api/dataset").then().statusCode(200).body("people.id", org.hamcrest.Matchers.hasItems("E1",
                "E2")).body("people.id", org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("E3")));
        long revision = given().get("/api/dataset").then().extract().jsonPath().getLong("revision");
        json().header("If-Match", revision).body("true").put("/api/dataset/people/E2/excluded").then()
                .statusCode(200);

        Map<String, byte[]> zip = unzip(json().body("{\"month\":\"2027-01\",\"to\":\"2027-02\"}")
                .post("/api/plugins/sp5/ergebnis").then().statusCode(200).contentType("application/zip").extract()
                .asByteArray());
        assertThat(zip).containsOnlyKeys("dataset.json", "report.csv", "warnings.txt", "anfrage.json",
                "mapping.json", "korrekturen.json");
        JsonNode question = mapper.readTree(zip.get("anfrage.json"));
        assertThat(question.path("format").asText()).isEqualTo("dispofold/sp5-ergebnis");
        List<String> args = new ArrayList<>();
        question.path("args").forEach(a -> args.add(a.asText()));
        assertThat(args).containsExactly("--month", "2027-01", "--to", "2027-02", "--history-months", "1",
                "--demand", "history", "--mix-min-duties", "1", "--hidden-shifts-months", "3", "--recent-months",
                "3");
        assertThat(mapper.readTree(zip.get("korrekturen.json")).path("people").path("E2").path("exclude")
                .asBoolean()).isTrue();

        for (String name : List.of("mapping.json", "korrekturen.json")) {
            Files.write(dir.resolve(name), zip.get(name));
        }
        args.addAll(List.of("--data", Sp5Profile.ROOT.resolve("ordner").toString(), "--mapping",
                dir.resolve("mapping.json").toString(), "--corrections", dir.resolve("korrekturen.json").toString(),
                "--out", dir.resolve("d.json").toString(), "--report", dir.resolve("r.csv").toString()));
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(Sp5ExportTest.commandLine(args.toArray(String[]::new), new PrintStream(err, true, StandardCharsets.UTF_8)))
                .isZero();
        assertThat(zip.get("dataset.json")).isEqualTo(Files.readAllBytes(dir.resolve("d.json")));
        assertThat(zip.get("report.csv")).isEqualTo(Files.readAllBytes(dir.resolve("r.csv")));
        String warnings = err.toString(StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("Warnung: "))
                .map(l -> l.substring("Warnung: ".length()) + "\n").collect(Collectors.joining());
        assertThat(new String(zip.get("warnings.txt"), StandardCharsets.UTF_8)).isEqualTo(warnings)
                .contains("Korrekturen: 1 Mitarbeiter korrigiert");
        // the next import keeps the person excluded, and the corrections are listed as applied
        json().body("{\"month\":\"2027-01\"}").post("/api/import/plugin/sp5?keep=true").then().statusCode(200);
        given().get("/api/dataset").then().statusCode(200)
                .body("people.find { it.id == 'E2' }.excludedFrom", org.hamcrest.Matchers.notNullValue());
        assertThat(store.appliedCorrections().orElseThrow().path("people").path("E2").path("exclude").asBoolean())
                .isTrue();
    }

    static Map<String, byte[]> unzip(byte[] data) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(data))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                files.put(e.getName(), in.readAllBytes());
            }
        }
        return files;
    }
}
