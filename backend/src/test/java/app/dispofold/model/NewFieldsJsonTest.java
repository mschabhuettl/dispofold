package app.dispofold.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;

/**
 * The fields added for the own average limit, the free days inside an absence and the averaging period: missing in
 * documents saved before them, they read as null (the defaults) and are not written back, so that such documents
 * and their fingerprints stay the same.
 */
class NewFieldsJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void rulesSavedBeforeTheNewFieldsKeepTheirShape() throws Exception {
        ObjectNode json = MAPPER.valueToTree(Rules.defaults());
        assertThat(json.has("averageTolerancePercent")).isFalse();
        assertThat(json.has("freeInsideAbsence")).isFalse();
        assertThat(json.has("averagingPeriod")).isFalse();
        Rules old = MAPPER.treeToValue(json, Rules.class);
        assertThat(old.averageTolerancePercent()).isNull();
        assertThat(old.averageTolerance()).isEqualTo(Rules.DEFAULT_AVERAGE_TOLERANCE);
        assertThat(old.freeDaysInsideAbsence()).isTrue();
        assertThat(old.averaging()).isEqualTo(AveragingPeriod.HALF_YEAR);
        assertThat(MAPPER.writeValueAsString(old)).isEqualTo(MAPPER.writeValueAsString(Rules.defaults()));

        json.put("averageTolerancePercent", 0.0).put("freeInsideAbsence", false).put("averagingPeriod", "ROLLING");
        Rules set = MAPPER.treeToValue(json, Rules.class);
        assertThat(set.averageTolerance()).isZero();
        assertThat(set.freeDaysInsideAbsence()).isFalse();
        assertThat(set.averaging()).isEqualTo(AveragingPeriod.ROLLING);
    }

    @Test
    void historyWithoutWeeklyHoursHasNoSuchField() throws Exception {
        HistoryEntry without = new HistoryEntry("P1", "2027-01", HistoryEntry.IMPORT, 1, 0, 0, 0, 0, 0, 160, null,
                null, 0, 0);
        assertThat(MAPPER.valueToTree(without).has("weeklyHours")).isFalse();
        HistoryEntry with = new HistoryEntry("P1", "2027-01", HistoryEntry.PLAN, 1, 0, 0, 0, 0, 0, 160, null, null,
                0, 0, 30.0);
        assertThat(MAPPER.treeToValue(MAPPER.valueToTree(with), HistoryEntry.class)).isEqualTo(with);
    }
}
