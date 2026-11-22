package app.dispofold.export;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.importclient.Issue;
import app.dispofold.model.Area;
import app.dispofold.model.Dataset;
import app.dispofold.model.Person;
import app.dispofold.model.ShiftType;

/**
 * A corrections file loaded in Dispofold (format {@code dispofold/corrections}, every version the export client
 * reads): the base of the corrections until an import through an import source has applied it, as if an earlier
 * import had. While it waits, it takes the place of the corrections the last import applied; the master data changed
 * in Dispofold since the last import win over it per person and field (see {@link #merge}). The file is passed on
 * unchanged where nothing was changed, so that the source applies exactly the loaded file.
 * <p>
 * The file as a whole is checked as the client loads it (format, version, the sections as objects); a file that
 * fails is refused. Single entries are only looked at for the summary: the source checks each of them again when it
 * applies the file and skips what it cannot take. Notes name ids, field names and counts only, never free text of
 * the file (a key that is no person id may be a name).
 */
public final class LoadedCorrections {

    /** The highest version of the file the client reads (1 has no {@code exclude} and no {@code demand}). */
    public static final int MAX_VERSION = Corrections.VERSION;
    private static final String NOTE = "note";
    private static final Pattern PERSON_ID = Pattern.compile("E\\d+");
    private static final Pattern TIME = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?");
    private static final Set<String> KINDS = Set.of("NEVER", "ON_REQUEST");

    /** The file cannot be used at all; {@code code} has the text in the UI ({@code error.<code>}). */
    public static final class Rejected extends RuntimeException {
        private final String code;

        Rejected(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * What a loaded file brings, for the confirmation before it is saved.
     *
     * @param version the file's version
     * @param people person entries in the file
     * @param existing of these, the people in the current data; null when there are no people yet
     * @param fields per field the entries that look applicable, in the client's order, then {@code exclude} and
     *        {@code demand} (areas)
     * @param skipped entries that will not apply, each with the reason as a note code
     * @param overridden entries the master data changed in Dispofold since the last import override
     * @param replaced entries of the corrections the last import applied, which the file replaces
     */
    public record Summary(int version, int people, Integer existing, Map<String, Integer> fields,
            List<Issue> skipped, int overridden, int replaced) {
    }

    /**
     * The effective corrections file while a loaded file waits.
     *
     * @param file the loaded file with the changes from Dispofold over it
     * @param notes the changes Dispofold cannot express in the file
     * @param changed entries that come from Dispofold
     * @param withdrawn exclusions taken back in Dispofold since the last import
     */
    public record Merged(ObjectNode file, List<Issue> notes, int changed, int withdrawn) {
    }

    private LoadedCorrections() {
    }

    /** The file, checked as the client loads it. */
    public static ObjectNode parse(String body, ObjectMapper mapper) {
        JsonNode file;
        try {
            // nothing may follow the object, as for the export program
            file = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(body == null ? "" : body);
        } catch (JsonProcessingException e) {
            file = null;
        }
        if (file == null || !file.isObject()) {
            throw new Rejected("corrections.load.unreadable", "Die Datei ist kein JSON-Objekt");
        }
        if (!Corrections.FORMAT.equals(file.path("format").textValue())) {
            throw new Rejected("corrections.load.format",
                    "Unbekanntes Format, erwartet \"" + Corrections.FORMAT + "\"");
        }
        JsonNode version = file.path("version");
        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.intValue() < 1
                || version.intValue() > MAX_VERSION) {
            throw new Rejected("corrections.load.version", "Version nicht unterstützt, unterstützt bis " + MAX_VERSION);
        }
        if (!file.path("people").isObject()) {
            throw new Rejected("corrections.load.people", "'people' muss ein Objekt sein");
        }
        if (version.intValue() >= 2 && file.has(Corrections.DEMAND) && !file.get(Corrections.DEMAND).isObject()) {
            throw new Rejected("corrections.load.demand", "'demand' muss ein Objekt sein");
        }
        return (ObjectNode) file;
    }

