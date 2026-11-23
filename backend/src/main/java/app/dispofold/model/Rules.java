package app.dispofold.model;

import java.time.LocalTime;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Planning rules. Defaults follow a collective agreement typical for dispatch centres; values the company
 * agreement ("Betriebsvereinbarung") decides are parameters. See docs/regeln.md.
 *
 * @param minDailyRestHours collective agreement, rest periods; a shortened rest is for operations, never for planning
 * @param minWeeklyRestHours collective agreement, rest periods, checked per calendar week (Monday to Sunday)
 * @param maxDutyHours collective agreement, averaging rule
 * @param maxWeekHours collective agreement, averaging rule, per calendar week
 * @param maxAverageWeekHours collective agreement, averaging rule: the full-time average over the averaging period and
 *        the upper bound of every person's own limit (see {@code averageTolerancePercent})
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
 * @param averagingWeeks weeks over which {@code maxAverageWeekHours} is averaged when the period is
 *        {@link AveragingPeriod#ROLLING}
 * @param maxOverTargetPercent a person's counted hours of a month may exceed the target by at most this share of
 *        the target (hard); null in rules saved before it existed means {@link #DEFAULT_OVER_TARGET_PERCENT}
 * @param minFreeSundays Sundays of each calendar month without work per person (hard; a default value, adjustable,
 *        0 switches it off); null in rules saved before it existed means {@link #DEFAULT_FREE_SUNDAYS}
 * @param nightDistribution how nights are distributed; null in rules saved before it existed means
 *        {@link NightDistribution#HABIT}
 * @param averageTolerancePercent the average limit of a person is their weekly hours plus this share of them, at most
 *        {@code maxAverageWeekHours}; null in rules saved before it existed means {@link #DEFAULT_AVERAGE_TOLERANCE}
 * @param freeInsideAbsence a holiday, Saturday or Sunday between two workdays with an absence that lowers the target
 *        is free (hard), and so is a holiday whose other workdays of the week all have one; null in rules saved
 *        before it existed means on
 * @param averagingPeriod the period of the average weekly hours; null in rules saved before it existed means
 *        {@link AveragingPeriod#HALF_YEAR}
 * @param accountBalancing how the goal evens out the hours account; null in rules saved before it existed means
 *        {@link AccountBalancing#PERIOD_END}
 * @param carryMinusRest with {@link AccountBalancing#PERIOD_END}: a minus balance at the end of a period carries
 *        into the next one (a plus balance never does); null in rules saved before it existed means no (dropped)
 */
public record Rules(double minDailyRestHours, double minWeeklyRestHours, double maxDutyHours, double maxWeekHours,
        double maxAverageWeekHours, int minBlock, int maxBlock, int maxWishesPerPerson,
        boolean holidayWorkEarnsCompRest, LocalTime nightStart, LocalTime nightEnd, double nightMinHours,
        double accountCarryShare, int historyMonths, Map<String, Long> weights, String timeZone,
        int averagingWeeks, Double maxOverTargetPercent, Integer minFreeSundays,
        NightDistribution nightDistribution, @JsonInclude(JsonInclude.Include.NON_NULL) Double averageTolerancePercent,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean freeInsideAbsence,
        @JsonInclude(JsonInclude.Include.NON_NULL) AveragingPeriod averagingPeriod,
        @JsonInclude(JsonInclude.Include.NON_NULL) AccountBalancing accountBalancing,
        @JsonInclude(JsonInclude.Include.NON_NULL) Boolean carryMinusRest) {

    public static final String DEFAULT_TIME_ZONE = "Europe/Vienna";
    /** Planning never goes below this daily rest; a shortened rest is for operations only. */
    public static final double MIN_DAILY_REST_HOURS = 11;
    public static final double DEFAULT_OVER_TARGET_PERCENT = 10;
    public static final int DEFAULT_FREE_SUNDAYS = 2;
    public static final double DEFAULT_AVERAGE_TOLERANCE = 10;

    /** Rules without the parameters added later (null): their accessors give the defaults. */
    public Rules(double minDailyRestHours, double minWeeklyRestHours, double maxDutyHours, double maxWeekHours,
            double maxAverageWeekHours, int minBlock, int maxBlock, int maxWishesPerPerson,
            boolean holidayWorkEarnsCompRest, LocalTime nightStart, LocalTime nightEnd, double nightMinHours,
            double accountCarryShare, int historyMonths, Map<String, Long> weights, String timeZone,
            int averagingWeeks, Double maxOverTargetPercent, Integer minFreeSundays,
            NightDistribution nightDistribution) {
        this(minDailyRestHours, minWeeklyRestHours, maxDutyHours, maxWeekHours, maxAverageWeekHours, minBlock,
                maxBlock, maxWishesPerPerson, holidayWorkEarnsCompRest, nightStart, nightEnd, nightMinHours,
                accountCarryShare, historyMonths, weights, timeZone, averagingWeeks, maxOverTargetPercent,
                minFreeSundays, nightDistribution, null, null, null);
    }

    /** Rules without the account balancing parameters (null): their accessors give the defaults. */
    public Rules(double minDailyRestHours, double minWeeklyRestHours, double maxDutyHours, double maxWeekHours,
            double maxAverageWeekHours, int minBlock, int maxBlock, int maxWishesPerPerson,
            boolean holidayWorkEarnsCompRest, LocalTime nightStart, LocalTime nightEnd, double nightMinHours,
            double accountCarryShare, int historyMonths, Map<String, Long> weights, String timeZone,
            int averagingWeeks, Double maxOverTargetPercent, Integer minFreeSundays,
            NightDistribution nightDistribution, Double averageTolerancePercent, Boolean freeInsideAbsence,
            AveragingPeriod averagingPeriod) {
        this(minDailyRestHours, minWeeklyRestHours, maxDutyHours, maxWeekHours, maxAverageWeekHours, minBlock,
                maxBlock, maxWishesPerPerson, holidayWorkEarnsCompRest, nightStart, nightEnd, nightMinHours,
                accountCarryShare, historyMonths, weights, timeZone, averagingWeeks, maxOverTargetPercent,
                minFreeSundays, nightDistribution, averageTolerancePercent, freeInsideAbsence, averagingPeriod, null,
                null);
    }

    /** {@link #accountBalancing()} with the default for rules saved without it. */
    public AccountBalancing balancing() {
        return accountBalancing != null ? accountBalancing : AccountBalancing.PERIOD_END;
    }

    /** The account evens out by the end of the calendar half-year (the default); else by the fixed share. */
    public boolean balancesByPeriodEnd() {
        return balancing() == AccountBalancing.PERIOD_END && averaging() == AveragingPeriod.HALF_YEAR;
    }

    /** {@link #carryMinusRest()} with the default for rules saved without it. */
    public boolean carriesMinusRest() {
        return carryMinusRest != null && carryMinusRest;
    }

    /** {@link #averageTolerancePercent()} with the default for rules saved without it. */
    public double averageTolerance() {
        return averageTolerancePercent != null ? averageTolerancePercent : DEFAULT_AVERAGE_TOLERANCE;
    }

    /** {@link #averagingPeriod()} with the default for rules saved without it. */
    public AveragingPeriod averaging() {
        return averagingPeriod != null ? averagingPeriod : AveragingPeriod.HALF_YEAR;
    }

    /** {@link #freeInsideAbsence()} with the default for rules saved without it. */
    public boolean freeDaysInsideAbsence() {
        return freeInsideAbsence == null || freeInsideAbsence;
    }

    /** {@link #minFreeSundays()} with the default for rules saved without it. */
    public int freeSundays() {
        return minFreeSundays != null ? minFreeSundays : DEFAULT_FREE_SUNDAYS;
    }

    /** {@link #nightDistribution()} with the default for rules saved without it. */
    public NightDistribution nightMode() {
        return nightDistribution != null ? nightDistribution : NightDistribution.HABIT;
    }

    /** {@link #maxOverTargetPercent()} with the default for rules saved without it. */
    public double overTargetPercent() {
        return maxOverTargetPercent != null ? maxOverTargetPercent : DEFAULT_OVER_TARGET_PERCENT;
    }


    /** The defaults; the parameters added later stay null (their defaults), so that the input fingerprint of plans
     * made before them stays the same. */
    public static Rules defaults() {
        return new Rules(11, 36, 12, 60, 46.16, 2, 4, 6, true, LocalTime.of(22, 0), LocalTime.of(6, 0), 2, 0.25, 3,
                Map.of(), DEFAULT_TIME_ZONE, 52, DEFAULT_OVER_TARGET_PERCENT,
                DEFAULT_FREE_SUNDAYS, NightDistribution.HABIT, null, null, null);
    }
}
