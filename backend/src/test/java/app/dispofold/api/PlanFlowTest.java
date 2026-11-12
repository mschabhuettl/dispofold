package app.dispofold.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.model.HistoryEntry;
import app.dispofold.model.Plan;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.PlanStatus;
import app.dispofold.model.PreviousDuty;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

@QuarkusTest
@TestProfile(FakeFacadeProfile.class)
@TestSecurity(user = "planer", roles = "planner")
class PlanFlowTest {

    static final YearMonth MONTH = YearMonth.of(2031, 3);

    @Inject
    ObjectMapper mapper;

    @Inject
    FakePlanningFacade facade;

    @ConfigProperty(name = "app.solver.move-limit")
    long defaultMoveLimit;

    @ConfigProperty(name = "app.solver.time-limit-seconds")
    int defaultTimeLimit;

    @BeforeEach
    void importData() throws Exception {
        FakePlanningFacade.solveMillis = 100;
        FakePlanningFacade.fail = false;
        FakePlanningFacade.finalHardViolations = 0;
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(TestData.dataset(MONTH)))
                .post("/api/import/dataset").then().statusCode(200)
                .body("people", equalTo(6)).body("months", equalTo(List.of(MONTH.toString())));
    }

    @AfterEach
    void waitForSolver() {
        await().atMost(Duration.ofSeconds(10)).until(() -> !facade.isSolving(MONTH) && !solving());
    }

    boolean solving() {
        return given().get("/api/plans/" + MONTH).then().extract().<Boolean> path("solving");
    }

    Plan plan() throws Exception {
        return mapper.convertValue(given().get("/api/plans/" + MONTH).then().statusCode(200).extract()
                .jsonPath().getMap("plan"), Plan.class);
    }

    @Test
    void solveEditUnpinFinalize() throws Exception {
        given().contentType(ContentType.JSON).body("{\"seed\": 42, \"timeLimitSeconds\": 5}")
                .post("/api/plans/" + MONTH + "/solve").then().statusCode(202).body("solving", equalTo(true))
                .body("plan.status", equalTo("SOLVING"));
        assertThat(facade.lastOptions().seed()).isEqualTo(42);
        assertThat(facade.lastOptions().timeLimitSeconds()).isEqualTo(5);
        // reproducible by default: a move limit from the configuration, no stop on missing improvement
        assertThat(facade.lastOptions().moveLimit()).isEqualTo(defaultMoveLimit);
        assertThat(facade.lastOptions().unimprovedSeconds()).isEqualTo(defaultMoveLimit > 0 ? 0 : 30);
        await().atMost(Duration.ofSeconds(10)).until(() -> !solving());

        Plan solved = plan();
        assertThat(solved.status()).isEqualTo(PlanStatus.SOLVED);
        assertThat(solved.seed()).isEqualTo(42);
        assertThat(solved.score()).isEqualTo("0hard/0medium/-5soft");
        assertThat(solved.assignments()).hasSize(6 * 31);
        // history of the dataset reaches the solver
        assertThat(facade.lastInput().dataset().history()).extracting(HistoryEntry::personId).contains("P0001");

        // a planner sets a cell, then releases the pins
        PlanAssignment cell = new PlanAssignment("P0002", MONTH.atDay(5), "DR-N", false, true);
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(cell))
                .put("/api/plans/" + MONTH + "/cells").then().statusCode(200);
        assertThat(plan().assignments()).contains(cell);
        // planner pins on a day fixed by the period stay pinned
        PlanAssignment fixed = new PlanAssignment("P0003", MONTH.atDay(1), "AN-T", false, true);
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(fixed))
                .put("/api/plans/" + MONTH + "/cells").then().statusCode(200);
        given().post("/api/plans/" + MONTH + "/unpin").then().statusCode(200);
        assertThat(plan().assignments()).contains(fixed)
                .contains(new PlanAssignment("P0002", MONTH.atDay(5), "DR-N", false, false));

        given().get("/api/plans/" + MONTH + "/analysis").then().statusCode(200)
                .body("people.personId", hasItem("P0002"));
        given().get("/api/plans/" + MONTH + "/people/P0002").then().statusCode(200)
                .body("stats.personId", equalTo("P0002"));
        given().get("/api/plans/" + MONTH + "/people/P9999").then().statusCode(404);
        given().get("/api/plans/" + MONTH + "/coverage/DR").then().statusCode(200).body("[0].areaId", equalTo("DR"));
        given().get("/api/plans/" + MONTH + "/coverage/XX").then().statusCode(404);

        // other tests may have left a plan of the previous month; it has to be closed first
        boolean previousClosed = given().post("/api/plans/" + MONTH.minusMonths(1) + "/finalize").then().extract()
                .statusCode() == 200;
        given().post("/api/plans/" + MONTH + "/finalize").then().statusCode(200)
                .body("find { it.personId == 'P0002' }.nights", equalTo(1))
                .body("find { it.personId == 'P0002' }.source", equalTo("plan"));
        // a closed month is read-only until it is reopened
        given().get("/api/plans/" + MONTH).then().body("plan.status", equalTo("CLOSED"));
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(cell))
                .put("/api/plans/" + MONTH + "/cells").then().statusCode(409).body("code", equalTo("plan.closed"));
        given().post("/api/plans/" + MONTH + "/solve").then().statusCode(409).body("code", equalTo("plan.closed"));
        given().post("/api/plans/" + MONTH + "/unpin").then().statusCode(409);
        given().post("/api/plans/" + MONTH + "/finalize").then().statusCode(409);
        // finalized history is part of the next planning input
        given().contentType(ContentType.JSON).body("{}").post("/api/plans/" + MONTH.plusMonths(1) + "/solve")
                .then().statusCode(202);
        assertThat(facade.lastInput().dataset().history())
                .anyMatch(h -> h.personId().equals("P0002") && h.month().equals(MONTH.toString()));
        assertThat(facade.lastOptions().seed()).isEqualTo(1);
        assertThat(facade.lastOptions().timeLimitSeconds()).isEqualTo(defaultTimeLimit);
        await().atMost(Duration.ofSeconds(10)).until(() -> !facade.isSolving(MONTH.plusMonths(1)));
        // the closed month's plan gives the next month's previous duties
        assertThat(facade.lastInput().period().previousDuties())
                .contains(new PreviousDuty("P0002", MONTH.atDay(5), "DR-N"));

        given().post("/api/plans/" + MONTH + "/reopen").then().statusCode(200).body("plan.status", equalTo("STOPPED"));
        given().post("/api/plans/" + MONTH + "/reopen").then().statusCode(409).body("code", equalTo("plan.notClosed"));
        if (previousClosed) {
            given().post("/api/plans/" + MONTH.minusMonths(1) + "/reopen").then().statusCode(200);
        }
    }

    /** The revision of the stored period, for a save based on it. */
    long revision() {
        return given().get("/api/periods/" + MONTH).then().statusCode(200).extract().jsonPath().getLong("revision");
    }

    @Test
    void staleSolveAndChangedPreviousMonthAreShown() throws Exception {
        given().contentType(ContentType.JSON).body("{}").post("/api/plans/" + MONTH + "/solve").then()
                .statusCode(202);
        await().atMost(Duration.ofSeconds(10)).until(() -> !solving());
        given().get("/api/plans/" + MONTH).then().body("stale", equalTo(false))
                .body("previousMonthChanged", equalTo(false)).body("previousFromPlan", equalTo(false));

        // the planning month changes after the solve: the plan is stale until it is solved again
        String period = "{\"month\":null,\"absences\":[{\"personId\":\"P0004\",\"date\":\"" + MONTH.atDay(12)
                + "\",\"typeCode\":\"U\"}],\"wishes\":[],\"fixed\":[],\"previousDuties\":[]}";
        given().contentType(ContentType.JSON).header("If-Match", revision()).body(period)
                .put("/api/periods/" + MONTH).then().statusCode(200);
        given().get("/api/plans/" + MONTH).then().body("stale", equalTo(true));
        given().contentType(ContentType.JSON).body("{}").post("/api/plans/" + MONTH + "/solve").then()
                .statusCode(202);
        await().atMost(Duration.ofSeconds(10)).until(() -> !solving());
        given().get("/api/plans/" + MONTH).then().body("stale", equalTo(false));

        // the previous month gets a plan afterwards: its duties become the previous duties, and the change shows
        PlanAssignment before = new PlanAssignment("P0002", MONTH.minusMonths(1).atEndOfMonth(), "DR-N", false,
                true);
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(before))
                .put("/api/plans/" + MONTH.minusMonths(1) + "/cells").then().statusCode(200);
        given().get("/api/plans/" + MONTH).then().body("previousMonthChanged", equalTo(true))
                .body("previousFromPlan", equalTo(true)).body("stale", equalTo(true));
        given().contentType(ContentType.JSON).body("{}").post("/api/plans/" + MONTH + "/solve").then()
                .statusCode(202);
        assertThat(facade.lastInput().period().previousDuties())
                .containsExactly(new PreviousDuty("P0002", MONTH.minusMonths(1).atEndOfMonth(), "DR-N"));
        await().atMost(Duration.ofSeconds(10)).until(() -> !solving());
        given().get("/api/plans/" + MONTH).then().body("previousMonthChanged", equalTo(false));
    }

    @Test
    void coldStartKeepsOnlyThePins() throws Exception {
        given().contentType(ContentType.JSON).body("{}").post("/api/plans/" + MONTH + "/solve").then()
                .statusCode(202);
        await().atMost(Duration.ofSeconds(10)).until(() -> !solving());
        PlanAssignment pinned = new PlanAssignment("P0002", MONTH.atDay(5), "DR-N", false, true);
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(pinned))
                .put("/api/plans/" + MONTH + "/cells").then().statusCode(200);

        given().contentType(ContentType.JSON).body("{}").post("/api/plans/" + MONTH + "/solve").then()
                .statusCode(202);
        assertThat(facade.lastInput().assignments()).hasSize(6 * 31);
        await().atMost(Duration.ofSeconds(10)).until(() -> !solving());

        given().contentType(ContentType.JSON).body("{\"coldStart\": true}").post("/api/plans/" + MONTH + "/solve")
                .then().statusCode(202);
        assertThat(facade.lastInput().assignments()).isNotEmpty().contains(pinned)
                .allSatisfy(a -> assertThat(a.pinned()).isTrue());
    }

    @Test
    void exportsAreBlockedWhileSolving() throws Exception {
        FakePlanningFacade.solveMillis = 60_000;
        FakePlanningFacade.finalHardViolations = 2;
        given().post("/api/plans/" + MONTH + "/solve").then().statusCode(202);
        given().get("/api/plans/" + MONTH + "/export?format=csv").then().statusCode(409)
                .body("code", equalTo("plan.solving"));
        given().post("/api/plans/" + MONTH + "/stop").then().statusCode(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> !solving());
        // The plan breaks hard rules: the export is marked.
        given().get("/api/plans/" + MONTH + "/export?format=csv").then().statusCode(200)
                .header("Content-Disposition", containsString("mit-verstoessen"))
                .body(containsString("harte Regelverstöße"));
    }

    @Test
    void aNewMonthSavedTwiceFromTheSameStateConflicts() {
        YearMonth empty = YearMonth.of(2040, 2);
        given().get("/api/periods/" + empty).then().body("revision", equalTo(0));
        String period = "{\"month\":null,\"absences\":[],\"wishes\":[],\"fixed\":[],\"previousDuties\":[]}";
        given().contentType(ContentType.JSON).header("If-Match", 0).body(period).put("/api/periods/" + empty).then()
                .statusCode(200).body("revision", equalTo(1));
        given().contentType(ContentType.JSON).header("If-Match", 0).body(period).put("/api/periods/" + empty).then()
                .statusCode(409).body("code", equalTo("period.conflict"));
    }

    @Test
    void monthsAreClosedInOrderAndReopenedBackwards() throws Exception {
        YearMonth previous = MONTH.minusMonths(1);
        PlanAssignment before = new PlanAssignment("P0002", previous.atDay(10), "DR-N", false, true);
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(before))
                .put("/api/plans/" + previous + "/cells").then().statusCode(200);
        given().contentType(ContentType.JSON).body("{}").post("/api/plans/" + MONTH + "/solve").then()
                .statusCode(202);
        await().atMost(Duration.ofSeconds(10)).until(() -> !solving());

        // the open previous month must be closed first
        given().post("/api/plans/" + MONTH + "/finalize").then().statusCode(409)
                .body("code", equalTo("plan.previousOpen"));
        given().post("/api/plans/" + previous + "/finalize").then().statusCode(200);
        given().post("/api/plans/" + MONTH + "/finalize").then().statusCode(200);
        given().get("/api/plans/" + MONTH).then().body("stale", equalTo(false));
        // reopening goes backwards
        given().post("/api/plans/" + previous + "/reopen").then().statusCode(409)
                .body("code", equalTo("plan.nextClosed"));
        given().post("/api/plans/" + MONTH + "/reopen").then().statusCode(200);
        given().post("/api/plans/" + previous + "/reopen").then().statusCode(200);
        // an unpin in the previous month changes no duty
        given().post("/api/plans/" + previous + "/unpin").then().statusCode(200);
        given().get("/api/plans/" + MONTH).then().body("previousMonthChanged", equalTo(false));
    }

    @Test
    void aCellPinnedByAPlannerKeepsItsValueWhenTheFixedDutyChanges() throws Exception {
        given().contentType(ContentType.JSON).body("{}").post("/api/plans/" + MONTH + "/solve").then()
                .statusCode(202);
        await().atMost(Duration.ofSeconds(10)).until(() -> !solving());
        // P0003 has a fixed duty on day 1; the planner pins another value there
        PlanAssignment own = new PlanAssignment("P0003", MONTH.atDay(1), null, false, true);
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(own))
                .put("/api/plans/" + MONTH + "/cells").then().statusCode(200);
        String period = "{\"month\":null,\"absences\":[],\"wishes\":[],\"previousDuties\":[],"
                + "\"fixed\":[{\"personId\":\"P0003\",\"date\":\"" + MONTH.atDay(1) + "\",\"shiftId\":\"DR-T\"}]}";
        given().contentType(ContentType.JSON).header("If-Match", revision()).body(period)
                .put("/api/periods/" + MONTH).then().statusCode(200);
        assertThat(plan().assignments()).contains(own);
    }

    @Test
    void moveLimitMakesTheRunReproducible() {
        given().contentType(ContentType.JSON).body("{\"seed\": 7, \"moveLimit\": 5000}")
                .post("/api/plans/" + MONTH + "/solve").then().statusCode(202);
        assertThat(facade.lastOptions().moveLimit()).isEqualTo(5000);
        assertThat(facade.lastOptions().unimprovedSeconds()).isZero();
    }

    @Test
    void changedFixedDutiesReachThePlan() throws Exception {
        given().contentType(ContentType.JSON).body("{}").post("/api/plans/" + MONTH + "/solve").then()
                .statusCode(202);
        await().atMost(Duration.ofSeconds(10)).until(() -> !solving());

        // the fixed duty of P0003 moves from day 1 to day 2
        String period = "{\"month\":null,\"absences\":[],\"wishes\":[],\"previousDuties\":[],"
                + "\"fixed\":[{\"personId\":\"P0003\",\"date\":\"" + MONTH.atDay(2)
                + "\",\"shiftId\":\"AN-T\"}]}";
        given().contentType(ContentType.JSON).header("If-Match", revision()).body(period)
                .put("/api/periods/" + MONTH).then().statusCode(200);
        assertThat(plan().assignments())
                .contains(new PlanAssignment("P0003", MONTH.atDay(2), "AN-T", false, true))
                .noneMatch(a -> a.personId().equals("P0003") && a.date().equals(MONTH.atDay(1)) && a.pinned());
    }

    @Test
    void editsAreLockedWhileSolvingAndStopKeepsBest() throws Exception {
        FakePlanningFacade.solveMillis = 60_000;
        given().post("/api/plans/" + MONTH + "/solve").then().statusCode(202);
        given().post("/api/plans/" + MONTH + "/solve").then().statusCode(409);
        PlanAssignment cell = new PlanAssignment("P0002", MONTH.atDay(5), null, false, true);
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(cell))
                .put("/api/plans/" + MONTH + "/cells").then().statusCode(409).body("error", containsString("läuft"));
        given().post("/api/plans/" + MONTH + "/unpin").then().statusCode(409);
        given().post("/api/plans/" + MONTH + "/finalize").then().statusCode(409);
        given().contentType(ContentType.JSON).body(mapper.writeValueAsString(plan())).post("/api/plans/" + MONTH
                + "/import").then().statusCode(409);
        // progress is persisted while solving (at most every 2 s)
        await().atMost(Duration.ofSeconds(10)).until(() -> "0hard/-10medium/-5soft".equals(plan().score()));
        given().get("/api/plans/" + MONTH).then().body("plan.status", equalTo("SOLVING"))
                .body("hardViolations", equalTo(1));

        given().post("/api/plans/" + MONTH + "/stop").then().statusCode(200);
        await().atMost(Duration.ofSeconds(10)).until(() -> !solving());
        assertThat(plan().status()).isEqualTo(PlanStatus.STOPPED);
        assertThat(plan().assignments()).hasSize(6 * 31);
    }

    @Test
    void failureMarksPlanFailed() {
        FakePlanningFacade.fail = true;
        given().post("/api/plans/" + MONTH + "/solve").then().statusCode(202);
        await().atMost(Duration.ofSeconds(10)).until(() -> !solving());
        given().get("/api/plans/" + MONTH).then().body("plan.status", equalTo("FAILED"));
    }

    @Test
    void invalidCellsAreRejected() throws Exception {
        for (PlanAssignment bad : List.of(new PlanAssignment("P9999", MONTH.atDay(1), null, false, true),
                new PlanAssignment("P0001", LocalDate.of(2031, 4, 1), null, false, true),
                new PlanAssignment("P0001", MONTH.atDay(1), "XX", false, true),
                new PlanAssignment("P0001", MONTH.atDay(1), "DR-T", true, true))) {
            given().contentType(ContentType.JSON).body(mapper.writeValueAsString(bad))
                    .put("/api/plans/" + MONTH + "/cells").then().statusCode(400);
        }
        given().get("/api/plans/2031-13").then().statusCode(400);
    }

    @Test
    void monthsPeriodsRulesConstraints() throws Exception {
        given().get("/api/months").then().statusCode(200)
                .body("find { it.month == '" + MONTH + "' }.hasPeriod", equalTo(true));
        given().get("/api/periods/" + MONTH).then().statusCode(200).body("absences[0].typeCode", equalTo("U"));
        given().get("/api/periods/2040-01").then().statusCode(200).body("month", equalTo("2040-01"))
                .body("absences.size()", equalTo(0));
        String invalid = "{\"month\":null,\"absences\":[{\"personId\":\"P0004\",\"date\":\"2031-03-10\","
                + "\"typeCode\":\"U\"},{\"personId\":\"P9999\",\"date\":\"2031-03-10\",\"typeCode\":\"U\"}],"
                + "\"wishes\":[],\"fixed\":[],\"previousDuties\":[]}";
        long revision = revision();
        // unknown people are rejected, not dropped
        given().contentType(ContentType.JSON).header("If-Match", revision).body(invalid).put("/api/periods/" + MONTH)
                .then().statusCode(400).body("code", equalTo("period.invalid"))
                .body("errors.code", hasItem("import.unknownPerson"));
        String period = "{\"month\":null,\"absences\":[{\"personId\":\"P0004\",\"date\":\"2031-03-10\","
                + "\"typeCode\":\"U\"}],\"wishes\":[],\"fixed\":[],\"previousDuties\":[],\"revision\":" + revision
                + "}";
        given().contentType(ContentType.JSON).body(period).put("/api/periods/" + MONTH).then().statusCode(200)
                .body("month", equalTo(MONTH.toString())).body("absences.size()", equalTo(1))
                .body("revision", equalTo((int) revision + 1));
        // a save based on an old revision is a conflict, a save without a revision is refused
        given().contentType(ContentType.JSON).header("If-Match", revision).body(period.replace(",\"revision\":"
                + revision, "")).put("/api/periods/" + MONTH).then().statusCode(409)
                .body("code", equalTo("period.conflict"));
        given().contentType(ContentType.JSON).body(period.replace(",\"revision\":" + revision, ""))
                .put("/api/periods/" + MONTH).then().statusCode(428).body("code", equalTo("period.revisionMissing"));

        given().get("/api/rules").then().statusCode(200).body("minDailyRestHours", equalTo(11.0f))
                .body("nightStart", equalTo("22:00"));
        String rules = given().get("/api/rules").then().extract().asString()
                .replace("\"weights\":{}", "\"weights\":{\"wishes\":77}");
        given().contentType(ContentType.JSON).body(rules).put("/api/rules").then().statusCode(200);
        given().get("/api/constraints").then().statusCode(200)
                .body("find { it.id == 'wishes' }.weight", equalTo(77))
                .body("find { it.id == 'rest' }.weight", equalTo(1));
        given().contentType(ContentType.JSON).body(rules.replace("\"weights\":{\"wishes\":77}", "\"weights\":{}"))
                .put("/api/rules").then().statusCode(200);

        String person = given().get("/api/dataset").then().statusCode(200).body("periods.size()", equalTo(0))
                .extract().jsonPath().prettify();
        String p1 = mapper.writeValueAsString(mapper.readTree(person).path("people").get(0));
        given().contentType(ContentType.JSON).body(p1.replace("\"weeklyHours\":40.0", "\"weeklyHours\":30.0"))
                .put("/api/dataset/people/P0001").then().statusCode(200).body("weeklyHours", equalTo(30.0f));
        given().get("/api/dataset").then().body("people[0].weeklyHours", equalTo(30.0f));
        given().contentType(ContentType.JSON).body(p1).put("/api/dataset/people/P0002").then().statusCode(400);
    }
}
