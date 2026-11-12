package app.dispofold.model;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

/**
 * A roster for one month. This is also the JSON export format (see docs/datenformat.md).
 *
 * @param score Timefold score string, e.g. "0hard/-120medium/-53210soft"
 */
public record Plan(String format, int version, YearMonth month, PlanStatus status, String score, long seed,
        Instant updated, List<PlanAssignment> assignments) {

    public static final String FORMAT = "dispofold/plan";
}
