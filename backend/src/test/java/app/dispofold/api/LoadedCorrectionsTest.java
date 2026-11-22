package app.dispofold.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import java.sql.Connection;
import java.sql.Statement;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.model.Dataset;
import app.dispofold.model.Person;
import app.dispofold.plugin.api.PluginHost;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

/**
 * Loading a corrections file on the Daten page: the checks of the file, the summary, replace and remove, the merge
 * with the changes made in Dispofold, the hand-over to import sources and the corrections file afterwards.
 */
@QuarkusTest
@TestProfile(FakeFacadeProfile.class)
@TestSecurity(user = "planer", roles = "planner")
class LoadedCorrectionsTest {

    static final YearMonth MONTH = YearMonth.of(2034, 5);
    /** Integer hours, a note, a foreign key, an exclusion and demand: passed on exactly as it is. */
    static final String FILE = """
            {"format": "dispofold/corrections", "version": 2, "people": {
              "P0001": {"note": "frei", "weeklyHours": 30, "hoursAccount": 5.5},
              "P0002": {"exclude": true},
              "Erika Muster": {"weeklyHours": 20},
              "E77": {"weeklyHours": 25}},
             "demand": {"AN": [{"days": ["MON"], "date": null, "from": "07:00", "to": "19:00", "min": 2, "max": null}]}}""";

    @Inject
    ObjectMapper mapper;

    @Inject
    DataSource dataSource;

    @Inject
    PluginHost host;

    @Inject
    ImportResource imports;

    @BeforeEach
    void empty() throws Exception {
        sql("delete from period", "delete from plan", "delete from history", "delete from dataset",
                "delete from loaded_corrections");
    }

