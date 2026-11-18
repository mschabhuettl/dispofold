package app.dispofold.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.sql.Connection;
import java.sql.Statement;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.model.Dataset;
import app.dispofold.model.Person;
import app.dispofold.store.Store;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;

/**
 * The Daten page's backend: the baseline of every import, the corrections file, the import that keeps the changes
 * made in Dispofold, and the pseudonymised export. Viewers are refused in ViewerFilterTest.
 */
@QuarkusTest
@TestProfile(FakeFacadeProfile.class)
@TestSecurity(user = "planer", roles = "planner")
class DataTest {

    static final YearMonth MONTH = YearMonth.of(2034, 2);

    @Inject
    ObjectMapper mapper;

    @Inject
    DataSource dataSource;

    @Inject
    Store store;

    @BeforeEach
    void importData() throws Exception {
        sql("delete from period", "delete from plan", "delete from history", "delete from dataset");
        importDataset(TestData.dataset(MONTH), "").statusCode(200);
    }

    void sql(String... statements) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.executeUpdate(statement);
            }
        }
    }

    ValidatableResponse importDataset(Dataset d, String query) throws Exception {
        return given().contentType(ContentType.JSON).body(mapper.writeValueAsString(d))
                .post("/api/import/dataset" + query).then();
    }

    /** Changes one field of a person in Dispofold. */
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

    /** The test dataset with some people changed "upstream". */
    static Dataset withHours(Map<String, Double> weeklyHours) {
        Dataset d = TestData.dataset(MONTH);
        List<Person> people = new ArrayList<>();
        for (Person p : d.people()) {
            double h = weeklyHours.getOrDefault(p.id(), p.weeklyHours());
            people.add(new Person(p.id(), p.firstName(), p.lastName(), p.shortName(), h, p.areaIds(),
                    p.hoursAccount(), p.compRestBalance(), p.familyRules(), p.externalId(), p.entryDate(),
                    p.exitDate(), p.shiftMix()));
        }
        return new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(), d.holidays(),
                d.absenceTypes(), people, d.restrictions(), d.history(), d.periods());
    }

    static Dataset master(Dataset d) {
        return new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(), d.holidays(),
                d.absenceTypes(), d.people(), d.restrictions(), d.history(), List.of());
    }

    @Test
    void everyImportStoresItsBaseline() throws Exception {
        assertThat(store.baseline()).contains(master(TestData.dataset(MONTH)));
        given().post("/api/import/demo?replace=true").then().statusCode(200);
        assertThat(store.baseline().orElseThrow().people()).hasSize(150);
    }

    @Test
    void correctionsFileOfTheChanges() throws Exception {
        given().get("/api/data/corrections").then().statusCode(200).body("count", equalTo(0))
                .body("file.format", equalTo("dispofold/corrections")).body("file.version", equalTo(2))
                .body("file.people.size()", equalTo(0));
        edit("P0002", "weeklyHours", 30);
        given().get("/api/data/corrections").then().statusCode(200).body("count", equalTo(1))
                .body("file.people.P0002.weeklyHours", equalTo(30.0f))
                .body("notes.code", hasItem("corrections.idFormat"))
                .body("earlier", equalTo(0)).body("complete", equalTo(false))
                .body(not(containsString("Testperson")));
    }

    /** The test dataset with the section of the corrections its source applied. */
    String withApplied(Dataset d, String applied) throws Exception {
        ObjectNode body = mapper.valueToTree(d);
        body.set("appliedCorrections", mapper.readTree("{\"format\": \"dispofold/corrections\", \"version\": 2, "
                + applied + "}"));
        return body.toString();
    }

    @Test
    void theCorrectionsFileKeepsTheCorrectionsAppliedToTheImport() throws Exception {
        // P0003 corrected and P0007 excluded by the source before this import
        given().contentType(ContentType.JSON).body(withApplied(withHours(Map.of("P0003", 20.0)),
                "\"people\": {\"P0003\": {\"weeklyHours\": 20.0}, \"P0007\": {\"exclude\": true}}, \"demand\": {}"))
                .post("/api/import/dataset?replace=true").then().statusCode(200);
        assertThat(store.appliedCorrections().orElseThrow().path("people").has("P0003")).isTrue();
        assertThat(store.baseline()).contains(master(withHours(Map.of("P0003", 20.0))));
        given().get("/api/data/corrections").then().statusCode(200).body("count", equalTo(2))
                .body("earlier", equalTo(2)).body("complete", equalTo(true))
                .body("file.people.P0003.weeklyHours", equalTo(20.0f))
                .body("file.people.P0007.exclude", equalTo(true));
        edit("P0002", "weeklyHours", 30);
        edit("P0003", "weeklyHours", 25);
        given().get("/api/data/corrections").then().statusCode(200).body("count", equalTo(3))
                .body("earlier", equalTo(1)).body("file.people.P0002.weeklyHours", equalTo(30.0f))
                .body("file.people.P0003.weeklyHours", equalTo(25.0f));

        // an import keeping the changes takes the new import's list; an import without one has none
        given().contentType(ContentType.JSON).body(withApplied(withHours(Map.of("P0002", 30.0, "P0003", 25.0)),
                "\"people\": {\"P0002\": {\"weeklyHours\": 30.0}, \"P0003\": {\"weeklyHours\": 25.0}}"))
                .post("/api/import/dataset?keep=true").then().statusCode(200);
        given().get("/api/data/corrections").then().statusCode(200).body("count", equalTo(2))
                .body("earlier", equalTo(2)).body("complete", equalTo(true));
        importDataset(TestData.dataset(MONTH), "?replace=true").statusCode(200);
        assertThat(store.appliedCorrections()).isEmpty();
        given().get("/api/data/corrections").then().statusCode(200).body("count", equalTo(0))
                .body("complete", equalTo(false));
    }

    @Test
    void withoutBaselineNothingIsGuessed() throws Exception {
        edit("P0002", "weeklyHours", 30);
        sql("update dataset set baseline = null");
        given().get("/api/data/corrections").then().statusCode(409).body("code", equalTo("corrections.noBaseline"));
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(TestData.dataset(MONTH)))
                .post("/api/import/preview").then().statusCode(409).body("code", equalTo("corrections.noBaseline"));
        importDataset(TestData.dataset(MONTH), "?keep=true").statusCode(409)
                .body("code", equalTo("corrections.noBaseline"));
        // replacing still works, and gives a baseline again
        importDataset(TestData.dataset(MONTH), "?replace=true").statusCode(200);
        given().get("/api/data/corrections").then().statusCode(200).body("count", equalTo(0));
    }

    @Test
    void importKeepingTheChanges() throws Exception {
        edit("P0001", "weeklyHours", 30);
        edit("P0002", "hoursAccount", 5);
        long revision = given().get("/api/dataset").then().extract().jsonPath().getLong("revision");
        // upstream: P0001 changed as well (conflict), P0003 changed only there
        Dataset incoming = withHours(Map.of("P0001", 35.0, "P0003", 20.0));

        importDataset(incoming, "").statusCode(409).body("code", equalTo("import.masterChanged"));
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(incoming)).post("/api/import/preview")
                .then().statusCode(200).body("changed", equalTo(true)).body("kept", equalTo(2))
                .body("conflicts.size()", equalTo(1)).body("conflicts[0].personId", equalTo("P0001"))
                .body("conflicts[0].field", equalTo("weeklyHours")).body(not(containsString("Testperson")));
        // the preview writes nothing
        given().get("/api/dataset").then().body("revision", equalTo((int) revision));

        importDataset(incoming, "?keep=true").statusCode(200).body("kept", equalTo(2));
        JsonNode d = mapper.readTree(given().get("/api/dataset").then().extract().asString());
        Map<String, JsonNode> people = new java.util.HashMap<>();
        d.path("people").forEach(p -> people.put(p.path("id").asText(), p));
        assertThat(people.get("P0001").path("weeklyHours").asDouble()).as("Dispofold wins").isEqualTo(30);
        assertThat(people.get("P0002").path("hoursAccount").asDouble()).as("kept").isEqualTo(5);
        assertThat(people.get("P0003").path("weeklyHours").asDouble()).as("new import").isEqualTo(20);
        // the raw import is the new baseline; the kept changes count as changed since the import
        assertThat(store.baseline()).contains(master(incoming));
        assertThat(d.path("changedSinceImport").asBoolean()).isTrue();
        given().get("/api/data/corrections").then().body("count", equalTo(2))
                .body("file.people.P0001.weeklyHours", equalTo(30.0f))
                .body("file.people.P0002.hoursAccount", equalTo(5.0f));
        // so a plain import warns again
        importDataset(incoming, "").statusCode(409).body("code", equalTo("import.masterChanged"));
    }

    @Test
    void previewOfAFullSizeDataset() throws Exception {
        edit("P0001", "weeklyHours", 30);
        String demo = java.nio.file.Files.readString(java.nio.file.Path.of("..", "tests", "fixtures", "synthetic",
                "demo-dataset-2027-01.synthetic.json"));
        // the demo has P0001 with other weekly hours than the baseline: a conflict
        given().contentType(ContentType.JSON).body(demo).post("/api/import/preview").then().statusCode(200)
                .body("changed", equalTo(true)).body("kept", equalTo(1)).body("conflicts.size()", equalTo(1));
    }

    @Test
    void previewListsAddedPeopleAndChangedNames() throws Exception {
        JsonNode d = mapper.readTree(given().get("/api/dataset").then().statusCode(200).extract().asString());
        ObjectNode p = (ObjectNode) d.path("people").get(0).deepCopy();
        String id = p.path("id").asText();
        p.put("lastName", "Umbenannt");
        given().contentType(ContentType.JSON).header("If-Match", d.path("revision").asText()).body(p)
                .put("/api/dataset/people/" + id).then().statusCode(200);
        long revision = given().get("/api/dataset").then().extract().jsonPath().getLong("revision");
        ObjectNode added = p.deepCopy();
        added.remove("id");
        added.put("lastName", "Neu");
        added.put("shortName", "NEU");
        added.putNull("externalId");
        String addedId = given().contentType(ContentType.JSON).header("If-Match", String.valueOf(revision)).body(added)
                .post("/api/dataset/people").then().statusCode(200).extract().jsonPath().getString("key");
        // neither can be kept: the preview keeps listing them
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(TestData.dataset(MONTH)))
                .post("/api/import/preview").then().statusCode(200)
                .body("notes.findAll { it.code == 'corrections.personAdded' }.params.personId", hasItem(addedId))
                .body("notes.findAll { it.code == 'corrections.identity' }.params.field", hasItem("lastName"));
    }

    @Test
    void keepingWithoutChangesIsAPlainImport() throws Exception {
        importDataset(withHours(Map.of("P0003", 20.0)), "?keep=true").statusCode(200).body("kept", equalTo(0));
        given().get("/api/dataset").then().body("changedSinceImport", equalTo(false));
        // changes that the new import already has: nothing left to keep
        edit("P0001", "weeklyHours", 30);
        importDataset(withHours(Map.of("P0001", 30.0)), "?keep=true").statusCode(200);
        given().get("/api/dataset").then().body("changedSinceImport", equalTo(false));
    }

    @Test
    void pseudonymisedDatasetAndPlan() throws Exception {
        JsonNode r = mapper.readTree(given().get("/api/data/anonymized?month=" + MONTH).then().statusCode(200)
                .extract().asString());
        assertThat(r.path("people").asInt()).isEqualTo(6);
        String shared = r.path("dataset").toString() + r.path("plan").toString();
        assertThat(shared).doesNotContain("Testperson").doesNotContain("Vorname").doesNotContain("P0001")
                .contains("\"P001\"");
        assertThat(r.path("dataset").path("periods").get(0).path("absences").get(0).path("personId").asText())
                .isEqualTo("P001");
        assertThat(r.path("key").asText()).startsWith("﻿pseudonym;id;externe_id;nachname;vorname;kuerzel")
                .contains("P001;P0001;1;Testperson1;Vorname1;T1");

        // a shift code equal to a name: refused, naming the place only
        JsonNode d = mapper.readTree(given().get("/api/dataset").then().extract().asString());
        ObjectNode shift = (ObjectNode) d.path("shifts").get(0);
        shift.put("code", "Testperson4");
        given().contentType(ContentType.JSON).header("If-Match", d.path("revision").asText()).body(shift)
                .put("/api/dataset/shifts/" + shift.path("id").asText()).then().statusCode(200);
        given().get("/api/data/anonymized?month=" + MONTH).then().statusCode(409)
                .body("code", equalTo("anonymize.leak")).body("params.places", containsString("shifts[0].code"))
                .body(not(containsString("Testperson")));
        given().get("/api/data/anonymized").then().statusCode(400).body("code", equalTo("request.month"));
    }
}
