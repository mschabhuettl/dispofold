package app.dispofold.security;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.api.TestData;
import app.dispofold.model.Person;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.http.Cookie;

/**
 * With the real solver: the hits of a plan by constraint are the hits of the person explanations, for planners and
 * (with the same constraints left out) for viewers.
 */
@QuarkusTest
class PlanHitsTest {

    static final YearMonth MONTH = YearMonth.of(2034, 3);

    @Inject
    ObjectMapper mapper;

    @Test
    void hitsOfThePlanAreThoseOfThePeople() throws Exception {
        Cookie planner = LoginTest.login("planer", "planpw");
        Cookie viewer = LoginTest.login("leser", "geheim");
        given().cookie(planner).contentType(ContentType.JSON)
                .body(mapper.writeValueAsString(TestData.dataset(MONTH))).post("/api/import/dataset?replace=true")
                .then().statusCode(200);
        given().cookie(planner).contentType(ContentType.JSON).body("{\"seed\": 1, \"timeLimitSeconds\": 3}")
                .post("/api/plans/" + MONTH + "/solve").then().statusCode(202);
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500)).until(() -> !given()
                .cookie(planner).get("/api/plans/" + MONTH).then().extract().<Boolean> path("solving"));

        Map<String, List<Map<String, Object>>> all = hits(planner, "");
        // the planner sees constraints that viewers do not, so the viewer check below has something to catch
        assertThat(all.keySet()).anyMatch(ViewerFilter.HIDDEN_CONSTRAINTS::contains);
        same(all, personHits(planner));

        Map<String, List<Map<String, Object>>> visible = hits(viewer, "");
        assertThat(visible).isNotEmpty();
        assertThat(visible.keySet()).noneMatch(ViewerFilter.HIDDEN_CONSTRAINTS::contains);
        same(visible, personHits(viewer));
        ViewerFilterTest.clean(given().cookie(viewer).get("/api/plans/" + MONTH + "/hits").asString());

        // one constraint
        String id = visible.keySet().iterator().next();
        same(hits(planner, "?constraint=" + id), Map.of(id, all.get(id)));
        String hidden = all.keySet().stream().filter(ViewerFilter.HIDDEN_CONSTRAINTS::contains).findFirst()
                .orElseThrow();
        assertThat(hits(viewer, "?constraint=" + hidden)).isEmpty();
        assertThat(hits(planner, "?constraint=unknown")).isEmpty();
    }

    private static Map<String, List<Map<String, Object>>> hits(Cookie who, String query) {
        return given().cookie(who).get("/api/plans/" + MONTH + "/hits" + query).then().statusCode(200).extract()
                .path("$");
    }

    /** The hits of every person explanation by constraint, each with its person id. */
    private static Map<String, List<Map<String, Object>>> personHits(Cookie who) {
        Map<String, List<Map<String, Object>>> union = new LinkedHashMap<>();
        for (Person p : TestData.dataset(MONTH).people()) {
            List<Map<String, Object>> hits = given().cookie(who).get("/api/plans/" + MONTH + "/people/" + p.id())
                    .then().statusCode(200).extract().path("hits");
            for (Map<String, Object> hit : hits) {
                Map<String, Object> withPerson = new LinkedHashMap<>(hit);
                withPerson.put("personId", p.id());
                union.computeIfAbsent((String) hit.get("constraintId"), k -> new ArrayList<>()).add(withPerson);
            }
        }
        return union;
    }

    private static void same(Map<String, List<Map<String, Object>>> actual,
            Map<String, List<Map<String, Object>>> expected) {
        assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());
        expected.forEach((id, hits) -> assertThat(actual.get(id)).as(id).containsExactlyInAnyOrderElementsOf(hits));
    }
}
