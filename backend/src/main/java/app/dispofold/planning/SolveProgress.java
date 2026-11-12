package app.dispofold.planning;

import java.util.List;

import app.dispofold.model.PlanAssignment;

/** A new best solution during solving, or the final one. */
public record SolveProgress(String score, int hardViolations, long elapsedMillis, boolean finished,
        List<PlanAssignment> assignments) {
}