    /** Fields, exclusions and demand areas of a checked file, as the export counts them. */
    public static int count(ObjectNode file) {
        int count = 0;
        for (JsonNode entry : iterable(file.path("people").elements())) {
            if (!entry.isObject()) {
                continue;
            }
            if (version(file) >= 2 && entry.path(Corrections.EXCLUDE).booleanValue()) {
                count++;
                continue;
            }
            for (String key : iterable(entry.fieldNames())) {
                if (!key.equals(NOTE) && !key.equals(Corrections.EXCLUDE)) {
                    count++;
                }
            }
        }
        return count + (version(file) >= 2 ? file.path(Corrections.DEMAND).size() : 0);
    }

    /**
     * The loaded file with the changes made in Dispofold since the last import ({@code base}, null without one) over
     * it: a person removed or excluded in Dispofold is excluded, a changed field takes the value from Dispofold, an
     * area whose demand changed takes all its rules from Dispofold, an exclusion taken back in Dispofold is dropped.
     * The corrections the last import applied are not taken over: the loaded file replaces them. Without changes
     * the file is the loaded one as it is; else a version 1 file becomes version 2 without the keys version 1
     * ignores.
     */
    public static Merged merge(ObjectNode loaded, Dataset base, Dataset current, ObjectMapper mapper) {
        if (base == null) {
            return new Merged(loaded.deepCopy(), List.of(), 0, 0);
        }
        Corrections.Changes checked = Corrections.check(Corrections.diff(base, current), base);
        Corrections.Changes changes = Corrections.withEarlier(checked, null, base);
        List<String> withdrawn = changes.people().values().stream()
                .filter(c -> c.fields().contains(Corrections.EXCLUDED_FROM) && c.person().excludedFrom() == null)
                .map(c -> c.person().id()).toList();
        ObjectNode own = Corrections.file(changes, mapper);
        ObjectNode out = loaded.deepCopy();
        if (own.path("people").isEmpty() && own.path(Corrections.DEMAND).isEmpty() && withdrawn.isEmpty()) {
            return new Merged(out, changes.notes(), 0, 0);
        }
        if (version(out) < 2) {
            out.put("version", Corrections.VERSION);
            for (JsonNode entry : iterable(out.path("people").elements())) {
                if (entry instanceof ObjectNode o) {
                    o.remove(Corrections.EXCLUDE);
                }
            }
            out.remove(Corrections.DEMAND);
        }
        ObjectNode people = (ObjectNode) out.get("people");
        for (String id : withdrawn) {
            if (people.get(id) instanceof ObjectNode o) {
                o.remove(Corrections.EXCLUDE);
            }
        }
        own.path("people").fields().forEachRemaining(e -> {
            JsonNode entry = e.getValue();
            if (entry.path(Corrections.EXCLUDE).booleanValue()) {
                people.set(e.getKey(), entry.deepCopy());
                return;
            }
            ObjectNode target = people.get(e.getKey()) instanceof ObjectNode o ? o : people.putObject(e.getKey());
            entry.fields().forEachRemaining(f -> target.set(f.getKey(), f.getValue().deepCopy()));
        });
        if (!own.path(Corrections.DEMAND).isEmpty()) {
            ObjectNode demand = out.with(Corrections.DEMAND);
            own.get(Corrections.DEMAND).fields().forEachRemaining(e -> demand.set(e.getKey(), e.getValue().deepCopy()));
        }
        return new Merged(out, changes.notes(), changes.count() - withdrawn.size(), withdrawn.size());
    }

