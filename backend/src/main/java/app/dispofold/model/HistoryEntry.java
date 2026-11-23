package app.dispofold.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Per-person figures of an earlier month: counters for fairness over several months, worked hours for the
 * long-term average and the balances after the month.
 *
 * @param month ISO month, e.g. 2026-09
 * @param source "import" (actual values from the source system, never overwritten by a month closing) or "plan"
 *        (written when a month is closed)
 * @param hours duty hours worked in the month
 * @param hoursAccountAfter hours account after the month, null if unknown
 * @param compRestBalanceAfter open compensatory rest days after the month, null if unknown
 * @param compRestUsed compensatory rest days taken in the month
 * @param compRestEarned compensatory rest days earned in the month (holiday work)
 * @param weeklyHours contract weekly hours in the month, for the person's average limit; null when unknown (the
 *        person's current weekly hours count); written only when set
 */
public record HistoryEntry(String personId, String month, String source, int nights, int weekendsWorked,
        int holidays, int unpopular, int wishes, int wishesMet, double hours, Double hoursAccountAfter,
        Integer compRestBalanceAfter, int compRestUsed, int compRestEarned,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double weeklyHours) {

    /** An entry without weekly hours. */
    public HistoryEntry(String personId, String month, String source, int nights, int weekendsWorked,
            int holidays, int unpopular, int wishes, int wishesMet, double hours, Double hoursAccountAfter,
            Integer compRestBalanceAfter, int compRestUsed, int compRestEarned) {
        this(personId, month, source, nights, weekendsWorked, holidays, unpopular, wishes, wishesMet, hours,
                hoursAccountAfter, compRestBalanceAfter, compRestUsed, compRestEarned, null);
    }

    public static final String IMPORT = "import";
    public static final String PLAN = "plan";
}
