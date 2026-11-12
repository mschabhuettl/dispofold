package app.dispofold.solver;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Conversions between the solver's minute time line (minutes since 1970-01-01 00:00 local) and java.time. */
public final class Times {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("dd.MM. HH:mm");

    private Times() {
    }

    public static long minute(LocalDate date) {
        return date.toEpochDay() * DutyCell.DAY_MINUTES;
    }

    public static long minute(LocalDateTime time) {
        return minute(time.toLocalDate()) + time.getHour() * 60 + time.getMinute();
    }

    public static int minuteOfDay(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    public static LocalDate date(long minute) {
        return LocalDate.ofEpochDay(Math.floorDiv(minute, DutyCell.DAY_MINUTES));
    }

    public static LocalDateTime dateTime(long minute) {
        return date(minute).atStartOfDay().plusMinutes(Math.floorMod(minute, DutyCell.DAY_MINUTES));
    }

    /** "9:30 h" */
    public static String hours(long minutes) {
        long abs = Math.abs(minutes);
        return (minutes < 0 ? "-" : "") + abs / 60 + ":" + String.format("%02d", abs % 60) + " h";
    }

    public static String range(long start, long end) {
        return dateTime(start).format(FORMAT) + " bis " + dateTime(end).format(FORMAT);
    }
}
