package app.dispofold.model;

import java.time.LocalDate;

/** A duty that is fixed in advance and not changed by the solver. A null shiftId fixes a day off. */
public record FixedAssignment(String personId, LocalDate date, String shiftId) {
}
