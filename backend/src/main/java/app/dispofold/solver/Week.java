package app.dispofold.solver;

/**
 * A calendar week (Monday 00:00 to the next Monday 00:00) that overlaps the planning month, in minutes since
 * 1970-01-01. Weekly rest and weekly hours are checked per week.
 */
public record Week(String id, long start, long end) {
}
