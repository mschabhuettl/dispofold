package app.dispofold.plugins.sp5;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.plugin.api.PluginErrors;

/**
 * The plugin's settings (version 1):
 * {@code {"root", "path", "mapping", "options": {historyMonths, areasFromHistory, shiftsFromHistory, demand,
 * mixMinDuties, shiftMix, hiddenShiftsMonths, recentMonths, nightsFair, areaSource, historyAreaDuties}}}. The options
 * are those of sp5_export.py with its defaults ({@code --no-shift-mix} is {@code shiftMix: false}); {@code nightsFair}
 * (default off) marks night shifts whose mapping rule leaves {@code unpopular} empty, as sp5_export.py always does.
 * {@code areaSource} (Dispofold only) is where a person's areas come from: "groups" (as sp5_export.py, with
 * areasFromHistory adding areas), "history" or "reserve" (the areas with at least historyAreaDuties duties, the group
 * areas below that left out or kept on request; areasFromHistory then has no effect). New settings start with
 * "reserve"; stored settings from before the option keep "groups", so that they compute as before. {@code "proposals":
 * {share, minDuties, months, restWords, discarded, familyShare, familyMinDuties, familyRatio, ignoreUntimed}} are the settings of the mapping proposals ({@link Sp5Proposals});
 * the discarded keys change only through their own routes, a save keeps the stored ones.
 */
final class Sp5Settings {

