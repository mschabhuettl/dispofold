package app.dispofold.model;

import java.time.LocalDateTime;

/** Demand against planned staffing of an area in one time segment. {@code max} may be null. */
public record CoverageSegment(String areaId, LocalDateTime from, LocalDateTime to, int required, Integer max,
        int planned) {
}
