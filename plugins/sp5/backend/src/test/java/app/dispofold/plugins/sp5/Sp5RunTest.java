package app.dispofold.plugins.sp5;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import app.dispofold.plugins.sp5.port.PyCsv;
import app.dispofold.plugins.sp5.port.PyJson;
import app.dispofold.plugins.sp5.port.Sp5Export;
import app.dispofold.plugins.sp5.port.Sp5ExportTest;
import app.dispofold.store.Store;

/** A run without an import on the synthetic folder: its summary, report, the mapping's check and the comparison. */
@QuarkusTest
@TestProfile(Sp5Profile.class)
@TestSecurity(user = "planer", roles = "planner")
class Sp5RunTest {

    static final String RUN = "{\"month\":\"2027-01\",\"to\":null}";

    @Inject
    Store store;

    @Inject
    ObjectMapper mapper;

    @Inject
    DataSource dataSource;

    @Inject
    Sp5Runs runs;

    @Inject
    Sp5Plugin plugin;

    @BeforeAll
    static void folder() throws Exception {
        Path folder = Sp5Profile.ROOT.resolve("lauf");
        Files.createDirectories(folder);
        Sp5ExportTest.folder(folder);
    }

    @BeforeEach
    void on() throws Exception {
        store.setPluginEnabled(Sp5Plugin.ID, true);
        ObjectNode s = mapper.createObjectNode();
        s.put("root", Sp5Profile.ROOT.toString());
        s.put("path", "lauf");
        s.set("mapping", mapper.readTree(Sp5ExportTest.MAPPING));
        s.putObject("options").put("historyMonths", 1).put("mixMinDuties", 1);
        store.savePluginSettings(Sp5Plugin.ID, 1, mapper.writeValueAsString(s));
    }

    static RequestSpecification json() {
        return given().contentType(ContentType.JSON);
    }

