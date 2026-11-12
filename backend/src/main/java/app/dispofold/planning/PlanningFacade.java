package app.dispofold.planning;

import java.time.YearMonth;
import java.util.List;
import java.util.function.Consumer;

import app.dispofold.model.ConstraintInfo;
import app.dispofold.model.CoverageSegment;
import app.dispofold.model.PersonExplanation;
import app.dispofold.model.PlanAnalysis;

/**
 * Entry point of the solver for the REST layer. Implemented as an application scoped CDI bean. Only one solve
 * per month runs at a time.
 */
public interface PlanningFacade {

    /** All constraints with label, level and default weight, in display order. */
    List<ConstraintInfo> constraintCatalog();

    /**
     * Starts solving in the background. {@code onProgress} gets every new best solution and finally one with
     * {@code finished = true}; {@code onError} gets a failure instead.
     */
    void solve(PlanningInput input, SolveOptions options, Consumer<SolveProgress> onProgress,
            Consumer<Throwable> onError);

    /** Stops a running solve early; the best solution so far is delivered as finished. */
    void stop(YearMonth month);

    boolean isSolving(YearMonth month);

    /** Score, constraint totals, uncovered demand and per-person figures of the given roster. */
    PlanAnalysis analyze(PlanningInput input);

    /** Key figures and all constraint matches of one person. */
    PersonExplanation explain(PlanningInput input, String personId);

    /** Demand against staffing of one area for every segment of the month. */
    List<CoverageSegment> coverage(PlanningInput input, String areaId);
}