    void sql(String... statements) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.executeUpdate(statement);
            }
        }
    }

    ValidatableResponse load(String body) {
        return given().contentType(ContentType.JSON).body(body).put("/api/data/corrections/loaded").then();
    }

    void importFile(Dataset d) throws Exception {
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(d))
                .post("/api/import/dataset?replace=true").then().statusCode(200);
    }

    void edit(String id, String field, double value) throws Exception {
        JsonNode d = mapper.readTree(given().get("/api/dataset").then().statusCode(200).extract().asString());
        for (JsonNode p : d.path("people")) {
            if (p.path("id").asText().equals(id)) {
                ((ObjectNode) p).put(field, value);
                given().contentType(ContentType.JSON).header("If-Match", d.path("revision").asText()).body(p)
                        .put("/api/dataset/people/" + id).then().statusCode(200);
                return;
            }
        }
        throw new AssertionError("no person " + id);
    }

    JsonNode effective() throws Exception {
        return mapper.readTree(given().get("/api/data/corrections").then().statusCode(200).extract().asString())
                .path("file");
    }

    static Dataset withHours(Dataset d, String id, double hours) {
        List<Person> people = new ArrayList<>();
        for (Person p : d.people()) {
            people.add(!p.id().equals(id) ? p : new Person(p.id(), p.firstName(), p.lastName(), p.shortName(),
                    hours, p.areaIds(), p.hoursAccount(), p.compRestBalance(), p.familyRules(), p.externalId(),
                    p.entryDate(), p.exitDate(), p.shiftMix()));
        }
        return new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(), d.holidays(),
                d.absenceTypes(), people, d.restrictions(), d.history(), d.periods());
    }

    @Test
    void aFileThatCannotBeUsedIsRefusedWithItsReason() {
        String[][] cases = { { "nicht json", "corrections.load.unreadable" },
                { "[1]", "corrections.load.unreadable" },
                { "{\"format\": \"other\", \"version\": 2, \"people\": {}}", "corrections.load.format" },
                { "{\"format\": \"dispofold/corrections\", \"version\": 3, \"people\": {}}",
                        "corrections.load.version" },
                { "{\"format\": \"dispofold/corrections\", \"version\": 0, \"people\": {}}",
                        "corrections.load.version" },
                { "{\"format\": \"dispofold/corrections\", \"version\": \"2\", \"people\": {}}",
                        "corrections.load.version" },
                { "{\"format\": \"dispofold/corrections\", \"version\": 1.5, \"people\": {}}",
                        "corrections.load.version" },
                { "{\"format\": \"dispofold/corrections\", \"version\": 2, \"people\": []}",
                        "corrections.load.people" },
                { "{\"format\": \"dispofold/corrections\", \"version\": 2}", "corrections.load.people" },
                { "{\"format\": \"dispofold/corrections\", \"version\": 2, \"people\": {}, \"demand\": null}",
                        "corrections.load.demand" } };
        for (String[] c : cases) {
            given().contentType(ContentType.JSON).body(c[0]).post("/api/data/corrections/check").then()
                    .statusCode(400).body("code", equalTo(c[1]));
            load(c[0]).statusCode(400).body("code", equalTo(c[1]));
        }
        // version 1 ignores demand, so a demand of any form is no reason to refuse it
        load("{\"format\": \"dispofold/corrections\", \"version\": 1, \"people\": {}, \"demand\": 3}")
                .statusCode(200).body("version", equalTo(1));
        given().get("/api/data/corrections/state").then().statusCode(200).body("summary.version", equalTo(1));
    }

    @Test
    void onAnEmptyDatabaseTheLoadedFileIsHandedOnUnchanged() throws Exception {
        given().get("/api/data/corrections/state").then().statusCode(200).body("none", equalTo(true))
                .body("loaded", nullValue());
        load(FILE).statusCode(200).body("people", equalTo(4)).body("existing", nullValue())
                .body("fields.weeklyHours", equalTo(3)).body("fields.hoursAccount", equalTo(1))
                .body("fields.exclude", equalTo(1)).body("fields.demand", equalTo(1))
                .body("skipped.code", equalTo(List.of("corrections.idFormat")));
        given().get("/api/data/corrections/state").then().body("none", equalTo(false))
                .body("summary.people", equalTo(4));
        assertThat(host.corrections()).contains((ObjectNode) mapper.readTree(FILE));
        assertThat(mapper.writeValueAsString(host.corrections().orElseThrow()))
                .isEqualTo(mapper.writeValueAsString(mapper.readTree(FILE)));
        given().get("/api/data/corrections").then().statusCode(200).body("count", equalTo(6))
                .body("earlier", equalTo(6)).body("complete", equalTo(true));
    }

    @Test
    void theSummaryNamesWhatWillNotApply() throws Exception {
        importFile(TestData.dataset(MONTH));
        String file = """
                {"format": "dispofold/corrections", "version": 2, "people": {
                  "P0001": {"weeklyHours": 30, "firstName": "X", "colour": 1, "areaIds": ["XX"]},
                  "P0003": {"weeklyHours": -1, "restrictions": [{"shiftId": "NOPE", "weekday": 0, "kind": "NEVER"}]},
                  "P0004": 7,
                  "E9": {"weeklyHours": 30}},
                 "demand": {"ZZ": [], "DR": [{"days": ["MON"], "from": "7", "to": "19:00", "min": 1}]}}""";
        given().contentType(ContentType.JSON).body(file).post("/api/data/corrections/check").then()
                .statusCode(200).body("people", equalTo(4)).body("existing", equalTo(3))
                .body("fields.weeklyHours", equalTo(2)).body("fields.demand", equalTo(0))
                .body("skipped.code", equalTo(List.of("corrections.load.fixedField", "corrections.load.unknownField",
                        "corrections.load.unknownArea", "corrections.load.invalid", "corrections.load.unknownShift",
                        "corrections.load.notObject", "corrections.load.personMissing", "corrections.idFormat",
                        "corrections.demandArea",
                        "corrections.load.demandInvalid")))
                .body("skipped[0].params.field", equalTo("firstName"))
                .body("skipped[1].params.field", nullValue());
        // checking saves nothing
        given().get("/api/data/corrections/state").then().body("loaded", nullValue());
    }

    @Test
    void dispofoldChangesWinPerPersonAndFieldAndTheFileReplacesTheAppliedOnes() throws Exception {
        importFile(TestData.dataset(MONTH));
        edit("P0001", "weeklyHours", 33);
        load(FILE).statusCode(200).body("overridden", equalTo(1)).body("replaced", equalTo(0));
        JsonNode file = effective();
        assertThat(file.path("people").path("P0001").path("weeklyHours").asDouble()).isEqualTo(33);
        assertThat(file.path("people").path("P0001").path("hoursAccount").asDouble()).isEqualTo(5.5);
        assertThat(file.path("people").path("P0002").path("exclude").asBoolean()).isTrue();
        assertThat(file.path("demand").path("AN").size()).isEqualTo(1);
        // the same file goes to the import source
        assertThat(host.corrections().orElseThrow()).isEqualTo(file);
        given().get("/api/data/corrections").then().body("count", equalTo(6)).body("earlier", equalTo(5));
    }

    @Test
    void loadingAgainReplacesAndRemovingGoesBackToTheChangesOfDispofold() throws Exception {
        importFile(TestData.dataset(MONTH));
        load(FILE).statusCode(200);
        load("{\"format\": \"dispofold/corrections\", \"version\": 2, \"people\": {\"P0003\": {\"weeklyHours\": 12}}}")
                .statusCode(200).body("people", equalTo(1));
        JsonNode file = effective();
        assertThat(file.path("people").has("P0001")).isFalse();
        assertThat(file.path("people").path("P0003").path("weeklyHours").asInt()).isEqualTo(12);

        given().delete("/api/data/corrections/loaded").then().statusCode(204);
        given().get("/api/data/corrections/state").then().body("loaded", nullValue()).body("none", equalTo(true));
        assertThat(effective().path("people").size()).isZero();
    }

    @Test
    void anImportThroughASourceTakesTheFileOverAndAFileImportDoesNot() throws Exception {
        importFile(TestData.dataset(MONTH));
        load(FILE).statusCode(200);
        // a plain file import changes nothing about the loaded file
        importFile(TestData.dataset(MONTH));
        given().get("/api/data/corrections/state").then().body("summary.people", equalTo(4));

        // the source applied the file: hours of P0001 from it, and lists it as applied
        ObjectNode applied = (ObjectNode) mapper.readTree("""
                {"format": "dispofold/corrections", "version": 2,
                 "people": {"P0001": {"weeklyHours": 30.0, "hoursAccount": 5.5}}, "demand": {}}""");
        Dataset fromSource = withHours(TestData.dataset(MONTH), "P0001", 30);
        imports.importDataset(fromSource, applied, true, false);
        given().get("/api/data/corrections/state").then().body("loaded", nullValue()).body("none", equalTo(false));

        // cumulative afterwards: the applied ones stay, a later change in Dispofold wins
        edit("P0003", "weeklyHours", 21);
        JsonNode file = effective();
        assertThat(file.path("people").path("P0001").path("weeklyHours").asDouble()).isEqualTo(30);
        assertThat(file.path("people").path("P0003").path("weeklyHours").asDouble()).isEqualTo(21);
        given().get("/api/data/corrections").then().body("earlier", equalTo(2)).body("complete", equalTo(true));

        // a new file replaces the corrections the last import applied: the summary says how many
        load(FILE).statusCode(200).body("replaced", equalTo(2));
    }

    @Test
    void aVersionOneFileWithChangesFromDispofoldBecomesVersionTwoWithoutWhatVersionOneIgnores() throws Exception {
        importFile(TestData.dataset(MONTH));
        String v1 = "{\"format\": \"dispofold/corrections\", \"version\": 1, \"people\": {\"P0001\": "
                + "{\"weeklyHours\": 30, \"exclude\": true}}, \"demand\": {\"AN\": []}}";
        load(v1).statusCode(200).body("fields.exclude", equalTo(0))
                .body("skipped.code", equalTo(List.of("corrections.load.versionIgnored", "corrections.idFormat",
                        "corrections.load.versionIgnored")));
        assertThat(effective()).isEqualTo(mapper.readTree(v1));
        edit("P0003", "weeklyHours", 21);
        JsonNode file = effective();
        assertThat(file.path("version").asInt()).isEqualTo(2);
        assertThat(file.has("demand")).isFalse();
        assertThat(file.path("people").path("P0001").has("exclude")).isFalse();
        assertThat(file.path("people").path("P0001").path("weeklyHours").asInt()).isEqualTo(30);
        assertThat(file.path("people").path("P0003").path("weeklyHours").asDouble()).isEqualTo(21);
    }
}
