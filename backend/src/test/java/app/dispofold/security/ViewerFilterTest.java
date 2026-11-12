package app.dispofold.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.dispofold.api.FakeFacadeProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.Cookie;

/** Viewers get no wishes, restrictions, family rules or absence reasons, and no exports; planners get all. */
@QuarkusTest
@TestProfile(FakeFacadeProfile.class)
class ViewerFilterTest {

    static final String MONTH = "2027-01";

    Cookie planner;
    Cookie viewer;

    @BeforeEach
    void demoData() {
        planner = LoginTest.login("planer", "planpw");
        viewer = LoginTest.login("leser", "geheim");
        given().cookie(planner).post("/api/import/demo").then().statusCode(200);
    }

    @Test
    void datasetWithoutWishesRestrictionsFamilyRules() {
        given().cookie(planner).get("/api/dataset").then().statusCode(200)
                .body("restrictions", not(empty())).body("people.familyRules.flatten()", not(empty()));
        given().cookie(planner).get("/api/dataset").then().body("history.wishes.sum()", org.hamcrest.Matchers.greaterThan(0));
        given().cookie(viewer).get("/api/dataset").then().statusCode(200)
                .body("history", not(empty())).body("history.wishes", everyItem(equalTo(0)))
                .body("history.wishesMet", everyItem(equalTo(0)))
                .body("people", not(empty())).body("restrictions", empty())
                .body("people.familyRules.flatten()", empty())
                .body("periods.wishes.flatten()", empty())
                .body("periods.absences.flatten().typeCode", everyItem(equalTo("ABW")));
    }

    @Test
    void periodWithoutWishesAndWithNeutralAbsences() {
        given().cookie(planner).get("/api/periods/" + MONTH).then().statusCode(200).body("wishes", not(empty()))
                .body("absences.typeCode", hasItem("K"));
        List<String> types = given().cookie(viewer).get("/api/periods/" + MONTH).then().statusCode(200)
                .body("wishes", empty()).body("absences", not(empty())).extract().path("absences.typeCode");
        assertThat(types).containsOnly(ViewerFilter.NEUTRAL_ABSENCE);
    }

    @Test
    void analysisAndExplanationWithoutWishes() {
        given().cookie(planner).get("/api/plans/" + MONTH + "/people/P0001").then().statusCode(200)
                .body("stats.wishes", equalTo(1)).body("hits.constraintId", hasItem("wishes"));
        given().cookie(viewer).get("/api/plans/" + MONTH + "/people/P0001").then().statusCode(200)
                .body("stats.wishes", equalTo(0)).body("stats.wishesMet", equalTo(0)).body("hits", empty())
                .body("stats.personId", equalTo("P0001"));
        List<Map<String, Object>> people = given().cookie(viewer).get("/api/plans/" + MONTH + "/analysis").then()
                .statusCode(200).extract().path("people");
        assertThat(people).isNotEmpty().allSatisfy(p -> assertThat(p).containsEntry("wishes", 0)
                .containsEntry("wishesMet", 0));
    }

    /** No wish, restriction or family rule content and no real absence type anywhere in the answer. */
    static void clean(String json) {
        assertThat(json).doesNotContain("\"kind\"").doesNotContain("\"weekdays\"").doesNotContain("\"latestEnd\"")
                .doesNotContain("\"validFrom\"");
        assertThat(json.replaceAll("\"typeCode\":\"ABW\"", "")).doesNotContain("\"typeCode\"");
        // Personal balances: absent or null wherever they appear.
        for (String field : new String[] { "hoursAccount", "compRestBalance", "hoursAccountAfter",
                "compRestBalanceAfter", "compRestUsed", "compRestEarned", "accountBefore", "accountAfter",
                "chargedHours", "creditedHours" }) {
            assertThat(json).as(field).doesNotContainPattern("\"" + field + "\":(?!null)");
        }
    }

