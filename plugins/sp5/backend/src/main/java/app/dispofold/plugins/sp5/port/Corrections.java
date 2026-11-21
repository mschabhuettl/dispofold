package app.dispofold.plugins.sp5.port;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Port of sp5export/corrections.py: the corrections file (person master data corrected by hand, from version 2 also
 * exclusions and demand per area), applied to the exported dataset as the last step.
 */
public final class Corrections {

    static final String FORMAT = "dispofold/corrections";
    static final int VERSION = 2;
    static final List<String> CATEGORIES = List.of("EARLY", "DAY", "LATE", "NIGHT");
    static final List<String> FIELDS = List.of("weeklyHours", "areaIds", "hoursAccount", "compRestBalance",
            "familyRules", "entryDate", "exitDate", "shiftMix", "restrictions");
    static final List<String> FIXED = List.of("id", "externalId", "firstName", "lastName", "shortName");
    static final double SHIFT_MIX_TOLERANCE = 0.01;
    static final Set<String> RESTRICTION_KEYS = Set.of("shiftId", "category", "weekday", "kind", "validFrom",
            "validTo");
    static final Set<String> FAMILY_RULE_KEYS = Set.of("weekdays", "latestEnd", "earliestStart", "noNights", "note");
    static final Set<String> DEMAND_KEYS = Set.of("days", "date", "from", "to", "min", "max");
    static final List<String> DAY_CODES = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "HOL");
    private static final String FILE = "corrections.py";
    static final Pattern PERSON_ID = compile("E\\d+$");
    private static final Pattern DATE = compile("\\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\\d|3[01])$");
    private static final Pattern TIME = compile("([01]\\d|2[0-3]):[0-5]\\d(:[0-5]\\d)?$");

    /** The corrections file cannot be used at all (unreadable, unknown format or version). German message. */
    public static final class CorrectionsError extends Exception {
        public CorrectionsError(String message) {
            super(message);
        }
    }

    /** What {@link #apply} returns: report rows (person, field, status, reason, exported, corrected), warnings. */
    record Applied(List<List<Object>> rows, List<String> warnings, int corrected, int applied, int skipped,
            String summary) {
    }

    private Corrections() {
    }

    private static Pattern compile(String p) {
        try {
            return PyRe.compile(p);
        } catch (PyRe.Error e) {
            throw new IllegalStateException(e);
        }
    }

    /** load(path): the file as `name` (the path as the user gave it) is named in the messages. */
    public static Map<String, Object> load(Path path, String name) throws CorrectionsError {
        byte[] data;
        try {
            data = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new CorrectionsError("Korrekturdatei " + name + " nicht lesbar (" + Dbf.pyErrorName(e, path) + ")");
        }
        return load(data, name);
    }

    /** The corrections (version, people, demand) of the file's bytes; `name` is named in the messages. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> load(byte[] bytes, String name) throws CorrectionsError {
        Object data;
        try {
            data = PyJson.load(bytes);
        } catch (PyJson.DecodeError e) {
            throw new CorrectionsError("Korrekturdatei " + name + " nicht lesbar (" + e.type() + ")");
        }
        if (!(data instanceof Map<?, ?> m) || !FORMAT.equals(m.get("format"))) {
            throw new CorrectionsError("Korrekturdatei " + name + ": unbekanntes Format, erwartet \"" + FORMAT + "\"");
        }
        Map<String, Object> map = (Map<String, Object>) m;
        Object version = map.get("version");
        if (!Py.isInt(version) || Py.compare(version, 1L) < 0 || Py.compare(version, (long) VERSION) > 0) {
            throw new CorrectionsError("Korrekturdatei " + name + ": Version nicht unterstützt, unterstützt bis "
                    + VERSION);
        }
        if (!(map.get("people") instanceof Map)) {
            throw new CorrectionsError("Korrekturdatei " + name + ": 'people' muss ein Objekt sein");
        }
        Object demand = Py.compare(version, 2L) >= 0 ? (map.containsKey("demand") ? map.get("demand")
                : new LinkedHashMap<>()) : new LinkedHashMap<>();
        if (!(demand instanceof Map)) {
            throw new CorrectionsError("Korrekturdatei " + name + ": 'demand' muss ein Objekt sein");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version);
        result.put("people", map.get("people"));
        result.put("demand", demand);
        return result;
    }

    // ── value checks ───────────────────────────────────────

    private static boolean isNumber(Object v) {
        return Py.at(FILE, 69, () -> (v instanceof Long || v instanceof Integer || v instanceof BigInteger
                || v instanceof Double) && Double.isFinite(Py.toDouble(v)));
    }

    private static boolean isDate(Object v) {
        if (!(v instanceof String s) || !PyRe.match(DATE, s)) {
            return false;
        }
        try {
            TimeUtil.fromIso(s);
        } catch (Py.Raised e) {
            return false;
        }
        return true;
    }

    private static boolean isTime(Object v) {
        return v instanceof String s && PyRe.match(TIME, s);
    }

    private static boolean between(Object v, long lo, long hi) {
        return Py.compare(v, lo) >= 0 && Py.compare(v, hi) <= 0;
    }

    private static String shares(Object shares, java.util.function.Predicate<String> keysOk) {
        if (!(shares instanceof Map<?, ?> m)) {
            return "Anteile müssen ein Objekt sein";
        }
        for (Object k : m.keySet()) {
            if (!keysOk.test((String) k)) {
                return "unbekannter Schlüssel in den Anteilen";
            }
        }
        for (Object s : m.values()) {
            if (!(isNumber(s) && Py.compare(s, 0L) >= 0 && Py.compare(s, 1L) <= 0)) {
                return "Anteil nicht zwischen 0 und 1";
            }
        }
        if (!m.isEmpty() && Math.abs(Py.toDouble(Py.sum(m.values())) - 1) > SHIFT_MIX_TOLERANCE) {
            return "Anteile ergeben nicht 1";
        }
        return null;
    }

    private static String mixText(Object mixValue) {
        if (!Py.truthy(mixValue)) {
            return "kein Mix";
        }
        Map<?, ?> mix = (Map<?, ?>) mixValue;
        List<String> parts = new ArrayList<>();
        for (String part : List.of("categories", "areas")) {
            if (Py.truthy(mix.get(part))) {
                List<String> items = new ArrayList<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) mix.get(part)).entrySet()) {
                    items.add(e.getKey() + " " + Py.fixed(Py.toDouble(e.getValue()), 3));
                }
                parts.add(part + " " + String.join(", ", items));
            }
        }
        return String.join(" | ", parts);
    }

    /** _norm: absent keys and null alike, numbers as floats, lists of objects in any order (tuples as Lists). */
    static Object norm(Object value) {
        if (value instanceof Map<?, ?> m) {
            List<List<Object>> pairs = new ArrayList<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getValue() != null) {
                    pairs.add(java.util.Arrays.asList(e.getKey(), norm(e.getValue())));
                }
            }
            pairs.sort((a, b) -> Py.compareStr((String) a.get(0), (String) b.get(0)));
            return new ArrayList<Object>(pairs);
        }
        if (value instanceof List<?> l) {
            List<Object> items = new ArrayList<>();
            boolean allDicts = true;
            for (Object v : l) {
                items.add(norm(v));
                allDicts &= v instanceof Map;
            }
            if (allDicts) {
                items.sort((a, b) -> Py.compareStr(tupleRepr(a), tupleRepr(b)));
            }
            return items;
        }
        if (isNumber(value)) {
            return Py.toDouble(value);
        }
        return value;
    }

    /** repr of a normalized value: Lists are tuples. */
    private static String tupleRepr(Object v) {
        if (v instanceof List<?> l) {
            StringBuilder b = new StringBuilder("(");
            for (int i = 0; i < l.size(); i++) {
                b.append(i > 0 ? ", " : "").append(tupleRepr(l.get(i)));
            }
            return b.append(l.size() == 1 ? ",)" : ")").toString();
        }
        return Py.repr(v);
    }

    private static boolean normEq(Object a, Object b) {
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (int i = 0; i < x.size(); i++) {
                if (x.get(i) != y.get(i) && !normEq(x.get(i), y.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return Py.eq(a, b);
    }

    @SuppressWarnings("unchecked")
    private static String text(String field, Object value) {
        if (value == null) {
            return "null";
        }
        return switch (field) {
            case "areaIds" -> String.join(" ", (List<String>) value);
            case "shiftMix" -> mixText(value);
            case "familyRules" -> ((List<?>) value).size() + " Regeln";
            case "restrictions" -> ((List<?>) value).size() + " Einschränkungen";
            default -> Py.str(value);
        };
    }

    /** _Check: one field value of one person checked like Dispofold's import; the reason or null. */
    private static final class Check {
        final Set<String> areaIds;
        final Set<String> shiftIds;

        Check(Set<String> areaIds, Set<String> shiftIds) {
            this.areaIds = areaIds;
            this.shiftIds = shiftIds;
        }

        String check(String field, Object v, Map<String, Object> person, Map<?, ?> entry) {
            return switch (field) {
                case "weeklyHours" -> isNumber(v) && Py.compare(v, 0L) > 0 ? null : "muss eine Zahl größer 0 sein";
                case "hoursAccount" -> isNumber(v) ? null : "muss eine Zahl sein";
                case "compRestBalance" -> Py.isInt(v) ? null : "muss eine ganze Zahl sein";
                case "areaIds" -> areaIds(v, person, entry);
                case "familyRules" -> familyRules(v);
                case "entryDate" -> entryDate(v, person, entry);
                case "exitDate" -> v != null && !isDate(v) ? "Datum nicht im Format JJJJ-MM-TT"
                        : order(person.get("entryDate"), v);
                case "shiftMix" -> shiftMix(v, person);
                default -> restrictions(v);
            };
        }

        String areaIds(Object v, Map<String, Object> person, Map<?, ?> entry) {
            if (!(v instanceof List<?> l) || l.isEmpty() || l.stream().anyMatch(a -> !(a instanceof String))
                    || new HashSet<>(l).size() != l.size()) {
                return "muss eine nicht leere Liste verschiedener Bereichs-Ids sein";
            }
            if (!areaIds.containsAll(l)) {
                return "unbekannte Bereichs-Id";
            }
            Object mix = person.get("shiftMix");
            if (entry.containsKey("shiftMix")) {
                Map<String, Object> changed = new LinkedHashMap<>(person);
                changed.put("areaIds", v);
                if (shiftMix(entry.get("shiftMix"), changed) == null) {
                    mix = entry.get("shiftMix");
                }
            }
            if (Py.truthy(mix)) {
                Object areas = ((Map<?, ?>) mix).containsKey("areas") ? ((Map<?, ?>) mix).get("areas") : Map.of();
                if (!new HashSet<>(l).containsAll(((Map<?, ?>) areas).keySet())) {
                    return "Schichtmix hat Anteile in einem Bereich, der nicht mehr in areaIds steht (shiftMix "
                            + "mitkorrigieren)";
                }
            }
            return null;
        }

        String familyRules(Object v) {
            if (v == null) {
                return null;
            }
            if (!(v instanceof List<?> l) || l.stream().anyMatch(f -> !(f instanceof Map<?, ?> m)
                    || !FAMILY_RULE_KEYS.containsAll(m.keySet()))) {
                return "muss eine Liste von Regeln (weekdays, latestEnd, earliestStart, noNights, note) sein";
            }
            for (Object o : l) {
                Map<?, ?> f = (Map<?, ?>) o;
                Object days = f.get("weekdays");
                if (days != null && !(days instanceof List<?> dl
                        && dl.stream().noneMatch(d -> !(Py.isInt(d) && between(d, 1, 7))))) {
                    return "weekdays muss eine Liste von 1 bis 7 sein";
                }
                for (String k : List.of("latestEnd", "earliestStart")) {
                    if (f.get(k) != null && !isTime(f.get(k))) {
                        return "Uhrzeit nicht im Format HH:MM";
                    }
                }
                if (f.containsKey("noNights") && !(f.get("noNights") instanceof Boolean)) {
                    return "noNights muss true oder false sein";
                }
                if (f.get("note") != null && !(f.get("note") instanceof String)) {
                    return "note muss Text sein";
                }
            }
            return null;
        }

        String entryDate(Object v, Map<String, Object> person, Map<?, ?> entry) {
            if (v != null && !isDate(v)) {
                return "Datum nicht im Format JJJJ-MM-TT";
            }
            Object leave = entry.containsKey("exitDate") && (entry.get("exitDate") == null || isDate(entry.get("exitDate")))
                    ? entry.get("exitDate") : person.get("exitDate");
            return order(v, leave);
        }

        static String order(Object entry, Object leave) {
            return isDate(entry) && isDate(leave) && Py.compareStr((String) leave, (String) entry) < 0
                    ? "Austritt vor Eintritt" : null;
        }

        String shiftMix(Object v, Map<String, Object> person) {
            if (v == null) {
                return null;
            }
            if (!(v instanceof Map<?, ?> m) || !Set.of("categories", "areas").containsAll(m.keySet())) {
                return "muss null oder ein Objekt mit categories und areas sein";
            }
            String problem = shares(m.containsKey("categories") ? m.get("categories") : Map.of(), CATEGORIES::contains);
            if (problem == null) {
                problem = shares(m.containsKey("areas") ? m.get("areas") : Map.of(), k -> true);
            }
            if (problem != null) {
                return problem;
            }
            Map<?, ?> areas = m.containsKey("areas") ? (Map<?, ?>) m.get("areas") : Map.of();
            Object own = person.get("areaIds");
            if (own == null) {
                throw new Py.Raised("TypeError"); // set(None)
            }
            if (!new HashSet<>((List<?>) own).containsAll(areas.keySet())) {
                return "Bereichsanteil für einen Bereich, der nicht in areaIds steht";
            }
            return null;
        }

        String restrictions(Object v) {
            if (!(v instanceof List<?> l)) {
                return "muss eine Liste sein";
            }
            for (Object o : l) {
                if (!(o instanceof Map<?, ?> r) || !RESTRICTION_KEYS.containsAll(r.keySet())) {
                    return "Einschränkung mit unbekanntem Schlüssel (personId wird nicht angegeben)";
                }
                Object sid = r.get("shiftId");
                Object category = r.get("category");
                if ((sid == null) == (category == null)) {
                    return "Einschränkung braucht genau eines von shiftId und category";
                }
                if (sid != null && (!(sid instanceof String) || !shiftIds.contains(sid))) {
                    return "unbekannte Schicht-Id";
                }
                if (category != null && !(category instanceof String && CATEGORIES.contains(category))) {
                    return "unbekannte Kategorie";
                }
                if (!(Py.isInt(r.get("weekday")) && between(r.get("weekday"), 0, 7))) {
                    return "weekday muss eine ganze Zahl von 0 bis 7 sein";
                }
                if (!"NEVER".equals(r.get("kind")) && !"ON_REQUEST".equals(r.get("kind"))) {
                    return "kind muss NEVER oder ON_REQUEST sein";
                }
                Object start = r.get("validFrom");
                Object end = r.get("validTo");
                if ((start != null && !isDate(start)) || (end != null && !isDate(end))) {
                    return "Gültigkeit nicht im Format JJJJ-MM-TT";
                }
                if (start != null && end != null && Py.compareStr((String) end, (String) start) < 0) {
                    return "Gültigkeit endet vor dem Beginn";
                }
            }
            return null;
        }
    }

    /** _demand_rules: the demand rules of one area checked like Dispofold's import; the reason or null. */
    private static String demandRules(Object rules) {
        if (!(rules instanceof List<?> l)) {
            return "muss eine Liste von Bedarfsregeln sein";
        }
        for (Object o : l) {
            if (!(o instanceof Map<?, ?> r) || !DEMAND_KEYS.containsAll(r.keySet())) {
                return "Regel mit unbekanntem Schlüssel (areaId wird nicht angegeben)";
            }
            Object days = r.get("days");
            Object day = r.get("date");
            if (days != null && !(days instanceof List<?> dl
                    && dl.stream().noneMatch(d -> !(d instanceof String && DAY_CODES.contains(d))))) {
                return "days muss eine Liste aus MON bis SUN und HOL sein";
            }
            if (day != null && !isDate(day)) {
                return "date nicht im Format JJJJ-MM-TT";
            }
            if (day == null && !Py.truthy(days)) {
                return "Regel braucht days oder date";
            }
            if (!isTime(r.get("from")) || !isTime(r.get("to"))) {
                return "from und to müssen Uhrzeiten HH:MM sein";
            }
            Object low = r.get("min");
            Object high = r.get("max");
            if (!(Py.isInt(low) && Py.compare(low, 0L) >= 0)) {
                return "min muss eine ganze Zahl ab 0 sein";
            }
            if (high != null && !(Py.isInt(high) && Py.compare(high, low) >= 0)) {
                return "max muss null oder eine ganze Zahl ab min sein";
            }
        }
        return null;
    }

    /** _drop: the person and every list entry with their personId (in the dataset and its periods). */
    @SuppressWarnings("unchecked")
    private static void drop(Map<String, Object> dataset, String pid) {
        dataset.put("people", new ArrayList<>(((List<Map<String, Object>>) dataset.get("people")).stream()
                .filter(p -> !pid.equals(p.get("id"))).toList()));
        List<Map<String, Object>> holders = new ArrayList<>();
        holders.add(dataset);
        holders.addAll((List<Map<String, Object>>) dataset.get("periods"));
        for (Map<String, Object> holder : holders) {
            for (Map.Entry<String, Object> e : holder.entrySet()) {
                if (e.getValue() instanceof List<?> items && !e.getKey().equals("people")) {
                    e.setValue(new ArrayList<>(items.stream()
                            .filter(x -> !(x instanceof Map<?, ?> m && Py.eq(m.get("personId"), pid))).toList()));
                }
            }
        }
    }

    private static List<Object> row(Object... values) {
        return new ArrayList<>(List.of(values));
    }

    private static boolean personId(String key) {
        return PyRe.match(PERSON_ID, key);
    }

    /** int(k[1:]) of a key that matches PERSON_ID (unbounded, Unicode digits, a trailing newline allowed). */
    private static BigInteger idNumber(String key) {
        StringBuilder b = new StringBuilder();
        Py.strip(key.substring(key.offsetByCodePoints(0, 1))).codePoints()
                .forEach(c -> b.append((char) ('0' + Character.digit(c, 10))));
        return new BigInteger(b.toString());
    }

    /** apply: the corrections (as {@link #load} returns them) applied to `dataset` in place. */
    @SuppressWarnings("unchecked")
    static Applied apply(Map<String, Object> dataset, Map<String, Object> corrections) {
        Object version = corrections.get("version");
        boolean v2 = Py.compare(version, 2L) >= 0;
        Map<String, Object> people = (Map<String, Object>) corrections.get("people");
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) dataset.get("people")) {
            byId.put((String) p.get("id"), p);
        }
        Set<String> areaIdSet = new HashSet<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) dataset.get("areas")) {
            areaIdSet.add((String) a.get("id"));
        }
        Set<String> shiftIds = new HashSet<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) dataset.get("shifts")) {
            shiftIds.add((String) s.get("id"));
        }
        Check check = new Check(areaIdSet, shiftIds);
        List<String> known = new ArrayList<>(FIELDS);
        known.addAll(FIXED);
        if (v2) {
            known.add("exclude");
        }
        List<List<Object>> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int corrected = 0;
        int applied = 0;
        int skipped = 0;
        int excluded = 0;
        List<String> keys = new ArrayList<>(people.keySet());
        keys.sort(Comparator.comparing((String k) -> personId(k) ? 0 : 1).thenComparing((a, b) -> personId(a)
                ? idNumber(a).compareTo(idNumber(b)) : Py.compareStr(a, b)));
        for (String key : keys) {
            Object entryValue = people.get(key);
            String pid = personId(key) ? key : "";
            List<String> fields = new ArrayList<>();
            if (entryValue instanceof Map<?, ?> m) {
                for (Object f : m.keySet()) {
                    if (!"note".equals(f)) {
                        fields.add((String) f);
                    }
                }
            }
            Map<String, Object> person = byId.get(pid);
            if (person == null || !(entryValue instanceof Map)) {
                String why = person != null ? "kein Objekt" : !pid.isEmpty() ? "Mitarbeiter nicht exportiert"
                        : "keine Mitarbeiter-Id (E und SP5-Id)";
                String status = person != null ? "ungueltig" : "person_fehlt";
                for (String f : fields.isEmpty() ? List.of("") : fields) {
                    rows.add(row(pid, known.contains(f) ? f : "", status, why, "", ""));
                }
                skipped += Math.max(fields.size(), 1);
                warnings.add("Korrektur für " + (pid.isEmpty() ? "eine unbekannte Id" : pid) + " übersprungen: " + why);
                continue;
            }
            Map<String, Object> entry = (Map<String, Object>) entryValue;
            if (known.contains("exclude") && entry.containsKey("exclude")) {
                fields.remove("exclude");
                Object value = entry.get("exclude");
                String why = null;
                if (!(value instanceof Boolean b)) {
                    why = "muss true oder false sein";
                } else if (b && ((List<?>) dataset.get("people")).size() == 1) {
                    why = "letzter Mitarbeiter im Datensatz";
                } else if (b) {
                    drop(dataset, pid);
                    rows.add(row(pid, "exclude", "ausgeschlossen", "", "", "true"));
                    applied++;
                    corrected++;
                    excluded++;
                    continue;
                }
                if (why != null) {
                    rows.add(row(pid, "exclude", "ungueltig", why, "", ""));
                    skipped++;
                    warnings.add("Korrektur " + pid + " exclude übersprungen: " + why);
                }
            }
            boolean changed = false;
            List<Map<String, Object>> own = new ArrayList<>();
            for (Map<String, Object> r : (List<Map<String, Object>>) dataset.get("restrictions")) {
                if (Py.eq(r.get("personId"), pid)) {
                    own.add(r);
                }
            }
            List<String> ordered = new ArrayList<>(fields);
            ordered.sort(Comparator.comparingInt(f -> FIELDS.contains(f) ? FIELDS.indexOf(f) : FIELDS.size()));
            for (String field : ordered) {
                Object value = entry.get(field);
                Object old = field.equals("restrictions") ? own : person.get(field);
                String why;
                if (!FIELDS.contains(field)) {
                    why = FIXED.contains(field) ? "nicht korrigierbar" : "unbekanntes Feld";
                    rows.add(row(pid, FIXED.contains(field) ? field : "", "ungueltig", why, "", ""));
                } else if ((why = Py.at(FILE, 314, () -> check.check(field, value, person, entry))) != null) {
                    rows.add(row(pid, field, "ungueltig", why, text(field, old), ""));
                } else {
                    Object was = old;
                    if (field.equals("restrictions")) {
                        List<Object> stripped = new ArrayList<>();
                        for (Map<String, Object> r : own) {
                            Map<String, Object> c = new LinkedHashMap<>(r);
                            c.remove("personId");
                            stripped.add(c);
                        }
                        was = stripped;
                    }
                    String status = normEq(norm(was), norm(value)) ? "gleich_quelle" : "angewendet";
                    rows.add(row(pid, field, status, "", text(field, old), text(field, value)));
                    if (field.equals("restrictions")) {
                        List<Map<String, Object>> rest = new ArrayList<>();
                        for (Map<String, Object> r : (List<Map<String, Object>>) dataset.get("restrictions")) {
                            if (!Py.eq(r.get("personId"), pid)) {
                                rest.add(r);
                            }
                        }
                        int at = rest.size();
                        for (int i = 0; i < rest.size(); i++) {
                            if (Py.compare(rest.get(i).get("personId"), pid) > 0) {
                                at = i;
                                break;
                            }
                        }
                        List<Map<String, Object>> result = new ArrayList<>(rest.subList(0, at));
                        for (Object r : (List<?>) value) {
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("personId", pid);
                            c.putAll((Map<String, Object>) r);
                            result.add(c);
                        }
                        result.addAll(rest.subList(at, rest.size()));
                        dataset.put("restrictions", result);
                    } else if (field.equals("shiftMix") && value == null) {
                        person.remove("shiftMix");
                    } else {
                        person.put(field, field.equals("weeklyHours") || field.equals("hoursAccount")
                                ? (Object) Py.at(FILE, 328, () -> Py.toDouble(value)) : value);
                    }
                    applied++;
                    changed = true;
                    continue;
                }
                skipped++;
                warnings.add("Korrektur " + pid + " " + (FIELDS.contains(field) || FIXED.contains(field) ? field
                        : "(unbekanntes Feld)") + " übersprungen: " + why);
            }
            corrected += changed ? 1 : 0;
        }
        if (!v2) {
            return new Applied(rows, warnings, corrected, applied, skipped, null);
        }
        List<String> areaIds = new ArrayList<>();
        for (Map<String, Object> a : (List<Map<String, Object>>) dataset.get("areas")) {
            areaIds.add((String) a.get("id"));
        }
        Map<String, Object> demand = (Map<String, Object>) corrections.get("demand");
        int replaced = 0;
        int wrong = 0;
        List<String> areas = new ArrayList<>(demand.keySet());
        areas.sort(Comparator.comparingInt(a -> areaIds.contains(a) ? areaIds.indexOf(a) : areaIds.size()));
        for (String area : areas) {
            Object rules = demand.get(area);
            List<Map<String, Object>> own = new ArrayList<>();
            for (Map<String, Object> r : (List<Map<String, Object>>) dataset.get("demand")) {
                if (Py.eq(r.get("areaId"), area)) {
                    own.add(r);
                }
            }
            boolean knownArea = areaIds.contains(area);
            String why = !knownArea ? "unbekannte Bereichs-Id" : demandRules(rules);
            String field = knownArea ? "demand:" + area : "demand";
            if (why != null) {
                rows.add(row("", field, "ungueltig", why, knownArea ? own.size() + " Regeln" : "", ""));
                wrong++;
                warnings.add("Korrektur " + field + " übersprungen: " + why);
                continue;
            }
            List<Object> was = new ArrayList<>();
            for (Map<String, Object> r : own) {
                Map<String, Object> c = new LinkedHashMap<>(r);
                c.remove("areaId");
                was.add(c);
            }
            String status = normEq(norm(was), norm(rules)) ? "gleich_quelle" : "angewendet";
            rows.add(row("", field, status, "", own.size() + " Regeln", ((List<?>) rules).size() + " Regeln"));
            List<Map<String, Object>> all = (List<Map<String, Object>>) dataset.get("demand");
            int before = all.size();
            for (int i = 0; i < all.size(); i++) {
                if (Py.eq(all.get(i).get("areaId"), area)) {
                    before = i;
                    break;
                }
            }
            int at = 0;
            for (int i = 0; i < before; i++) {
                at += Py.eq(all.get(i).get("areaId"), area) ? 0 : 1;
            }
            List<Map<String, Object>> rest = new ArrayList<>();
            for (Map<String, Object> r : all) {
                if (!Py.eq(r.get("areaId"), area)) {
                    rest.add(r);
                }
            }
            List<Map<String, Object>> result = new ArrayList<>(rest.subList(0, at));
            for (Object r : (List<?>) rules) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("areaId", area);
                c.putAll((Map<String, Object>) r);
                result.add(c);
            }
            result.addAll(rest.subList(at, rest.size()));
            dataset.put("demand", result);
            replaced++;
        }
        String summary = "Korrekturen Version 2: " + excluded + " Mitarbeiter ausgeschlossen, Bedarf in " + replaced
                + " Bereichen ersetzt, " + wrong + " Bereiche übersprungen";
        return new Applied(rows, warnings, corrected, applied, skipped, summary);
    }

    /** The dataset section appliedCorrections: the applied entries in the format of the file, version 2. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> section(Map<String, Object> corrections, List<List<Object>> rows) {
        Map<String, Object> people = new LinkedHashMap<>();
        Map<String, Object> demand = new LinkedHashMap<>();
        for (List<Object> r : rows) {
            String pid = (String) r.get(0);
            String field = (String) r.get(1);
            Object status = r.get(2);
            if (!"angewendet".equals(status) && !"gleich_quelle".equals(status) && !"ausgeschlossen".equals(status)) {
                continue;
            }
            if (field.startsWith("demand:")) {
                String area = field.substring("demand:".length());
                demand.put(area, ((Map<String, Object>) corrections.get("demand")).get(area));
            } else {
                Object value = ((Map<String, Object>) ((Map<String, Object>) corrections.get("people")).get(pid))
                        .get(field);
                ((Map<String, Object>) people.computeIfAbsent(pid, k -> new LinkedHashMap<String, Object>())).put(field,
                        field.equals("weeklyHours") || field.equals("hoursAccount") ? (Object) Py.toDouble(value)
                                : value);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("format", FORMAT);
        result.put("version", (long) VERSION);
        result.put("people", people);
        result.put("demand", demand);
        return result;
    }
}