    static final int MAX_HISTORY_MONTHS = 24;
    static final List<String> AREA_SOURCES = List.of("groups", "history", "reserve");

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
        o.put("nightsFair", false);
        o.put("areaSource", "reserve");
        o.put("historyAreaDuties", 3);
        ObjectNode p = s.putObject("proposals");
        p.put("share", 80);
        p.put("minDuties", 3);
        p.put("months", 0);
        p.putArray("restWords").add("Ersatzruhe");
        p.putArray("discarded");
        p.put("familyShare", 70);
        p.put("familyMinDuties", 2);
        p.put("familyRatio", 50);
        p.put("ignoreUntimed", true);
        return s;
    }

    /**
     * The stored settings with every missing key at its default; settings stored before the option areaSource (any
     * saved settings: an empty object is what switching the plugin on stores) keep the areas from the groups.
     */
    static ObjectNode withDefaults(ObjectMapper mapper, JsonNode stored) {
        ObjectNode s = defaults(mapper);
        if (stored != null && stored.isObject()) {
            if (stored.size() > 0 && !stored.path("options").has("areaSource")) {
                ((ObjectNode) s.get("options")).put("areaSource", "groups");
            }
            stored.fields().forEachRemaining(e -> {
                if (e.getKey().equals("options") || e.getKey().equals("proposals")) {
                    if (e.getValue().isObject()) {
                        e.getValue().fields().forEachRemaining(o -> ((ObjectNode) s.get(e.getKey()))
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
        for (String k : new String[] { "options", "proposals" }) {
            if (body.has(k) && !body.get(k).isObject()) {
                throw invalid(k);
            }
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
        if (!o.get("nightsFair").isBoolean()) {
            throw invalid("nightsFair");
        }
        if (!o.get("areaSource").isTextual() || !AREA_SOURCES.contains(o.get("areaSource").asText())) {
            throw invalid("areaSource");
        }
        if (!intIn(o.get("historyAreaDuties"), 1, Integer.MAX_VALUE)) {
            throw invalid("historyAreaDuties");
        }
        JsonNode p = s.get("proposals");
        if (!intIn(p.get("share"), 1, 100)) {
            throw invalid("proposals.share");
        }
        if (!intIn(p.get("minDuties"), 0, Integer.MAX_VALUE)) {
            throw invalid("proposals.minDuties");
        }
        if (!intIn(p.get("months"), 0, 1200)) {
            throw invalid("proposals.months");
        }
        for (String k : new String[] { "familyShare", "familyRatio" }) {
            if (!intIn(p.get(k), 1, 100)) {
                throw invalid("proposals." + k);
            }
        }
        if (!intIn(p.get("familyMinDuties"), 1, Integer.MAX_VALUE)) {
            throw invalid("proposals.familyMinDuties");
        }
        if (!p.get("ignoreUntimed").isBoolean()) {
            throw invalid("proposals.ignoreUntimed");
        }
        for (String k : new String[] { "restWords", "discarded" }) {
            if (!p.get(k).isArray()) {
                throw invalid("proposals." + k);
            }
            for (JsonNode w : p.get(k)) {
                if (!w.isTextual() || w.asText().isBlank()) {
                    throw invalid("proposals." + k);
                }
            }
        }
        if (!s.get("mapping").isNull() && !s.get("mapping").isObject()) {
            throw invalid("mapping");
        }
        return s;
    }

    /** The settings of the proposals. */
    static Sp5Proposals.Settings proposals(JsonNode settings) {
        JsonNode p = settings.get("proposals");
        List<String> words = new ArrayList<>();
        p.get("restWords").forEach(w -> words.add(w.asText()));
        Set<String> discarded = new LinkedHashSet<>();
        p.get("discarded").forEach(w -> discarded.add(w.asText()));
        return new Sp5Proposals.Settings(p.get("share").asInt(), p.get("minDuties").asInt(), p.get("months").asInt(),
                words, discarded, p.get("familyShare").asInt(), p.get("familyMinDuties").asInt(),
                p.get("familyRatio").asInt(), p.get("ignoreUntimed").asBoolean());
    }

    private static boolean intIn(JsonNode n, int min, int max) {
        return n.isIntegralNumber() && n.canConvertToInt() && n.asInt() >= min && n.asInt() <= max;
    }

    /** The settings as the pages name them, for the error text (param "label"; "field" stays the key). */
    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("options", "Optionen"),
            Map.entry("proposals", "Einstellungen der Vorschläge"),
            Map.entry("folder", "Ordner"),
            Map.entry("historyMonths", "Monate Historie (0 bis " + MAX_HISTORY_MONTHS + ")"),
            Map.entry("areasFromHistory", "Bereiche aus der Historie ab … Diensten (leer oder ab 1)"),
            Map.entry("shiftsFromHistory", "Schichten seltener als … Dienste nur auf Anfrage (leer oder ab 1)"),
            Map.entry("demand", "Bedarf"),
            Map.entry("mixMinDuties", "Gewohnte Verteilung ab … Diensten (ab 1)"),
            Map.entry("hiddenShiftsMonths", "Ausgeblendete Schichten mit Diensten in den … Monaten davor (ab 0)"),
            Map.entry("recentMonths", "Bereiche ohne Dienst in den letzten … Monaten nur auf Anfrage (ab 0)"),
            Map.entry("areaSource", "Bereiche einer Person aus"),
            Map.entry("historyAreaDuties", "Bereich aus der Historie ab … Diensten (ab 1)"),
            Map.entry("shiftMix", "Gewohnte Verteilung übernehmen"),
            Map.entry("nightsFair", "Nachtschichten automatisch fair verteilen"),
            Map.entry("proposals.share", "Anteil für eine Schicht (1 bis 100 %)"),
            Map.entry("proposals.minDuties", "Seltene Schicht: weniger Dienste als (ab 0)"),
            Map.entry("proposals.months", "Zeitraum in Monaten (0 bis 1200)"),
            Map.entry("proposals.restWords", "Wörter für die Ersatzruhe (keines leer)"),
            Map.entry("proposals.discarded", "Verworfene Vorschläge"),
            Map.entry("proposals.familyShare", "Schichtfamilien zusammenlegen ab (1 bis 100 %)"),
            Map.entry("proposals.familyMinDuties", "Arbeitet eine Familie ab … Diensten (ab 1)"),
            Map.entry("proposals.familyRatio", "Schichtnamen empfehlen unter (1 bis 100 %)"),
            Map.entry("proposals.ignoreUntimed", "Schichten ohne Zeitraum und Bedarf ignorieren"),
            Map.entry("mapping", "Zuordnung"),
            Map.entry("key", "Vorschlag"));

    static jakarta.ws.rs.WebApplicationException invalid(String field) {
        return PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.setting",
                Map.of("field", field, "label", LABELS.getOrDefault(field, field)), "Einstellung ungültig: " + field);
    }
}
