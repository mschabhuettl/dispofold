package app.dispofold.model;

import java.time.LocalDate;

/**
 * One cell of the roster: a person on a day.
 *
 * @param shiftId the duty, or null for a day without duty
 * @param compRest the day is planned as compensatory rest ("Ersatzruhe"); shiftId is null then
 * @param pinned fixed by a planner; the solver keeps it
 */
public record PlanAssignment(String personId, LocalDate date, String shiftId, boolean compRest, boolean pinned) {
}
