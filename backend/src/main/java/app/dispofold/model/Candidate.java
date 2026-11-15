package app.dispofold.model;

import java.time.LocalDate;

/**
 * A person who could be asked to take a duty on request: the shift is only on request for the person on that day,
 * the day is free and setting the duty adds no hard violation.
 *
 * @param plannedHours hours of the month so far that count towards the target (duties and credited days)
 */
public record Candidate(String personId, LocalDate date, String shiftId, double plannedHours, double targetHours) {
}
