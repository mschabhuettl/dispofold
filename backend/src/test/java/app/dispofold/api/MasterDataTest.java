package app.dispofold.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.sql.Connection;
import java.sql.Statement;
import java.time.YearMonth;
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
import app.dispofold.store.Store;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;

/**
 * Master data edited in Dispofold: every kind, the import's checks, removal rules, revisions, export and the import
 * guard. Viewers are refused in RolesTest.
 */
@QuarkusTest
@TestProfile(FakeFacadeProfile.class)
@TestSecurity(user = "planer", roles = "planner")
class MasterDataTest {

    static final YearMonth MONTH = YearMonth.of(2033, 3);

    @Inject
    ObjectMapper mapper;

    @Inject
    DataSource dataSource;

    @Inject
    Store store;

    /** A clean store with the small test dataset (month MONTH), imported once. */
    @BeforeEach
    void importData() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String table : List.of("period", "plan", "history", "dataset")) {
                s.executeUpdate("delete from " + table);
            }
        }
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(TestData.dataset(MONTH)))
                .post("/api/import/dataset").then().statusCode(200);
    }

    long revision() {
        return given().get("/api/dataset").then().statusCode(200).extract().jsonPath().getLong("revision");
    }

    JsonNode dataset() throws Exception {
        return mapper.readTree(given().get("/api/dataset").then().statusCode(200).extract().asString());
    }

    RequestSpecification at(long revision) {
        return given().contentType(ContentType.JSON).header("If-Match", String.valueOf(revision));
    }

    ValidatableResponse create(String kind, Object body) {
        return at(revision()).body(body).post("/api/dataset/" + kind).then();
    }

    ValidatableResponse update(String kind, String key, Object body) {
        return at(revision()).body(body).put("/api/dataset/" + kind + "/" + key).then();
    }

    ValidatableResponse remove(String kind, String key, boolean confirm) {
        return at(revision()).delete("/api/dataset/" + kind + "/" + key + (confirm ? "?confirm=true" : "")).then();
    }

    ObjectNode person(String id) throws Exception {
        for (JsonNode p : dataset().path("people")) {
            if (p.path("id").asText().equals(id)) {
                return (ObjectNode) p;
            }
        }
        throw new AssertionError("no person " + id);
    }

    void closeMonth() {
        given().contentType(ContentType.JSON)
                .body(Map.of("personId", "P0004", "date", MONTH.atDay(5).toString(), "shiftId", "AN-T", "pinned", true))
                .put("/api/plans/" + MONTH + "/cells").then().statusCode(200);
        given().post("/api/plans/" + MONTH + "/finalize").then().statusCode(200);
    }

    @Test
    void everyKindCanBeAddedChangedAndRemoved() throws Exception {
        long start = revision();
        given().get("/api/dataset").then().body("changedSinceImport", equalTo(false));

        Map<String, Object> newPerson = Map.of("id", "TYPED", "firstName", "Neu", "lastName", "Person",
                "shortName", "NP", "weeklyHours", 38.5, "areaIds", List.of("AN"), "hoursAccount", 1.5,
                "compRestBalance", 1, "familyRules", List.of(Map.of("weekdays", List.of(2), "latestEnd", "18:00",
                        "noNights", true, "note", "Kinder")),
                "externalId", "X-1");
        create("people", newPerson).statusCode(200).body("key", equalTo("PN1")).body("item.id", equalTo("PN1"))
                .body("revision", equalTo((int) start + 1));
        ObjectNode p = person("PN1");
        assertThat(p.path("weeklyHours").asDouble()).isEqualTo(38.5);
        assertThat(p.path("familyRules").get(0).path("latestEnd").asText()).isEqualTo("18:00");
        p.put("lastName", "Geändert").put("entryDate", MONTH.atDay(10).toString());
        update("people", "PN1", p).statusCode(200).body("item.lastName", equalTo("Geändert"));
        given().get("/api/dataset").then().body("changedSinceImport", equalTo(true));
        // a new person is used nowhere: removed without asking; the id is never given again
        remove("people", "PN1", false).statusCode(200).body("removed", equalTo(0));
        create("people", newPerson).statusCode(200).body("key", equalTo("PN2"));

        create("areas", Map.of("id", "LF", "name", "Leitstelle Feuer", "color", "#B91C1C")).statusCode(200)
                .body("key", equalTo("LF"));
        Map<String, Object> shift = Map.of("areaId", "LF", "code", "F1", "name", "Feuer Tag", "start", "07:00",
                "end", "19:00", "category", "DAY", "color", "#FDE68A", "unpopular", false);
        create("shifts", shift).statusCode(200).body("key", equalTo("SN1")).body("item.areaId", equalTo("LF"));
        ObjectNode s = (ObjectNode) mapper.valueToTree(shift);
        s.put("id", "SN1").put("name", "Feuer Tag lang");
        update("shifts", "SN1", s).statusCode(200).body("item.name", equalTo("Feuer Tag lang"));
        create("demand", Map.of("areaId", "LF", "days", List.of("MON", "TUE"), "from", "07:00", "to", "19:00",
                "min", 1)).statusCode(200).body("key", equalTo("3"));
        update("demand", "3", Map.of("areaId", "LF", "days", List.of("MON"), "from", "07:00", "to", "19:00",
                "min", 2, "max", 3)).statusCode(200).body("item.max", equalTo(3));
        create("holidays", Map.of("date", MONTH.atDay(20).toString(), "name", "Zusatzfeiertag")).statusCode(200)
                .body("key", equalTo("1"));
        update("holidays", "1", Map.of("date", MONTH.atDay(21).toString(), "name", "Verschoben")).statusCode(200);
        create("absenceTypes", Map.of("code", "K", "name", "Krank", "reducesTarget", true, "chargesAccount", false,
                "compensatoryRest", false, "color", "#E5E7EB")).statusCode(200).body("key", equalTo("K"));
        update("absenceTypes", "K", Map.of("code", "K", "name", "Krankenstand", "reducesTarget", true,
                "chargesAccount", false, "compensatoryRest", false, "color", "#E5E7EB")).statusCode(200);

        JsonNode d = dataset();
        assertThat(d.path("areas").findValuesAsText("id")).contains("LF");
        assertThat(d.path("demand").get(3).path("min").asInt()).isEqualTo(2);
        assertThat(d.path("holidays").get(1).path("name").asText()).isEqualTo("Verschoben");
        assertThat(d.path("absenceTypes").findValuesAsText("name")).contains("Krankenstand");

        remove("holidays", "1", false).statusCode(200);
        remove("demand", "3", false).statusCode(200);
        remove("absenceTypes", "K", false).statusCode(200);
        // an area keeps its shifts until they are removed; its demand and the people's areas go with it
        remove("areas", "LF", true).statusCode(409).body("code", equalTo("masterdata.areaHasShifts"))
                .body("params.count", equalTo(1));
        remove("shifts", "SN1", false).statusCode(200);
        ObjectNode withArea = person("PN2");
        withArea.putArray("areaIds").add("AN").add("LF");
        update("people", "PN2", withArea).statusCode(200);
        remove("areas", "LF", false).statusCode(409).body("code", equalTo("masterdata.inUse"))
                .body("params.count", equalTo(1));
        remove("areas", "LF", true).statusCode(200).body("removed", equalTo(1));
        assertThat(mapper.convertValue(person("PN2").path("areaIds"), List.class)).isEqualTo(List.of("AN"));
        // removed short codes are not given again, existing ones not twice
        create("areas", Map.of("id", "LF", "name", "Wieder", "color", "#000000")).statusCode(400)
                .body("code", equalTo("masterdata.idTaken"));
        create("absenceTypes", Map.of("code", "U", "name", "Doppelt", "reducesTarget", true, "chargesAccount",
                false, "compensatoryRest", false, "color", "#000000")).statusCode(400)
                .body("code", equalTo("masterdata.idTaken"));
        create("areas", Map.of("name", "Ohne Kürzel", "color", "#000000")).statusCode(400)
                .body("code", equalTo("masterdata.idMissing"));
        update("areas", "AN", Map.of("id", "XX", "name", "Anders", "color", "#000000")).statusCode(400)
                .body("code", equalTo("masterdata.idMismatch"));
        update("people", "NOPE", newPerson).statusCode(404).body("code", equalTo("masterdata.unknown"));
        update("holidays", "9", Map.of("date", MONTH.atDay(1).toString(), "name", "x")).statusCode(404);
        create("rooms", Map.of()).statusCode(404).body("code", equalTo("masterdata.kind"));
        create("people", List.of()).statusCode(400).body("code", equalTo("masterdata.format"));
    }

    @Test
    void changesAreCheckedLikeAnImport() throws Exception {
        long before = revision();
        ObjectNode p = person("P0002");
        p.put("weeklyHours", 0).putArray("areaIds").add("NOPE");
        update("people", "P0002", p).statusCode(400).body("code", equalTo("masterdata.invalid"))
                .body("errors.code", hasItem("import.badWeeklyHours")).body("errors.code", hasItem("import.unknownArea"))
                .body(not(org.hamcrest.Matchers.containsString("Vorname2")));
        create("shifts", Map.of("areaId", "AN", "code", "L", "name", "Lang", "start", "06:00", "end", "20:00",
                "unpopular", false)).statusCode(400).body("errors.code", hasItem("import.shiftTooLong"));
        // the same text as the import: the codes of docs/datenformat.md
        create("demand", Map.of("areaId", "AN", "days", List.of(), "from", "07:00", "to", "19:00", "min", 1))
                .statusCode(400).body("errors.code", hasItem("import.badDemand"));
        assertThat(revision()).isEqualTo(before);
        assertThat(person("P0002").path("weeklyHours").asDouble()).isEqualTo(40.0);
    }

    @Test
    void restrictionsAndFamilyRulesBelongToThePerson() throws Exception {
        List<Map<String, Object>> restrictions = List.of(
                Map.of("personId", "OTHER", "shiftId", "DR-N", "weekday", 0, "kind", "NEVER"),
                Map.of("category", "NIGHT", "weekday", 6, "kind", "ON_REQUEST", "validFrom",
                        MONTH.atDay(1).toString(), "validTo", MONTH.atEndOfMonth().toString()));
        at(revision()).body(restrictions).put("/api/dataset/people/P0002/restrictions").then().statusCode(200)
                .body("item.size()", equalTo(2)).body("item.personId", equalTo(List.of("P0002", "P0002")));
        JsonNode d = dataset();
        assertThat(d.path("restrictions").findValuesAsText("personId")).containsExactly("P0002", "P0002");
        at(revision()).body(List.of(Map.of("shiftId", "DR-N", "category", "NIGHT", "weekday", 0, "kind", "NEVER")))
                .put("/api/dataset/people/P0002/restrictions").then().statusCode(400)
                .body("errors.code", hasItem("import.restrictionTarget"));
        at(revision()).body(List.of(Map.of("shiftId", "GONE", "weekday", 9, "kind", "NEVER")))
                .put("/api/dataset/people/P0002/restrictions").then().statusCode(400)
                .body("errors.code", hasItem("import.unknownShift")).body("errors.code", hasItem("import.badWeekday"));
        at(revision()).body(List.of()).put("/api/dataset/people/NOPE/restrictions").then().statusCode(404);
        // removing the shift asks and takes the person's restriction on it along
        remove("shifts", "DR-N", false).statusCode(409).body("code", equalTo("masterdata.inUse"))
                .body("params.count", equalTo(1));
        ObjectNode p = person("P0002");
        p.putArray("familyRules").addObject().put("noNights", true).putArray("weekdays").add(8);
        update("people", "P0002", p).statusCode(400).body("errors.code", hasItem("import.badWeekday"));
    }

    @Test
    void referencesInOpenMonthsAreRemovedAfterConfirmation() throws Exception {
        given().contentType(ContentType.JSON)
                .body(Map.of("personId", "P0002", "date", MONTH.atDay(4).toString(), "shiftId", "DR-T", "pinned", true))
                .put("/api/plans/" + MONTH + "/cells").then().statusCode(200);
        // P0002 has a wish and a plan cell in the open month
        remove("people", "P0002", false).statusCode(409).body("code", equalTo("masterdata.inUse"))
                .body("params.count", equalTo(2)).body("params.months", equalTo(MONTH.toString()));
        assertThat(person("P0002")).isNotNull();
        remove("people", "P0002", true).statusCode(200).body("removed", equalTo(2));
        given().get("/api/periods/" + MONTH).then().body("wishes.size()", equalTo(0))
                .body("absences.size()", equalTo(1));
        given().get("/api/plans/" + MONTH).then().body("plan.assignments.personId", not(hasItem("P0002")));
        // the fixed duty of P0003 uses AN-T: the shift goes with it after confirmation
        remove("shifts", "AN-T", false).statusCode(409).body("params.count", equalTo(1));
        remove("shifts", "AN-T", true).statusCode(200);
        given().get("/api/periods/" + MONTH).then().body("fixed.size()", equalTo(0));
        remove("absenceTypes", "U", false).statusCode(409).body("code", equalTo("masterdata.inUse"));
    }

    @Test
    void whatAClosedMonthUsesStaysAndTheClosedMonthIsUnchanged() throws Exception {
        // P0001 has imported history: suggest the exit date instead
        remove("people", "P0001", true).statusCode(409).body("code", equalTo("masterdata.inUseClosed"))
                .body("params.months", equalTo(MONTH.minusMonths(1).toString()));
        closeMonth();
        String plan = given().get("/api/plans/" + MONTH).then().extract().path("plan.assignments").toString();
        var history = store.history();
        remove("people", "P0004", true).statusCode(409).body("code", equalTo("masterdata.inUseClosed"))
                .body("params.months", equalTo(MONTH.toString()));
        remove("absenceTypes", "U", true).statusCode(409).body("code", equalTo("masterdata.inUseClosed"));
        remove("shifts", "AN-T", true).statusCode(409).body("code", equalTo("masterdata.inUseClosed"));

        // an open month planned before the change shows "Eingaben geändert"; the closed month stays as it was
        YearMonth next = MONTH.plusMonths(1);
        given().contentType(ContentType.JSON)
                .body(Map.of("personId", "P0004", "date", next.atDay(5).toString(), "shiftId", "AN-T", "pinned", true))
                .put("/api/plans/" + next + "/cells").then().statusCode(200);
        given().contentType(ContentType.JSON).body("{}").post("/api/plans/" + next + "/solve").then().statusCode(202);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                .until(() -> !given().get("/api/plans/" + next).then().extract().<Boolean> path("solving"));
        given().get("/api/plans/" + next).then().body("stale", equalTo(false));
        ObjectNode p = person("P0004");
        p.put("weeklyHours", 30.0);
        update("people", "P0004", p).statusCode(200);
        given().get("/api/plans/" + next).then().body("stale", equalTo(true));
        given().get("/api/plans/" + MONTH).then().body("stale", equalTo(false)).body("plan.status", equalTo("CLOSED"));
        assertThat(given().get("/api/plans/" + MONTH).then().extract().path("plan.assignments").toString())
                .isEqualTo(plan);
        assertThat(store.history()).isEqualTo(history);
    }

    @Test
    void peopleAndShiftsStayWhileAMonthIsSolving() throws Exception {
        create("people", Map.of("firstName", "Neu", "lastName", "Person", "weeklyHours", 40, "areaIds",
                List.of("AN"), "hoursAccount", 0, "compRestBalance", 0)).statusCode(200);
        FakePlanningFacade.solveMillis = 3000;
        try {
            given().contentType(ContentType.JSON).body("{}").post("/api/plans/" + MONTH + "/solve").then()
                    .statusCode(202);
            remove("people", "PN1", true).statusCode(409).body("code", equalTo("plan.solving"));
            remove("shifts", "DR-N", true).statusCode(409).body("code", equalTo("plan.solving"));
        } finally {
            FakePlanningFacade.solveMillis = 100;
            org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10))
                    .until(() -> !given().get("/api/plans/" + MONTH).then().extract().<Boolean> path("solving"));
        }
        remove("people", "PN1", true).statusCode(200);
    }

    @Test
    void aChangeBasedOnAnOldRevisionConflicts() throws Exception {
        long read = revision();
        ObjectNode p = person("P0003");
        p.put("weeklyHours", 35.0);
        at(read).body(p).put("/api/dataset/people/P0003").then().statusCode(200);
        p.put("weeklyHours", 20.0);
        at(read).body(p).put("/api/dataset/people/P0003").then().statusCode(409)
                .body("code", equalTo("masterdata.conflict")).body("params.revision", equalTo((int) read + 1));
        at(read).delete("/api/dataset/holidays/0").then().statusCode(409);
        given().contentType(ContentType.JSON).body(p).put("/api/dataset/people/P0003").then().statusCode(428)
                .body("code", equalTo("masterdata.revisionMissing"));
        assertThat(person("P0003").path("weeklyHours").asDouble()).isEqualTo(35.0);
        // an import counts up the revision too
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(TestData.dataset(MONTH)))
                .post("/api/import/dataset?replace=true").then().statusCode(200);
        at(read + 1).body(p).put("/api/dataset/people/P0003").then().statusCode(409);
    }

    @Test
    void exportedDatasetImportsToTheSameData() throws Exception {
        ObjectNode p = person("P0005");
        p.put("lastName", "Nachher");
        update("people", "P0005", p).statusCode(200);
        closeMonth();
        String first = given().get("/api/dataset/export").then().statusCode(200)
                .header("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"dispofold-datensatz-"))
                .extract().asString();
        Dataset exported = mapper.readValue(first, Dataset.class);
        assertThat(exported.version()).isEqualTo(Dataset.VERSION);
        assertThat(exported.periods()).hasSize(1);
        JsonNode master = dataset();
        // the closed month comes back unchanged and stays closed
        given().contentType(ContentType.JSON).body(first).post("/api/import/dataset?replace=true").then()
                .statusCode(200);
        String second = given().get("/api/dataset/export").then().statusCode(200).extract().asString();
        assertThat(mapper.readTree(second)).isEqualTo(mapper.readTree(first));
        JsonNode again = dataset();
        ((ObjectNode) master).remove(List.of("revision", "changedSinceImport"));
        ((ObjectNode) again).remove(List.of("revision", "changedSinceImport"));
        assertThat(again).isEqualTo(master);
        given().get("/api/plans/" + MONTH).then().body("plan.status", equalTo("CLOSED"));
        // a closed month with other inputs is still refused
        ObjectNode changed = (ObjectNode) mapper.readTree(first);
        ((ObjectNode) changed.path("periods").get(0)).putArray("wishes");
        given().contentType(ContentType.JSON).body(changed).post("/api/import/dataset?replace=true").then()
                .statusCode(409).body("code", equalTo("import.closed"));
    }

    @Test
    void importAsksBeforeReplacingChangedMasterData() throws Exception {
        String dataset = mapper.writeValueAsString(TestData.dataset(MONTH));
        given().contentType(ContentType.JSON).body(dataset).post("/api/import/dataset").then().statusCode(200);
        ObjectNode p = person("P0006");
        p.put("shortName", "XX");
        update("people", "P0006", p).statusCode(200);
        given().get("/api/dataset").then().body("changedSinceImport", equalTo(true));
        given().contentType(ContentType.JSON).body(dataset).post("/api/import/dataset").then().statusCode(409)
                .body("code", equalTo("import.masterChanged"));
        given().post("/api/import/demo").then().statusCode(409).body("code", equalTo("import.masterChanged"));
        assertThat(person("P0006").path("shortName").asText()).isEqualTo("XX");
        // an invalid file is rejected as such, without asking first
        given().contentType(ContentType.JSON).body("{\"format\":\"other\",\"version\":2}")
                .post("/api/import/dataset").then().statusCode(400).body("code", equalTo("import.invalid"));
        given().contentType(ContentType.JSON).body(dataset).post("/api/import/dataset?replace=true").then()
                .statusCode(200);
        given().get("/api/dataset").then().body("changedSinceImport", equalTo(false));
        assertThat(person("P0006").path("shortName").asText()).isEqualTo("T6");
    }
}
