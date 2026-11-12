package app.dispofold.solver;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

import java.time.Duration;
import java.time.YearMonth;
import java.util.concurrent.CountDownLatch;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import app.dispofold.model.PlanAssignment;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

/**
 * With the real solver and the real-size month: the solve request returns at once, and while the model of that
 * month is being built other requests (another month, the month list, the dataset) are answered.
 */
@QuarkusTest
@TestSecurity(user = "planer", roles = "planner")
class NonBlockingSolveTest {

    static final YearMonth MONTH = YearMonth.of(2027, 1);
    static final YearMonth OTHER = YearMonth.of(2027, 2);

    @Inject
    ObjectMapper mapper;

    @Test
    void otherUsersAreNotBlockedWhileAMonthIsBuilt() throws Exception {
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(Fixtures.dataset(Fixtures.LARGE)))
                .post("/api/import/dataset").then().statusCode(200).body("people", equalTo(150));
        CountDownLatch gate = new CountDownLatch(1);
        TimefoldPlanningFacade.beforeModel = gate;
        try {
            long started = System.nanoTime();
            given().contentType(ContentType.JSON).body("{\"timeLimitSeconds\": 600}")
                    .post("/api/plans/" + MONTH + "/solve").then().statusCode(202)
                    .body("solving", equalTo(true));
            // The solver thread waits at the gate, so everything below happens during the construction.
            given().get("/api/plans/" + MONTH).then().statusCode(200).body("solving", equalTo(true));
            given().get("/api/months").then().statusCode(200);
            given().get("/api/dataset").then().statusCode(200);
            String cell = mapper.writeValueAsString(new PlanAssignment("P0001", OTHER.atDay(3), null, false, true));
            given().contentType(ContentType.JSON).body(cell).put("/api/plans/" + OTHER + "/cells").then()
                    .statusCode(200);
            given().get("/api/plans/" + OTHER + "/analysis").then().statusCode(200);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(60));

            given().post("/api/plans/" + MONTH + "/stop").then().statusCode(200);
        } finally {
            gate.countDown();
            TimefoldPlanningFacade.beforeModel = null;
        }
        await().atMost(Duration.ofMinutes(2)).until(() -> !given().get("/api/plans/" + MONTH).then().extract()
                .<Boolean> path("solving"));
        given().get("/api/plans/" + MONTH).then().body("plan.status", equalTo("STOPPED"));
    }
}
