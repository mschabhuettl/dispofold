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
 * Rules saved before version 0.28, 0.29 or 0.30 hold every tunable weight: the changed weights that stand at the old
 * defaults (or at the old defaults times one Wichtigkeit level of their goal) move to the new ones once; weights set
 * by hand stay. Before 0.30 the tolerance around the target at its old default 0 moves to the new default too.
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
                .body("weights.blockLength", equalTo(36000)).body("weights.isolatedDayOff", equalTo(60000))
                .body("weights.forwardRotation", equalTo(700)).body("weights.wishes", equalTo(300));
        // Set by hand to the old value after the upgrade: it stays.
        String rules = given().get("/api/rules").then().extract().asString().replace("36000", "6000");
        given().contentType(ContentType.JSON).body(rules).put("/api/rules").then().statusCode(200);
        given().get("/api/rules").then().body("weights.blockLength", equalTo(6000));
        // The new rules have no stored weight, so their defaults apply (this profile's fake solver has its own
        // catalogue, so the real one is asked).
        given().get("/api/rules").then().body("weights.dayNightSwitch", org.hamcrest.Matchers.nullValue());
        assertThat(ConstraintCatalog.weight(ConstraintCatalog.get(ConstraintCatalog.DAY_NIGHT_SWITCH), Map.of()))
                .isEqualTo(20000);
        assertThat(ConstraintCatalog.get(ConstraintCatalog.DAY_NIGHT_SWITCHES).defaultWeight()).isEqualTo(60000);
        assertThat(ConstraintCatalog.get(ConstraintCatalog.NIGHT_BLOCK).defaultWeight()).isEqualTo(12000);
    }

    @Test
    void aWichtigkeitLevelStaysThatLevel() throws Exception {
        storeOld(withWeights(oldWeights(12000, 20000)), 1);
        given().get("/api/rules").then().body("weights.blockLength", equalTo(72000))
                .body("weights.isolatedDayOff", equalTo(120000));
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
                .containsExactlyEntriesOf(Map.of("blockLength", 18000L));
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
                .body("weights.dayNightSwitches", equalTo(60000)).body("weights.shiftMix", equalTo(25))
                .body("weights.dayNightSwitch", equalTo(20000)).body("weights.nightBlock", equalTo(12000))
                .body("weights.blockLength", equalTo(36000));
        // saved again: the current version, the old value set by hand stays
        String rules = given().get("/api/rules").then().extract().asString()
                .replace("\"dayNightSwitches\":60000", "\"dayNightSwitches\":3000");
        given().contentType(ContentType.JSON).body(rules).put("/api/rules").then().statusCode(200);
        given().get("/api/rules").then().body("weights.dayNightSwitches", equalTo(3000));
    }

    @Test
    void theNightGoalKeepsItsLevelAndHandWeightsStay() throws Exception {
        storeOld(withWeights(weights028(2, 80)), 2);
        given().get("/api/rules").then().body("weights.dayNightSwitches", equalTo(120000))
                .body("weights.nightBlock", equalTo(24000)).body("weights.shiftMix", equalTo(80));
        // one weight of the goal set by hand: the goal is no level, its weights stay
        Map<String, Long> hand = weights028(1, 100);
        hand.put("nightBlock", 7000L);
        storeOld(withWeights(hand), 2);
        given().get("/api/rules").then().body("weights.dayNightSwitches", equalTo(3000))
                .body("weights.shiftMix", equalTo(25));
    }

    /** The rules the page of 0.29 saved: every weight and the tolerance around the target, 0 then. */
    static Rules rules029(long mix, Integer tolerance) {
        Map<String, Long> w = weights028(1, mix);
        w.put("dayNightSwitches", 30000L);
        w.put("overMinimum", 2000L);
        Rules d = Rules.defaults().withWeights(w);
        return d.withTargetToleranceHours(tolerance);
    }

    @Test
    void rulesOf029TakeTheNewMixWeightAndToleranceOnce() throws Exception {
        storeOld(rules029(100, 0), 3);
        given().get("/api/rules").then().statusCode(200).body("weights.shiftMix", equalTo(25))
                .body("weights.dayNightSwitches", equalTo(60000)).body("targetToleranceHours",
                        org.hamcrest.Matchers.nullValue());
        assertThat(Rules.defaults().targetTolerance()).isEqualTo(8);
        // saved again (version 4): 0 and 100 set by hand stay
        String rules = given().get("/api/rules").then().extract().asString().replace("\"shiftMix\":25",
                "\"shiftMix\":100").replaceFirst("\\{", "{\"targetToleranceHours\":0,");
        given().contentType(ContentType.JSON).body(rules).put("/api/rules").then().statusCode(200);
        given().get("/api/rules").then().body("weights.shiftMix", equalTo(100)).body("targetToleranceHours",
                equalTo(0));
    }

    @Test
    void valuesOf029SetByHandStay() throws Exception {
        storeOld(rules029(60, 4), 3);
        given().get("/api/rules").then().body("weights.shiftMix", equalTo(60)).body("targetToleranceHours",
                equalTo(4));
        // a tolerance of 0 with a weight set by hand: the tolerance is no weight and moves on its own
        assertThat(ConstraintCatalog.upgradeRules(rules029(60, 0), 3).targetToleranceHours()).isNull();
        assertThat(ConstraintCatalog.upgradeRules(rules029(60, 0), 4).targetToleranceHours()).isEqualTo(0);
    }

    @Test
    void blockAndNightWeightsOf030TakeTheNewDefaultsOnce() throws Exception {
        // saved by 0.30 at "normal" and at "weniger"; one set by hand
        storeOld(rules029(25, null), 4);
        given().get("/api/rules").then().statusCode(200).body("weights.blockLength", equalTo(36000))
                .body("weights.isolatedDayOff", equalTo(60000)).body("weights.shiftMix", equalTo(25))
                .body("weights.dayNightSwitch", equalTo(20000)).body("weights.dayNightSwitches", equalTo(60000))
                .body("weights.nightBlock", equalTo(12000));
        Map<String, Long> less = weights028(1, 25);
        less.put("blockLength", 9000L);
        less.put("isolatedDayOff", 15000L);
        storeOld(withWeights(less), 4);
        given().get("/api/rules").then().body("weights.blockLength", equalTo(18000))
                .body("weights.isolatedDayOff", equalTo(30000));
        Map<String, Long> hand = weights028(1, 25);
        hand.put("blockLength", 20000L);
        storeOld(withWeights(hand), 4);
        given().get("/api/rules").then().body("weights.blockLength", equalTo(20000))
                .body("weights.isolatedDayOff", equalTo(30000));
        // saved by this version: the old defaults set by hand stay
        storeOld(withWeights(weights028(1, 25)), ConstraintCatalog.WEIGHTS_VERSION);
        given().get("/api/rules").then().body("weights.blockLength", equalTo(18000));
        // the new rules of this version have no stored weight: their defaults apply
        assertThat(ConstraintCatalog.get(ConstraintCatalog.PERSON_SHIFTS).defaultWeight()).isEqualTo(30000);
        assertThat(ConstraintCatalog.get(ConstraintCatalog.PERSON_LENGTHS).defaultWeight()).isEqualTo(100000);
    }
}
