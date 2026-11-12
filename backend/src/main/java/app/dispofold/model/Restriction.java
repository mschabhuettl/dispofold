package app.dispofold.model;

import java.time.LocalDate;

/**
 * Person x shift (or shift category) x weekday restriction.
 *
 * @param shiftId the shift concerned; exactly one of shiftId and category is set
 * @param category all shifts of this category, e.g. NIGHT
 * @param weekday ISO day 1..7, or 0 for every day
 * @param validFrom first day the restriction applies, null: open
 * @param validTo last day the restriction applies, null: open
 */
public record Restriction(String personId, String shiftId, ShiftCategory category, int weekday,
        RestrictionKind kind, LocalDate validFrom, LocalDate validTo) {

    /** Applies on this day (validity and weekday). */
    public boolean appliesOn(LocalDate date) {
        return (validFrom == null || !date.isBefore(validFrom)) && (validTo == null || !date.isAfter(validTo))
                && (weekday == 0 || weekday == date.getDayOfWeek().getValue());
    }
}
