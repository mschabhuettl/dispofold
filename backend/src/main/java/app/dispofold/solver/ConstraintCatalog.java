package app.dispofold.solver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.dispofold.model.ConstraintInfo;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;

/** Ids, levels and default weights of all constraints, in display order. */
public final class ConstraintCatalog {

    public static final String HARD = "HARD";
    public static final String MEDIUM = "MEDIUM";
    public static final String SOFT = "SOFT";

    public static final String DAILY_REST = "dailyRest";
    public static final String WEEKLY_REST = "weeklyRest";
    public static final String MAX_DUTY = "maxDutyLength";
    public static final String MAX_WEEK = "maxWeekHours";
    public static final String MAX_MONTH = "maxMonthHours";
    public static final String FREE_SUNDAYS = "freeSundays";
    public static final String QUALIFICATION = "qualification";
    public static final String ABSENCE = "absence";
    public static final String FREE_INSIDE_ABSENCE = "freeInsideAbsence";
    public static final String NEVER = "neverRestriction";
    public static final String COMP_REST_BALANCE = "compRestBalance";
    public static final String AVERAGE_WEEK = "averageWeekHours";
    public static final String EMPLOYMENT = "employment";
    public static final String MISSING_SHIFT = "missingShift";
    public static final String DEMAND = "demand";
    public static final String TARGET_HOURS = "targetHours";
    public static final String FAIR_NIGHTS = "fairNights";
    public static final String FAIR_WEEKENDS = "fairWeekends";
    public static final String FAIR_HOLIDAYS = "fairHolidays";
    public static final String FAIR_UNPOPULAR = "fairUnpopular";
    public static final String WISHES = "wishes";
    public static final String BLOCK_LENGTH = "blockLength";
    public static final String ISOLATED_DAY_OFF = "isolatedDayOff";
    public static final String FORWARD_ROTATION = "forwardRotation";
    public static final String SPLIT_WEEKEND = "splitWeekend";
    public static final String COMP_REST_USE = "compRestUse";
    public static final String SHIFT_VARIETY = "shiftVariety";
    public static final String AREA_VARIETY = "areaVariety";
    public static final String SHIFT_MIX = "shiftMix";
    public static final String FAMILY = "family";
    public static final String ON_REQUEST = "onRequest";
    public static final String OVERSTAFFING = "overstaffing";
    public static final String USELESS_DUTY = "uselessDuty";

    /**
     * Weight of an uncovered person minute against one minute above the demand (weight 1): more than all duties of
     * a month can ever overstaff (20 000 people x 31 days x a duty of at most 1499 real minutes is below it), so
     * covering demand comes first for the whole plan, not only per duty. Because overstaffing stays below this
     * weight, both parts can be read exactly from the medium score: uncovered minutes = -medium / weight,
     * overstaffed minutes = -medium % weight.
     */
    public static final long DEMAND_WEIGHT = 1_000_000_000L;

    /** Display order: hard, medium, soft. Texts are in the user interface under constraint.<id>. */
    public static final List<ConstraintInfo> ALL = List.of(
            hard(DAILY_REST), hard(WEEKLY_REST), hard(MAX_DUTY), hard(MAX_WEEK), hard(MAX_MONTH), hard(FREE_SUNDAYS), hard(AVERAGE_WEEK),
            hard(QUALIFICATION), hard(ABSENCE), hard(FREE_INSIDE_ABSENCE), hard(EMPLOYMENT), hard(NEVER), hard(COMP_REST_BALANCE),
            hard(MISSING_SHIFT),
            // Medium: staffing against demand, in person minutes. One missing minute weighs more than any staffing
            // above the demand in the whole plan (DEMAND_WEIGHT), so a gap is never left open to avoid it; and
            // staffing above the demand always weighs more than any soft rule, so it never serves target hours.
            new ConstraintInfo(DEMAND, MEDIUM, DEMAND_WEIGHT, false), new ConstraintInfo(OVERSTAFFING, MEDIUM, 1, false),
            new ConstraintInfo(USELESS_DUTY, MEDIUM, 1, false),
            soft(TARGET_HOURS, 20), soft(FAIR_NIGHTS, 3), soft(FAIR_WEEKENDS, 3), soft(FAIR_HOLIDAYS, 3),
            soft(FAIR_UNPOPULAR, 2), soft(WISHES, 300), soft(BLOCK_LENGTH, 6000), soft(ISOLATED_DAY_OFF, 10000),
            soft(FORWARD_ROTATION, 500), soft(SPLIT_WEEKEND, 300), soft(COMP_REST_USE, 500),
            soft(SHIFT_VARIETY, 5), soft(AREA_VARIETY, 1), soft(SHIFT_MIX, 100), soft(FAMILY, 800),
            // On-request duties are never chosen by the solver (see RosterBuilder); the constraint only marks
            // duties set by planners, so its weight is not a setting.
            new ConstraintInfo(ON_REQUEST, SOFT, 1000, false));

    private ConstraintCatalog() {
    }

    private static ConstraintInfo hard(String id) {
        return new ConstraintInfo(id, HARD, 1, false);
    }

    private static ConstraintInfo soft(String id, long weight) {
        return new ConstraintInfo(id, SOFT, weight, true);
    }

    public static ConstraintInfo get(String id) {
        return ALL.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** Effective weight: the configured one for tunable constraints, else the default. */
    public static long weight(ConstraintInfo info, Map<String, Long> weights) {
        Long configured = weights == null ? null : weights.get(info.id());
        return info.tunable() && configured != null ? configured : info.defaultWeight();
    }

    public static ConstraintWeightOverrides<HardMediumSoftScore> overrides(Map<String, Long> weights) {
        Map<String, HardMediumSoftScore> map = new HashMap<>();
        for (ConstraintInfo info : ALL) {
            long w = weight(info, weights);
            map.put(info.id(), switch (info.level()) {
                case HARD -> HardMediumSoftScore.ofHard(w);
                case MEDIUM -> HardMediumSoftScore.ofMedium(w);
                default -> HardMediumSoftScore.ofSoft(w);
            });
        }
        return ConstraintWeightOverrides.of(map);
    }
}
