package app.dispofold.plugins.sp5;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

import app.dispofold.model.Dataset;
import app.dispofold.model.Period;
import app.dispofold.store.Store;

/**
 * The move from the export program with its corrections file, against that program itself: on a fresh database the
 * file is loaded on the Daten page, and the plugin's export and import then give byte for byte what the program
 * gives with the same file. Needs a checkout of the program at the pinned commit in {@code EXPORT_CHECKOUT} (the
 * plugin-compare job sets it) and its Python ({@code EXPORT_PYTHON}, default python3); skipped without.
 */
@QuarkusTest
@TestProfile(Sp5Profile.class)
@TestSecurity(user = "planer", roles = "planner")
class LoadedCorrectionsCompareTest {

    static final String RUN = "{\"month\":\"2027-01\",\"to\":null}";
    static final String WARNING = "Warnung: ";

    @Inject
    Store store;

    @Inject
    ObjectMapper mapper;

    @Inject
    DataSource dataSource;

    @Test
    void onAFreshDatabaseTheLoadedFileGivesWhatTheExportProgramGivesWithIt() throws Exception {
        String checkout = System.getenv("EXPORT_CHECKOUT");
        assumeTrue(checkout != null && !checkout.isBlank(), "EXPORT_CHECKOUT: checkout of the export program");
        Path program = Path.of(checkout).toAbsolutePath();
        String python = System.getenv().getOrDefault("EXPORT_PYTHON", "python3");
        Path work = Files.createDirectories(Path.of("target/sp5-loaded-corrections").toAbsolutePath());
        Path folder = Sp5Profile.ROOT.resolve("korrekturen-laden");
        delete(folder);
        delete(work);
        Files.createDirectories(work);
        run(program, python, "-m", "tools.sp5writer", "--month", "2027-01", "--people", "40", "--out",
                folder.toString());

        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String sql : List.of("delete from period", "delete from plan", "delete from history",
                    "delete from dataset", "delete from loaded_corrections")) {
                s.executeUpdate(sql);
            }
        }
        store.setPluginEnabled(Sp5Plugin.ID, true);
        ObjectNode settings = mapper.createObjectNode();
        settings.put("root", Sp5Profile.ROOT.toString());
        settings.put("path", "korrekturen-laden");
        settings.set("mapping", mapper.readTree(program.resolve("mapping.example.json").toFile()));
        settings.putObject("options").put("historyMonths", 3);
        store.savePluginSettings(Sp5Plugin.ID, 1, mapper.writeValueAsString(settings));

        // the ids of the folder, from a run without corrections (there are none yet)
        Map<String, byte[]> plain = result();
        assertThat(plain).doesNotContainKey(Sp5Plugin.CORRECTIONS_FILE);
        JsonNode exported = mapper.readTree(plain.get("dataset.json"));
        List<String> people = new ArrayList<>();
        exported.path("people").forEach(p -> people.add(p.path("id").asText()));
        String shift = exported.path("shifts").get(0).path("id").asText();
        String area = exported.path("areas").get(0).path("id").asText();
        // whole numbers, a note, an exclusion, restrictions, invalid and unknown entries, a key that is no id, demand
        String file = """
                {"format": "dispofold/corrections", "version": 2,
                 "people": {
                  "%s": {"note": "Teilzeit", "weeklyHours": 30, "hoursAccount": 12.5, "compRestBalance": 2},
                  "%s": {"exclude": true},
                  "%s": {"restrictions": [{"shiftId": "%s", "category": null, "weekday": 0, "kind": "NEVER",
                          "validFrom": null, "validTo": null}]},
                  "%s": {"weeklyHours": "viel", "firstName": "X"},
                  "E999999": {"weeklyHours": 20},
                  "Erika Muster": {"weeklyHours": 20}},
                 "demand": {"%s": [{"days": ["MON", "TUE", "WED", "THU", "FRI"], "date": null, "from": "07:00",
                   "to": "19:00", "min": 2, "max": 3}]}}
                """.formatted(people.get(0), people.get(1), people.get(2), shift, people.get(3), area);
        Path corrections = work.resolve("korrekturen.json");
        Files.writeString(corrections, file);
        given().contentType(ContentType.JSON).body(file.getBytes(StandardCharsets.UTF_8))
                .put("/api/data/corrections/loaded").then().statusCode(200);

        Map<String, byte[]> zip = result();
        JsonNode request = mapper.readTree(zip.get("anfrage.json"));
        List<String> args = new ArrayList<>();
        request.path("args").forEach(a -> args.add(a.asText()));
        Files.write(work.resolve("mapping.json"), zip.get("mapping.json"));
        Path out = Files.createDirectories(work.resolve("python"));
        List<String> cmd = new ArrayList<>(List.of(python, "-X", "utf8", program.resolve("sp5_export.py").toString(),
                "--data", folder.toString()));
        cmd.addAll(args);
        cmd.addAll(List.of("--mapping", work.resolve("mapping.json").toString(), "--out",
                out.resolve("dataset.json").toString(), "--report", out.resolve("report.csv").toString(),
                "--corrections", corrections.toString()));
        String stderr = run(program, cmd.toArray(String[]::new));
        StringBuilder warnings = new StringBuilder();
        stderr.replace("\r\n", "\n").lines().filter(l -> l.startsWith(WARNING))
                .forEach(l -> warnings.append(l.substring(WARNING.length())).append('\n'));

        byte[] dataset = Files.readAllBytes(out.resolve("dataset.json"));
        assertThat(new String(zip.get("dataset.json"), StandardCharsets.UTF_8))
                .isEqualTo(new String(dataset, StandardCharsets.UTF_8));
        assertThat(new String(zip.get("report.csv"), StandardCharsets.UTF_8))
                .isEqualTo(Files.readString(out.resolve("report.csv"), StandardCharsets.UTF_8));
        assertThat(new String(zip.get("warnings.txt"), StandardCharsets.UTF_8)).isEqualTo(warnings.toString());
        // the file reached the program as loaded: applied, skipped and excluded entries in the report
        String report = new String(zip.get("report.csv"), StandardCharsets.UTF_8);
        assertThat(report).contains("angewendet").contains("ausgeschlossen").contains("ungueltig");

        // the import of a run is the same dataset, and takes the loaded file over
        JsonNode summary = mapper.readTree(given().contentType(ContentType.JSON).body(RUN)
                .post("/api/plugins/sp5/lauf").then().statusCode(200).extract().asString());
        ObjectNode importing = ((ObjectNode) summary.path("request").deepCopy()).put("run",
                summary.path("id").asText());
        given().contentType(ContentType.JSON).body(importing).post("/api/import/plugin/sp5?replace=true").then()
                .statusCode(200);
        Dataset expected = mapper.readValue(dataset, Dataset.class);
        Dataset imported = mapper.readValue(given().get("/api/dataset").then().statusCode(200).extract().asString(),
                Dataset.class);
        assertThat(imported.people()).isEqualTo(expected.people());
        assertThat(imported.restrictions()).isEqualTo(expected.restrictions());
        assertThat(imported.demand()).isEqualTo(expected.demand());
        assertThat(imported.areas()).isEqualTo(expected.areas());
        assertThat(imported.shifts()).isEqualTo(expected.shifts());
        Period period = mapper.readValue(given().get("/api/periods/2027-01").then().statusCode(200).extract()
                .asString(), Period.class);
        assertThat(period).isEqualTo(expected.periods().get(0));
        assertThat(store.loadedCorrections()).isEmpty();
        assertThat(store.appliedCorrections().orElseThrow().path("people").has(people.get(0))).isTrue();
    }

    Map<String, byte[]> result() throws IOException {
        return Sp5PluginTest.unzip(given().contentType(ContentType.JSON).body(RUN).post("/api/plugins/sp5/ergebnis")
                .then().statusCode(200).extract().asByteArray());
    }

    /** Runs a command in dir; fails with its output when it does not end with 0. Returns stderr. */
    static String run(Path dir, String... cmd) throws IOException, InterruptedException {
        Path err = Files.createTempFile("sp5-stderr", ".txt");
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectError(err.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        int code = p.waitFor();
        String stderr = Files.readString(err, StandardCharsets.UTF_8);
        Files.delete(err);
        assertThat(code).as(String.join(" ", cmd) + "\n" + stderr).isZero();
        return stderr;
    }

    static void delete(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> all = Files.walk(dir)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