    @Test
    void everyViewerAnswerIsClean() {
        String area = given().cookie(planner).get("/api/dataset").then().extract().path("areas[0].id");
        // The planner sees the details, so the check would notice them.
        assertThat(given().cookie(planner).get("/api/periods/" + MONTH).asString()).contains("\"kind\"");
        String plannerDataset = given().cookie(planner).get("/api/dataset").asString();
        assertThat(plannerDataset).containsPattern("\"hoursAccount\":(?!null)")
                .containsPattern("\"compRestBalanceAfter\":(?!null)");
        assertThat(given().cookie(planner).get("/api/plans/" + MONTH + "/analysis").asString())
                .containsPattern("\"accountAfter\":(?!null)");
        for (String path : new String[] { "/api/dataset", "/api/periods/" + MONTH, "/api/plans/" + MONTH,
                "/api/plans/" + MONTH + "/analysis", "/api/plans/" + MONTH + "/people/P0004",
                "/api/plans/" + MONTH + "/coverage/" + area, "/api/months" }) {
            String body = given().cookie(viewer).get(path).then().statusCode(200).extract().asString();
            clean(body);
        }
        // Other spellings of the same paths: either not found or redacted as well.
        int served = 0;
        for (String path : new String[] { "/api/dataset/", "//api/dataset", "/api/periods/" + MONTH + "/",
                "/api/plans/" + MONTH + "/analysis/", "/api/plans/" + MONTH + "/people/P0004/" }) {
            var r = given().cookie(viewer).get(path);
            assertThat(r.statusCode()).as(path).isIn(200, 404);
            if (r.statusCode() == 200) {
                clean(r.asString());
                served++;
            }
        }
        assertThat(served).as("some spellings reach the resources").isPositive();
    }

    @Test
    void planIsVisibleExportsAreNot() {
        given().cookie(viewer).get("/api/plans/" + MONTH).then().statusCode(200);
        for (String format : new String[] { "json", "csv", "xlsx" }) {
            given().cookie(viewer).get("/api/plans/" + MONTH + "/export?format=" + format).then().statusCode(403)
                    .body("code", equalTo("security.forbidden"));
        }
        given().get("/api/dataset").then().statusCode(401).body("code", equalTo("security.unauthenticated"));
    }

    @Test
    void personEditErrorsHaveCodes() {
        Map<String, Object> p1 = given().cookie(planner).get("/api/dataset").then().extract().path("people[0]");
        given().cookie(planner).contentType("application/json").body(p1).put("/api/dataset/people/P0002").then()
                .statusCode(400).body("code", equalTo("person.idMismatch")).body("params.personId", equalTo("P0002"));
        java.util.HashMap<String, Object> unknown = new java.util.HashMap<>(p1);
        unknown.put("id", "X9999");
        given().cookie(planner).contentType("application/json").body(unknown).put("/api/dataset/people/X9999").then()
                .statusCode(404).body("code", equalTo("person.unknown"));
        java.util.HashMap<String, Object> badArea = new java.util.HashMap<>(p1);
        badArea.put("areaIds", List.of("NOPE"));
        given().cookie(planner).contentType("application/json").body(badArea).put("/api/dataset/people/P0001").then()
                .statusCode(400).body("code", equalTo("person.unknownArea"));
    }

    @Test
    void redaction() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var hit = mapper.createObjectNode().put("constraintId", "maxWeekHours");
        hit.putObject("params").put("typeCode", "K").put("minutes", 3000);
        var e = mapper.createObjectNode();
        e.putObject("stats").put("historyWishes", 4).put("duties", 3);
        e.putArray("hits").add(hit).add(mapper.createObjectNode().put("constraintId", "family"));
        ViewerFilter.explanation(e);
        assertThat(e.toString()).isEqualTo(
                "{\"stats\":{\"historyWishes\":0,\"duties\":3},\"hits\":[{\"constraintId\":\"maxWeekHours\",\"params\":{\"minutes\":3000}}]}");
    }
}
