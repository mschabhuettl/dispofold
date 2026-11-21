package app.dispofold.plugins.sp5.port;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Port of sp5export/holidays.py: Austrian public holidays. */
final class Holidays {

    private Holidays() {
    }

    /** Gregorian Easter Sunday (anonymous Gregorian algorithm). */
    static LocalDate easterSunday(int year) {
        int a = Math.floorMod(year, 19);
        int b = Math.floorDiv(year, 100);
        int c = Math.floorMod(year, 100);
        int d = Math.floorDiv(b, 4);
        int e = Math.floorMod(b, 4);
        int f = Math.floorDiv(b + 8, 25);
        int g = Math.floorDiv(b - f + 1, 3);
        int h = Math.floorMod(19 * a + b - d - g + 15, 30);
        int i = Math.floorDiv(c, 4);
        int k = Math.floorMod(c, 4);
        int m = Math.floorMod(32 + 2 * e + 2 * i - h - k, 7);
        int n = Math.floorDiv(a + 11 * h + 22 * m, 451);
        int x = h + m - 7 * n + 114;
        return LocalDate.of(year, Math.floorDiv(x, 31), Math.floorMod(x, 31) + 1);
    }

    private record Day(LocalDate date, String name) {
    }

    /** Nationwide public holidays of Austria for one year, sorted by date (then name). */
    static List<Map<String, Object>> austrianHolidays(int year) {
        LocalDate easter = easterSunday(year);
        List<Day> days = new ArrayList<>(List.of(
                new Day(LocalDate.of(year, 1, 1), "Neujahr"),
                new Day(LocalDate.of(year, 1, 6), "Heilige Drei Könige"),
                new Day(easter.plusDays(1), "Ostermontag"),
                new Day(LocalDate.of(year, 5, 1), "Staatsfeiertag"),
                new Day(easter.plusDays(39), "Christi Himmelfahrt"),
                new Day(easter.plusDays(50), "Pfingstmontag"),
                new Day(easter.plusDays(60), "Fronleichnam"),
                new Day(LocalDate.of(year, 8, 15), "Mariä Himmelfahrt"),
                new Day(LocalDate.of(year, 10, 26), "Nationalfeiertag"),
                new Day(LocalDate.of(year, 11, 1), "Allerheiligen"),
                new Day(LocalDate.of(year, 12, 8), "Mariä Empfängnis"),
                new Day(LocalDate.of(year, 12, 25), "Christtag"),
                new Day(LocalDate.of(year, 12, 26), "Stefanitag")));
        days.sort(Comparator.comparing(Day::date).thenComparing(Day::name, Py::compareStr));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Day d : days) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("date", d.date().toString());
            h.put("name", d.name());
            result.add(h);
        }
        return result;
    }
}
