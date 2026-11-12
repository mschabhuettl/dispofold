package app.dispofold.model;

import java.time.LocalDateTime;

/** Demand that the plan does not cover. */
public record CoverageGap(String areaId, LocalDateTime from, LocalDateTime to, int required, int planned) {
}