    /**
     * The summary of a checked file against the current data (null if there is none), with the changes made in
     * Dispofold since the last import ({@code base}, null without one) and the corrections that import applied
     * ({@code applied}, null if unknown).
     */
    public static Summary summary(ObjectNode file, Dataset current, Dataset base, JsonNode applied,
            ObjectMapper mapper) {
        int version = version(file);
        List<Person> people = current == null || current.people() == null ? List.of() : current.people();
        Set<String> ids = people.stream().map(Person::id).collect(Collectors.toSet());
        Set<String> areas = current == null || current.areas() == null ? Set.of()
                : current.areas().stream().map(Area::id).collect(Collectors.toSet());
        Set<String> shifts = current == null || current.shifts() == null ? Set.of()
                : current.shifts().stream().map(ShiftType::id).collect(Collectors.toSet());
        Map<String, Integer> fields = new LinkedHashMap<>();
        Stream.concat(Corrections.FIELDS.stream(), Stream.of(Corrections.RESTRICTIONS, Corrections.EXCLUDE,
                Corrections.DEMAND)).forEach(f -> fields.put(f, 0));
        List<Issue> skipped = new ArrayList<>();
        int foreign = 0;
        int existing = 0;
        JsonNode entries = file.get("people");
        for (String key : iterable(entries.fieldNames())) {
            JsonNode entry = entries.get(key);
            // a key that is neither of the client's form nor a person here may be a name: never echoed
            boolean own = PERSON_ID.matcher(key).matches();
            if (!own) {
                foreign++;
            }
            String id = own || ids.contains(key) ? key : "";
            if (ids.contains(key)) {
                existing++;
            } else if (!ids.isEmpty() && own) {
                skipped.add(note("corrections.load.personMissing", "personId", id));
            }
            if (!entry.isObject()) {
                skipped.add(note("corrections.load.notObject", "personId", id));
                continue;
            }
            for (String field : iterable(entry.fieldNames())) {
                if (field.equals(NOTE)) {
                    continue;
                }
                String reason = field.equals(Corrections.EXCLUDE) && version < 2 ? "versionIgnored"
                        : field.equals(Corrections.EXCLUDE) ? (entry.get(field).isBoolean() ? null : "invalid")
                        : field.equals("id") || Corrections.IDENTITY.contains(field) ? "fixedField"
                        : fields.containsKey(field) && !field.equals(Corrections.DEMAND)
                                ? reason(field, entry, areas, shifts)
                        : "unknownField";
                if (reason == null) {
                    // counted where it can apply: a person id of the client's form or a person here, and for a
                    // person excluded only the exclusion
                    boolean excluded = version >= 2 && entry.path(Corrections.EXCLUDE).booleanValue();
                    if ((own || ids.contains(key)) && (!excluded || field.equals(Corrections.EXCLUDE))
                            && (!field.equals(Corrections.EXCLUDE) || entry.get(field).booleanValue())) {
                        fields.merge(field, 1, Integer::sum);
                    }
                } else {
                    boolean known = !reason.equals("unknownField");
                    skipped.add(known ? note("corrections.load." + reason, "personId", id, "field", field)
                            : note("corrections.load." + reason, "personId", id));
                }
            }
        }
        if (foreign > 0) {
            skipped.add(note("corrections.idFormat", "count", foreign));
        }
        JsonNode demand = file.path(Corrections.DEMAND);
        if (version < 2 && file.has(Corrections.DEMAND)) {
            skipped.add(note("corrections.load.versionIgnored", "field", Corrections.DEMAND));
        } else {
            for (String area : iterable(demand.fieldNames())) {
                if (!areas.isEmpty() && !areas.contains(area)) {
                    skipped.add(note("corrections.demandArea", "areaId", area));
                } else if (!rules(demand.get(area))) {
                    skipped.add(note("corrections.load.demandInvalid", "areaId", area));
                } else {
                    fields.merge(Corrections.DEMAND, 1, Integer::sum);
                }
            }
        }
        int overridden = 0;
        if (base != null) {
            ObjectNode own = Corrections.file(
                    Corrections.withEarlier(Corrections.check(Corrections.diff(base, current), base), null, base),
                    mapper);
            for (var e : iterable(own.path("people").fields())) {
                JsonNode mine = entries.get(e.getKey());
                if (mine == null || !mine.isObject()) {
                    continue;
                }
                for (String f : iterable(mine.fieldNames())) {
                    if (!f.equals(NOTE) && (e.getValue().has(f) || e.getValue().has(Corrections.EXCLUDE))) {
                        overridden++;
                    }
                }
            }
            if (version >= 2) {
                for (String area : iterable(own.path(Corrections.DEMAND).fieldNames())) {
                    overridden += demand.has(area) ? 1 : 0;
                }
            }
        }
        int replaced = applied instanceof ObjectNode a && a.path("people").isObject() ? count(a) : 0;
        return new Summary(version, entries.size(), ids.isEmpty() ? null : existing, fields, List.copyOf(skipped),
                overridden, replaced);
    }

