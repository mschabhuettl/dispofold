package app.dispofold.solver;

import java.util.Map;
import java.util.Set;

/**
 * A planned person with everything the constraints need, precomputed from the dataset, the period and the
 * rules. Problem fact; identity is the person id.
 */
public final class Staff {

    /** Fairness loads are counts per {@value #FAIR_DAYS} available days of a 40 h person, in thousandths. */
    public static final double FAIR_DAYS = 30;

    private final String id;
    private final double weeklyHours;
    private final Set<String> areaIds;
    private final int creditMinutes;
    private final int targetMinutes;
    private final int compRestBalance;
    private final Limits limits;
    private final History history;
    private final int wishes;
    private final Fairness fairness;
    private final Average average;
    private final Map<String, Integer> previous;

    /** Per-person limits from {@link app.dispofold.model.Rules}, in minutes. */
    public record Limits(int dailyRest, int weeklyRest, int maxDuty, int maxWeek, int minBlock, int maxBlock) {
    }

    /** Sums over the history months counted for fairness; {@code months} is the number of those months. */
    public record History(int nights, int weekends, int holidays, int unpopular, int wishes, int wishesMet,
            int months) {
        public static final History NONE = new History(0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Weight of one counted duty in the fairness loads and which loads apply. The weight makes loads comparable:
     * a person with fewer available days (part of the month employed, long absence, part time) carries the same
     * load with fewer duties.
     *
     * @param weight thousandths per duty, 0: not compared at all (no available day)
     * @param nights the person can do night duties
     * @param unpopular the person can do unpopular duties
     */
    public record Fairness(long weight, boolean nights, boolean unpopular) {
        public static final Fairness NONE = new Fairness(0, false, false);

        /** @param availableDays days of this month the person can be planned plus the history months' days */
        public static Fairness of(double weeklyHours, double availableDays, boolean nights, boolean unpopular) {
            if (availableDays <= 0) {
                return NONE;
            }
            double hours = weeklyHours > 0 ? weeklyHours : 40;
            return new Fairness(Math.round(1000 * FAIR_DAYS * 40 / hours / availableDays), nights, unpopular);
        }
    }

    /**
     * Worked minutes and weeks of the history months inside the averaging period, for the average weekly hours.
     *
     * @param complete the history covers the whole averaging period, so the average is a hard rule
     * @param maxWeekMinutes the allowed average per week
     */
    public record Average(long minutes, double weeks, boolean complete, int maxWeekMinutes) {
        public static final Average NONE = new Average(0, 0, false, Integer.MAX_VALUE);
    }

    public Staff(String id, double weeklyHours, Set<String> areaIds, int creditMinutes, int targetMinutes,
            int compRestBalance, Limits limits, History history, int wishes, Fairness fairness, Average average,
            Map<String, Integer> previous) {
        this.id = id;
        this.weeklyHours = weeklyHours;
        this.areaIds = areaIds;
        this.creditMinutes = creditMinutes;
        this.targetMinutes = targetMinutes;
        this.compRestBalance = compRestBalance;
        this.limits = limits;
        this.history = history;
        this.wishes = wishes;
        this.fairness = fairness;
        this.average = average;
        this.previous = previous;
    }

    /** Key of the duties per area and category for variety, e.g. {@code AN/NIGHT}. */
    public static String varietyKey(ShiftOption o) {
        return o.getAreaId() + "/" + o.getCategory();
    }

    /** Duties of the previous month with this variety key, or in this area (key = area id). */
    public int previous(String key) {
        return previous.getOrDefault(key, 0);
    }

    public String getId() {
        return id;
    }

    public double getWeeklyHours() {
        return weeklyHours;
    }

    public Set<String> getAreaIds() {
        return areaIds;
    }

    /** Minutes credited for an absence day or a compensatory rest day (weekly hours / 5). */
    public int getCreditMinutes() {
        return creditMinutes;
    }

    /** Monthly target after the hours account correction, in minutes. */
    public int getTargetMinutes() {
        return targetMinutes;
    }

    public int getCompRestBalance() {
        return compRestBalance;
    }

    public Limits getLimits() {
        return limits;
    }

    public History getHistory() {
        return history;
    }

    /** Wishes of this month that are taken into account. */
    public int getWishes() {
        return wishes;
    }

    public Fairness getFairness() {
        return fairness;
    }

    public Average getAverage() {
        return average;
    }

    public boolean isMultiArea() {
        return areaIds.size() > 1;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Staff other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Staff[" + id + "]";
    }
}
