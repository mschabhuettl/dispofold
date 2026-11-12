package app.dispofold.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.time.YearMonth;
import java.util.List;

import jakarta.inject.Inject;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import app.dispofold.model.Dataset;
import app.dispofold.store.Store;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;

/** Viewers see the plan but no health data, wishes or restrictions, and get no exports. */
@QuarkusTest
@TestProfile(FakeFacadeProfile.class)
@TestSecurity(user = "leser", roles = "viewer")
class ViewerTest {

    static final YearMonth MONTH = YearMonth.of(2031, 5);

    @Inject
    Store store;

    @BeforeEach
    void data() {
        Dataset d = TestData.dataset(MONTH);
        store.saveDataset(new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), d.people(), d.restrictions(), d.history(), List.of()));
        store.savePeriod(d.periods().get(0));
    }

    @Test
    void viewersSeeNoHealthDataAndGetNoExports() {
        given().get("/api/periods/" + MONTH).then().statusCode(200).body("absences[0].typeCode", equalTo("ABW"))
                .body("wishes.size()", equalTo(0));
        given().get("/api/plans/" + MONTH + "/export?format=csv").then().statusCode(403);
        given().get("/api/plans/" + MONTH + "/people/P0002").then().statusCode(200)
                .body("hits.code", not(hasItem("hit.wishes"))).body("stats.wishes", equalTo(0));
        given().get("/api/plans/" + MONTH + "/analysis").then().statusCode(200)
                .body("people[0].wishes", equalTo(0)).body("people[0].chargedHours", nullValue())
                .body("people[0].accountAfter", nullValue()).body("people[0].accountBefore", nullValue())
                .body("people[0].compRestBalanceAfter", nullValue()).body("people[0].compRestEarned", nullValue())
                .body("people[0].creditedHours", nullValue());
    }

    @Test
    @TestSecurity(user = "planer", roles = "planner")
    void plannersSeeTheBalances() {
        given().get("/api/plans/" + MONTH + "/analysis").then().statusCode(200)
                .body("people[0].accountAfter", notNullValue()).body("people[0].chargedHours", notNullValue())
                .body("people[0].wishes", equalTo(1));
        given().get("/api/periods/" + MONTH).then().statusCode(200).body("absences[0].typeCode", equalTo("U"));
    }
}
