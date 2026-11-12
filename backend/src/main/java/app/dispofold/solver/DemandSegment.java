package app.dispofold.solver;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A piece of an area's demand during which the required staffing and the set of shifts that could cover it
 * do not change. start and end are real minutes (see {@link TimeLine}), from and to the same as local times.
 *
 * @param day the day whose demand window this segment belongs to
 * @param max null when there is no upper limit
 */
public record DemandSegment(String id, String areaId, LocalDate day, long start, long end, int required,
        Integer max, LocalDateTime from, LocalDateTime to) {

    /** Staffing above this counts as overstaffing: the maximum, or the required staffing when there is none. */
    public int upper() {
        return max != null ? max : required;
    }

    public long minutes() {
        return end - start;
    }

    @Override
    public String toString() {
        return id;
    }
}
