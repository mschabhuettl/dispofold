package app.dispofold.plugins.sp5;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The option "Bereiche einer Person aus": new settings start with "reserve", stored ones keep their meaning. */
class Sp5SettingsTest {

    final ObjectMapper m = new ObjectMapper();

    @Test
    void newSettingsTakeTheReserveStoredOnesKeepTheGroups() throws Exception {
        assertThat(Sp5Settings.withDefaults(m, null).get("options").get("areaSource").asText()).isEqualTo("reserve");
        // stored before the option, with areas from history at 3 and shifts from history at 1: as before
        JsonNode stored = m.readTree("""
                {"root": "", "path": "", "mapping": null,
                 "options": {"historyMonths": 12, "areasFromHistory": 3, "shiftsFromHistory": 1}}""");
        JsonNode o = Sp5Settings.withDefaults(m, stored).get("options");
        assertThat(o.get("areaSource").asText()).isEqualTo("groups");
        assertThat(o.get("areasFromHistory").asInt()).isEqualTo(3);
        assertThat(o.get("shiftsFromHistory").asInt()).isEqualTo(1);
        // stored without options at all: the groups too
        assertThat(Sp5Settings.withDefaults(m, m.readTree("{\"root\": \"\"}")).get("options").get("areaSource").asText())
                .isEqualTo("groups");
        // a saved choice stays
        JsonNode chosen = m.readTree("{\"options\": {\"areaSource\": \"history\", \"historyAreaDuties\": 5}}");
        assertThat(Sp5Settings.checked(m, chosen).get("options").get("historyAreaDuties").asInt()).isEqualTo(5);
        for (String bad : List.of("{\"areaSource\": \"alle\"}", "{\"areaSource\": 1}", "{\"historyAreaDuties\": 0}")) {
            JsonNode body = m.readTree("{\"options\": " + bad + "}");
            assertThatThrownBy(() -> Sp5Settings.checked(m, body)).as(bad).isNotNull();
        }
    }
}
