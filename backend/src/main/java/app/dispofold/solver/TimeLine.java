package app.dispofold.solver;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;

/**
 * The solver's time line: minutes since the epoch (real elapsed time) for local dates and times in one zone. Across
 * the change to summer time a day has 23 hours, back to winter time 25, so durations and rest are real minutes.
 * Local times that do not exist (spring gap) move forward; ambiguous ones (autumn) take the earlier offset.
 */
public final class TimeLine {

    /** No time zone changes; used where real and local time are the same (tests). */
    public static final TimeLine UTC = new TimeLine(ZoneOffset.UTC);

    private final ZoneId zone;

    public TimeLine(ZoneId zone) {
        this.zone = zone;
    }

    public ZoneId zone() {
        return zone;
    }

    /** @param minuteOfDay local minutes after midnight of {@code date}; 1440 and more fall on later days */
    public long at(LocalDate date, int minuteOfDay) {
        return date.atStartOfDay().plusMinutes(minuteOfDay).atZone(zone).toEpochSecond() / 60;
    }

    public long midnight(LocalDate date) {
        return at(date, 0);
    }

    public LocalDateTime local(long minute) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(minute * 60), zone);
    }

    /** True if no change of the offset falls between the date's midnight and two days later. */
    public boolean regular(LocalDate date) {
        ZoneOffsetTransition next = zone.getRules().nextTransition(date.atStartOfDay(zone).toInstant());
        return next == null || !next.getInstant().isBefore(date.plusDays(2).atStartOfDay(zone).toInstant());
    }
}
