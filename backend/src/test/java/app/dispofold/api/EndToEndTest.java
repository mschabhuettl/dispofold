package app.dispofold.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;

import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/** With the real solver: import a small synthetic dataset, solve with a short limit, analyse and export. */
@QuarkusTest
@TestSecurity(user = "planer", roles = "planner")
class EndToEndTest {

    static final YearMonth MONTH = YearMonth.of(2033, 2);

    @Inject
    ObjectMapper mapper;

    @Test
    void importSolveAnalyse() throws Exception {
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(TestData.dataset(MONTH)))
                .post("/api/import/dataset").then().statusCode(200);
        given().get("/api/constraints").then().statusCode(200).body("size()", greaterThan(0));

        given().contentType(ContentType.JSON).body("{\"seed\": 3, \"timeLimitSeconds\": 5}")
                .post("/api/plans/" + MONTH + "/solve").then().statusCode(202);
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .until(() -> !given().get("/api/plans/" + MONTH).then().extract().<Boolean> path("solving"));

        given().get("/api/plans/" + MONTH).then().statusCode(200).body("plan.status", equalTo("SOLVED"))
                .body("plan.score", notNullValue()).body("plan.seed", equalTo(3));
        List<Map<String, Object>> cells = given().get("/api/plans/" + MONTH).then().extract().path("plan.assignments");
        assertThat(cells).hasSize(6 * MONTH.lengthOfMonth());
        // the fixed duty of the period is kept
        assertThat(cells).anyMatch(c -> "P0003".equals(c.get("personId")) && "2033-02-01".equals(c.get("date"))
                && "AN-T".equals(c.get("shiftId")));

        given().get("/api/plans/" + MONTH + "/analysis").then().statusCode(200).body("score", notNullValue())
                .body("people.size()", equalTo(6));
        given().get("/api/plans/" + MONTH + "/people/P0001").then().statusCode(200)
                .body("stats.personId", equalTo("P0001"));
        given().get("/api/plans/" + MONTH + "/coverage/DR").then().statusCode(200);
        given().get("/api/plans/" + MONTH + "/export?format=xlsx").then().statusCode(200);
        given().post("/api/plans/" + MONTH + "/finalize").then().statusCode(200).body("size()", equalTo(6));
    }
}
