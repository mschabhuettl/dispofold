package app.dispofold.solver;

import java.time.YearMonth;
import java.util.List;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.domain.solution.PlanningEntityCollectionProperty;
import ai.timefold.solver.core.api.domain.solution.PlanningScore;
import ai.timefold.solver.core.api.domain.solution.PlanningSolution;
import ai.timefold.solver.core.api.domain.solution.ProblemFactCollectionProperty;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;

/** The roster of one month as a Timefold planning solution. */
@PlanningSolution
public class Roster {

    private YearMonth month;
    private TimeLine timeLine;
    @ProblemFactCollectionProperty
    private List<Staff> staff;
    @ProblemFactCollectionProperty
    private List<ShiftOption> shiftOptions;
    @ProblemFactCollectionProperty
    private List<DemandSegment> segments;
    @ProblemFactCollectionProperty
    private List<Week> weeks;
    @PlanningEntityCollectionProperty
    private List<DutyCell> cells;
    private ConstraintWeightOverrides<HardMediumSoftScore> constraintWeightOverrides;
    @PlanningScore
    private HardMediumSoftScore score;

    public Roster() {
    }

    public Roster(YearMonth month, TimeLine timeLine, List<Staff> staff, List<ShiftOption> shiftOptions,
            List<DemandSegment> segments, List<Week> weeks, List<DutyCell> cells,
            ConstraintWeightOverrides<HardMediumSoftScore> constraintWeightOverrides) {
        this.month = month;
        this.timeLine = timeLine;
        this.staff = staff;
        this.shiftOptions = shiftOptions;
        this.segments = segments;
        this.weeks = weeks;
        this.cells = cells;
        this.constraintWeightOverrides = constraintWeightOverrides;
    }

    public YearMonth getMonth() {
        return month;
    }

    public TimeLine getTimeLine() {
        return timeLine;
    }

    public List<Staff> getStaff() {
        return staff;
    }

    public List<ShiftOption> getShiftOptions() {
        return shiftOptions;
    }

    public List<DemandSegment> getSegments() {
        return segments;
    }

    public List<Week> getWeeks() {
        return weeks;
    }

    public List<DutyCell> getCells() {
        return cells;
    }

    public ConstraintWeightOverrides<HardMediumSoftScore> getConstraintWeightOverrides() {
        return constraintWeightOverrides;
    }

    public void setConstraintWeightOverrides(ConstraintWeightOverrides<HardMediumSoftScore> overrides) {
        this.constraintWeightOverrides = overrides;
    }

    public HardMediumSoftScore getScore() {
        return score;
    }

    public void setScore(HardMediumSoftScore score) {
        this.score = score;
    }
}