    /** Why the value of a person field cannot be applied, or null: the checks of the import, roughly. */
    private static String reason(String field, JsonNode entry, Set<String> areas, Set<String> shifts) {
        JsonNode v = entry.get(field);
        boolean ok = switch (field) {
            case "weeklyHours" -> number(v) && v.doubleValue() > 0;
            case "hoursAccount" -> number(v);
            case "compRestBalance" -> v.isIntegralNumber();
            case "areaIds" -> v.isArray() && !v.isEmpty() && texts(v) && distinct(v);
            case "familyRules" -> v.isNull() || v.isArray() && all(v, JsonNode::isObject);
            case "entryDate", "exitDate" -> v.isNull() || date(v);
            case "shiftMix" -> v.isNull() || v.isObject() && all(v.fieldNames(), k -> k.equals("categories")
                    || k.equals("areas"));
            case Corrections.RESTRICTIONS -> v.isArray() && all(v, r -> r.isObject()
                    && KINDS.contains(r.path("kind").asText()) && r.hasNonNull("shiftId") != r.hasNonNull("category"));
            default -> false;
        };
        if (!ok) {
            return "invalid";
        }
        if (field.equals("areaIds") && !areas.isEmpty() && !all(v, a -> areas.contains(a.asText()))) {
            return "unknownArea";
        }
        if (field.equals(Corrections.RESTRICTIONS) && !shifts.isEmpty()
                && !all(v, r -> !r.hasNonNull("shiftId") || shifts.contains(r.get("shiftId").asText()))) {
            return "unknownShift";
        }
        return null;
    }

    private static boolean rules(JsonNode rules) {
        return rules.isArray() && all(rules, r -> r.isObject() && time(r.path("from")) && time(r.path("to"))
                && r.path("min").isIntegralNumber() && r.path("min").asLong() >= 0
                && (r.hasNonNull("date") ? date(r.get("date")) : r.path("days").isArray() && !r.get("days").isEmpty()));
    }

    private static boolean number(JsonNode v) {
        return v.isNumber() && Double.isFinite(v.doubleValue());
    }

    private static boolean texts(JsonNode v) {
        return all(v, JsonNode::isTextual);
    }

    private static boolean distinct(JsonNode v) {
        Set<String> seen = new HashSet<>();
        return all(v, a -> seen.add(a.asText()));
    }

    private static boolean date(JsonNode v) {
        if (!v.isTextual() || !v.asText().matches("\\d{4}-\\d{2}-\\d{2}")) {
            return false;
        }
        try {
            LocalDate.parse(v.asText());
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean time(JsonNode v) {
        return v.isTextual() && TIME.matcher(v.asText()).matches();
    }

    private static int version(JsonNode file) {
        return file.path("version").asInt();
    }

    private static boolean all(JsonNode array, java.util.function.Predicate<JsonNode> test) {
        for (JsonNode item : array) {
            if (!test.test(item)) {
                return false;
            }
        }
        return true;
    }

    private static boolean all(Iterator<String> keys, java.util.function.Predicate<String> test) {
        while (keys.hasNext()) {
            if (!test.test(keys.next())) {
                return false;
            }
        }
        return true;
    }

    private static <T> Iterable<T> iterable(Iterator<T> it) {
        return () -> it;
    }

    private static Issue note(String code, Object... params) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < params.length; i += 2) {
            map.put((String) params[i], params[i + 1]);
        }
        return new Issue(code, map);
    }
}
