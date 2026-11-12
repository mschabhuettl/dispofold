package app.dispofold.model;

import java.util.List;

/**
 * Evaluation of a plan.
 *
 * @param hardViolations number of hard constraint matches (must be 0)
 * @param gaps uncovered demand; empty when the demand is fully covered
 */
public record PlanAnalysis(String score, int hardViolations, List<ConstraintSummary> constraints,
        List<CoverageGap> gaps, List<PersonStats> people) {
}
