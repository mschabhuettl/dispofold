package app.dispofold.api;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import jakarta.inject.Inject;

import app.dispofold.model.Rules;
import app.dispofold.solver.ConstraintCatalog;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Rules saved before version 0.28 or 0.29 hold every tunable weight: the changed weights that stand at the old
 * defaults (or at the old defaults times one Wichtigkeit level of their goal) move to the new ones once; weights set
 * by hand stay.
 */
@QuarkusTest
@TestProfile(FakeFacadeProfile.class)
@TestSecurity(user = "planer", roles = "planner")
class RuleWeightsMigrationTest {

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper mapper;

    @AfterEach
    void defaults() throws Exception {
        storeOld(Rules.defaults(), 2);
    }

    /** Stores rules as a version before 0.28 did. */
    void storeOld(Rules rules, int version) throws Exception {
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement d = c.prepareStatement("delete from rules")) {
                d.executeUpdate();
            }
            try (PreparedStatement i = c.prepareStatement(
                    "insert into rules (id, doc, weights_version) values (1, ?, ?)")) {
                i.setString(1, mapper.writeValueAsString(rules));
                i.setInt(2, version);
                i.executeUpdate();
            }
        }
    }

    static Rules withWeights(Map<String, Long> weights) {
        return Rules.defaults().withWeights(weights);
    }

    static Map<String, Long> oldWeights(long block, long dayOff) {
        Map<String, Long> w = new LinkedHashMap<>();
        w.put("wishes", 300L);
        w.put("blockLength", block);
        w.put("isolatedDayOff", dayOff);
        w.put("forwardRotation", 700L);
        return w;
    }

    @Test
    void untouchedWeightsTakeTheNewDefaultsOnce() throws Exception {
        storeOld(withWeights(oldWeights(6000, 10000)), 1);
        given().get("/api/rules").then().statusCode(200)
                .body("weights.blockLength", equalTo(18000)).body("weights.isolatedDayOff", equalTo(30000))
                .body("weights.forwardRotation", equalTo(700)).body("weights.wishes", equalTo(300));
        // Set by hand to the old value after the upgrade: it stays.
        String rules = given().get("/api/rules").then().extract().asString().replace("18000", "6000");
        given().contentType(ContentType.JSON).body(rules).put("/api/rules").then().statusCode(200);
        given().get("/api/rules").then().body("weights.blockLength", equalTo(6000));
        // The new rules have no stored weight, so their defaults apply (this profile's fake solver has its own
        // catalogue, so the real one is asked).
        given().get("/api/rules").then().body("weights.dayNightSwitch", org.hamcrest.Matchers.nullValue());
        assertThat(ConstraintCatalog.weight(ConstraintCatalog.get(ConstraintCatalog.DAY_NIGHT_SWITCH), Map.of()))
                .isEqualTo(10000);
        assertThat(ConstraintCatalog.get(ConstraintCatalog.DAY_NIGHT_SWITCHES).defaultWeight()).isEqualTo(30000);
        assertThat(ConstraintCatalog.get(ConstraintCatalog.NIGHT_BLOCK).defaultWeight()).isEqualTo(6000);
    }

    @Test
    void aWichtigkeitLevelStaysThatLevel() throws Exception {
        storeOld(withWeights(oldWeights(12000, 20000)), 1);
        given().get("/api/rules").then().body("weights.blockLength", equalTo(36000))
                .body("weights.isolatedDayOff", equalTo(60000));
    }

    @Test
    void weightsSetByHandStay() throws Exception {
        storeOld(withWeights(oldWeights(6000, 15000)), 1);
        given().get("/api/rules").then().body("weights.blockLength", equalTo(6000))
                .body("weights.isolatedDayOff", equalTo(15000));
    }

    @Test
    void upgradeKeepsOtherWeightsAndEmptyMaps() {
        assertThat(ConstraintCatalog.upgradeWeights(Map.of(), 1)).isEmpty();
        assertThat(ConstraintCatalog.upgradeWeights(Map.of("wishes", 5L), 1)).containsExactlyEntriesOf(Map.of("wishes", 5L));
        assertThat(ConstraintCatalog.upgradeWeights(Map.of("blockLength", 3000L), 1))
                .containsExactlyEntriesOf(Map.of("blockLength", 9000L));
        // saved by 0.28: the block weights are not touched again
        assertThat(ConstraintCatalog.upgradeWeights(Map.of("blockLength", 6000L), 2))
                .containsExactlyEntriesOf(Map.of("blockLength", 6000L));
    }

    /** The weights the rules page of 0.28 saved, with the night goal at one level and the usual distribution. */
    static Map<String, Long> weights028(double nightFactor, long mix) {
        Map<String, Long> w = new LinkedHashMap<>();
        w.put("blockLength", 18000L);
        w.put("isolatedDayOff", 30000L);
        w.put("dayNightSwitch", Math.round(10000 * nightFactor));
        w.put("dayNightSwitches", Math.round(3000 * nightFactor));
        w.put("nightBlock", Math.round(6000 * nightFactor));
        w.put("shiftMix", mix);
        return w;
    }

    @Test
    void weightsOf028TakeTheNewDefaultsOnce() throws Exception {
        storeOld(withWeights(weights028(1, 100)), 2);
        given().get("/api/rules").then().statusCode(200)
                .body("weights.dayNightSwitches", equalTo(30000)).body("weights.shiftMix", equalTo(100))
                .body("weights.dayNightSwitch", equalTo(10000)).body("weights.nightBlock", equalTo(6000))
                .body("weights.blockLength", equalTo(18000));
        // saved again: version 3, the old value set by hand stays
        String rules = given().get("/api/rules").then().extract().asString()
                .replace("\"dayNightSwitches\":30000", "\"dayNightSwitches\":3000");
        given().contentType(ContentType.JSON).body(rules).put("/api/rules").then().statusCode(200);
        given().get("/api/rules").then().body("weights.dayNightSwitches", equalTo(3000));
    }

    @Test
    void theNightGoalKeepsItsLevelAndHandWeightsStay() throws Exception {
        storeOld(withWeights(weights028(2, 80)), 2);
        given().get("/api/rules").then().body("weights.dayNightSwitches", equalTo(60000))
                .body("weights.nightBlock", equalTo(12000)).body("weights.shiftMix", equalTo(80));
        // one weight of the goal set by hand: the goal is no level, its weights stay
        Map<String, Long> hand = weights028(1, 100);
        hand.put("nightBlock", 7000L);
        storeOld(withWeights(hand), 2);
        given().get("/api/rules").then().body("weights.dayNightSwitches", equalTo(3000))
                .body("weights.shiftMix", equalTo(100));
    }
}
