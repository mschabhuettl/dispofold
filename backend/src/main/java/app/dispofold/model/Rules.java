package app.dispofold.model;

import java.time.LocalTime;
import java.util.Map;

/**
 * Planning rules. Defaults follow a collective agreement typical for dispatch centres; values the company
 * agreement ("Betriebsvereinbarung") decides are parameters. See docs/regeln.md.
 *
 * @param minDailyRestHours collective agreement, rest periods; a shortened rest is for operations, never for planning
 * @param minWeeklyRestHours collective agreement, rest periods, checked per calendar week (Monday to Sunday)
 * @param maxDutyHours collective agreement, averaging rule
 * @param maxWeekHours collective agreement, averaging rule, per calendar week
 * @param maxAverageWeekHours collective agreement, averaging rule; average over the averaging period; shown, not solved
 * @param minBlock preferred minimum number of consecutive duties
 * @param maxBlock preferred maximum number of consecutive duties
 * @param maxWishesPerPerson wishes per person and month that are taken into account
 * @param holidayWorkEarnsCompRest collective agreement: work on a public holiday (not Sunday) earns a rest day
 * @param nightStart a duty counts as a night if it covers at least {@code nightMinHours} in this window
 * @param accountCarryShare share of the hours account that one month should even out (0..1)
 * @param historyMonths earlier months counted for fairness
 * @param weights soft constraint weights by constraint id; missing ids use the catalogue default
 * @param timeZone zone of all times, e.g. Europe/Vienna; duty lengths and rest use real minutes across the
 *        changes to and from summer time
 * @param averagingWeeks weeks over which {@code maxAverageWeekHours} is averaged
 */
public record Rules(double minDailyRestHours, double minWeeklyRestHours, double maxDutyHours, double maxWeekHours,
        double maxAverageWeekHours, int minBlock, int maxBlock, int maxWishesPerPerson,
        boolean holidayWorkEarnsCompRest, LocalTime nightStart, LocalTime nightEnd, double nightMinHours,
        double accountCarryShare, int historyMonths, Map<String, Long> weights, String timeZone,
        int averagingWeeks) {

    public static final String DEFAULT_TIME_ZONE = "Europe/Vienna";
    /** Planning never goes below this daily rest; a shortened rest is for operations only. */
    public static final double MIN_DAILY_REST_HOURS = 11;


    public static Rules defaults() {
        return new Rules(11, 36, 12, 60, 46.16, 2, 4, 6, true, LocalTime.of(22, 0), LocalTime.of(6, 0), 2, 0.25, 3,
                Map.of(), DEFAULT_TIME_ZONE, 52);
    }
}
