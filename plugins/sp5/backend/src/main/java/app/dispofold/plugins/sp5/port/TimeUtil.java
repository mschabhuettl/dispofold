package app.dispofold.plugins.sp5.port;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Port of sp5export/timeutil.py (clock times and months) and the date rules of Python's datetime.date. */
public final class TimeUtil {

    static final int DAY = 24 * 60;
    static final List<String> WEEKDAY_CODES = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN");
    private static final Pattern MONTH;
    private static final Pattern ISO_DATE = Pattern.compile(
            "([0-9]{4})(?:-([0-9]{2})-([0-9]{2})|([0-9]{2})([0-9]{2})|(-?)W([0-9]{2})(?:\\6([0-9]))?)");

    static {
        try {
            MONTH = PyRe.compile("^\\d{4}-\\d{2}$");
        } catch (PyRe.Error e) {
            throw new IllegalStateException(e);
        }
    }

    private TimeUtil() {
    }

    /** Minutes since midnight of "HH:MM" or "HH:MM:SS". */
    static int minutes(String hhmm) {
        String[] parts = hhmm.split(":", -1);
        if (parts.length < 2) {
            throw new Py.Raised("IndexError");
        }
        return (int) (Py.pyInt(parts[0]) * 60 + Py.pyInt(parts[1]));
    }

    static String hhmm(long total) {
        total = Math.floorMod(total, DAY);
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    /** Start and end in minutes; an end not after the start lies on the next day. */
    static int[] interval(String start, String end) {
        int s = minutes(start);
        int e = minutes(end);
        if (e <= s) {
            e += DAY;
        }
        return new int[] { s, e };
    }

    /** parse_month: year and month, or ValueError. */
    static long[] parseMonth(String month) {
        if (!PyRe.match(MONTH, month == null ? "" : month)) {
            throw new Py.Raised("ValueError");
        }
        long m = Py.pyInt(month.substring(month.offsetByCodePoints(0, 5)));
        if (m < 1 || m > 12) {
            throw new Py.Raised("ValueError");
        }
        return new long[] { Py.pyInt(month.substring(0, month.offsetByCodePoints(0, 4))), m };
    }

    static boolean validMonth(String month) {
        try {
            parseMonth(month);
            return true;
        } catch (Py.Raised e) {
            return false;
        }
    }

    static List<LocalDate> monthDays(String month) {
        long[] ym = Py.at("timeutil.py", 31, () -> parseMonth(month));
        LocalDate d = Py.at("timeutil.py", 37, () -> date(ym[0], ym[1], 1));
        List<LocalDate> days = new ArrayList<>();
        while (d.getMonthValue() == ym[1]) {
            days.add(d);
            LocalDate cur = d;
            d = Py.at("timeutil.py", 41, () -> plusDays(cur, 1));
        }
        return days;
    }

    static String addMonths(String month, long delta) {
        long[] ym = parseMonth(month);
        long index = ym[0] * 12 + (ym[1] - 1) + delta;
        long y = Math.floorDiv(index, 12);
        return (y < 0 ? "-" + String.format(Locale.ROOT, "%03d", -y) : String.format(Locale.ROOT, "%04d", y)) + "-"
                + String.format(Locale.ROOT, "%02d", Math.floorMod(index, 12) + 1);
    }

    // ── datetime.date ───────────────────────────────────────

    /** date(y, m, d): years 1 to 9999, else ValueError. */
    static LocalDate date(long y, long m, long d) {
        if (y < 1 || y > 9999) {
            throw new Py.Raised("ValueError");
        }
        try {
            return LocalDate.of((int) y, (int) m, (int) d);
        } catch (DateTimeException | ArithmeticException e) {
            throw new Py.Raised("ValueError");
        }
    }

    /** d + timedelta(days=n): OverflowError outside the years 1 to 9999. */
    static LocalDate plusDays(LocalDate d, long n) {
        LocalDate r = d.plusDays(n);
        if (r.getYear() < 1 || r.getYear() > 9999) {
            throw new Py.Raised("OverflowError");
        }
        return r;
    }

    /** date.fromisoformat(s) of Python 3.14 (YYYY-MM-DD, YYYYMMDD and the ISO week forms), else ValueError. */
    static LocalDate fromIso(String s) {
        Matcher m = ISO_DATE.matcher(s);
        if (!m.matches()) {
            throw new Py.Raised("ValueError");
        }
        int year = Integer.parseInt(m.group(1));
        if (m.group(2) != null) {
            return date(year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        }
        if (m.group(4) != null) {
            return date(year, Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)));
        }
        int week = Integer.parseInt(m.group(7));
        int day = m.group(8) == null ? 1 : Integer.parseInt(m.group(8));
        if (year < 1 || week < 1 || day < 1 || day > 7) {
            throw new Py.Raised("ValueError");
        }
        LocalDate jan4 = LocalDate.of(year, 1, 4);
        long weeks = jan4.range(IsoFields.WEEK_OF_WEEK_BASED_YEAR).getMaximum();
        if (week > weeks) {
            throw new Py.Raised("ValueError");
        }
        LocalDate r = jan4.with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week).with(java.time.DayOfWeek.of(day));
        if (r.getYear() < 1 || r.getYear() > 9999) {
            throw new Py.Raised("ValueError");
        }
        return r;
    }

    /** sp5.py _date: an ISO date of a str value, else null. */
    static LocalDate parseDate(Object value) {
        if (!(value instanceof String s) || s.isEmpty()) {
            return null;
        }
        try {
            return fromIso(s);
        } catch (Py.Raised e) {
            return null;
        }
    }

    /** date.weekday(): 0 = Monday. */
    static int weekday(LocalDate d) {
        return d.getDayOfWeek().getValue() - 1;
    }
}
