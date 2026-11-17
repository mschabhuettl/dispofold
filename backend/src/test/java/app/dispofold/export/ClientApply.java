package app.dispofold.export;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Test-side port of how the export client checks and applies a corrections file (version 1 and 2) to a dataset as
 * JSON, independent of {@link Corrections}: a field with an invalid value or an unknown id is skipped, so the round
 * trip asserts that nothing is skipped.
 */
final class ClientApply {

    /** Applied and skipped entries as "personId field" or "demand:area". */
    record Outcome(List<String> applied, List<String> skipped) {
    }

    static final List<String> FIELDS = List.of("weeklyHours", "areaIds", "hoursAccount", "compRestBalance",
            "familyRules", "entryDate", "exitDate", "shiftMix", "restrictions");
    static final Set<String> CATEGORIES = Set.of("EARLY", "DAY", "LATE", "NIGHT");
    static final Set<String> DAY_CODES = Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "HOL");
    static final Set<String> RESTRICTION_KEYS = Set.of("shiftId", "category", "weekday", "kind", "validFrom",
            "validTo");
    static final Set<String> FAMILY_RULE_KEYS = Set.of("weekdays", "latestEnd", "earliestStart", "noNights", "note");
    static final Set<String> DEMAND_KEYS = Set.of("days", "date", "from", "to", "min", "max");
    static final Pattern PERSON_ID = Pattern.compile("E\\d+");
    static final Pattern DATE = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])");
    static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?");
    static final double TOLERANCE = 0.01;

    private ClientApply() {
    }

    static Outcome apply(ObjectNode dataset, JsonNode file) {
        if (!"dispofold/corrections".equals(file.path("format").asText()) || !file.path("version").isIntegralNumber()
                || file.get("version").asInt() < 1 || file.get("version").asInt() > 2
                || !file.path("people").isObject()) {
            throw new IllegalArgumentException("file not usable");
        }
        int version = file.get("version").asInt();
        JsonNode demandFile = version >= 2 && file.has("demand") ? file.get("demand")
                : JsonNodeFactory.instance.objectNode();
        if (!demandFile.isObject()) {
            throw new IllegalArgumentException("demand not an object");
        }
        List<String> applied = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Map<String, ObjectNode> byId = new LinkedHashMap<>();
        dataset.withArray("people").forEach(p -> byId.put(p.get("id").asText(), (ObjectNode) p));
        Set<String> areaIds = new HashSet<>();
        dataset.withArray("areas").forEach(a -> areaIds.add(a.get("id").asText()));
        Set<String> shiftIds = new HashSet<>();
        dataset.withArray("shifts").forEach(s -> shiftIds.add(s.get("id").asText()));

        List<String> keys = new ArrayList<>();
        file.get("people").fieldNames().forEachRemaining(keys::add);
        keys.sort(Comparator.comparing((String k) -> PERSON_ID.matcher(k).matches() ? 0 : 1)
                .thenComparing(k -> PERSON_ID.matcher(k).matches() ? Long.parseLong(k.substring(1)) : 0L)
                .thenComparing(Comparator.naturalOrder()));
        for (String key : keys) {
            JsonNode entry = file.get("people").get(key);
            String pid = PERSON_ID.matcher(key).matches() ? key : "";
            List<String> fields = new ArrayList<>();
            if (entry.isObject()) {
                entry.fieldNames().forEachRemaining(f -> {
                    if (!f.equals("note")) {
                        fields.add(f);
                    }
                });
            }
            ObjectNode person = byId.get(pid);
            if (person == null || !entry.isObject()) {
                skipped.add(key + " (person)");
                continue;
            }
            if (version >= 2 && entry.has("exclude")) {
                fields.remove("exclude");
                JsonNode v = entry.get("exclude");
                if (!v.isBoolean() || v.asBoolean() && dataset.withArray("people").size() == 1) {
                    skipped.add(pid + " exclude");
                } else if (v.asBoolean()) {
                    drop(dataset, pid);
                    byId.remove(pid);
                    applied.add(pid + " exclude");
                    continue;
                }
            }
            fields.sort(Comparator.comparing(f -> FIELDS.contains(f) ? FIELDS.indexOf(f) : FIELDS.size()));
            for (String field : fields) {
                JsonNode value = entry.get(field);
                String why = FIELDS.contains(field) ? check(field, value, person, entry, areaIds, shiftIds)
                        : "unknown field";
                if (why != null) {
                    skipped.add(pid + " " + field + ": " + why);
                    continue;
                }
                applied.add(pid + " " + field);
                if (field.equals("restrictions")) {
                    ArrayNode all = dataset.withArray("restrictions");
                    List<JsonNode> rest = new ArrayList<>();
                    all.forEach(r -> {
                        if (!r.get("personId").asText().equals(pid)) {
                            rest.add(r);
                        }
                    });
                    int at = rest.size();
                    for (int i = 0; i < rest.size(); i++) {
                        if (rest.get(i).get("personId").asText().compareTo(pid) > 0) {
                            at = i;
                            break;
                        }
                    }
                    List<JsonNode> own = new ArrayList<>();
                    value.forEach(r -> {
                        ObjectNode o = JsonNodeFactory.instance.objectNode().put("personId", pid);
                        o.setAll((ObjectNode) r);
                        own.add(o);
                    });
                    rest.addAll(at, own);
                    all.removeAll();
                    all.addAll(rest);
                } else if (field.equals("shiftMix") && value.isNull()) {
                    person.remove("shiftMix");
                } else {
                    person.set(field, value);
                }
            }
        }
        if (version < 2) {
            return new Outcome(applied, skipped);
        }
        List<String> order = new ArrayList<>();
        dataset.withArray("areas").forEach(a -> order.add(a.get("id").asText()));
        List<String> areas = new ArrayList<>();
        demandFile.fieldNames().forEachRemaining(areas::add);
        areas.sort(Comparator.comparing(a -> order.contains(a) ? order.indexOf(a) : order.size()));
        for (String area : areas) {
            JsonNode rules = demandFile.get(area);
            String why = !areaIds.contains(area) ? "unknown area" : demandRules(rules);
            if (why != null) {
                skipped.add("demand:" + area + ": " + why);
                continue;
            }
            applied.add("demand:" + area);
            ArrayNode all = dataset.withArray("demand");
            int before = all.size();
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).get("areaId").asText().equals(area)) {
                    before = i;
                    break;
                }
            }
            int at = 0;
            for (int i = 0; i < before; i++) {
                if (!all.get(i).get("areaId").asText().equals(area)) {
                    at++;
                }
            }
            List<JsonNode> rest = new ArrayList<>();
            all.forEach(r -> {
                if (!r.get("areaId").asText().equals(area)) {
                    rest.add(r);
                }
            });
            List<JsonNode> own = new ArrayList<>();
            rules.forEach(r -> {
                ObjectNode o = JsonNodeFactory.instance.objectNode().put("areaId", area);
                o.setAll((ObjectNode) r);
                own.add(o);
            });
            rest.addAll(at, own);
            all.removeAll();
            all.addAll(rest);
        }
        return new Outcome(applied, skipped);
    }

    private static String check(String field, JsonNode v, ObjectNode person, JsonNode entry, Set<String> areaIds,
            Set<String> shiftIds) {
        switch (field) {
            case "weeklyHours":
                return number(v) && v.asDouble() > 0 ? null : "not > 0";
            case "hoursAccount":
                return number(v) ? null : "not a number";
            case "compRestBalance":
                return v.isIntegralNumber() ? null : "not an integer";
            case "areaIds": {
                if (!v.isArray() || v.isEmpty()) {
                    return "not a non-empty list";
                }
                Set<String> ids = new HashSet<>();
                for (JsonNode a : v) {
                    if (!a.isTextual() || !ids.add(a.asText())) {
                        return "not distinct ids";
                    }
                }
                if (!areaIds.containsAll(ids)) {
                    return "unknown area";
                }
                JsonNode mix = person.get("shiftMix");
                if (entry.has("shiftMix")) {
                    ObjectNode future = person.deepCopy();
                    future.set("areaIds", v);
                    if (check("shiftMix", entry.get("shiftMix"), future, entry, areaIds, shiftIds) == null) {
                        mix = entry.get("shiftMix");
                    }
                }
                if (mix != null && mix.isObject() && !mix.isEmpty()) {
                    for (Iterator<String> it = mix.path("areas").fieldNames(); it.hasNext();) {
                        if (!ids.contains(it.next())) {
                            return "distribution outside the areas";
                        }
                    }
                }
                return null;
            }
            case "familyRules":
                if (v.isNull()) {
                    return null;
                }
                if (!v.isArray()) {
                    return "not a list";
                }
                for (JsonNode f : v) {
                    if (!f.isObject() || !keysIn(f, FAMILY_RULE_KEYS)) {
                        return "bad rule";
                    }
                    JsonNode days = f.get("weekdays");
                    if (days != null && !days.isNull()) {
                        if (!days.isArray()) {
                            return "weekdays";
                        }
                        for (JsonNode d : days) {
                            if (!d.isIntegralNumber() || d.asInt() < 1 || d.asInt() > 7) {
                                return "weekdays";
                            }
                        }
                    }
                    for (String k : List.of("latestEnd", "earliestStart")) {
                        JsonNode t = f.get(k);
                        if (t != null && !t.isNull() && !(t.isTextual() && TIME.matcher(t.asText()).matches())) {
                            return "time";
                        }
                    }
                    if (f.has("noNights") && !f.get("noNights").isBoolean()) {
                        return "noNights";
                    }
                    if (f.has("note") && !f.get("note").isNull() && !f.get("note").isTextual()) {
                        return "note";
                    }
                }
                return null;
            case "entryDate": {
                if (!v.isNull() && !date(v)) {
                    return "date";
                }
                JsonNode leave = entry.has("exitDate") && (entry.get("exitDate").isNull() || date(entry.get("exitDate")))
                        ? entry.get("exitDate") : person.get("exitDate");
                return order(v, leave);
            }
            case "exitDate":
                if (!v.isNull() && !date(v)) {
                    return "date";
                }
                return order(person.get("entryDate"), v);
            case "shiftMix": {
                if (v.isNull()) {
                    return null;
                }
                if (!v.isObject() || !keysIn(v, Set.of("categories", "areas"))) {
                    return "not a distribution";
                }
                JsonNode categories = v.has("categories") ? v.get("categories")
                        : JsonNodeFactory.instance.objectNode();
                JsonNode areas = v.has("areas") ? v.get("areas") : JsonNodeFactory.instance.objectNode();
                String why = shares(categories, true);
                if (why == null) {
                    why = shares(areas, false);
                }
                if (why != null) {
                    return why;
                }
                Set<String> own = new HashSet<>();
                person.path("areaIds").forEach(a -> own.add(a.asText()));
                for (Iterator<String> it = areas.fieldNames(); it.hasNext();) {
                    if (!own.contains(it.next())) {
                        return "share for an area the person does not have";
                    }
                }
                return null;
            }
            default: // restrictions
                if (!v.isArray()) {
                    return "not a list";
                }
                for (JsonNode r : v) {
                    if (!r.isObject() || !keysIn(r, RESTRICTION_KEYS)) {
                        return "unknown key (personId?)";
                    }
                    JsonNode sid = r.get("shiftId");
                    JsonNode category = r.get("category");
                    boolean noShift = sid == null || sid.isNull();
                    boolean noCategory = category == null || category.isNull();
                    if (noShift == noCategory) {
                        return "shiftId or category";
                    }
                    if (!noShift && (!sid.isTextual() || !shiftIds.contains(sid.asText()))) {
                        return "unknown shift";
                    }
                    if (!noCategory && !CATEGORIES.contains(category.asText())) {
                        return "unknown category";
                    }
                    JsonNode weekday = r.get("weekday");
                    if (weekday == null || !weekday.isIntegralNumber() || weekday.asInt() < 0 || weekday.asInt() > 7) {
                        return "weekday";
                    }
                    if (!Set.of("NEVER", "ON_REQUEST").contains(r.path("kind").asText())) {
                        return "kind";
                    }
                    JsonNode start = r.get("validFrom");
                    JsonNode end = r.get("validTo");
                    for (JsonNode d : new JsonNode[] { start, end }) {
                        if (d != null && !d.isNull() && !date(d)) {
                            return "validity";
                        }
                    }
                    if (start != null && !start.isNull() && end != null && !end.isNull()
                            && end.asText().compareTo(start.asText()) < 0) {
                        return "validity order";
                    }
                }
                return null;
        }
    }

    private static String demandRules(JsonNode rules) {
        if (!rules.isArray()) {
            return "not a list";
        }
        for (JsonNode r : rules) {
            if (!r.isObject() || !keysIn(r, DEMAND_KEYS)) {
                return "unknown key (areaId?)";
            }
            JsonNode days = r.get("days");
            JsonNode day = r.get("date");
            if (days != null && !days.isNull()) {
                if (!days.isArray()) {
                    return "days";
                }
                for (JsonNode d : days) {
                    if (!d.isTextual() || !DAY_CODES.contains(d.asText())) {
                        return "days";
                    }
                }
            }
            if (day != null && !day.isNull() && !date(day)) {
                return "date";
            }
            if ((day == null || day.isNull()) && (days == null || days.isNull() || days.isEmpty())) {
                return "days or date";
            }
            for (String k : List.of("from", "to")) {
                if (!r.path(k).isTextual() || !TIME.matcher(r.get(k).asText()).matches()) {
                    return "time";
                }
            }
            JsonNode low = r.get("min");
            JsonNode high = r.get("max");
            if (low == null || !low.isIntegralNumber() || low.asInt() < 0) {
                return "min";
            }
            if (high != null && !high.isNull() && !(high.isIntegralNumber() && high.asInt() >= low.asInt())) {
                return "max";
            }
        }
        return null;
    }

    private static String shares(JsonNode shares, boolean categories) {
        if (!shares.isObject()) {
            return "shares not an object";
        }
        double sum = 0;
        for (Iterator<Map.Entry<String, JsonNode>> it = shares.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            if (categories && !CATEGORIES.contains(e.getKey())) {
                return "unknown category";
            }
            if (!number(e.getValue()) || e.getValue().asDouble() < 0 || e.getValue().asDouble() > 1) {
                return "share not between 0 and 1";
            }
            sum += e.getValue().asDouble();
        }
        return !shares.isEmpty() && Math.abs(sum - 1) > TOLERANCE ? "shares do not sum to 1" : null;
    }

    private static void drop(ObjectNode dataset, String pid) {
        List<ObjectNode> holders = new ArrayList<>();
        holders.add(dataset);
        dataset.withArray("periods").forEach(p -> holders.add((ObjectNode) p));
        for (ObjectNode holder : holders) {
            for (Iterator<Map.Entry<String, JsonNode>> it = holder.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getValue().isArray()) {
                    continue;
                }
                ArrayNode items = (ArrayNode) e.getValue();
                for (int i = items.size() - 1; i >= 0; i--) {
                    JsonNode x = items.get(i);
                    boolean person = e.getKey().equals("people") && x.path("id").asText().equals(pid);
                    boolean refers = !e.getKey().equals("people") && x.isObject()
                            && pid.equals(x.path("personId").asText(null));
                    if (person || refers) {
                        items.remove(i);
                    }
                }
            }
        }
    }

    private static String order(JsonNode entry, JsonNode leave) {
        return entry != null && leave != null && date(entry) && date(leave)
                && leave.asText().compareTo(entry.asText()) < 0 ? "exit before entry" : null;
    }

    private static boolean keysIn(JsonNode o, Set<String> keys) {
        for (Iterator<String> it = o.fieldNames(); it.hasNext();) {
            if (!keys.contains(it.next())) {
                return false;
            }
        }
        return true;
    }

    private static boolean number(JsonNode v) {
        return v != null && v.isNumber() && Double.isFinite(v.asDouble());
    }

    private static boolean date(JsonNode v) {
        if (v == null || !v.isTextual() || !DATE.matcher(v.asText()).matches()) {
            return false;
        }
        try {
            LocalDate.parse(v.asText());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