    void sql(String statement) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(statement);
        }
    }

    JsonNode post(String path, Object body) throws Exception {
        return mapper.readTree(json().body(body).post("/api/plugins/sp5/" + path).then().statusCode(200).extract()
                .asString());
    }

    JsonNode get(String path) throws Exception {
        return mapper.readTree(given().get("/api/plugins/sp5/" + path).then().statusCode(200).extract().asString());
    }

    @Test
    void aRunImportsNothingAndCountsWhatTheResultHolds() throws Exception {
        long revision = store.datasetState().revision();
        JsonNode summary = post("lauf", RUN);
        assertThat(store.datasetState().revision()).isEqualTo(revision);
        assertThat(summary.path("request").path("month").asText()).isEqualTo("2027-01");
        assertThat(summary.path("request").path("to").isNull()).isTrue();
        List<String> keys = new ArrayList<>();
        summary.path("request").path("options").fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrder("historyMonths", "areasFromHistory", "shiftsFromHistory",
                "demand", "mixMinDuties", "shiftMix", "hiddenShiftsMonths", "recentMonths", "nightsFair");
        assertThat(summary.path("request").path("options").path("historyMonths").asInt()).isEqualTo(1);
        assertThat(summary.path("months")).containsExactly(mapper.readTree("\"2027-01\""));
        assertThat(summary.path("ranAt").asText()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\dZ");
        assertThat(summary.path("corrections").asBoolean()).isEqualTo(store.baseline().isPresent());
        assertThat(get("lauf")).isEqualTo(summary);

        Map<String, byte[]> zip = Sp5PluginTest.unzip(json().body(RUN).post("/api/plugins/sp5/ergebnis").then()
                .statusCode(200).extract().asByteArray());
        JsonNode dataset = mapper.readTree(zip.get("dataset.json"));
        JsonNode period = dataset.path("periods").get(0);
        JsonNode counts = summary.path("counts");
        assertThat(counts.path("people").asInt()).isEqualTo(dataset.path("people").size()).isPositive();
        assertThat(counts.path("areas").asInt()).isEqualTo(dataset.path("areas").size());
        assertThat(counts.path("shifts").asInt()).isEqualTo(dataset.path("shifts").size());
        assertThat(counts.path("demandRules").asInt()).isEqualTo(dataset.path("demand").size());
        assertThat(counts.path("duties").asInt()).isEqualTo(period.path("fixed").size());
        assertThat(counts.path("previousDuties").asInt()).isEqualTo(period.path("previousDuties").size());
        assertThat(counts.path("absences").asInt()).isEqualTo(period.path("absences").size());
        String warnings = new String(zip.get("warnings.txt"), StandardCharsets.UTF_8);
        assertThat(counts.path("warnings").asInt()).isEqualTo(warnings.lines().count());
        String report = new String(zip.get("report.csv"), StandardCharsets.UTF_8);
        assertThat(counts.path("reportRows").asInt()).isEqualTo(report.split("\r\n").length - 1);
        assertThat(counts.path("corrections").asInt()).isEqualTo(Arrays.stream(report.split("\r\n"))
                .filter(l -> l.startsWith("korrektur;")).count());
    }

    @Test
    void theReportIsTheRowsAndWarningsOfTheFiles() throws Exception {
        post("lauf", RUN);
        Map<String, byte[]> zip = Sp5PluginTest.unzip(json().body(RUN).post("/api/plugins/sp5/ergebnis").then()
                .statusCode(200).extract().asByteArray());
        JsonNode report = get("lauf/bericht");
        List<String> columns = new ArrayList<>();
        report.path("columns").forEach(c -> columns.add(c.asText()));
        assertThat(columns).hasSize(Sp5Export.REPORT_COLUMNS).isEqualTo(Sp5Export.REPORT_HEADER);
        // written back as the CSV writes them, the rows are the file
        StringBuilder csv = new StringBuilder();
        PyCsv.row(csv, columns);
        for (JsonNode row : report.path("rows")) {
            row.fields().forEachRemaining(e -> assertThat(e.getValue().asText()).isNotEmpty());
            PyCsv.row(csv, columns.stream().map(c -> row.path(c).asText("")).toList());
        }
        assertThat(report.path("rows").size()).isPositive();
        assertThat(csv.toString().getBytes(StandardCharsets.UTF_8)).isEqualTo(zip.get("report.csv"));
        List<String> warnings = new ArrayList<>();
        report.path("warnings").forEach(w -> warnings.add(w.asText()));
        assertThat(warnings).isNotEmpty().containsExactlyElementsOf(
                new String(zip.get("warnings.txt"), StandardCharsets.UTF_8).lines().toList());

        byte[] file = given().get("/api/plugins/sp5/lauf/bericht.csv").then().statusCode(200)
                .contentType("text/csv; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"dispofold-sp5-bericht-2027-01.csv\"")
                .extract().asByteArray();
        assertThat(file).isEqualTo(zip.get("report.csv"))
                .isEqualTo(Sp5Export.reportBytes(runs.last().result));
    }

    @Test
    void theImportOfARunLoadsOnlyThatRunUnchanged() throws Exception {
        sql("delete from dataset");
        JsonNode first = post("lauf", RUN);
        ObjectNode request = ((ObjectNode) first.path("request").deepCopy()).put("run", first.path("id").asText());
        json().body(request).post("/api/import/plugin/sp5/preview").then().statusCode(200);
        json().body(request).post("/api/import/plugin/sp5?replace=true").then().statusCode(200);

        // another run since: the first one is no longer offered
        JsonNode second = post("lauf", RUN);
        json().body(request).post("/api/import/plugin/sp5/preview").then().statusCode(409)
                .body("code", equalTo("plugin.sp5.staleRun"));
        ObjectNode latest = ((ObjectNode) second.path("request").deepCopy()).put("run", second.path("id").asText());
        json().body(latest).post("/api/import/plugin/sp5/preview").then().statusCode(200);

        // the mapping changed since the run: neither its import nor its comparison without the corrections
        ObjectNode s = (ObjectNode) mapper.readTree(store.plugin(Sp5Plugin.ID).orElseThrow().settings());
        ((com.fasterxml.jackson.databind.node.ArrayNode) s.get("mapping").get("ignoreShifts")).add("Z9");
        store.savePluginSettings(Sp5Plugin.ID, 1, mapper.writeValueAsString(s));
        json().body(latest).post("/api/import/plugin/sp5/preview").then().statusCode(409)
                .body("code", equalTo("plugin.sp5.staleRun"));
        given().get("/api/plugins/sp5/vergleich?korrekturen=false").then().statusCode(409)
                .body("code", equalTo("plugin.sp5.staleRun"));
        // without a run id (the Daten page): read as it is now
        json().body(RUN).post("/api/import/plugin/sp5/preview").then().statusCode(200);
        sql("delete from dataset");
    }

    @Test
    void optionsOfTheRequestOverrideTheSettingsAndTheImportLoadsTheSame() throws Exception {
        for (String[] bad : List.of(new String[] { "historyMonths", "25" }, new String[] { "demand", "\"plan\"" },
                new String[] { "shiftMix", "1" })) {
            json().body("{\"month\":\"2027-01\",\"options\":{\"" + bad[0] + "\":" + bad[1] + "}}")
                    .post("/api/plugins/sp5/lauf").then().statusCode(400).body("code", equalTo("plugin.sp5.setting"))
                    .body("params.field", equalTo(bad[0]));
        }
        json().body("{\"month\":\"2027-01\",\"options\":[]}").post("/api/plugins/sp5/lauf").then().statusCode(400)
                .body("code", equalTo("plugin.sp5.setting")).body("params.field", equalTo("options"));

        JsonNode summary = post("lauf", "{\"month\":\"2027-01\",\"options\":{\"demand\":\"sp5\",\"shiftMix\":false}}");
        JsonNode options = summary.path("request").path("options");
        assertThat(options.path("demand").asText()).isEqualTo("sp5");
        assertThat(options.path("shiftMix").asBoolean()).isFalse();
        assertThat(options.path("historyMonths").asInt()).isEqualTo(1);
        // the summary's request, given to the import, loads the dataset of the run
        assertThat(plugin.load(summary.path("request")).dataset()).isEqualTo(runs.last().dataset);
        Map<String, byte[]> zip = Sp5PluginTest.unzip(json().body(summary.path("request"))
                .post("/api/plugins/sp5/ergebnis").then().statusCode(200).extract().asByteArray());
        assertThat(mapper.readTree(zip.get("anfrage.json")).path("args").toString()).contains("\"--demand\",\"sp5\"",
                "\"--no-shift-mix\"");
        json().body(summary.path("request")).post("/api/import/plugin/sp5?replace=true").then().statusCode(200);
        assertThat(mapper.readTree(given().get("/api/dataset").then().statusCode(200).extract().asString())
                .path("demand").size()).isEqualTo(runs.last().dataset.demand().size());
        // without options, the settings as they are
        assertThat(post("lauf", RUN).path("request").path("options").path("demand").asText()).isEqualTo("history");
    }

    @Test
    void theCheckNamesWhatTheImportWouldRefuse() throws Exception {
        assertThat(post("zuordnung/pruefen", Sp5ExportTest.MAPPING).path("errors").isEmpty()).isTrue();
        String bad = "{\"areas\": [{\"id\": \"AN\", \"name\": \"Annahme\"}], \"shifts\": [{\"areaId\": \"XX\"}],"
                + " \"groups\": [{\"match\": \"T*\", \"areaIds\": []}], \"ignoreShifts\": [\"re:*x\", 3],"
                + " \"extra\": true}";
        List<String> errors = new ArrayList<>();
        post("zuordnung/pruefen", bad).path("errors").forEach(e -> errors.add(e.asText()));
        assertThat(errors).hasSizeGreaterThan(3);
        assertThatThrownBy(() -> Sp5Export.validateMapping(PyJson.load(bad))).isInstanceOf(Sp5Export.Sp5Error.class)
                .hasMessage(String.join("; ", errors));
        assertThat(post("zuordnung/pruefen", "[]").path("errors"))
                .containsExactly(mapper.readTree("\"die Zuordnung muss ein JSON-Objekt sein\""));
    }

    @Test
    void codesSayWhichRuleMatches() throws Exception {
        JsonNode codes = post("zuordnung/codes", Sp5ExportTest.MAPPING);
        assertThat(codes.path("errors").isEmpty()).isTrue();
        assertThat(shift(codes, "A1")).isEqualTo(mapper.readTree("{\"sp5Id\": \"1\", \"code\": \"A1\", \"name\":"
                + " \"Dienst A1\", \"hidden\": false, \"result\": {\"kind\": \"rule\", \"index\": 0, \"areaId\":"
                + " \"AN\", \"category\": null}}"));
        assertThat(shift(codes, "N1").path("result").path("index").asInt()).isEqualTo(1);
        assertThat(shift(codes, "X2").path("result")).isEqualTo(mapper.readTree("{\"kind\":\"ignored\",\"index\":0}"));
        assertThat(shift(codes, "Q9").path("result")).isEqualTo(mapper.readTree("{\"kind\":\"none\"}"));
        assertThat(group(codes, "Team Annahme")).isEqualTo(mapper.readTree("{\"sp5Id\": \"1\", \"name\": \"Team "
                + "Annahme\", \"members\": 2, \"result\": {\"kind\": \"rule\", \"index\": 0, \"areaIds\": [\"AN\"]}}"));
        assertThat(group(codes, "Verwaltung").path("result"))
                .isEqualTo(mapper.readTree("{\"kind\":\"excluded\",\"index\":0}"));
        assertThat(group(codes, "Verwaltung").path("members").asInt()).isEqualTo(1);
        assertThat(codes.path("absenceTypes")).containsExactly(mapper.readTree("{\"sp5Id\": \"1\", \"code\": \"U\","
                + " \"name\": \"Urlaub\", \"result\": {\"kind\": \"none\"}}"));

        // an invalid pattern matches nothing; a group the rules leave is ignored; absences: ignored before rest
        ObjectNode other = (ObjectNode) mapper.readTree(Sp5ExportTest.MAPPING);
        other.set("shifts", mapper.readTree("[{\"match\": \"re:*x\", \"areaId\": \"AN\"}, 7, {\"match\": \"A*\","
                + " \"areaId\": \"AN\", \"category\": \"EARLY\"}]"));
        other.set("groups", mapper.readTree("[{\"match\": \"Keine\", \"areaIds\": [\"AN\"]}]"));
        other.remove("excludeGroups");
        other.set("ignoreGroups", mapper.readTree("[\"re:(\", \"Team*\"]"));
        other.set("compensatoryRestAbsences", mapper.readTree("[\"U\"]"));
        codes = post("zuordnung/codes", other);
        assertThat(codes.path("errors").size()).isEqualTo(3);
        assertThat(shift(codes, "A1").path("result")).isEqualTo(mapper.readTree("{\"kind\": \"rule\", \"index\": 2,"
                + " \"areaId\": \"AN\", \"category\": \"EARLY\"}"));
        assertThat(group(codes, "Team Annahme").path("result"))
                .isEqualTo(mapper.readTree("{\"kind\":\"ignored\",\"index\":1}"));
        assertThat(group(codes, "Verwaltung").path("result")).isEqualTo(mapper.readTree("{\"kind\":\"none\"}"));
        assertThat(codes.path("absenceTypes").get(0).path("result"))
                .isEqualTo(mapper.readTree("{\"kind\":\"compensatoryRest\",\"index\":0}"));
        other.set("ignoreAbsences", mapper.readTree("[\"X\", \"U\"]"));
        assertThat(post("zuordnung/codes", other).path("absenceTypes").get(0).path("result"))
                .isEqualTo(mapper.readTree("{\"kind\":\"ignored\",\"index\":1}"));
        // any JSON: nothing matches
        codes = post("zuordnung/codes", "[]");
        assertThat(codes.path("errors").size()).isEqualTo(1);
        assertThat(shift(codes, "A1").path("result")).isEqualTo(mapper.readTree("{\"kind\":\"none\"}"));
    }

    static JsonNode shift(JsonNode codes, String code) {
        for (JsonNode s : codes.path("shifts")) {
            if (s.path("code").asText().equals(code)) {
                return s;
            }
        }
        throw new AssertionError("no shift " + code);
    }

    static JsonNode group(JsonNode codes, String name) {
        for (JsonNode g : codes.path("groups")) {
            if (g.path("name").asText().equals(name)) {
                return g;
            }
        }
        throw new AssertionError("no group " + name);
    }

    static JsonNode person(JsonNode list, String id) {
        for (JsonNode p : list) {
            if (p.path("id").asText().equals(id)) {
                return p;
            }
        }
        return null;
    }

    @Test
    void comparedWithTheLastImportWithAndWithoutTheCorrections() throws Exception {
        sql("delete from dataset");
        post("lauf", RUN);
        given().get("/api/plugins/sp5/vergleich").then().statusCode(409).body("code", equalTo("plugin.sp5.noBaseline"));
        json().body(RUN).post("/api/import/plugin/sp5?replace=true").then().statusCode(200);
        JsonNode same = get("vergleich");
        assertThat(same.path("against").asText()).isEqualTo("import");
        assertThat(same.path("corrections").asBoolean()).isTrue();
        assertThat(same.path("people").path("changed").isEmpty()).isTrue();
        assertThat(same.path("duties").isEmpty()).isTrue();
        assertThat(same.path("demand").isEmpty()).isTrue();
        same.path("counts").forEach(c -> assertThat(c.path("old")).as(c.path("section").asText())
                .isEqualTo(c.path("new")));

        // a change in Dispofold: in the run with the corrections, not in the run without them
        JsonNode d = mapper.readTree(given().get("/api/dataset").then().statusCode(200).extract().asString());
        JsonNode e1 = person(d.path("people"), "E1");
        ((ObjectNode) e1).put("weeklyHours", 12.5);
        json().header("If-Match", d.path("revision").asText()).body(e1).put("/api/dataset/people/E1").then()
                .statusCode(200);
        assertThat(post("lauf", RUN).path("corrections").asBoolean()).isTrue();
        JsonNode on = get("vergleich?korrekturen=true");
        JsonNode changed = person(on.path("people").path("changed"), "E1");
        assertThat(changed.path("name").asText()).isEqualTo("Muster Anna");
        assertThat(changed.path("fields")).containsExactly(mapper.readTree("{\"field\": \"weeklyHours\", \"old\":"
                + " 40.0, \"new\": 12.5}"));
        JsonNode off = get("vergleich?korrekturen=false");
        assertThat(off.path("corrections").asBoolean()).isFalse();
        assertThat(person(off.path("people").path("changed"), "E1")).isNull();

        // a person excluded in Dispofold: the run leaves her out, the import keeps her; no difference
        long revision = given().get("/api/dataset").then().extract().jsonPath().getLong("revision");
        json().header("If-Match", revision).body("true").put("/api/dataset/people/E2/excluded").then()
                .statusCode(200);
        post("lauf", RUN);
        json().body(RUN).post("/api/import/plugin/sp5?replace=true").then().statusCode(200);
        JsonNode excluded = get("vergleich");
        assertThat(person(excluded.path("people").path("removed"), "E2")).isNull();
        assertThat(excluded.path("people").path("changed").isEmpty()).isTrue();
        excluded.path("counts").forEach(c -> assertThat(c.path("old")).as(c.path("section").asText())
                .isEqualTo(c.path("new")));
        // the correction is not left for the other tests of this database
        sql("delete from dataset");
    }

    @Test
    void comparedWithAFile() throws Exception {
        post("lauf", RUN);
        ObjectNode old = mapper.valueToTree(runs.last().dataset);
        assertThat(person(old.path("people"), "E1")).isNotNull();
        ArrayNode people = (ArrayNode) old.path("people");
        ObjectNode e1 = (ObjectNode) person(people, "E1");
        ObjectNode gone = e1.deepCopy().put("id", "E99").put("lastName", "Fort").put("firstName", "Fritz");
        // E1 as the run has it (a change in Dispofold by another test may be applied as a correction)
        double hours = e1.path("weeklyHours").asDouble();
        e1.put("weeklyHours", hours + 1).put("shortName", "XX");
        people.removeAll();
        people.add(e1).add(gone);
        ObjectNode restriction = mapper.createObjectNode().put("personId", "E1").put("shiftId", "S1")
                .putNull("category").put("weekday", 2).put("kind", "NEVER").putNull("validFrom").putNull("validTo");
        old.putArray("restrictions").add(restriction).add(restriction.deepCopy());
        ObjectNode period = (ObjectNode) old.path("periods").get(0);
        int fixed = period.path("fixed").size();
        ((ArrayNode) period.path("fixed")).add(mapper.createObjectNode().put("personId", "E1")
                .put("date", "2027-01-07").put("shiftId", "S77")).add(mapper.createObjectNode().put("personId", "E1")
                .put("date", "2027-01-08").put("shiftId", "S1"));
        ObjectNode rule = (ObjectNode) old.path("demand").get(0);
        rule.put("min", rule.path("min").asInt() + 1);
        ((ArrayNode) old.path("demand")).add(mapper.createObjectNode().put("areaId", "AN").putNull("days")
                .put("date", "2027-01-06").put("from", "07:00").put("to", "08:00").put("min", 1).putNull("max"));

        JsonNode diff = mapper.readTree(json().body(old).post("/api/plugins/sp5/vergleich").then().statusCode(200)
                .extract().asString());
        assertThat(diff.path("against").asText()).isEqualTo("datei");
        assertThat(diff.path("counts").get(0)).isEqualTo(mapper.readTree("{\"section\": \"people\", \"old\": 2,"
                + " \"new\": " + runs.last().dataset.people().size() + "}"));
        assertThat(diff.path("counts")).contains(mapper.readTree("{\"section\": \"restrictions\", \"old\": 2,"
                + " \"new\": 0}"));
        assertThat(person(diff.path("people").path("removed"), "E99")).isEqualTo(mapper.readTree("{\"id\": \"E99\","
                + " \"name\": \"Fort Fritz\"}"));
        assertThat(diff.path("people").path("added").toString()).doesNotContain("\"E1\"");
        JsonNode changed = person(diff.path("people").path("changed"), "E1");
        assertThat(changed.path("fields")).containsExactly(
                mapper.readTree("{\"field\": \"shortName\", \"old\": \"XX\", \"new\": \"AM\"}"),
                mapper.createObjectNode().put("field", "weeklyHours").put("old", hours + 1).put("new", hours),
                mapper.readTree("{\"field\": \"restrictions\", \"added\": [], \"removed\": [" + restriction + ","
                        + restriction + "]}"));
        assertThat(diff.path("duties")).containsExactly(
                mapper.createObjectNode().put("month", "2027-01").put("areaId", "AN").put("old", fixed + 1)
                        .put("new", fixed),
                mapper.readTree("{\"month\": \"2027-01\", \"areaId\": null, \"old\": 1, \"new\": 0}"));
        JsonNode first = runs.last().dataset.demand().isEmpty() ? null : mapper.valueToTree(runs.last().dataset
                .demand().get(0));
        assertThat(diff.path("demand").toString()).contains("\"date\":\"2027-01-06\",\"from\":\"07:00\",\"to\":"
                + "\"08:00\",\"old\":[{\"min\":1,\"max\":null}],\"new\":[]");
        assertThat(diff.path("demand").toString()).contains("\"from\":" + first.path("from") + ",\"to\":"
                + first.path("to"));
        assertThat(diff.path("demand").size()).isEqualTo(2);

        for (String bad : List.of("{}", "[1]", "kein JSON", "{\"format\": \"dispofold/korrekturen\"}",
                "{\"format\": \"dispofold/dataset\", \"version\": 1}",
                "{\"format\": \"dispofold/dataset\", \"version\": 2, \"people\": [null]}",
                "{\"format\": \"dispofold/dataset\", \"version\": 2, \"periods\": [{\"month\": \"2027-01\", \"fixed\": [null]}]}",
                "{\"format\": \"dispofold/dataset\", \"version\": 2, \"periods\": [{\"month\": \"2027-01\", \"absences\": [null]}]}")) {
            json().body(bad).post("/api/plugins/sp5/vergleich").then().statusCode(400)
                    .body("code", equalTo("plugin.sp5.dataset"));
        }
    }

    @Test
    void withoutARunThereIsNothingToShow() {
        runs.forget();
        for (String path : List.of("lauf", "lauf/bericht", "lauf/bericht.csv", "vergleich")) {
            given().get("/api/plugins/sp5/" + path).then().statusCode(404).body("code", equalTo("plugin.sp5.noRun"));
        }
        json().body("{}").post("/api/plugins/sp5/vergleich").then().statusCode(404)
                .body("code", equalTo("plugin.sp5.noRun"));
    }

    @Test
    @TestSecurity(user = "leser", roles = "viewer")
    void readersGetNoneOfTheNewRoutes() {
        for (String path : List.of("lauf", "zuordnung/pruefen", "zuordnung/codes", "vergleich")) {
            json().body("{}").post("/api/plugins/sp5/" + path).then().statusCode(403);
        }
        for (String path : List.of("lauf", "lauf/bericht", "lauf/bericht.csv", "vergleich",
                "vergleich?korrekturen=false")) {
            given().get("/api/plugins/sp5/" + path).then().statusCode(403);
        }
    }
}
