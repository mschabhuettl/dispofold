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
    public static final String QUALIFICATION = "qualification";
    public static final String ABSENCE = "absence";
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
    public static final String FAMILY = "family";
    public static final String ON_REQUEST = "onRequest";
    public static final String OVERSTAFFING = "overstaffing";
    public static final String USELESS_DUTY = "uselessDuty";

    /** Display order: hard, medium, soft. Texts are in the user interface under constraint.<id>. */
    public static final List<ConstraintInfo> ALL = List.of(
            hard(DAILY_REST), hard(WEEKLY_REST), hard(MAX_DUTY), hard(MAX_WEEK), hard(AVERAGE_WEEK),
            hard(QUALIFICATION), hard(ABSENCE), hard(EMPLOYMENT), hard(NEVER), hard(COMP_REST_BALANCE),
            hard(MISSING_SHIFT),
            new ConstraintInfo(DEMAND, MEDIUM, 1),
            soft(TARGET_HOURS, 20), soft(FAIR_NIGHTS, 3), soft(FAIR_WEEKENDS, 3), soft(FAIR_HOLIDAYS, 3),
            soft(FAIR_UNPOPULAR, 2), soft(WISHES, 300), soft(BLOCK_LENGTH, 600), soft(ISOLATED_DAY_OFF, 400),
            soft(FORWARD_ROTATION, 500), soft(SPLIT_WEEKEND, 300), soft(COMP_REST_USE, 500),
            soft(SHIFT_VARIETY, 5), soft(AREA_VARIETY, 1), soft(FAMILY, 800), soft(ON_REQUEST, 1000),
            soft(OVERSTAFFING, 2), soft(USELESS_DUTY, 5000));

    private ConstraintCatalog() {
    }

    private static ConstraintInfo hard(String id) {
        return new ConstraintInfo(id, HARD, 1);
    }

    private static ConstraintInfo soft(String id, long weight) {
        return new ConstraintInfo(id, SOFT, weight);
    }

    public static ConstraintInfo get(String id) {
        return ALL.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    /** Effective weight: the configured one for soft constraints, else the default. */
    public static long weight(ConstraintInfo info, Map<String, Long> weights) {
        Long configured = weights == null ? null : weights.get(info.id());
        return SOFT.equals(info.level()) && configured != null ? configured : info.defaultWeight();
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
