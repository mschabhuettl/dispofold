package app.dispofold.plugins.sp5;

import java.util.Map;

import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.plugin.api.PluginErrors;

/**
 * The plugin's settings (version 1):
 * {@code {"root", "path", "mapping", "options": {historyMonths, areasFromHistory, shiftsFromHistory, demand,
 * mixMinDuties, shiftMix, hiddenShiftsMonths, recentMonths}}}. The options are those of sp5_export.py with its
 * defaults ({@code --no-shift-mix} is {@code shiftMix: false}).
 */
final class Sp5Settings {

    static final int MAX_HISTORY_MONTHS = 24;

    private Sp5Settings() {
    }

    static ObjectNode defaults(ObjectMapper mapper) {
        ObjectNode s = mapper.createObjectNode();
        s.put("root", "");
        s.put("path", "");
        s.putNull("mapping");
        ObjectNode o = s.putObject("options");
        o.put("historyMonths", 12);
        o.putNull("areasFromHistory");
        o.putNull("shiftsFromHistory");
        o.put("demand", "history");
        o.put("mixMinDuties", 10);
        o.put("shiftMix", true);
        o.put("hiddenShiftsMonths", 3);
        o.put("recentMonths", 3);
        return s;
    }

    /** The stored settings with every missing key at its default. */
    static ObjectNode withDefaults(ObjectMapper mapper, JsonNode stored) {
        ObjectNode s = defaults(mapper);
        if (stored != null && stored.isObject()) {
            stored.fields().forEachRemaining(e -> {
                if (e.getKey().equals("options")) {
                    if (e.getValue().isObject()) {
                        e.getValue().fields().forEachRemaining(o -> ((ObjectNode) s.get("options"))
                                .set(o.getKey(), o.getValue()));
                    }
                } else if (s.has(e.getKey())) {
                    s.set(e.getKey(), e.getValue());
                }
            });
        }
        return s;
    }

    /** Checks settings a planner saves; the mapping is checked by the port as sp5_export.py checks it. */
    static ObjectNode checked(ObjectMapper mapper, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw invalid("options");
        }
        if (body.has("options") && !body.get("options").isObject()) {
            throw invalid("options");
        }
        ObjectNode s = withDefaults(mapper, body);
        if (!s.get("root").isTextual() || !s.get("path").isTextual()) {
            throw invalid("folder");
        }
        JsonNode o = s.get("options");
        if (!intIn(o.get("historyMonths"), 0, MAX_HISTORY_MONTHS)) {
            throw invalid("historyMonths");
        }
        for (String k : new String[] { "areasFromHistory", "shiftsFromHistory" }) {
            if (!o.get(k).isNull() && !intIn(o.get(k), 1, Integer.MAX_VALUE)) {
                throw invalid(k);
            }
        }
        if (!o.get("demand").isTextual() || !(o.get("demand").asText().equals("history")
                || o.get("demand").asText().equals("sp5"))) {
            throw invalid("demand");
        }
        for (String k : new String[] { "mixMinDuties" }) {
            if (!intIn(o.get(k), 1, Integer.MAX_VALUE)) {
                throw invalid(k);
            }
        }
        for (String k : new String[] { "hiddenShiftsMonths", "recentMonths" }) {
            if (!intIn(o.get(k), 0, Integer.MAX_VALUE)) {
                throw invalid(k);
            }
        }
        if (!o.get("shiftMix").isBoolean()) {
            throw invalid("shiftMix");
        }
        if (!s.get("mapping").isNull() && !s.get("mapping").isObject()) {
            throw invalid("mapping");
        }
        return s;
    }

    private static boolean intIn(JsonNode n, int min, int max) {
        return n.isIntegralNumber() && n.canConvertToInt() && n.asInt() >= min && n.asInt() <= max;
    }

    static jakarta.ws.rs.WebApplicationException invalid(String field) {
        return PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.setting", Map.of("field", field),
                "Einstellung ungültig: " + field);
    }
}
