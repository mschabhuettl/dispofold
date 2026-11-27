package app.dispofold.solver;

import java.util.Map;
import java.util.Set;

/**
 * A planned person with everything the constraints need, precomputed from the dataset, the period and the
 * rules. Problem fact; identity is the person id.
 */
public final class Staff {

    /** Not in the night comparison: the usual category mix decides the nights (night distribution HABIT). */
    public static final String NIGHTS_MIX = "MIX";
    /** Not in the night comparison: the person never does nights (no night shift, restrictions, family rules). */
    public static final String NIGHTS_NEVER = "NEVER";

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
    /** Usual shares per category and area, or null (see {@link app.dispofold.model.ShiftMix}). */
    private app.dispofold.model.ShiftMix mix;
    /** Why the person is not in the night comparison ({@link #NIGHTS_MIX}, {@link #NIGHTS_NEVER}), null: in it. */
    private String nightExclusion;
    /** The category shares of the mix are without the night share and apply to the duties that are not nights. */
    private boolean nightsOutsideMix;
    private NightWindow nightWindow = NightWindow.NONE;
    /** Nights over the window above the usual share that the mix rule does not count (see nightMixTolerance). */
    private int nightMixTolerance = app.dispofold.model.Rules.DEFAULT_NIGHT_MIX_TOLERANCE;
    /** Over the window, fewer nights than the share count too (see nightMixFloor). */
    private boolean nightMixFloor;
    /** Minutes above or below the scaled target that the target rule does not count. */
    private int targetToleranceMinutes;

    private Set<String> unusualShifts = Set.of();

    private Set<String> unusualLengths = Set.of();
    /** Target the soft rule aims at: the target scaled to the work the demand needs (see RosterBuilder). */
    private int scaledTargetMinutes;

    /**
     * Per-person limits from {@link app.dispofold.model.Rules}, in minutes.
     *
     * @param maxMonth counted minutes of the month allowed at most: the target plus the tolerance
     * @param maxSundaysWorked Sundays of the month with work allowed at most: Sundays minus the free ones required
     */
    public record Limits(int dailyRest, int weeklyRest, int maxDuty, int maxWeek, int minBlock, int maxBlock,
            int maxMonth, int maxSundaysWorked, int nightRest, int dayToNight, int nightMin, int nightMax) {

        /** Limits with the default free days around nights and night blocks. */
        public Limits(int dailyRest, int weeklyRest, int maxDuty, int maxWeek, int minBlock, int maxBlock,
                int maxMonth, int maxSundaysWorked) {
            this(dailyRest, weeklyRest, maxDuty, maxWeek, minBlock, maxBlock, maxMonth, maxSundaysWorked,
                    app.dispofold.model.Rules.DEFAULT_NIGHT_REST_DAYS, 0, app.dispofold.model.Rules.DEFAULT_NIGHT_BLOCK_MIN,
                    app.dispofold.model.Rules.DEFAULT_NIGHT_BLOCK_MAX);
        }
    }

    /**
     * The months before this one in the window of the usual night share (see {@code nightMixMonths}): how many of them
     * the history has, and their nights and worked minutes.
     */
    public record NightWindow(int months, int nights, long minutes) {
        public static final NightWindow NONE = new NightWindow(0, 0, 0);
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
     * @param maxWeekMinutes the person's own limit of the average per week: the weekly hours of each month plus the
     *        tolerance, at most the rules' maximum, weighted by the month's weeks
     */
    public record Average(long minutes, double weeks, boolean complete, double maxWeekMinutes) {
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
        this.scaledTargetMinutes = targetMinutes;
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

    /** The target scaled to the available work; equal to the target when the demand needs all of it. */
    public int getScaledTargetMinutes() {
        return scaledTargetMinutes;
    }

    public void setScaledTargetMinutes(int scaledTargetMinutes) {
        this.scaledTargetMinutes = scaledTargetMinutes;
    }

    public app.dispofold.model.ShiftMix getMix() {
        return mix;
    }

    public void setMix(app.dispofold.model.ShiftMix mix) {
        this.mix = mix;
    }

    /** The person has usual shares per category: they replace shift variety (and, in HABIT, night fairness). */
    public boolean hasCategoryMix() {
        return mix != null && !mix.categoriesOrEmpty().isEmpty();
    }

    /** In the night comparison {@code fairNights} (when the person can do nights at all, see {@link Fairness}). */
    public boolean isInNightPool() {
        return nightExclusion == null;
    }

    public String getNightExclusion() {
        return nightExclusion;
    }

    public void setNightExclusion(String nightExclusion) {
        this.nightExclusion = nightExclusion;
    }

    /**
     * The category shares count among the duties that are not of category NIGHT (night distribution EVEN, or a person
     * who never does nights).
     */
    public boolean isNightsOutsideMix() {
        return nightsOutsideMix;
    }

    public void setNightsOutsideMix(boolean nightsOutsideMix) {
        this.nightsOutsideMix = nightsOutsideMix;
    }

    public NightWindow getNightWindow() {
        return nightWindow;
    }

    public void setNightWindow(NightWindow nightWindow) {
        this.nightWindow = nightWindow;
    }

    public int getNightMixTolerance() {
        return nightMixTolerance;
    }

    public void setNightMixTolerance(int nightMixTolerance) {
        this.nightMixTolerance = nightMixTolerance;
    }

    public boolean isNightMixFloor() {
        return nightMixFloor;
    }

    public void setNightMixFloor(boolean nightMixFloor) {
        this.nightMixFloor = nightMixFloor;
    }

    public int getTargetToleranceMinutes() {
        return targetToleranceMinutes;
    }

    public void setTargetToleranceMinutes(int targetToleranceMinutes) {
        this.targetToleranceMinutes = targetToleranceMinutes;
    }

    /** Ids of the shifts the person did not work in the history (rule personShifts); empty without one. */
    public Set<String> getUnusualShifts() {
        return unusualShifts;
    }

    /** Ids of the shifts in a length class the person never worked in the history (rule personLengths). */
    public Set<String> getUnusualLengths() {
        return unusualLengths;
    }

    public void setUnusual(Set<String> shifts, Set<String> lengths) {
        this.unusualShifts = shifts;
        this.unusualLengths = lengths;
    }

    /** The person has usual shares per area: they replace area variety. */
    public boolean hasAreaMix() {
        return mix != null && !mix.areasOrEmpty().isEmpty();
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
