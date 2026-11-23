package app.dispofold.model;

/**
 * Per-person key figures of a plan. History counters are the sums over {@link Rules#historyMonths()} earlier
 * months.
 *
 * @param targetHours monthly target from contract hours and calendar, before the account correction
 * @param creditedHours hours credited by absences and compensatory rest
 * @param plannedHours duty hours in the plan
 * @param accountAfter hours account after this month (account + planned + credited - target)
 * @param compRestBalanceAfter open compensatory rest days after this month
 * @param availableDays days of the month the person is employed and not absent
 * @param chargedHours hours of absences that are booked against the hours account (time off in lieu)
 * @param averageWeekHours average weekly duty hours over the averaging period (history months plus this month)
 * @param averageComplete the history covers the whole averaging period, so the average is checked as a hard rule
 * @param averageLimitHours the person's own limit of the average: weekly hours plus the tolerance, at most the
 *        rules' maximum (see {@link Rules#averageTolerancePercent()})
 * @param goalHours the goal of the month: target corrected by a share of the hours account (the hard cap is based
 *        on it)
 * @param scaledGoalHours the goal scaled to the work the demand needs, which the soft target rule aims at; equal to
 *        {@code goalHours} when the demand needs all the work
 * @param plannedMix the plan's shares of the person's duties per category and area, to compare with the stored
 *        mix; null without duties
 * @param nightPool the person is in the night comparison ({@code fairNights}); null for viewers
 * @param nightReason why not: {@code MIX} (the usual category mix decides the nights, night distribution HABIT),
 *        {@code NEVER} (no night shift in the person's areas, restrictions or family rules exclude all nights), null
 *        when in the comparison, without available days or for viewers
 * @param nightLoad the person's night load as the rule compares it: nights of this and the history months per 30
 *        available days of a 40 h person; null when not compared and for viewers
 * @param nightMean the mean night load of everybody in the comparison; null for viewers
 * @param weekendLoad the person's weekend load as {@code fairWeekends} compares it (weekends of this and the history
 *        months per 30 available days of a 40 h person); null when not compared and for viewers
 * @param holidayLoad the same for holiday duties ({@code fairHolidays})
 * @param unpopularLoad the same for unpopular duties ({@code fairUnpopular})
 */
public record PersonStats(String personId, double targetHours, double creditedHours, double plannedHours,
        double accountBefore, double accountAfter, int duties, int nights, int weekendsWorked, int weekendsOff,
        int holidays, int unpopular, int wishes, int wishesMet, int compRestPlanned, int compRestEarned,
        int compRestBalanceAfter, int singleDays, int historyNights, int historyWeekends, int historyHolidays,
        int historyUnpopular, int historyWishes, int historyWishesMet, int availableDays, double chargedHours,
        double averageWeekHours, boolean averageComplete, double averageLimitHours, double goalHours, double scaledGoalHours,
        ShiftMix plannedMix, Boolean nightPool, String nightReason, Double nightLoad, Double nightMean,
        Double weekendLoad, Double holidayLoad, Double unpopularLoad) {
}
