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
 */
public record PersonStats(String personId, double targetHours, double creditedHours, double plannedHours,
        double accountBefore, double accountAfter, int duties, int nights, int weekendsWorked, int weekendsOff,
        int holidays, int unpopular, int wishes, int wishesMet, int compRestPlanned, int compRestEarned,
        int compRestBalanceAfter, int singleDays, int historyNights, int historyWeekends, int historyHolidays,
        int historyUnpopular, int historyWishes, int historyWishesMet, int availableDays, double chargedHours,
        double averageWeekHours, boolean averageComplete) {
}
