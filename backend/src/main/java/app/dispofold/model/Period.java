package app.dispofold.model;

import java.time.YearMonth;
import java.util.List;

/** Input for one planning month that planners enter or import. */
public record Period(YearMonth month, List<Absence> absences, List<Wish> wishes, List<FixedAssignment> fixed,
        List<PreviousDuty> previousDuties) {
}
