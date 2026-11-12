package app.dispofold.planning;

import java.util.List;

import app.dispofold.model.Dataset;
import app.dispofold.model.Period;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.Rules;

/**
 * Everything the solver needs for one month.
 *
 * @param assignments the current roster cells; pinned cells are kept, the others are a starting point
 *        (may be empty)
 */
public record PlanningInput(Dataset dataset, Period period, Rules rules, List<PlanAssignment> assignments) {
}
