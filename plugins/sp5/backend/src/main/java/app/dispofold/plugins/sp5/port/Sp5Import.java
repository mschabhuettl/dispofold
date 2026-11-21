package app.dispofold.plugins.sp5.port;

import app.dispofold.plugins.sp5.port.Sp5Export.Sp5Error;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the class _Import of sp5export/sp5.py: the read-only import of an SP5 folder into a dataset. Methods keep
 * the names and the order of the Python code. Values are plain Java (see {@link Py}); dict keys taken from table
 * values go through {@link Py#key} so that numbers equal in Python find each other.
 */
final class Sp5Import {

    static final String FILE = "sp5.py";
    static final int MAX_DUTY_MINUTES = 12 * 60;
    static final int HOLIDAY_INDEX = 7;
    static final int PREVIOUS_DAYS = 7;
    static final String DEFAULT_SHIFT_COLOR = "#E8E8E8";
    static final String DEFAULT_ABSENCE_COLOR = "#CCCCCC";
    static final int MAX_PERCENT = 90;
    static final List<String> CATEGORIES = List.of("EARLY", "DAY", "LATE", "NIGHT");
    static final Map<Long, String> BASE_NAMES = new LinkedHashMap<>();
    private static final Pattern TIME_RANGE;

    static {
        BASE_NAMES.put(0L, "Tag");
        BASE_NAMES.put(1L, "Woche");
        BASE_NAMES.put(2L, "Monat");
        BASE_NAMES.put(3L, "gesamt");
        try {
            TIME_RANGE = PyRe.compile("^\\s*(\\d{1,2}:\\d\\d)\\s*-\\s*(\\d{1,2}:\\d\\d)\\s*$");
        } catch (PyRe.Error e) {
            throw new IllegalStateException(e);
        }
    }

    static Map<String, Object> ignoredShiftAbsence() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("code", "EXT");
        t.put("name", "Sonstiger Dienst (ignorierte Schicht)");
        t.put("reducesTarget", true);
        t.put("chargesAccount", false);
        t.put("compensatoryRest", false);
        t.put("color", DEFAULT_ABSENCE_COLOR);
        return t;
    }

    /** A Counter: insertion-ordered counts, 0 for a missing key. */
    static final class Counter extends LinkedHashMap<Object, Long> {
        void add(Object key, long n) {
            merge(key, n, Long::sum);
        }

        long n(Object key) {
            return getOrDefault(key, 0L);
        }
    }

    /** A tuple as dict key (nulls allowed, equal by content). */
    static List<Object> t(Object... values) {
        return Arrays.asList(values);
    }

    static Object k(Object v) {
        return Py.key(v);
    }

    /** One duty: person id, date, shift. */
    record Duty(String pid, LocalDate d, Map<String, Object> s) {
    }

    /** A shift without area or dropped: short name, reason, hours of a duty. */
    record Lost(String shortName, String why, double hours) {
    }

    /** A person decided after the history: the record, entry and exit. */
    record Pending(Map<String, Object> e, LocalDate start, LocalDate end) {
    }

    final Path folder;
    final Map<String, Object> mapping;
    final int hiddenShiftsMonths;
    final int recentMonths;
    final Integer mixMinDuties;
    final String demandSource;
    final String month;
    final List<String> months;
    final int historyMonths;
    final Integer areasFromHistory;
    final Integer shiftsFromHistory;
    final List<String> warnings = new ArrayList<>();
    final List<LocalDate> days;
    final LocalDate first;
    final LocalDate last;
    final LocalDate historyStart;
    final List<List<Object>> reportRows = new ArrayList<>();
    final Counter shiftCounts = new Counter();
    final Map<Object, Counter> areaMonths = new LinkedHashMap<>();
    final Map<Object, LocalDate> lastDuty = new HashMap<>();
    final Map<String, Set<String>> pastAreas = new LinkedHashMap<>();
    Map<Object, Mix> groupRegular = new LinkedHashMap<>();
    final Map<String, List<Map<String, Object>>> tables = new HashMap<>();
    final Map<String, LocalDate[]> employment = new LinkedHashMap<>();

    Map<Object, Map<String, Object>> shiftBySp5;
    Map<Object, Lost> withoutArea;
    Map<Object, Lost> droppedShifts;
    Set<Object> excludedGroups;
    Set<String> excludedPeople;
    Map<Object, Map<String, Object>> personBySp5;
    Map<Object, Pending> noArea;
    Counter dropped;
    Counter bases;
    int stale;
    Map<Object, Map<String, Object>> absenceBySp5;
    List<Object[]> rejected;
    Counter leftOutShifts;
    Counter dutyTotals;

    @SuppressWarnings("unchecked")
    Sp5Import(Path folder, List<String> months, Map<String, Object> mapping, int historyMonths, Integer areasFromHistory,
            Integer shiftsFromHistory, String demand, Integer mixMinDuties, int hiddenShiftsMonths, int recentMonths) {
        this.folder = folder;
        this.mapping = mapping;
        this.hiddenShiftsMonths = hiddenShiftsMonths;
        this.recentMonths = recentMonths;
        this.mixMinDuties = mixMinDuties;
        this.demandSource = demand;
        this.month = months.get(0);
        this.months = months;
        this.historyMonths = historyMonths;
        this.areasFromHistory = areasFromHistory;
        this.shiftsFromHistory = shiftsFromHistory;
        List<LocalDate> all = new ArrayList<>();
        for (String m : months) {
            all.addAll(TimeUtil.monthDays(m));
        }
        this.days = all;
        this.first = all.get(0);
        this.last = all.get(all.size() - 1);
        this.historyStart = Py.at(FILE, 239, () -> TimeUtil.fromIso(TimeUtil.addMonths(month, -historyMonths) + "-01"));
    }

    void warn(String text) {
        warnings.add(text);
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> areas() {
        return (List<Map<String, Object>>) mapping.get("areas");
    }

    List<String> areaIds() {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> a : areas()) {
            ids.add((String) a.get("id"));
        }
        return ids;
    }

    @SuppressWarnings("unchecked")
    List<Object> patterns(String key) {
        Object v = mapping.get(key);
        return v == null && !mapping.containsKey(key) ? List.of() : (List<Object>) v;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> rules(String key) {
        return (List<Map<String, Object>>) mapping.get(key);
    }

    // ── patterns ────────────────────────────────────────────

    static boolean matches(String pattern, String value) {
        if (pattern.startsWith("re:")) {
            try {
                return PyRe.match(PyRe.compile(pattern.substring(3)), value);
            } catch (PyRe.Error e) {
                throw new IllegalStateException("pattern not validated", e);
            }
        }
        return PyRe.fnmatchcase(value, pattern);
    }

    static Map<String, Object> firstRule(List<Map<String, Object>> rules, String value) {
        for (Map<String, Object> r : rules) {
            if (matches((String) r.get("match"), value)) {
                return r;
            }
        }
        return null;
    }

    static boolean any(List<Object> patterns, String value) {
        for (Object p : patterns) {
            if (matches((String) p, value)) {
                return true;
            }
        }
        return false;
    }

    static String text(Object value) {
        return value instanceof String s ? Py.strip(s) : "";
    }

    static String category(String start, String end) {
        int[] se = TimeUtil.interval(start, end);
        if (se[1] > TimeUtil.DAY || se[0] >= 17 * 60) {
            return "NIGHT";
        }
        if (se[0] < 7 * 60) {
            return "EARLY";
        }
        return se[0] >= 10 * 60 ? "LATE" : "DAY";
    }

    static String normTime(String t) {
        return TimeUtil.hhmm(TimeUtil.minutes(t));
    }

    /** _shares: shares of the counts in the order of `keys`, rounded to thousandths, summing to exactly 1. */
    static Map<String, Object> shares(Counter counts, List<String> keys) {
        long total = 0;
        for (String key : keys) {
            total += counts.n(key);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (total == 0) {
            return result;
        }
        Map<String, Long> parts = new LinkedHashMap<>();
        for (String key : keys) {
            long v = Math.floorDiv(2000 * counts.n(key) + total, 2 * total);
            if (v != 0) {
                parts.put(key, v);
            }
        }
        String top = null;
        long sum = 0;
        for (Map.Entry<String, Long> e : parts.entrySet()) {
            if (top == null || e.getValue() > parts.get(top)) {
                top = e.getKey();
            }
            sum += e.getValue();
        }
        if (top == null) {
            throw new Py.PyException("ValueError", FILE, 199);
        }
        parts.put(top, parts.get(top) + 1000 - sum);
        for (Map.Entry<String, Long> e : parts.entrySet()) {
            result.put(e.getKey(), e.getValue() / 1000.0);
        }
        return result;
    }

    static String mixText(Object shares) {
        List<String> items = new ArrayList<>();
        if (Py.truthy(shares)) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) shares).entrySet()) {
                items.add(e.getKey() + " " + Py.fixed(Py.toDouble(e.getValue()), 3));
            }
        }
        return String.join(", ", items);
    }

    static List<Object> row(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    /** `count` empty strings, to spread into a report row. */
    static Object[] blank(int count) {
        Object[] b = new Object[count];
        Arrays.fill(b, "");
        return b;
    }

    /** A report row from values and arrays of values (flattened). */
    static List<Object> flat(Object... parts) {
        List<Object> r = new ArrayList<>();
        for (Object p : parts) {
            if (p instanceof Object[] a) {
                r.addAll(Arrays.asList(a));
            } else {
                r.add(p);
            }
        }
        return r;
    }

    // ── tables ──────────────────────────────────────────────

    Path tablePath(String name) {
        String wanted = (name + ".dbf").toLowerCase(java.util.Locale.ROOT);
        String[] entries = folder.toFile().list();
        if (entries == null) {
            File f = folder.toFile();
            throw new Py.PyException(!f.exists() ? "FileNotFoundError" : f.isDirectory() ? "PermissionError"
                    : "NotADirectoryError", FILE, 172);
        }
        for (String entry : entries) {
            if (entry.toLowerCase(java.util.Locale.ROOT).equals(wanted)) {
                return folder.resolve(entry);
            }
        }
        return null;
    }

    List<Map<String, Object>> table(String name) throws Sp5Error {
        return table(name, false, false);
    }

    List<Map<String, Object>> table(String name, boolean required, boolean optional) throws Sp5Error {
        if (!tables.containsKey(name)) {
            Path path = tablePath(name);
            if (path == null) {
                if (required) {
                    throw new Sp5Error("Tabelle " + name + " fehlt im SP5-Ordner");
                }
                if (!optional) {
                    warn("Tabelle " + name + " fehlt, wird als leer behandelt");
                }
                tables.put(name, new ArrayList<>());
            } else {
                try {
                    tables.put(name, Dbf.read(path));
                } catch (Dbf.DbfError e) {
                    throw new Sp5Error("Tabelle " + name + ": " + e.getMessage());
                }
                if (required && tables.get(name).isEmpty()) {
                    throw new Sp5Error("Tabelle " + name + " ist leer");
                }
            }
        }
        return tables.get(name);
    }

    // ── master data ─────────────────────────────────────────

    static Counter timesOf(Map<String, Object> r) {
        Counter times = new Counter();
        for (int k = 0; k < 8; k++) {
            Matcher m = TIME_RANGE.matcher(text(r.get("STARTEND" + k)));
            if (m.lookingAt()) {
                times.add(t(normTime(m.group("g1")), normTime(m.group("g2"))), 1);
            }
        }
        return times;
    }

    @SuppressWarnings("unchecked")
    static List<Object> mostCommon(Counter times) {
        Object best = null;
        long n = -1;
        for (Map.Entry<Object, Long> e : times.entrySet()) {
            if (e.getValue() > n) {
                best = e.getKey();
                n = e.getValue();
            }
        }
        return (List<Object>) best;
    }

    static double hoursOf(Counter times) {
        if (times.isEmpty()) {
            return 0.0;
        }
        List<Object> se = mostCommon(times);
        int[] ab = TimeUtil.interval((String) se.get(0), (String) se.get(1));
        return (ab[1] - ab[0]) / 60.0;
    }

    void shifts() throws Sp5Error {
        Set<String> areaIdSet = new HashSet<>(areaIds());
        List<Object> ignore = patterns("ignoreShifts");
        shiftBySp5 = new LinkedHashMap<>();
        withoutArea = new LinkedHashMap<>();
        droppedShifts = new LinkedHashMap<>();
        Map<Object, Map<String, Object>> hidden = new LinkedHashMap<>();
        Map<Object, Object> hiddenIds = new HashMap<>();
        for (Map<String, Object> r : table("5SHIFT", true, false)) {
            if (Py.truthy(r.get("HIDE"))) {
                hidden.put(k(r.get("ID")), r);
                hiddenIds.putIfAbsent(k(r.get("ID")), r.get("ID"));
            }
        }
        Object[] inUseAndUse = hiddenInUse(hidden);
        @SuppressWarnings("unchecked")
        Set<Object> inUse = (Set<Object>) inUseAndUse[0];
        @SuppressWarnings("unchecked")
        Map<Object, Object[]> use = (Map<Object, Object[]>) inUseAndUse[1];
        for (Map<String, Object> r : table("5SHIFT", true, false)) {
            Object sp5Id = r.get("ID");
            String shortName = text(r.get("SHORTNAME"));
            if (Py.truthy(r.get("HIDE")) && !inUse.contains(k(sp5Id))) {
                droppedShifts.put(k(sp5Id), new Lost(shortName, "ausgeblendet", hoursOf(timesOf(r))));
                continue;
            }
            if (any(ignore, shortName)) {
                withoutArea.put(k(sp5Id), new Lost(shortName, "ignoriert", hoursOf(timesOf(r))));
                continue;
            }
            Map<String, Object> rule = firstRule(rules("shifts"), shortName);
            if (rule == null || !areaIdSet.contains(rule.get("areaId"))) {
                warn("Schicht ohne Bereich ignoriert: SP5-Id " + Py.str(sp5Id) + ", Kürzel " + Py.strRepr(shortName));
                withoutArea.put(k(sp5Id), new Lost(shortName, "nicht zugeordnet", hoursOf(timesOf(r))));
                continue;
            }
            Counter times = timesOf(r);
            if (times.isEmpty()) {
                warn("Schicht ohne Dienstzeit ignoriert: SP5-Id " + Py.str(sp5Id) + ", Kürzel "
                        + Py.strRepr(shortName));
                droppedShifts.put(k(sp5Id), new Lost(shortName, "ohne_dienstzeit", 0.0));
                continue;
            }
            List<Object> se = mostCommon(times);
            String start = (String) se.get(0);
            String end = (String) se.get(1);
            if (times.size() > 1) {
                warn("Schicht " + Py.strRepr(shortName) + " (SP5-Id " + Py.str(sp5Id)
                        + ") hat je Wochentag andere Zeiten; verwendet wird " + start + "-" + end);
            }
            int[] ab = TimeUtil.interval(start, end);
            if (ab[1] - ab[0] > MAX_DUTY_MINUTES) {
                warn("Schicht " + Py.strRepr(shortName) + " (SP5-Id " + Py.str(sp5Id) + ") dauert mehr als 12 Stunden ("
                        + start + "-" + end + ") und wird nicht übernommen");
                droppedShifts.put(k(sp5Id), new Lost(shortName, "zu_lang", (ab[1] - ab[0]) / 60.0));
                continue;
            }
            Object ruleCategory = rule.get("category");
            String cat = Py.truthy(ruleCategory) ? (String) ruleCategory : category(start, end);
            Object color = r.get("COLORBK");
            Map<String, Object> shift = new LinkedHashMap<>();
            shift.put("id", "S" + Py.str(sp5Id));
            shift.put("areaId", rule.get("areaId"));
            shift.put("code", shortName);
            String name = text(r.get("NAME"));
            shift.put("name", name.isEmpty() ? shortName : name);
            shift.put("start", start);
            shift.put("end", end);
            shift.put("category", cat);
            shift.put("color", Py.isIntLike(color) && Py.compare(color, 0L) > 0 ? Sp5Calc.bgrToHex(Py.pyInt(color))
                    : DEFAULT_SHIFT_COLOR);
            shift.put("unpopular", rule.containsKey("unpopular") ? rule.get("unpopular") : cat.equals("NIGHT"));
            shiftBySp5.put(k(sp5Id), shift);
        }
        if (hidden.isEmpty()) {
            return;
        }
        List<Object> sorted = new ArrayList<>(use.keySet());
        sorted.sort((a, b) -> Py.at(FILE, 337, () -> Py.compare(a, b)));
        for (Object sp5Id : sorted) {
            long n = (Long) use.get(sp5Id)[0];
            LocalDate lastDay = (LocalDate) use.get(sp5Id)[1];
            Map<String, Object> h = hidden.get(sp5Id);
            String shortName = text(h.get("SHORTNAME"));
            double hours = hoursOf(timesOf(h));
            boolean taken = shiftBySp5.containsKey(sp5Id);
            String why = taken ? "" : !inUse.contains(sp5Id) ? "nicht_in_gebrauch"
                    : (withoutArea.containsKey(sp5Id) ? withoutArea.get(sp5Id) : droppedShifts.get(sp5Id)).why()
                            .replace(" ", "_");
            reportRows.add(flat("ausgeblendet", "", "", shortName, n, blank(6), Py.fixed(n * hours, 1), blank(6), why,
                    hiddenIds.get(sp5Id), blank(4), taken ? "uebernommen" : "verworfen", blank(3),
                    lastDay.toString()));
        }
        long takenCount = hidden.keySet().stream().filter(shiftBySp5::containsKey).count();
        warn("Ausgeblendete Schichten: " + takenCount + " übernommen, weil in Gebrauch (Dienste in den Planungsmonaten "
                + "oder den " + hiddenShiftsMonths + " Monaten davor), " + (hidden.size() - takenCount)
                + " nicht übernommen");
    }

    /** {in use (set of hidden shift keys), use (key -> {duties, date of the last one})}. */
    Object[] hiddenInUse(Map<Object, Map<String, Object>> hidden) throws Sp5Error {
        Set<Object> inUse = new HashSet<>();
        Map<Object, Object[]> use = new LinkedHashMap<>();
        if (hidden.isEmpty()) {
            return new Object[] { inUse, use };
        }
        LocalDate other = Py.at(FILE, 357, () -> TimeUtil.fromIso(TimeUtil.addMonths(month, -hiddenShiftsMonths)
                + "-01"));
        LocalDate recent = historyStart.isAfter(other) ? historyStart : other;
        Set<Object> seen = new HashSet<>();
        for (Map<String, Object> r : rows(historyStart, last).get(1)) {
            Object sid = k(r.get("SHIFTID"));
            LocalDate d = TimeUtil.parseDate(r.get("DATE"));
            if (d == null || d.isBefore(historyStart) || d.isAfter(last) || seen.contains(t(k(r.get("EMPLOYEEID")), d))) {
                continue;
            }
            seen.add(t(k(r.get("EMPLOYEEID")), d));
            if (!hidden.containsKey(sid)) {
                continue;
            }
            Object[] prev = use.getOrDefault(sid, new Object[] { 0L, d });
            LocalDate lastDay = (LocalDate) prev[1];
            use.put(sid, new Object[] { (Long) prev[0] + 1, lastDay.isAfter(d) ? lastDay : d });
            if (hiddenShiftsMonths != 0 && !d.isBefore(recent)) {
                inUse.add(sid);
            }
        }
        return new Object[] { inUse, use };
    }

    @SuppressWarnings("unchecked")
    void people() throws Sp5Error {
        Map<Object, Object> own = new HashMap<>();
        Map<Object, Object> parent = new LinkedHashMap<>();
        List<Map<String, Object>> unmapped = new ArrayList<>();
        List<Object> exclude = patterns("excludeGroups");
        List<Object> ignore = patterns("ignoreGroups");
        for (Map<String, Object> g : table("5GROUP", true, false)) {
            String name = text(g.get("NAME"));
            Object gid = k(g.get("ID"));
            parent.put(gid, k(Py.truthy(g.get("SUPERID")) ? g.get("SUPERID") : 0L));
            Map<String, Object> rule;
            if (any(exclude, name)) {
                own.put(gid, "exclude");
            } else if ((rule = firstRule(rules("groups"), name)) != null) {
                own.put(gid, new LinkedHashSet<>((List<String>) rule.get("areaIds")));
            } else if (any(ignore, name)) {
                own.put(gid, "ignore");
            } else {
                unmapped.add(g);
            }
        }
        Map<Object, Object> groups = new LinkedHashMap<>();
        for (Object gid : parent.keySet()) {
            Set<Object> seen = new HashSet<>();
            Object cur = gid;
            while (Py.truthy(cur) && !own.containsKey(cur) && !seen.contains(cur)) {
                seen.add(cur);
                cur = parent.getOrDefault(cur, 0L);
            }
            if (own.containsKey(cur)) {
                groups.put(gid, own.get(cur));
            }
        }
        for (Map<String, Object> g : unmapped) {
            if (!groups.containsKey(k(g.get("ID")))) {
                warn("Gruppe ohne Zuordnung: SP5-Id " + Py.str(g.get("ID")) + ", Kürzel "
                        + Py.strRepr(text(g.get("SHORTNAME"))));
            }
        }
        excludedGroups = new HashSet<>();
        for (Map.Entry<Object, Object> e : groups.entrySet()) {
            if ("exclude".equals(e.getValue())) {
                excludedGroups.add(e.getKey());
            }
        }
        Map<Object, List<Object>> member = new LinkedHashMap<>();
        Map<Object, Object> memberIds = new HashMap<>();
        for (Map<String, Object> a : table("5GRASG", true, false)) {
            Object g = groups.getOrDefault(k(a.get("GROUPID")), "ignore");
            if (!"ignore".equals(g)) {
                member.computeIfAbsent(k(a.get("EMPLOYEEID")), x -> new ArrayList<>()).add(g);
                memberIds.putIfAbsent(k(a.get("EMPLOYEEID")), a.get("EMPLOYEEID"));
            }
        }
        excludedPeople = new HashSet<>();
        for (Map.Entry<Object, List<Object>> e : member.entrySet()) {
            if (e.getValue().contains("exclude")) {
                excludedPeople.add("E" + Py.str(memberIds.get(e.getKey())));
            }
        }
        personBySp5 = new LinkedHashMap<>();
        noArea = new LinkedHashMap<>();
        dropped = new Counter();
        bases = new Counter();
        stale = 0;
        for (Map<String, Object> e : table("5EMPL", true, false)) {
            Object eid = k(e.get("ID"));
            LocalDate start = TimeUtil.parseDate(e.get("EMPSTART"));
            LocalDate end = TimeUtil.parseDate(e.get("EMPEND"));
            if (Py.truthy(e.get("HIDE"))) {
                dropped.add("ausgeblendet", 1);
                continue;
            }
            if (start != null && end != null && end.isBefore(start)) {
                dropped.add("Austritt vor Eintritt", 1);
                continue;
            }
            if ((start != null && start.isAfter(last)) || (end != null && end.isBefore(first))) {
                dropped.add("nicht beschäftigt im Monat", 1);
                continue;
            }
            List<Object> values = member.computeIfAbsent(eid, x -> new ArrayList<>());
            if (values.contains("exclude")) {
                dropped.add("ausgeschlossene Gruppe", 1);
                continue;
            }
            Set<String> areas = new HashSet<>();
            for (Object v : values) {
                areas.addAll((Set<String>) v);
            }
            if (areas.isEmpty()) {
                noArea.put(eid, new Pending(e, start, end));
                continue;
            }
            addPerson(e, start, end, areas);
        }
    }

    void addPerson(Map<String, Object> e, LocalDate start, LocalDate end, Set<String> areas) throws Sp5Error {
        Object eid = e.get("ID");
        long base = Py.at(FILE, 433, () -> Py.pyInt(Py.orZero(e.get("CALCBASE"))));
        bases.add(base, 1);
        Double hours = weeklyHours(e, base, start, end);
        if (hours != null && base != 1) {
            double hrsweek = Py.at(FILE, 436, () -> Py.pyFloat(Py.orZero(e.get("HRSWEEK"))));
            if (Math.abs(hours - hrsweek) >= 0.005) {
                stale++;
                reportRows.add(flat("wochenstunden", "E" + Py.str(eid), blank(11), hours > 0 ? (Object) hours : "",
                        blank(6), base, Py.at(FILE, 440, () -> Py.pyFloat(Py.orZero(e.get("HRSWEEK")))), hours));
            }
        }
        if (hours == null || hours <= 0) {
            if (hours != null) {
                warn("Mitarbeiter E" + Py.str(eid) + " hat keine Wochenstunden (CALCBASE " + base
                        + ") und wird nicht übernommen");
            }
            dropped.add("ohne Wochenstunden", 1);
            return;
        }
        employment.put("E" + Py.str(eid), new LocalDate[] { start, end });
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", "E" + Py.str(eid));
        p.put("firstName", text(e.get("FIRSTNAME")));
        p.put("lastName", text(e.get("NAME")));
        p.put("shortName", text(e.get("SHORTNAME")));
        p.put("weeklyHours", hours);
        List<String> areaIds = new ArrayList<>();
        for (String a : areaIds()) {
            if (areas.contains(a)) {
                areaIds.add(a);
            }
        }
        p.put("areaIds", areaIds);
        p.put("hoursAccount", 0.0);
        p.put("compRestBalance", 0L);
        p.put("familyRules", new ArrayList<>());
        p.put("externalId", Py.str(eid));
        p.put("entryDate", start != null ? start.toString() : null);
        p.put("exitDate", end != null ? end.toString() : null);
        personBySp5.put(k(eid), p);
    }

    void peopleSummary() {
        long partly = employment.values().stream().filter(se -> (se[0] != null && se[0].isAfter(first))
                || (se[1] != null && se[1].isBefore(last))).count();
        if (partly != 0) {
            warn(partly + " Mitarbeiter nur teilweise im Monat beschäftigt; Ein- und Austritt sind übernommen");
        }
        List<Object> reasons = new ArrayList<>(dropped.keySet());
        reasons.sort((a, b) -> Py.compareStr((String) a, (String) b));
        for (Object reason : reasons) {
            warn(dropped.n(reason) + " Mitarbeiter nicht übernommen (" + reason + ")");
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Long, String> b : BASE_NAMES.entrySet()) {
            parts.add(b.getKey() + " (" + b.getValue() + ") " + bases.n(b.getKey()));
        }
        warn("Wochenstunden nach CALCBASE: " + String.join(", ", parts) + "; HRSWEEK weicht bei " + stale
                + " davon ab");
        warn("Stundenkonto und offene Ersatzruhetage werden aus SP5 nicht übernommen (0)");
    }

    Double weeklyHours(Map<String, Object> e, long base, LocalDate start, LocalDate end) throws Sp5Error {
        Object eid = e.get("ID");
        double hours;
        if (base == 0) {
            boolean[] mask = Sp5Calc.parseDayMask(Py.str(Py.truthy(e.get("WORKDAYS")) ? e.get("WORKDAYS") : ""), 8);
            int days = 0;
            for (int i = 0; i < 7; i++) {
                days += mask[i] ? 1 : 0;
            }
            int d = days;
            hours = Py.at(FILE, 473, () -> Py.pyFloat(Py.orZero(e.get("HRSDAY")))) * d;
        } else if (base == 1) {
            hours = Py.at(FILE, 475, () -> Py.pyFloat(Py.orZero(e.get("HRSWEEK"))));
        } else if (base == 2) {
            hours = Py.at(FILE, 477, () -> Py.pyFloat(Py.orZero(e.get("HRSMONTH")))) * 12 / 52;
        } else if (base == 3) {
            if (start == null || end == null) {
                warn("Mitarbeiter E" + Py.str(eid) + " hat Gesamtstunden (CALCBASE 3) ohne abgeschlossene "
                        + "Beschäftigung (EMPSTART bis EMPEND) und wird nicht übernommen");
                return null;
            }
            hours = Py.at(FILE, 483, () -> Py.pyFloat(Py.orZero(e.get("HRSTOTAL"))))
                    / ((ChronoUnit.DAYS.between(start, end) + 1) / 7.0);
        } else {
            throw new Sp5Error("Mitarbeiter E" + Py.str(eid) + ": unbekannte Berechnungsgrundlage CALCBASE " + base);
        }
        return Py.round(hours, 2);
    }

    List<Map<String, Object>> restrictions() throws Sp5Error {
        Map<Object, Map<Long, String>> grades = new LinkedHashMap<>();
        int odd = 0;
        for (Map<String, Object> r : table("5RESTR")) {
            Map<String, Object> person = personBySp5.get(k(r.get("EMPLOYEEID")));
            Map<String, Object> shift = shiftBySp5.get(k(r.get("SHIFTID")));
            Object grade = r.get("RESTRICT");
            if (person == null || shift == null || Py.eq(grade, 0L)) {
                continue;
            }
            if (!Py.eq(grade, 1L) && !Py.eq(grade, 2L)) {
                odd++;
            }
            String kind = Py.eq(grade, 1L) ? "ON_REQUEST" : "NEVER";
            long weekday = Py.at(FILE, 498, () -> Py.pyInt(Py.orZero(r.get("WEEKDAY"))));
            grades.computeIfAbsent(t(person.get("id"), shift.get("id")), x -> new LinkedHashMap<>()).put(weekday, kind);
        }
        List<Object> keys = new ArrayList<>(grades.keySet());
        keys.sort((a, b) -> Py.compare(a, b));
        List<Map<String, Object>> result = new ArrayList<>();
        int holidayOnly = 0;
        for (Object key : keys) {
            List<?> pair = (List<?>) key;
            Map<Long, String> byDay = grades.get(key);
            Set<String> kinds = new HashSet<>();
            for (long i = 0; i < 7; i++) {
                kinds.add(byDay.get(i));
            }
            if (kinds.size() == 1 && !kinds.contains(null)) {
                result.add(restriction((String) pair.get(0), (String) pair.get(1), 0L, kinds.iterator().next()));
                continue;
            }
            for (long i = 0; i < 7; i++) {
                if (byDay.containsKey(i)) {
                    result.add(restriction((String) pair.get(0), (String) pair.get(1), i + 1, byDay.get(i)));
                }
            }
            holidayOnly += byDay.containsKey((long) HOLIDAY_INDEX) ? 1 : 0;
        }
        if (holidayOnly != 0) {
            warn(holidayOnly + " Einschränkungen nur für Feiertage nicht übernommen");
        }
        if (odd != 0) {
            warn(odd + " Einschränkungen ohne gültigen Grad (RESTRICT) als 'nie' übernommen");
        }
        return result;
    }

    static Map<String, Object> restriction(String pid, String sid, long weekday, String kind) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("personId", pid);
        r.put("shiftId", sid);
        r.put("weekday", weekday);
        r.put("kind", kind);
        return r;
    }

    List<Map<String, Object>> absenceTypes() throws Sp5Error {
        List<Object> compensatory = patterns("compensatoryRestAbsences");
        List<Object> ignore = patterns("ignoreAbsences");
        Map<Object, Map<String, Object>> types = new LinkedHashMap<>();
        for (Map<String, Object> t : table("5LEAVT")) {
            String code = text(t.get("SHORTNAME"));
            if (code.isEmpty() || any(ignore, code)) {
                continue;
            }
            Object color = t.get("COLORBK");
            boolean credited = Py.truthy(t.get("CHARGETYP"));
            Map<String, Object> type = new LinkedHashMap<>();
            type.put("code", code);
            String name = text(t.get("NAME"));
            type.put("name", name.isEmpty() ? code : name);
            type.put("reducesTarget", credited);
            type.put("chargesAccount", credited && (Py.truthy(t.get("DEDUCTACT")) || Py.truthy(t.get("DEDUCTOVT"))));
            type.put("compensatoryRest", any(compensatory, code));
            type.put("color", Py.isIntLike(color) && Py.compare(color, 0L) > 0 ? Sp5Calc.bgrToHex(Py.pyInt(color))
                    : DEFAULT_ABSENCE_COLOR);
            types.put(k(t.get("ID")), type);
        }
        absenceBySp5 = types;
        List<Map<String, Object>> result = new ArrayList<>();
        Set<Object> codes = new HashSet<>();
        for (Map<String, Object> type : types.values()) {
            if (codes.add(type.get("code"))) {
                result.add(type);
            }
        }
        return result;
    }

    List<Map<String, Object>> absences() throws Sp5Error {
        Set<Object> seen = new HashSet<>();
        List<Map<String, Object>> result = new ArrayList<>();
        int doubled = 0;
        for (Map<String, Object> a : table("5ABSEN")) {
            Map<String, Object> person = personBySp5.get(k(a.get("EMPLOYEEID")));
            Map<String, Object> kind = absenceBySp5.get(k(a.get("LEAVETYPID")));
            LocalDate day = TimeUtil.parseDate(a.get("DATE"));
            if (person == null || kind == null || day == null || day.isBefore(first) || day.isAfter(last)) {
                continue;
            }
            LocalDate[] se = employment.get((String) person.get("id"));
            if ((se[0] != null && day.isBefore(se[0])) || (se[1] != null && day.isAfter(se[1]))) {
                continue;
            }
            List<Object> key = t(person.get("id"), day.toString());
            if (seen.contains(key)) {
                doubled++;
                continue;
            }
            seen.add(key);
            result.add(absence((String) key.get(0), (String) key.get(1), (String) kind.get("code")));
        }
        if (doubled != 0) {
            warn(doubled + " weitere Abwesenheiten am selben Tag ignoriert");
        }
        result.sort(ABSENCE_ORDER);
        return result;
    }

    static final Comparator<Map<String, Object>> ABSENCE_ORDER = (a, b) -> {
        int c = Py.compareStr((String) a.get("personId"), (String) b.get("personId"));
        return c != 0 ? c : Py.compareStr((String) a.get("date"), (String) b.get("date"));
    };

    static Map<String, Object> absence(String pid, String date, String code) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("personId", pid);
        a.put("date", date);
        a.put("typeCode", code);
        return a;
    }

    List<Map<String, Object>> ignoredDutyAbsences(List<Map<String, Object>> absences, List<Map<String, Object>> types)
            throws Sp5Error {
        Object flag = mapping.containsKey("ignoreShiftsAsAbsence") ? mapping.get("ignoreShiftsAsAbsence") : true;
        if (!Py.truthy(flag)) {
            return List.of();
        }
        Map<Object, Object> lost = new LinkedHashMap<>();
        duties(first, last, null, true, lost, null);
        Set<Object> taken = new HashSet<>();
        for (Map<String, Object> a : absences) {
            taken.add(t(a.get("personId"), a.get("date")));
        }
        String code = "EXT";
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Object[]> perMonth = new LinkedHashMap<>();
        List<Object> keys = new ArrayList<>(lost.keySet());
        keys.sort((a, b) -> Py.compare(a, b));
        for (Object key : keys) {
            List<?> pd = (List<?>) key;
            String pid = (String) pd.get(0);
            LocalDate d = (LocalDate) pd.get(1);
            Lost l = withoutArea.get(lost.get(key));
            LocalDate[] se = employment.get(pid);
            if (!l.why().equals("ignoriert") || taken.contains(t(pid, d.toString()))
                    || (se[0] != null && d.isBefore(se[0])) || (se[1] != null && d.isAfter(se[1]))) {
                continue;
            }
            result.add(absence(pid, d.toString(), code));
            Object[] pm = perMonth.computeIfAbsent(d.toString().substring(0, 7), x -> new Object[] { 0L, 0.0 });
            pm[0] = (Long) pm[0] + 1;
            pm[1] = (Double) pm[1] + l.hours();
        }
        if (!result.isEmpty() && types.stream().anyMatch(x -> code.equals(x.get("code")))) {
            throw new Sp5Error("Abwesenheitsart 'EXT' gibt es schon in SP5 (5LEAVT); sie wird für Dienste auf "
                    + "ignorierten Schichten gebraucht. In der Zuordnung 'ignoreAbsences' um 'EXT' ergänzen oder "
                    + "'ignoreShiftsAsAbsence' auf false setzen");
        }
        List<String> monthKeys = new ArrayList<>(perMonth.keySet());
        monthKeys.sort(Py::compareStr);
        for (String m : monthKeys) {
            Object[] pm = perMonth.get(m);
            warn("Dienste auf ignorierten Schichten im " + m + ": " + pm[0] + " Tage, " + Py.fixed((Double) pm[1], 1)
                    + " Stunden als Abwesenheit 'EXT' übernommen");
        }
        return result;
    }

    /** (person id, date) of full-day absences of exported types in [start, end]. */
    Set<Object> absent(LocalDate start, LocalDate end, boolean compensatory, Map<Object, Map<String, Object>> people)
            throws Sp5Error {
        Set<Object> result = new HashSet<>();
        Map<Object, Map<String, Object>> who = people == null ? personBySp5 : people;
        for (Map<String, Object> a : table("5ABSEN")) {
            Map<String, Object> person = who.get(k(a.get("EMPLOYEEID")));
            Map<String, Object> kind = absenceBySp5.get(k(a.get("LEAVETYPID")));
            LocalDate d = TimeUtil.parseDate(a.get("DATE"));
            if (person != null && !person.isEmpty() && kind != null && !kind.isEmpty() && d != null
                    && !d.isBefore(start) && !d.isAfter(end) && !Py.truthy(a.get("INTERVAL"))
                    && (Py.truthy(kind.get("compensatoryRest")) || !compensatory)) {
                result.add(t(person.get("id"), d));
            }
        }
        return result;
    }

    /** {holidays of the dataset, all SP5 holiday dates}. */
    Object[] holidays() throws Sp5Error {
        LocalDate dayBefore = Py.at(FILE, 601, () -> TimeUtil.plusDays(first, -1));
        int fromYear = (historyStart.isBefore(dayBefore) ? historyStart : dayBefore).getYear();
        int toYear = last.getYear();
        Map<String, String> full = new HashMap<>();
        Set<String> sp5Dates = new HashSet<>();
        for (Map<String, Object> h : table("5HOLID")) {
            LocalDate d = TimeUtil.parseDate(h.get("DATE"));
            if (d != null && d.getYear() >= fromYear && d.getYear() <= toYear) {
                sp5Dates.add(d.toString());
                if (!Py.truthy(h.get("INTERVAL"))) {
                    String name = text(h.get("NAME"));
                    full.put(d.toString(), name.isEmpty() ? "Feiertag" : name);
                }
            }
        }
        if (sp5Dates.isEmpty()) {
            warn("Keine Feiertage in SP5 (5HOLID); verwendet werden die österreichischen Feiertage");
            List<Map<String, Object>> result = new ArrayList<>();
            for (int y = fromYear; y <= toYear; y++) {
                result.addAll(Holidays.austrianHolidays(y));
            }
            Set<String> dates = new HashSet<>();
            for (Map<String, Object> h : result) {
                dates.add((String) h.get("date"));
            }
            return new Object[] { result, dates };
        }
        int half = sp5Dates.size() - full.size();
        if (half != 0) {
            warn(half + " halbe Feiertage (5HOLID INTERVAL) nicht als Feiertag übernommen");
        }
        List<String> dates = new ArrayList<>(full.keySet());
        dates.sort(Py::compareStr);
        List<Map<String, Object>> result = new ArrayList<>();
        for (String d : dates) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("date", d);
            h.put("name", full.get(d));
            result.add(h);
        }
        return new Object[] { result, sp5Dates };
    }

    // ── demand ──────────────────────────────────────────────

    /** A window (from, to, min, max) with max null for Python None. */
    static List<Integer> window(int a, int b, int min, Integer max) {
        return Arrays.asList(a, b, min, max);
    }

    static final Comparator<List<Integer>> WINDOW_ORDER = (x, y) -> {
        for (int i = 0; i < 4; i++) {
            Integer a = x.get(i);
            Integer b = y.get(i);
            if (a == null || b == null) {
                if (a != b) {
                    throw new Py.Raised("TypeError");
                }
                continue;
            }
            if (!a.equals(b)) {
                return Integer.compare(a, b);
            }
        }
        return 0;
    };

    static List<Map<String, Object>> rulesOf(String area, List<String> days, String on, List<List<Integer>> windows) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (List<Integer> w : windows) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("areaId", area);
            r.put("days", new ArrayList<>(days));
            r.put("date", on);
            r.put("from", TimeUtil.hhmm(w.get(0)));
            r.put("to", TimeUtil.hhmm(w.get(1)));
            r.put("min", (long) w.get(2));
            r.put("max", w.get(3) == null ? null : (Object) (long) w.get(3));
            result.add(r);
        }
        return result;
    }

    /** One entry of a demand mix: {min, max, max complete}. */
    static final class Mix {
        long min;
        long max;
        boolean complete = true;

        Mix copy() {
            Mix m = new Mix();
            m.min = min;
            m.max = max;
            m.complete = complete;
            return m;
        }
    }

    Map<Object, Mix> mixForIndex(Map<Object, Mix> regular, String area, int idx) {
        Map<Object, Mix> result = new LinkedHashMap<>();
        for (Map.Entry<Object, Mix> e : regular.entrySet()) {
            List<?> key = (List<?>) e.getKey();
            if (key.get(0).equals(area) && Py.eq(key.get(1), (long) idx) && (e.getValue().min != 0
                    || e.getValue().max != 0)) {
                result.put(key.get(2), e.getValue());
            }
        }
        return result;
    }

    List<int[]> spans(Map<Object, Mix> mix, int pos) {
        List<int[]> result = new ArrayList<>();
        for (Map.Entry<Object, Mix> e : mix.entrySet()) {
            long v = pos == 0 ? e.getValue().min : e.getValue().max;
            if (v != 0) {
                Map<String, Object> s = shiftBySp5.get(e.getKey());
                int[] se = TimeUtil.interval((String) s.get("start"), (String) s.get("end"));
                result.add(new int[] { se[0], se[1], (int) v });
            }
        }
        return result;
    }

    List<List<Integer>> windows(Map<Object, Mix> today, Map<Object, Mix> yesterday) {
        List<int[]> mins = Demand.coverageSegments(spans(today, 0), spans(yesterday, 0));
        boolean limited = today.values().stream().noneMatch(v -> !v.complete)
                && yesterday.values().stream().noneMatch(v -> !v.complete);
        List<int[]> maxes = limited ? Demand.coverageSegments(spans(today, 1), spans(yesterday, 1)) : List.of();
        List<List<Integer>> result = new ArrayList<>();
        for (int[] m : mins) {
            java.util.TreeSet<Integer> cuts = new java.util.TreeSet<>(List.of(m[0], m[1]));
            for (int[] x : maxes) {
                for (int v : new int[] { x[0], x[1] }) {
                    if (m[0] < v && v < m[1]) {
                        cuts.add(v);
                    }
                }
            }
            Integer[] c = cuts.toArray(new Integer[0]);
            for (int i = 0; i + 1 < c.length; i++) {
                int lo = c[i];
                Integer top = null;
                if (limited) {
                    for (int[] x : maxes) {
                        if (x[0] <= lo && lo < x[1]) {
                            top = x[2];
                            break;
                        }
                    }
                }
                result.add(window(lo, c[i + 1], m[2], top != null ? Math.max(top, m[2]) : null));
            }
        }
        return result;
    }

    List<Map<String, Object>> demand(Set<String> sp5HolidayDates, Set<String> holidayDates, boolean genericOnly)
            throws Sp5Error {
        Map<Object, Mix> regular = new LinkedHashMap<>();
        for (Map<String, Object> r : table("5SHDEM")) {
            Map<String, Object> shift = shiftBySp5.get(k(r.get("SHIFTID")));
            if (shift == null || excludedGroups.contains(k(r.get("GROUPID")))) {
                continue;
            }
            Mix entry = regular.computeIfAbsent(t(shift.get("areaId"),
                    Py.at(FILE, 628, () -> Py.pyInt(Py.orZero(r.get("WEEKDAY")))), k(r.get("SHIFTID"))), x -> new Mix());
            entry.min += Py.at(FILE, 629, () -> Py.pyInt(Py.orZero(r.get("MIN"))));
            entry.max += Py.at(FILE, 630, () -> Py.pyInt(Py.orZero(r.get("MAX"))));
            entry.complete = entry.complete && Py.truthy(r.get("MAX"));
        }
        Map<LocalDate, Map<Object, Mix>> special = new LinkedHashMap<>();
        for (Map<String, Object> r : table("5SPDEM")) {
            Map<String, Object> shift = shiftBySp5.get(k(r.get("SHIFTID")));
            LocalDate d = TimeUtil.parseDate(r.get("DATE"));
            if (shift != null && d != null && !excludedGroups.contains(k(r.get("GROUPID")))) {
                Mix e = special.computeIfAbsent(d, x -> new LinkedHashMap<>())
                        .computeIfAbsent(t(k(r.get("GROUPID")), k(r.get("SHIFTID"))), x -> new Mix());
                e.min += Py.at(FILE, 637, () -> Py.pyInt(Py.orZero(r.get("MIN"))));
                e.max += Py.at(FILE, 638, () -> Py.pyInt(Py.orZero(r.get("MAX"))));
                e.complete = e.complete && Py.truthy(r.get("MAX"));
            }
        }
        Map<Object, Mix> groupRegularMix = new LinkedHashMap<>();
        if (!special.isEmpty()) {
            for (Map<String, Object> r : table("5SHDEM")) {
                if (shiftBySp5.containsKey(k(r.get("SHIFTID"))) && !excludedGroups.contains(k(r.get("GROUPID")))) {
                    Mix e = groupRegularMix.computeIfAbsent(t(k(r.get("GROUPID")),
                            Py.at(FILE, 645, () -> Py.pyInt(Py.orZero(r.get("WEEKDAY")))), k(r.get("SHIFTID"))),
                            x -> new Mix());
                    e.min += Py.at(FILE, 646, () -> Py.pyInt(Py.orZero(r.get("MIN"))));
                    e.max += Py.at(FILE, 647, () -> Py.pyInt(Py.orZero(r.get("MAX"))));
                    e.complete = e.complete && Py.truthy(r.get("MAX"));
                }
            }
            groupRegular = new LinkedHashMap<>(groupRegularMix);
        }
        List<String> areas = areaIds();
        List<Map<String, Object>> rules = new ArrayList<>();
        Map<String, Boolean> hasHoliday = new HashMap<>();
        for (String a : areas) {
            hasHoliday.put(a, !mixForIndex(regular, a, HOLIDAY_INDEX).isEmpty());
        }
        for (String area : areas) {
            Map<List<List<Integer>>, List<String>> groups = new LinkedHashMap<>();
            for (int idx = 0; idx < 7; idx++) {
                List<List<Integer>> w = windows(mixForIndex(regular, area, idx),
                        mixForIndex(regular, area, Math.floorMod(idx - 1, 7)));
                groups.computeIfAbsent(w, x -> new ArrayList<>()).add(TimeUtil.WEEKDAY_CODES.get(idx));
            }
            for (Map.Entry<List<List<Integer>>, List<String>> g : groups.entrySet()) {
                rules.addAll(rulesOf(area, g.getValue(), null, g.getKey()));
            }
            if (hasHoliday.get(area)) {
                Map<Object, Mix> hol = mixForIndex(regular, area, HOLIDAY_INDEX);
                rules.addAll(rulesOf(area, List.of("HOL"), null, windows(hol, hol)));
            }
        }
        if (genericOnly) {
            return rules;
        }
        Map<String, Object> draft = new HashMap<>();
        draft.put("demand", rules);
        List<Map<String, Object>> dated = new ArrayList<>();
        for (String area : areas) {
            for (LocalDate d : days) {
                LocalDate before = Py.at(FILE, 707, () -> TimeUtil.plusDays(d, -1));
                List<List<Integer>> exact = windows(mixForDate(regular, special, sp5HolidayDates, area, d),
                        mixForDate(regular, special, sp5HolidayDates, area, before));
                List<List<Integer>> generic = new ArrayList<>();
                for (Map<String, Object> r : rulesForDay(draft, area, d, holidayDates)) {
                    int[] se = TimeUtil.interval((String) r.get("from"), (String) r.get("to"));
                    Object max = r.get("max");
                    generic.add(window(se[0], se[1], (int) Py.pyInt(r.get("min")),
                            max == null ? null : (int) Py.pyInt(max)));
                }
                List<List<Integer>> g = new ArrayList<>(generic);
                List<List<Integer>> x = new ArrayList<>(exact);
                Py.at(FILE, 710, () -> {
                    g.sort(WINDOW_ORDER);
                    x.sort(WINDOW_ORDER);
                    return null;
                });
                if (!g.equals(x)) {
                    if (!exact.isEmpty()) {
                        dated.addAll(rulesOf(area, List.of(), d.toString(), exact));
                    } else {
                        warn("Bereich " + area + ": am " + d + " kein Bedarf in SP5, es gilt der Wochentagsbedarf");
                    }
                }
            }
        }
        List<Map<String, Object>> all = new ArrayList<>(rules);
        all.addAll(dated);
        return all;
    }

    Map<Object, Mix> mixForDate(Map<Object, Mix> regular, Map<LocalDate, Map<Object, Mix>> special,
            Set<String> sp5HolidayDates, String area, LocalDate d) {
        int idx = sp5HolidayDates.contains(d.toString()) ? HOLIDAY_INDEX : TimeUtil.weekday(d);
        Map<Object, Mix> mix = new LinkedHashMap<>();
        for (Map.Entry<Object, Mix> e : mixForIndex(regular, area, idx).entrySet()) {
            mix.put(e.getKey(), e.getValue().copy());
        }
        for (Map.Entry<Object, Mix> e : special.getOrDefault(d, Map.of()).entrySet()) {
            List<?> gs = (List<?>) e.getKey();
            Object sid = gs.get(1);
            if (!shiftBySp5.get(sid).get("areaId").equals(area)) {
                continue;
            }
            Mix old = groupRegular.get(t(gs.get(0), (long) idx, sid));
            Mix cur = mix.computeIfAbsent(sid, x -> new Mix());
            Mix v = e.getValue();
            cur.min += v.min - (old == null ? 0 : old.min);
            cur.max += v.max - (old == null ? 0 : old.max);
            cur.complete = cur.complete && v.complete;
        }
        return mix;
    }

    /** feasibility.rules_for_day: date rules, then HOL rules, then weekday rules of an area. */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rulesForDay(Map<String, Object> dataset, String areaId, LocalDate day,
            Set<String> holidayDates) {
        List<Map<String, Object>> rules = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) dataset.get("demand")) {
            if (Py.eq(r.get("areaId"), areaId)) {
                rules.add(r);
            }
        }
        String iso = day.toString();
        List<Map<String, Object>> dated = rules.stream().filter(r -> Py.eq(r.get("date"), iso)).toList();
        if (!dated.isEmpty()) {
            return dated;
        }
        List<Map<String, Object>> general = rules.stream().filter(r -> !Py.truthy(r.get("date"))).toList();
        if (holidayDates.contains(iso)) {
            List<Map<String, Object>> hol = general.stream().filter(r -> ((List<?>) r.get("days")).contains("HOL"))
                    .toList();
            if (!hol.isEmpty()) {
                return hol;
            }
        }
        String code = TimeUtil.WEEKDAY_CODES.get(TimeUtil.weekday(day));
        return general.stream().filter(r -> ((List<?>) r.get("days")).contains(code)).toList();
    }

    List<Map<String, Object>> demandFromHistory(Set<String> holidayDates, List<Map<String, Object>> sp5Rules)
            throws Sp5Error {
        LocalDate end = Py.at(FILE, 729, () -> TimeUtil.plusDays(first, -1));
        Map<Object, Map<String, Object>> everyone = new LinkedHashMap<>();
        for (Map<String, Object> e : table("5EMPL", true, false)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", "E" + Py.str(e.get("ID")));
            everyone.put(k(e.get("ID")), p);
        }
        Set<Object> exported = new HashSet<>();
        for (Map<String, Object> p : personBySp5.values()) {
            exported.add(p.get("id"));
        }
        Map<Object, List<int[]>> byDay = new LinkedHashMap<>();
        Counter counted = new Counter();
        Counter leftOut = new Counter();
        Counter outside = new Counter();
        rejected = new ArrayList<>();
        leftOutShifts = new Counter();
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = historyStart; !d.isAfter(end); d = d.plusDays(1)) {
            days.add(d);
        }
        java.util.function.Function<LocalDate, String> dayType = d -> holidayDates.contains(d.toString()) ? "HOL"
                : TimeUtil.WEEKDAY_CODES.get(TimeUtil.weekday(d));
        for (Duty duty : duties(historyStart, end, everyone, true, null, rejected)) {
            Map<String, Object> s = duty.s();
            Object area = s.get("areaId");
            if (!exported.contains(duty.pid())) {
                leftOutShifts.add(s.get("id"), 1);
                if (excludedPeople.contains(duty.pid())) {
                    leftOut.add(t(area, dayType.apply(duty.d())), 1);
                    continue;
                }
                outside.add(t(area, dayType.apply(duty.d())), 1);
            }
            counted.add(t(area, dayType.apply(duty.d())), 1);
            byDay.computeIfAbsent(t(area, duty.d()), x -> new ArrayList<>())
                    .add(TimeUtil.interval((String) s.get("start"), (String) s.get("end")));
        }
        List<Map<String, Object>> rules = new ArrayList<>();
        List<String> codes = new ArrayList<>(TimeUtil.WEEKDAY_CODES);
        codes.add("HOL");
        for (String area : areaIds()) {
            java.util.TreeSet<Integer> pointSet = new java.util.TreeSet<>();
            for (Map.Entry<Object, List<int[]>> e : byDay.entrySet()) {
                if (((List<?>) e.getKey()).get(0).equals(area)) {
                    for (int[] span : e.getValue()) {
                        pointSet.add(span[0]);
                        pointSet.add(span[1]);
                    }
                }
            }
            Integer[] points = pointSet.toArray(new Integer[0]);
            Map<String, List<int[]>> profiles = new LinkedHashMap<>();
            for (String code : codes) {
                List<List<int[]>> samples = new ArrayList<>();
                for (LocalDate d : days) {
                    if (dayType.apply(d).equals(code)) {
                        samples.add(byDay.getOrDefault(t(area, d), List.of()));
                    }
                }
                List<int[]> profile = new ArrayList<>();
                for (int i = 0; i + 1 < points.length; i++) {
                    int a = points[i];
                    int b = points[i + 1];
                    List<Integer> counts = new ArrayList<>();
                    for (List<int[]> spans : samples) {
                        int c = 0;
                        for (int[] se : spans) {
                            c += se[0] <= a && b <= se[1] ? 1 : 0;
                        }
                        counts.add(c);
                    }
                    if (counts.isEmpty()) {
                        counts.add(0);
                    }
                    int hi = Demand.percentileUp(counts, MAX_PERCENT);
                    if (hi != 0) {
                        profile.add(new int[] { a, b, Demand.medianUp(counts), hi });
                    }
                }
                profiles.put(code, profile);
            }
            Map<List<Object>, List<String>> groups = new LinkedHashMap<>();
            for (String code : codes) {
                List<List<Integer>> w = new ArrayList<>();
                for (int[] x : Demand.startDayWindows(profiles.get(code))) {
                    w.add(window(x[0], x[1], x[2], x[3]));
                }
                groups.computeIfAbsent(t(code.equals("HOL"), w), x -> new ArrayList<>()).add(code);
            }
            for (Map.Entry<List<Object>, List<String>> g : groups.entrySet()) {
                @SuppressWarnings("unchecked")
                List<List<Integer>> w = (List<List<Integer>>) g.getKey().get(1);
                rules.addAll(rulesOf(area, g.getValue(), null, w));
            }
            if (days.stream().anyMatch(d -> holidayDates.contains(d.toString())) && profiles.get("HOL").isEmpty()
                    && TimeUtil.WEEKDAY_CODES.stream().anyMatch(c -> !profiles.get(c).isEmpty())) {
                rules.addAll(rulesOf(area, List.of("HOL"), null, List.of(window(points[0],
                        Math.min(points[points.length - 1], points[0] + TimeUtil.DAY), 0, null))));
            }
            List<String> peaks = new ArrayList<>();
            for (int i = 0; i < codes.size(); i++) {
                String code = codes.get(i);
                List<int[]> before = profiles.get(code.equals("HOL") ? "HOL" : codes.get(Math.floorMod(i - 1, 7)));
                int low = peak(profiles.get(code), before, 2);
                int high = peak(profiles.get(code), before, 3);
                long sp5 = 0;
                boolean anyRule = false;
                for (Map<String, Object> r : sp5Rules) {
                    if (Py.eq(r.get("areaId"), area) && !Py.truthy(r.get("date"))
                            && ((List<?>) r.get("days")).contains(code)) {
                        long v = Py.pyInt(r.get("min"));
                        sp5 = anyRule ? Math.max(sp5, v) : v;
                        anyRule = true;
                    }
                }
                peaks.add(code + " " + low + "-" + high + "/" + sp5);
                reportRows.add(flat("bedarf", "", area, "", counted.n(t(area, code)), "", "", code, (long) low,
                        sp5, leftOut.n(t(area, code)), "", (long) high, blank(14), outside.n(t(area, code))));
            }
            long done = 0;
            long skippedDuties = 0;
            long others = 0;
            for (String c : codes) {
                done += counted.n(t(area, c));
                skippedDuties += leftOut.n(t(area, c));
                others += outside.n(t(area, c));
            }
            warn("Bedarf im Bereich " + area + ", Spitze je Tagestyp aus der Historie/aus SP5: " + String.join(", ", peaks)
                    + "; " + done + " Dienste in den " + historyMonths + " Monaten davor gezählt, davon " + others
                    + " von Mitarbeitern, die nicht übernommen werden; " + skippedDuties
                    + " nicht gezählt (ausgeschlossene Gruppe)");
        }
        if (demandSource.equals("history")) {
            warn("Bedarf aus der Historie: Median je Bereich und Tagestyp über die " + historyMonths
                    + " Monate davor");
        } else {
            warn("Bedarf aus SP5 (5SHDEM, 5SPDEM); die Historie nur zum Vergleich");
        }
        return rules;
    }

    /** The peak of a profile column (2: median, 3: max) over a calendar day with the night of the day before. */
    static int peak(List<int[]> profile, List<int[]> before, int column) {
        List<int[]> today = new ArrayList<>();
        for (int[] x : profile) {
            today.add(new int[] { x[0], x[1], x[column] });
        }
        List<int[]> yesterday = new ArrayList<>();
        for (int[] x : before) {
            yesterday.add(new int[] { x[0], x[1], x[column] });
        }
        int max = 0;
        boolean anySegment = false;
        for (int[] s : Demand.coverageSegments(today, yesterday)) {
            max = anySegment ? Math.max(max, s[2]) : s[2];
            anySegment = true;
        }
        return max;
    }

    void unmappedReport() throws Sp5Error {
        Map<Object, Object> lost = new LinkedHashMap<>();
        LocalDate end = Py.at(FILE, 801, () -> TimeUtil.plusDays(first, -1));
        duties(historyStart, end, null, true, lost, null);
        Counter counts = new Counter();
        for (Object v : lost.values()) {
            counts.add(v, 1);
        }
        List<Object[]> rows = new ArrayList<>();
        for (Map.Entry<Object, Lost> e : withoutArea.entrySet()) {
            long n = counts.n(e.getKey());
            if (n != 0) {
                rows.add(new Object[] { e.getValue().shortName(), e.getValue().why(), n, n * e.getValue().hours() });
            }
        }
        rows.sort((a, b) -> {
            int c = Double.compare(-(Double) a[3], -(Double) b[3]);
            if ((Double) a[3] == (double) (Double) b[3]) {
                c = 0;
            }
            return c != 0 ? c : Py.compareStr((String) a[0], (String) b[0]);
        });
        for (Object[] r : rows) {
            warn("Schicht " + Py.strRepr((String) r[0]) + " " + r[1] + ": " + r[2] + " Dienste, "
                    + Py.fixed((Double) r[3], 1) + " Stunden in den " + historyMonths + " Monaten davor");
            reportRows.add(row("ignoriert".equals(r[1]) ? "ignoriert" : "nicht_zugeordnet", "", "", r[0], r[2], "", "",
                    "", "", "", "", Py.fixed((Double) r[3], 1)));
        }
        long duties = 0;
        List<Object> hours = new ArrayList<>();
        for (Object[] r : rows) {
            duties += (Long) r[2];
            hours.add(r[3]);
        }
        warn("Schichten ohne Bereich: " + rows.size() + " Schichten, " + duties + " Dienste, "
                + Py.fixed(Py.sum(hours), 1) + " Stunden in den " + historyMonths + " Monaten davor");
    }

    void droppedReport() {
        LocalDate end = Py.at(FILE, 822, () -> TimeUtil.plusDays(first, -1));
        String window = historyStart + ".." + end;
        Set<Object> exported = new HashSet<>();
        for (Map<String, Object> p : personBySp5.values()) {
            exported.add(p.get("id"));
        }
        Counter counts = new Counter();
        Map<Object, Object> rawIds = new HashMap<>();
        for (Object[] r : rejected) {
            Object sid = k(r[1]);
            rawIds.putIfAbsent(sid, r[1]);
            if (!exported.contains(r[0])) {
                counts.add(t("mitarbeiter_und_schicht_nicht_uebernommen", sid), 1);
            } else if (!"gezaehlt".equals(r[2])) {
                counts.add(t(r[2], sid), 1);
            } else {
                Lost l = withoutArea.containsKey(sid) ? withoutArea.get(sid) : droppedShifts.get(sid);
                counts.add(t(l.why().replace(" ", "_"), sid), 1);
            }
        }
        Map<Object, Object> byId = new HashMap<>();
        for (Map.Entry<Object, Map<String, Object>> e : shiftBySp5.entrySet()) {
            byId.put(e.getValue().get("id"), e.getKey());
        }
        for (Map.Entry<Object, Long> e : leftOutShifts.entrySet()) {
            Object sp5Id = byId.get(e.getKey());
            counts.add(t("mitarbeiter_nicht_uebernommen", sp5Id), e.getValue());
        }
        Map<String, Object[]> totals = new LinkedHashMap<>();
        List<Object> keys = new ArrayList<>(counts.keySet());
        keys.sort((a, b) -> {
            List<?> x = (List<?>) a;
            List<?> y = (List<?>) b;
            int c = Py.compareStr((String) x.get(0), (String) y.get(0));
            return c != 0 ? c : Py.compareStr(Py.str(raw(rawIds, x.get(1))), Py.str(raw(rawIds, y.get(1))));
        });
        for (Object key : keys) {
            List<?> ws = (List<?>) key;
            String why = (String) ws.get(0);
            Object sid = ws.get(1);
            long n = counts.n(key);
            Object[] ch = codeHours(sid);
            double hours = (Double) ch[1];
            Object[] tot = totals.computeIfAbsent(why, x -> new Object[] { 0L, 0.0 });
            tot[0] = (Long) tot[0] + n;
            tot[1] = (Double) tot[1] + n * hours;
            Object rawSid = raw(rawIds, sid);
            reportRows.add(flat("verworfen", "", "", ch[0], n, blank(6), Py.fixed(n * hours, 1), blank(6), why,
                    rawSid == null ? "" : Py.str(rawSid)));
        }
        Consumer<String> w = this::warn;
        String head = "Verworfene Dienste " + window;
        w.accept(head + ", Schicht nicht zugeordnet: " + total(totals, "nicht_zugeordnet"));
        w.accept(head + ", Schicht ignoriert (ignoreShifts): " + total(totals, "ignoriert"));
        w.accept(head + ", Schicht ausgeblendet (HIDE): " + total(totals, "ausgeblendet"));
        w.accept(head + ", Schicht länger als 12 Stunden oder ohne Dienstzeit: "
                + total(totals, "zu_lang", "ohne_dienstzeit"));
        w.accept(head + ", sonstige: " + total(totals, "unbekannte_schicht", "abwesenheit", "anderer_eintrag") + " ("
                + totalCount(totals, "unbekannte_schicht") + " ohne Schicht in 5SHIFT, "
                + totalCount(totals, "abwesenheit") + " an Tagen mit ganztägiger Abwesenheit, "
                + totalCount(totals, "anderer_eintrag") + " neben einem anderen Eintrag desselben Tags)");
        long shiftRows = 0;
        for (Map.Entry<Object, Long> e : counts.entrySet()) {
            if (!((String) ((List<?>) e.getKey()).get(0)).startsWith("mitarbeiter_")) {
                shiftRows += e.getValue();
            }
        }
        w.accept(head + ": zusammen " + shiftRows + " Dienste übernommener Mitarbeiter auf Schichten, die nicht "
                + "übernommen werden (wie die Warnung '... Dienste " + window
                + " mit nicht zugeordneter Schicht ignoriert')");
        w.accept(head + ", Mitarbeiter nicht übernommen: " + total(totals, "mitarbeiter_nicht_uebernommen")
                + " auf übernommenen Schichten (im Bedarf gezählt außer ausgeschlossene Gruppe), dazu "
                + total(totals, "mitarbeiter_und_schicht_nicht_uebernommen") + " auf Schichten, die nicht übernommen "
                + "werden");
    }

    private static Object raw(Map<Object, Object> rawIds, Object sid) {
        return rawIds.containsKey(sid) ? rawIds.get(sid) : sid;
    }

    private static long totalCount(Map<String, Object[]> totals, String why) {
        Object[] t = totals.computeIfAbsent(why, x -> new Object[] { 0L, 0.0 });
        return (Long) t[0];
    }

    private static String total(Map<String, Object[]> totals, String... whys) {
        long n = 0;
        List<Object> hours = new ArrayList<>();
        for (String why : whys) {
            Object[] t = totals.computeIfAbsent(why, x -> new Object[] { 0L, 0.0 });
            n += (Long) t[0];
            hours.add(t[1]);
        }
        return n + " Dienste, " + Py.fixed(Py.sum(hours), 1) + " Stunden";
    }

    Object[] codeHours(Object sid) {
        if (shiftBySp5.containsKey(sid)) {
            Map<String, Object> s = shiftBySp5.get(sid);
            int[] ab = TimeUtil.interval((String) s.get("start"), (String) s.get("end"));
            return new Object[] { s.get("code"), (ab[1] - ab[0]) / 60.0 };
        }
        Lost l = withoutArea.containsKey(sid) ? withoutArea.get(sid) : droppedShifts.get(sid);
        return l == null ? new Object[] { "", 0.0 } : new Object[] { l.shortName(), l.hours() };
    }

    // ── duties ──────────────────────────────────────────────

    List<Duty> duties(LocalDate start, LocalDate end) throws Sp5Error {
        return duties(start, end, null, false, null, null);
    }

    /** See sp5.py duties: (person id, date, shift) from 5MASHI, 5SPSHI and the cycles in [start, end]. */
    List<Duty> duties(LocalDate start, LocalDate end, Map<Object, Map<String, Object>> people, boolean quiet,
            Map<Object, Object> lost, List<Object[]> rejectedRows) throws Sp5Error {
        Map<Object, Map<String, Object>> result = new LinkedHashMap<>();
        int unknown = 0;
        Map<Object, Object> loose = new LinkedHashMap<>();
        Map<Object, Object> firstRow = new LinkedHashMap<>();
        List<Object[]> rowsOut = new ArrayList<>();
        Map<Object, Map<String, Object>> who = people == null ? personBySp5 : people;
        Consumer<String> w = quiet ? x -> { } : this::warn;
        List<List<Map<String, Object>>> both = rows(start, end);
        List<Map<String, Object>> special = both.get(0);
        List<Map<String, Object>> rows = both.get(1);
        List<Map<String, Object>> withShift = special.stream().filter(r -> Py.truthy(r.get("SHIFTID"))).toList();
        Set<Object> replaced = Collections.newSetFromMap(new IdentityHashMap<>());
        replaced.addAll(withShift);
        long otherType = withShift.stream().filter(r -> Py.truthy(r.get("TYPE"))).count();
        if (otherType != 0) {
            w.accept(otherType + " Sonderdienste " + start + ".." + end
                    + " (5SPSHI) mit TYPE ungleich 0 als Dienst übernommen");
        }
        int withoutShift = special.size() - withShift.size();
        if (withoutShift != 0) {
            w.accept(withoutShift + " Sonderdienste " + start + ".." + end + " (5SPSHI) ohne Schicht nicht übernommen");
        }
        for (Map<String, Object> r : rows) {
            LocalDate d = TimeUtil.parseDate(r.get("DATE"));
            Map<String, Object> person = who.get(k(r.get("EMPLOYEEID")));
            if (d == null || person == null || d.isBefore(start) || d.isAfter(end)) {
                continue;
            }
            Object sidKey = k(r.get("SHIFTID"));
            Map<String, Object> shift = shiftBySp5.get(sidKey);
            List<Object> key = t(person.get("id"), d);
            if (shift == null) {
                unknown++;
                rowsOut.add(new Object[] { key, r.get("SHIFTID"), r });
                if (withoutArea.containsKey(sidKey) && !result.containsKey(key)) {
                    loose.putIfAbsent(key, sidKey);
                }
                if ((withoutArea.containsKey(sidKey) || droppedShifts.containsKey(sidKey)) && !result.containsKey(key)) {
                    firstRow.putIfAbsent(t(key, withoutArea.containsKey(sidKey)), r);
                }
                if (replaced.contains(r) && !result.containsKey(key)) {
                    result.put(key, null);
                }
                continue;
            }
            if (!result.containsKey(key)) {
                result.put(key, shift);
            }
        }
        result.values().removeIf(java.util.Objects::isNull);
        if (unknown != 0) {
            w.accept(unknown + " Dienste " + start + ".." + end + " mit nicht zugeordneter Schicht ignoriert");
        }
        Set<Object> absentDays = absent(start, end, false, people);
        if (lost != null) {
            for (Map.Entry<Object, Object> e : loose.entrySet()) {
                if (!result.containsKey(e.getKey()) && !absentDays.contains(e.getKey())) {
                    lost.put(e.getKey(), e.getValue());
                }
            }
        }
        if (rejectedRows != null) {
            Set<Object> firsts = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Map.Entry<Object, Object> e : firstRow.entrySet()) {
                List<?> ko = (List<?>) e.getKey();
                if ((Boolean) ko.get(1) || !firstRow.containsKey(t(ko.get(0), true))) {
                    firsts.add(e.getValue());
                }
            }
            for (Object[] o : rowsOut) {
                Object key = o[0];
                Object sidKey = k(o[1]);
                String what = !withoutArea.containsKey(sidKey) && !droppedShifts.containsKey(sidKey)
                        ? "unbekannte_schicht" : absentDays.contains(key) ? "abwesenheit"
                        : firsts.contains(o[2]) && !result.containsKey(key) ? "gezaehlt" : "anderer_eintrag";
                rejectedRows.add(new Object[] { ((List<?>) key).get(0), o[1], what });
            }
        }
        int absentCount = 0;
        for (Object key : result.keySet()) {
            absentCount += absentDays.contains(key) ? 1 : 0;
        }
        if (absentCount != 0) {
            w.accept(absentCount + " Dienste " + start + ".." + end
                    + " an Tagen mit ganztägiger Abwesenheit nicht übernommen");
        }
        List<Object> keys = new ArrayList<>(result.keySet());
        keys.sort((a, b) -> Py.compare(a, b));
        List<Duty> duties = new ArrayList<>();
        for (Object key : keys) {
            if (!absentDays.contains(key)) {
                List<?> pd = (List<?>) key;
                duties.add(new Duty((String) pd.get(0), (LocalDate) pd.get(1), result.get(key)));
            }
        }
        return duties;
    }

    /** {5SPSHI rows in [start, end], the duty rows in their order of precedence}. */
    List<List<Map<String, Object>>> rows(LocalDate start, LocalDate end) throws Sp5Error {
        List<Map<String, Object>> special = new ArrayList<>();
        for (Map<String, Object> r : table("5SPSHI")) {
            LocalDate d = TimeUtil.parseDate(r.get("DATE"));
            if (d != null && !d.isBefore(start) && !d.isAfter(end)) {
                special.add(r);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> r : special) {
            if (Py.truthy(r.get("SHIFTID"))) {
                rows.add(r);
            }
        }
        for (Map<String, Object> r : table("5MASHI")) {
            if (!Py.eq(r.get("TYPE"), 1L)) {
                rows.add(r);
            }
        }
        List<Map<String, Object>> assignments = table("5CYASS", false, true);
        List<Map<String, Object>> cycles = table("5CYCLE", false, true);
        List<Map<String, Object>> entries = table("5CYENT", false, true);
        List<Map<String, Object>> exceptions = table("5CYEXC", false, true);
        rows.addAll(Sp5Calc.expandCycleAssignments(assignments, cycles, entries, exceptions, start, end));
        return List.of(special, rows);
    }

    List<Map<String, Object>> history(Set<String> holidayDates) throws Sp5Error {
        LocalDate start = historyStart;
        Map<Object, Object[]> counters = new LinkedHashMap<>(); // {nights, weekends, holidays, unpopular, hours, earned}
        LocalDate end = Py.at(FILE, 962, () -> TimeUtil.plusDays(first, -1));
        for (Duty duty : duties(start, end)) {
            Object[] c = counters.computeIfAbsent(t(duty.pid(), duty.d().toString().substring(0, 7)), x -> counter());
            Map<String, Object> s = duty.s();
            int[] ab = TimeUtil.interval((String) s.get("start"), (String) s.get("end"));
            c[4] = (Double) c[4] + (ab[1] - ab[0]) / 60.0;
            c[0] = (Long) c[0] + ("NIGHT".equals(s.get("category")) ? 1 : 0);
            c[3] = (Long) c[3] + (Py.truthy(s.get("unpopular")) ? 1 : 0);
            boolean holiday = holidayDates.contains(duty.d().toString());
            c[2] = (Long) c[2] + (holiday ? 1 : 0);
            c[5] = (Long) c[5] + (holiday && TimeUtil.weekday(duty.d()) != 6 ? 1 : 0);
            if (TimeUtil.weekday(duty.d()) >= 5) {
                @SuppressWarnings("unchecked")
                Set<Object> weekends = (Set<Object>) c[1];
                weekends.add(t(duty.d().get(IsoFields.WEEK_BASED_YEAR), duty.d().get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)));
            }
        }
        Counter compRest = new Counter();
        LocalDate end2 = Py.at(FILE, 973, () -> TimeUtil.plusDays(first, -1));
        for (Object pd : absent(start, end2, true, null)) {
            List<?> x = (List<?>) pd;
            compRest.add(t(x.get(0), x.get(1).toString().substring(0, 7)), 1);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int k = historyMonths; k > 0; k--) {
            int kk = k;
            String m = Py.at("timeutil.py", 31, () -> TimeUtil.addMonths(month, -kk));
            List<LocalDate> days = TimeUtil.monthDays(m);
            for (Map<String, Object> p : personBySp5.values()) {
                String pid = (String) p.get("id");
                LocalDate[] se = employment.get(pid);
                boolean employed = (se[0] == null || !se[0].isAfter(days.get(days.size() - 1)))
                        && (se[1] == null || !se[1].isBefore(days.get(0)));
                if (!employed && !counters.containsKey(t(pid, m)) && compRest.n(t(pid, m)) == 0) {
                    continue;
                }
                Object[] c = counters.computeIfAbsent(t(pid, m), x -> counter());
                Map<String, Object> h = new LinkedHashMap<>();
                h.put("personId", pid);
                h.put("month", m);
                h.put("source", "import");
                h.put("nights", c[0]);
                h.put("weekendsWorked", (long) ((Set<?>) c[1]).size());
                h.put("holidays", c[2]);
                h.put("unpopular", c[3]);
                h.put("wishes", 0L);
                h.put("wishesMet", 0L);
                h.put("hours", Py.round((Double) c[4], 2));
                h.put("hoursAccountAfter", null);
                h.put("compRestBalanceAfter", null);
                h.put("compRestUsed", compRest.n(t(pid, m)));
                h.put("compRestEarned", c[5]);
                result.add(h);
            }
        }
        return result;
    }

    private static Object[] counter() {
        return new Object[] { 0L, new HashSet<Object>(), 0L, 0L, 0.0, 0L };
    }

    // ── areas from history ──────────────────────────────────

    @SuppressWarnings("unchecked")
    void areasFromDuties() throws Sp5Error {
        Map<Object, Map<String, Object>> candidates = new LinkedHashMap<>();
        for (Map.Entry<Object, Pending> e : noArea.entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("id", "E" + Py.str(e.getKey()));
            c.put("areaIds", new ArrayList<String>());
            candidates.put(e.getKey(), c);
        }
        Map<Object, Map<String, Object>> people = new LinkedHashMap<>(personBySp5);
        people.putAll(candidates);
        Counter counts = new Counter();
        if (historyMonths != 0) {
            LocalDate end = Py.at(FILE, 1007, () -> TimeUtil.plusDays(first, -1));
            for (Duty duty : duties(historyStart, end, people, true, null, null)) {
                Object area = duty.s().get("areaId");
                counts.add(t(duty.pid(), area), 1);
                shiftCounts.add(t(duty.pid(), duty.s().get("id")), 1);
                areaMonths.computeIfAbsent(t(duty.pid(), area), x -> new Counter())
                        .add(duty.d().toString().substring(0, 7), 1);
                lastDuty.put(t(duty.pid(), area), duty.d());
            }
        }
        List<String> areas = areaIds();
        Map<Object, Set<String>> group = new HashMap<>();
        for (Map<String, Object> p : people.values()) {
            group.put(p.get("id"), new HashSet<>((List<String>) p.get("areaIds")));
        }
        for (Map<String, Object> p : people.values()) {
            for (String a : areas) {
                long n = counts.n(t(p.get("id"), a));
                if (n != 0 || group.get(p.get("id")).contains(a)) {
                    reportRows.add(row("bereich", p.get("id"), a, "", n,
                            group.get(p.get("id")).contains(a) ? "ja" : "nein", ""));
                }
            }
        }
        if (historyMonths != 0) {
            List<Object> exported = new ArrayList<>();
            for (Map<String, Object> p : personBySp5.values()) {
                exported.add(p.get("id"));
            }
            for (String a : areas) {
                long worked = exported.stream().filter(pid -> counts.n(t(pid, a)) != 0 && !group.get(pid).contains(a))
                        .count();
                long idle = exported.stream().filter(pid -> group.get(pid).contains(a) && counts.n(t(pid, a)) == 0)
                        .count();
                warn("Bereich " + a + ": " + worked + " Mitarbeiter mit Diensten in den " + historyMonths
                        + " Monaten davor ohne den Bereich aus Gruppen, " + idle
                        + " mit dem Bereich aus Gruppen ohne Dienst darin");
            }
            long loose = candidates.values().stream()
                    .filter(c -> areas.stream().anyMatch(a -> counts.n(t(c.get("id"), a)) != 0)).count();
            warn(loose + " Mitarbeiter ohne Bereich aus Gruppen haben in den " + historyMonths + " Monaten davor "
                    + "zugeordnete Schichten gearbeitet");
        }
        Integer n = areasFromHistory;
        int added = 0;
        int extra = 0;
        if (n != null && n != 0) {
            for (Map<String, Object> p : personBySp5.values()) {
                Set<String> own = group.get(p.get("id"));
                Set<String> more = new HashSet<>();
                for (String a : areas) {
                    if (counts.n(t(p.get("id"), a)) >= n && !own.contains(a)) {
                        more.add(a);
                    }
                }
                if (!more.isEmpty()) {
                    added += more.size();
                    List<String> ids = new ArrayList<>();
                    for (String a : areas) {
                        if (own.contains(a) || more.contains(a)) {
                            ids.add(a);
                        }
                    }
                    p.put("areaIds", ids);
                }
            }
        }
        for (Map.Entry<Object, Pending> e : noArea.entrySet()) {
            Set<String> worked = new HashSet<>();
            for (String a : areas) {
                if (n != null && n != 0 && counts.n(t("E" + Py.str(e.getKey()), a)) >= n) {
                    worked.add(a);
                }
            }
            if (worked.isEmpty()) {
                dropped.add("ohne Bereich", 1);
                continue;
            }
            addPerson(e.getValue().e(), e.getValue().start(), e.getValue().end(), worked);
            added += personBySp5.containsKey(e.getKey()) ? worked.size() : 0;
            extra += personBySp5.containsKey(e.getKey()) ? 1 : 0;
        }
        if (n != null && n != 0) {
            warn("Bereiche aus Diensten (mindestens " + n + "): " + added + " Bereiche ergänzt, davon für " + extra
                    + " Mitarbeiter ohne Bereich aus Gruppen, die damit übernommen werden");
        }
    }

    // ── shifts from history ─────────────────────────────────

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> shiftsFromDuties() throws Sp5Error {
        Integer n = shiftsFromHistory;
        if (historyMonths == 0) {
            return new ArrayList<>();
        }
        long least = n != null && n != 0 ? n : 1;
        Set<Object> sp5 = new HashSet<>();
        for (Map<String, Object> r : table("5RESTR")) {
            sp5.add(t(k(r.get("EMPLOYEEID")), k(r.get("SHIFTID"))));
        }
        LocalDate recent = Py.at(FILE, 1060, () -> TimeUtil.fromIso(TimeUtil.addMonths(month, -3) + "-01"));
        Map<Object, List<Object[]>> byArea = new HashMap<>();
        for (Map.Entry<Object, Map<String, Object>> e : shiftBySp5.entrySet()) {
            byArea.computeIfAbsent(e.getValue().get("areaId"), x -> new ArrayList<>())
                    .add(new Object[] { e.getKey(), e.getValue() });
        }
        Counter people = new Counter();
        Counter pairs = new Counter();
        int kept = 0;
        int skipped = 0;
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Object, Map<String, Object>> pe : personBySp5.entrySet()) {
            Object eid = pe.getKey();
            Map<String, Object> p = pe.getValue();
            List<Object[]> shifts = new ArrayList<>();
            for (String a : (List<String>) p.get("areaIds")) {
                shifts.addAll(byArea.getOrDefault(a, List.of()));
            }
            Map<Object, Long> count = new LinkedHashMap<>();
            for (Object[] x : shifts) {
                Map<String, Object> s = (Map<String, Object>) x[1];
                count.put(s.get("id"), shiftCounts.n(t(p.get("id"), s.get("id"))));
            }
            LocalDate entry = employment.get((String) p.get("id"))[0];
            long total = count.values().stream().mapToLong(Long::longValue).sum();
            boolean tooShort = total < least || (entry != null && entry.isAfter(recent));
            skipped += tooShort ? 1 : 0;
            Set<Object> few = new LinkedHashSet<>();
            for (Object[] x : shifts) {
                Object sp5Id = x[0];
                Map<String, Object> s = (Map<String, Object>) x[1];
                String mark = "";
                if (sp5.contains(t(eid, sp5Id))) {
                    mark = "sp5";
                    kept++;
                } else if (tooShort) {
                    mark = "zu_kurze_historie";
                } else if (count.get(s.get("id")) < least) {
                    pairs.add(s.get("areaId"), 1);
                    few.add(s.get("areaId"));
                    if (n != null && n != 0) {
                        mark = "auf_anfrage";
                        result.add(restriction((String) p.get("id"), (String) s.get("id"), 0L, "ON_REQUEST"));
                    }
                }
                reportRows.add(row("schicht", p.get("id"), s.get("areaId"), s.get("code"), count.get(s.get("id")), "",
                        mark));
            }
            for (Object a : few) {
                people.add(a, 1);
            }
        }
        String fewer = n != null && n != 0 ? "weniger als " + n + " Diensten" : "keinem Dienst";
        for (String a : areaIds()) {
            warn("Schichten im Bereich " + a + ": " + people.n(a) + " Mitarbeiter mit " + fewer
                    + " in mindestens einer Schicht des Bereichs in den " + historyMonths + " Monaten davor, "
                    + pairs.n(a) + " Paare aus Mitarbeiter und Schicht");
        }
        warn("Schichten aus Diensten: " + skipped + " Mitarbeiter mit zu kurzer Historie (weniger als " + least
                + " Dienste in ihren Bereichen oder Eintritt nach " + recent + ") nicht bewertet, " + kept
                + " Paare aus Mitarbeiter und Schicht mit Einschränkung aus SP5 belassen");
        if (n != null && n != 0) {
            warn("Schichten aus Diensten (mindestens " + n + "): " + result.size()
                    + " Einschränkungen 'nur auf Anfrage' ergänzt");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> areasNoLongerWorked(List<Map<String, Object>> restrictions) {
        int n = recentMonths;
        if (n == 0 || historyMonths == 0) {
            return new ArrayList<>();
        }
        LocalDate start = Py.at(FILE, 1107, () -> TimeUtil.fromIso(TimeUtil.addMonths(month, -n) + "-01"));
        Map<Object, Set<String>> active = new HashMap<>();
        for (Map.Entry<Object, Counter> e : areaMonths.entrySet()) {
            Set<String> set = active.computeIfAbsent(((List<?>) e.getKey()).get(0), x -> new HashSet<>());
            for (Object m : e.getValue().keySet()) {
                set.add((String) m);
            }
        }
        Set<Object> wholeWeek = new HashSet<>();
        for (Map<String, Object> r : restrictions) {
            if (Py.eq(r.get("weekday"), 0L)) {
                wholeWeek.add(t(r.get("personId"), r.get("shiftId")));
            }
        }
        Map<Object, List<Map<String, Object>>> byArea = new HashMap<>();
        for (Map<String, Object> s : shiftBySp5.values()) {
            byArea.computeIfAbsent(s.get("areaId"), x -> new ArrayList<>()).add(s);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        int skipped = 0;
        for (Map<String, Object> p : personBySp5.values()) {
            Object pid = p.get("id");
            List<String> sortedMonths = new ArrayList<>(active.getOrDefault(pid, Set.of()));
            sortedMonths.sort(Py::compareStr);
            List<String> recent = sortedMonths.subList(Math.max(0, sortedMonths.size() - n), sortedMonths.size());
            LocalDate entry = employment.get((String) pid)[0];
            if (recent.size() < n || (entry != null && !entry.isBefore(start))) {
                skipped++;
                continue;
            }
            for (String a : (List<String>) p.get("areaIds")) {
                Counter months = areaMonths.get(t(pid, a));
                if (months == null || months.isEmpty() || recent.stream().anyMatch(m -> months.n(m) != 0)) {
                    continue;
                }
                pastAreas.computeIfAbsent((String) pid, x -> new HashSet<>()).add(a);
                for (Map<String, Object> s : byArea.getOrDefault(a, List.of())) {
                    if (!wholeWeek.contains(t(pid, s.get("id")))) {
                        result.add(restriction((String) pid, (String) s.get("id"), 0L, "ON_REQUEST"));
                    }
                }
                long sum = months.values().stream().mapToLong(Long::longValue).sum();
                reportRows.add(flat("nicht_mehr", pid, a, "", sum, "", "auf_anfrage", blank(21),
                        lastDuty.get(t(pid, a)).toString()));
            }
        }
        int pairs = pastAreas.values().stream().mapToInt(Set::size).sum();
        warn("Bereiche nicht mehr gearbeitet (kein Dienst in den letzten " + n + " Monaten mit Diensten): "
                + pastAreas.size() + " Mitarbeiter, " + pairs + " Paare aus Mitarbeiter und Bereich, " + result.size()
                + " Einschränkungen 'nur auf Anfrage' ergänzt; " + skipped + " Mitarbeiter mit weniger als " + n
                + " Monaten mit Diensten oder Eintritt ab " + start + " nicht bewertet");
        return result;
    }

    // ── shift mix ───────────────────────────────────────────

    @SuppressWarnings("unchecked")
    void shiftMix() {
        Map<Object, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> s : shiftBySp5.values()) {
            byId.put(s.get("id"), s);
        }
        dutyTotals = new Counter();
        Map<Object, Counter> categories = new HashMap<>();
        Map<Object, Counter> areas = new HashMap<>();
        for (Map.Entry<Object, Long> e : shiftCounts.entrySet()) {
            List<?> ps = (List<?>) e.getKey();
            Object pid = ps.get(0);
            long n = e.getValue();
            dutyTotals.add(pid, n);
            Map<String, Object> s = byId.get(ps.get(1));
            if (pastAreas.getOrDefault((String) pid, Set.of()).contains(s.get("areaId"))) {
                continue;
            }
            categories.computeIfAbsent(pid, x -> new Counter()).add(s.get("category"), n);
            areas.computeIfAbsent(pid, x -> new Counter()).add(s.get("areaId"), n);
        }
        Integer n = mixMinDuties;
        if (n == null) {
            return;
        }
        int given = 0;
        for (Map<String, Object> p : personBySp5.values()) {
            Object pid = p.get("id");
            if (dutyTotals.n(pid) >= n) {
                Map<String, Object> mix = new LinkedHashMap<>();
                mix.put("categories", shares(categories.getOrDefault(pid, new Counter()), CATEGORIES));
                mix.put("areas", shares(areas.getOrDefault(pid, new Counter()), (List<String>) p.get("areaIds")));
                p.put("shiftMix", mix);
                given++;
            }
        }
        warn("Schichtmix aus der Historie: " + given + " Mitarbeiter mit Mix, " + (personBySp5.size() - given)
                + " mit weniger als " + n + " Diensten in den " + historyMonths + " Monaten davor ohne Mix");
    }

    @SuppressWarnings("unchecked")
    void personReport(List<Map<String, Object>> restrictions) {
        Counter onRequest = new Counter();
        for (Map<String, Object> r : restrictions) {
            if ("ON_REQUEST".equals(r.get("kind"))) {
                onRequest.add(r.get("personId"), 1);
            }
        }
        for (Map<String, Object> p : personBySp5.values()) {
            Object mixValue = p.get("shiftMix");
            Map<String, Object> mix = Py.truthy(mixValue) ? (Map<String, Object>) mixValue : Map.of();
            reportRows.add(flat("person", p.get("id"), "", "", dutyTotals.n(p.get("id")), blank(8), p.get("weeklyHours"),
                    String.join(" ", (List<String>) p.get("areaIds")), mixText(mix.get("categories")),
                    mixText(mix.get("areas")), onRequest.n(p.get("id"))));
        }
    }

    static final Comparator<Map<String, Object>> RESTRICTION_ORDER = (a, b) -> {
        int c = Py.compareStr((String) a.get("personId"), (String) b.get("personId"));
        if (c == 0) {
            c = Py.compareStr((String) a.get("shiftId"), (String) b.get("shiftId"));
        }
        return c != 0 ? c : Py.compare(a.get("weekday"), b.get("weekday"));
    };

    @SuppressWarnings("unchecked")
    Map<String, Object> run() throws Sp5Error {
        shifts();
        if (shiftBySp5.isEmpty()) {
            throw new Sp5Error("keine Schicht übernommen; Zuordnung 'shifts' und 'ignoreShifts' prüfen");
        }
        people();
        List<Map<String, Object>> absenceTypes = absenceTypes();
        areasFromDuties();
        peopleSummary();
        if (personBySp5.isEmpty()) {
            throw new Sp5Error("kein Mitarbeiter übernommen; Zuordnung 'groups' und 'excludeGroups' prüfen");
        }
        List<Map<String, Object>> restrictions = new ArrayList<>(restrictions());
        restrictions.addAll(shiftsFromDuties());
        restrictions.addAll(areasNoLongerWorked(restrictions));
        restrictions.sort(RESTRICTION_ORDER);
        shiftMix();
        personReport(restrictions);
        List<Map<String, Object>> absences = absences();
        List<Map<String, Object>> ignored = ignoredDutyAbsences(absences, absenceTypes);
        if (!ignored.isEmpty()) {
            absenceTypes.add(ignoredShiftAbsence());
            List<Map<String, Object>> all = new ArrayList<>(absences);
            all.addAll(ignored);
            all.sort(ABSENCE_ORDER);
            absences = all;
        }
        Object[] hol = holidays();
        List<Map<String, Object>> holidays = (List<Map<String, Object>>) hol[0];
        Set<String> sp5HolidayDates = (Set<String>) hol[1];
        Set<String> holidayDates = new HashSet<>();
        for (Map<String, Object> h : holidays) {
            holidayDates.add((String) h.get("date"));
        }
        List<Map<String, Object>> demand = demand(sp5HolidayDates, holidayDates, demandSource.equals("history"));
        if (historyMonths != 0) {
            List<Map<String, Object>> fromHistory = demandFromHistory(holidayDates, demand);
            if (demandSource.equals("history")) {
                demand = fromHistory;
            }
            unmappedReport();
            droppedReport();
        }
        LocalDate from = Py.at(FILE, 1207, () -> TimeUtil.plusDays(first, -PREVIOUS_DAYS));
        LocalDate to = Py.at(FILE, 1208, () -> TimeUtil.plusDays(first, -1));
        List<Map<String, Object>> previous = new ArrayList<>();
        for (Duty duty : duties(from, to)) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("personId", duty.pid());
            p.put("date", duty.d().toString());
            p.put("shiftId", duty.s().get("id"));
            previous.add(p);
        }
        List<Map<String, Object>> history = history(holidayDates);
        List<Map<String, Object>> people = new ArrayList<>(personBySp5.values());
        Map<Object, Long> personKeys = new IdentityHashMap<>();
        for (Map<String, Object> p : people) {
            personKeys.put(p, Py.at(FILE, 1210, () -> Py.pyInt(p.get("externalId"))));
        }
        people.sort(Comparator.comparingLong(personKeys::get));
        List<Map<String, Object>> shifts = new ArrayList<>(shiftBySp5.values());
        Map<Object, Long> shiftKeys = new IdentityHashMap<>();
        for (Map<String, Object> s : shifts) {
            shiftKeys.put(s, Py.at(FILE, 1217, () -> Py.pyInt(((String) s.get("id")).substring(1))));
        }
        shifts.sort(Comparator.comparingLong(shiftKeys::get));
        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("format", "dispofold/dataset");
        dataset.put("version", 2L);
        dataset.put("source", "sp5");
        dataset.put("areas", mapping.get("areas"));
        dataset.put("shifts", shifts);
        dataset.put("demand", demand);
        dataset.put("holidays", holidays);
        dataset.put("absenceTypes", absenceTypes);
        dataset.put("people", people);
        dataset.put("restrictions", restrictions);
        dataset.put("history", history);
        List<Map<String, Object>> periods = new ArrayList<>();
        for (String m : months) {
            Map<String, Object> period = new LinkedHashMap<>();
            period.put("month", m);
            List<Map<String, Object>> own = new ArrayList<>();
            for (Map<String, Object> a : absences) {
                if (((String) a.get("date")).substring(0, 7).equals(m)) {
                    own.add(a);
                }
            }
            period.put("absences", own);
            period.put("wishes", new ArrayList<>());
            period.put("fixed", new ArrayList<>());
            period.put("previousDuties", m.equals(month) ? previous : new ArrayList<>());
            periods.add(period);
        }
        dataset.put("periods", periods);
        return dataset;
    }
}
