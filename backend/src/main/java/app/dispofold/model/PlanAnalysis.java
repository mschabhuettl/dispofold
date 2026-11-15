package app.dispofold.model;

import java.util.List;

/**
 * Evaluation of a plan.
 *
 * @param hardViolations number of hard constraint matches (must be 0)
 * @param gaps uncovered demand; empty when the demand is fully covered
 * @param uncoveredHours person hours of demand not covered
 * @param overstaffedHours person hours above the demand (above the maximum, or the minimum without one, and duties
 *        without any demand); together with {@code uncoveredHours} they make the medium score
 * @param singleDaysOff single days off between two duties of a person
 * @param blockDays days by which blocks of duties are shorter or longer than the preferred block length
 */
public record PlanAnalysis(String score, int hardViolations, List<ConstraintSummary> constraints,
        List<CoverageGap> gaps, List<PersonStats> people, double uncoveredHours, double overstaffedHours,
        int singleDaysOff, int blockDays) {
}
