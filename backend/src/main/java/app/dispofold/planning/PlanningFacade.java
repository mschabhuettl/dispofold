package app.dispofold.planning;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import app.dispofold.model.Candidate;
import app.dispofold.model.ConstraintHit;
import app.dispofold.model.ConstraintInfo;
import app.dispofold.model.CoverageSegment;
import app.dispofold.model.PersonExplanation;
import app.dispofold.model.PersonHit;
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

    /** The constraint matches of every person by person id, as {@link #explain} gives them, from one analysis. */
    Map<String, List<ConstraintHit>> hits(PlanningInput input);

    /**
     * Every match with a penalty, from one analysis: those of every person (as {@link #hits} gives them) and, with
     * person id null, overstaffing (no person). Uncovered demand is left out: it is in the gaps of {@link #analyze}.
     */
    List<PersonHit> problems(PlanningInput input);

    /** Demand against staffing of one area for every segment of the month. */
    List<CoverageSegment> coverage(PlanningInput input, String areaId);

    /**
     * People who could take a duty on request that overlaps the given local time span of an area, fewest counted
     * hours against the target first.
     */
    List<Candidate> candidates(PlanningInput input, String areaId, LocalDateTime from, LocalDateTime to);
}
