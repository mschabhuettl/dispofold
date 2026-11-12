package app.dispofold.model;

import java.time.LocalDate;

/** A duty from the days before the planning month, needed for rest and block rules at the month start. */
public record PreviousDuty(String personId, LocalDate date, String shiftId) {
}
