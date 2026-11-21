package app.dispofold.plugins.sp5.port;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The parts of the vendored sp5lib that sp5export uses: calculations.parse_day_mask,
 * calculations.expand_cycle_assignments (with the rule of openschichtplaner5 1.0.1: an assignment without START or END
 * yields no duties, otherwise they run from START to END inclusive) and color_utils.bgr_to_hex. Python exceptions are
 * reported at the lines of the vendored calculations.py.
 */
final class Sp5Calc {

    private static final String FILE = "calculations.py";

    private Sp5Calc() {
    }

    /** parse_day_mask: space separated flags, or a compact form with one character per flag. */
    static boolean[] parseDayMask(String mask, int slots) {
        List<String> tokens = splitWhitespace(mask == null ? "" : mask);
        if (tokens.size() <= 1) {
            List<String> chars = new ArrayList<>();
            if (!tokens.isEmpty()) {
                tokens.get(0).codePoints().forEach(c -> chars.add(new String(Character.toChars(c))));
            }
            tokens = chars;
        }
        boolean[] result = new boolean[slots];
        for (int i = 0; i < slots; i++) {
            result[i] = i < tokens.size() && !tokens.get(i).isEmpty() && !tokens.get(i).equals("0");
        }
        return result;
    }

    /** str.split() without arguments. */
    static List<String> splitWhitespace(String s) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            while (i < s.length() && Py.isSpace(s.charAt(i))) {
                i++;
            }
            int start = i;
            while (i < s.length() && !Py.isSpace(s.charAt(i))) {
                i++;
            }
            if (i > start) {
                parts.add(s.substring(start, i));
            }
        }
        return parts;
    }

    /** bgr_to_hex: "#RRGGBB" of a Windows BGR integer (callers pass ints above 0). */
    static String bgrToHex(long bgr) {
        if (bgr < 0) {
            return "#FFFFFF";
        }
        return String.format(Locale.ROOT, "#%02X%02X%02X", bgr & 0xFF, (bgr >> 8) & 0xFF, (bgr >> 16) & 0xFF);
    }

    /** int(x or 0) reported at `line` of calculations.py. */
    private static long intOf(Object v, int line) {
        return Py.at(FILE, line, () -> Py.pyInt(Py.orZero(v)));
    }

    /** to_date of calculations.py: None or "" give null, anything else date.fromisoformat(str(value)). */
    private static LocalDate toDate(Object v) {
        if (v == null || "".equals(v)) {
            return null;
        }
        return Py.at(FILE, 54, () -> TimeUtil.fromIso(Py.str(v)));
    }

    /** expand_cycle_assignments: 5MASHI-shaped rows (EMPLOYEEID, DATE, SHIFTID, WORKPLACID) within [von, bis]. */
    static List<Map<String, Object>> expandCycleAssignments(List<Map<String, Object>> assignments,
            List<Map<String, Object>> cycles, List<Map<String, Object>> cycleEntries,
            List<Map<String, Object>> cycleExceptions, LocalDate von, LocalDate bis) {
        Map<Long, Map<String, Object>> cyclesById = new HashMap<>();
        for (Map<String, Object> rec : cycles) {
            cyclesById.put(intOf(rec.get("ID"), 1282), rec);
        }
        Map<Long, Map<Long, Map<String, Object>>> entriesByCycle = new HashMap<>();
        for (Map<String, Object> rec : cycleEntries) {
            Map<Long, Map<String, Object>> positions = entriesByCycle.computeIfAbsent(intOf(rec.get("CYCLEEID"), 1285),
                    k -> new HashMap<>());
            positions.put(intOf(rec.get("INDEX"), 1286), rec);
        }
        Map<List<Long>, Set<LocalDate>> exceptions = new HashMap<>();
        for (Map<String, Object> rec : cycleExceptions) {
            LocalDate d = toDate(rec.get("DATE"));
            if (d != null) {
                List<Long> key = List.of(intOf(rec.get("EMPLOYEEID"), 1291), intOf(rec.get("CYCLEASSID"), 1291));
                exceptions.computeIfAbsent(key, k -> new HashSet<>()).add(d);
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> ass : assignments) {
            Map<String, Object> cycle = cyclesById.get(intOf(ass.get("CYCLEID"), 1296));
            LocalDate start = toDate(ass.get("START"));
            if (cycle == null || start == null) {
                continue;
            }
            long size = intOf(cycle.get("SIZE"), 1300);
            boolean weekModel = intOf(cycle.get("UNIT"), 1301) == 1;
            long length = weekModel ? size * 7 : size;
            if (length <= 0) {
                continue;
            }
            LocalDate end = toDate(ass.get("END"));
            // openschichtplaner5 1.0.1: no END, no duties (the vendored copy runs an open assignment to `bis`)
            if (end == null) {
                continue;
            }
            LocalDate lo = von.isAfter(start) ? von : start;
            LocalDate hi = bis.isBefore(end) ? bis : end;
            long entrance = intOf(ass.get("ENTRANCE"), 1308);
            long base = weekModel ? entrance * 7 + TimeUtil.weekday(start) : entrance;
            Map<Long, Map<String, Object>> positions = entriesByCycle.getOrDefault(intOf(cycle.get("ID"), 1310),
                    Map.of());
            long employeeId = intOf(ass.get("EMPLOYEEID"), 1311);
            Set<LocalDate> skip = exceptions.getOrDefault(List.of(employeeId, intOf(ass.get("ID"), 1312)), Set.of());
            for (LocalDate d = lo; !d.isAfter(hi); d = d.plusDays(1)) {
                Map<String, Object> entry = positions.get(Math.floorMod(base + ChronoUnit.DAYS.between(start, d), length));
                if (entry != null && intOf(entry.get("SHIFTID"), 1316) != 0 && !skip.contains(d)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("EMPLOYEEID", employeeId);
                    row.put("DATE", d.toString());
                    row.put("SHIFTID", intOf(entry.get("SHIFTID"), 1321));
                    row.put("WORKPLACID", intOf(entry.get("WORKPLACID"), 1322));
                    result.add(row);
                }
            }
        }
        return result;
    }
}
