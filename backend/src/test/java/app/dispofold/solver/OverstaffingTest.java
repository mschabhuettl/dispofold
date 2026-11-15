package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;

import app.dispofold.model.Dataset;
import app.dispofold.model.DemandRule;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.PlanAssignment;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import org.junit.jupiter.api.Test;

/** Staffing above the demand is medium: never a way to reach target hours, never a reason to leave a gap. */
class OverstaffingTest {

    /**
     * The small dataset with one person on the day shift of AN and on the day and night shift of DR, every day:
     * far more people than demand, and windows that match the shifts, so no overstaffing is needed anywhere.
     */
    private static PlanningInput lowDemand() {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        Dataset d = base.dataset();
        List<String> all = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "HOL");
        List<DemandRule> one = List.of(
                new DemandRule("AN", all, null, LocalTime.of(7, 0), LocalTime.of(19, 0), 1, null),
                new DemandRule("DR", all, null, LocalTime.of(7, 0), LocalTime.of(19, 0), 1, null),
                new DemandRule("DR", all, null, LocalTime.of(19, 0), LocalTime.of(7, 0), 1, null));
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), one, d.holidays(),
                d.absenceTypes(), d.people(), d.restrictions(), d.history(), d.periods());
        return new PlanningInput(changed, base.period(), base.rules(), List.of());
    }

    @Test
    void capacityAboveDemandLeavesTargetHoursOpenInsteadOfDoubledPositions() throws Exception {
        PlanningInput input = lowDemand();
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        SolveProgress result = TimefoldPlanningFacadeTest.solve(facade, input, new SolveOptions(1, 15, 0, 0));
        PlanAnalysis analysis = facade.analyze(TimefoldPlanningFacadeTest.withAssignments(input,
                result.assignments()));

        assertThat(analysis.hardViolations()).isZero();
        // Every gap is filled: overstaffing never blocks coverage.
        assertThat(analysis.gaps()).isEmpty();
        assertThat(analysis.uncoveredHours()).isZero();
        // No doubled positions. Early plus late shift instead of one day shift may leave a half hour of overlap,
        // which no single change removes (local search); a whole duty above the demand never stays.
        assertThat(analysis.overstaffedHours()).isLessThan(2);
        // The hours that are not needed stay open.
        long below = analysis.people().stream()
                .filter(p -> p.plannedHours() + p.creditedHours() < p.targetHours() - 20).count();
        assertThat(below).isGreaterThan(analysis.people().size() / 2);
    }

    @Test
    void handSetOverstaffingShowsInTheMediumScore() {
        PlanningInput base = lowDemand();
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        // Nothing planned except two people on the same day shift of a weekday: one more than the demand.
        var day = base.period().month().atDay(5);
        PlanningInput input = TimefoldPlanningFacadeTest.withAssignments(base, List.of(
                new PlanAssignment("P0016", day, "AN-T", false, true),
                new PlanAssignment("P0017", day, "AN-T", false, true)));
        PlanAnalysis analysis = facade.analyze(input);
        PlanAnalysis single = facade.analyze(TimefoldPlanningFacadeTest.withAssignments(base,
                List.of(new PlanAssignment("P0016", day, "AN-T", false, true))));
        // 07:00 to 19:00 has a minimum of 1: the second person overstaffs 12 h.
        assertThat(analysis.overstaffedHours() - single.overstaffedHours()).isEqualTo(12.0);
        assertThat(analysis.constraints()).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo(ConstraintCatalog.OVERSTAFFING);
            assertThat(c.level()).isEqualTo(ConstraintCatalog.MEDIUM);
            assertThat(c.matches()).isPositive();
        });
        // One missing minute weighs more than the longest duty above the demand: a shift lasts less than a day,
        // plus one hour in the night the clock goes back.
        assertThat(ConstraintCatalog.get(ConstraintCatalog.DEMAND).defaultWeight())
                .isGreaterThan((24 * 60 - 1 + 60) * ConstraintCatalog.get(ConstraintCatalog.OVERSTAFFING).defaultWeight());
        assertThat(ConstraintCatalog.get(ConstraintCatalog.USELESS_DUTY).defaultWeight())
                .isEqualTo(ConstraintCatalog.get(ConstraintCatalog.OVERSTAFFING).defaultWeight());
    }

    @Test
    void savedSoftWeightsForOverstaffingStillLoadAndAreIgnored() {
        java.util.Map<String, Long> saved = java.util.Map.of(ConstraintCatalog.OVERSTAFFING, 2L,
                ConstraintCatalog.USELESS_DUTY, 5000L);
        assertThat(ConstraintCatalog.weight(ConstraintCatalog.get(ConstraintCatalog.OVERSTAFFING), saved)).isEqualTo(1);
        assertThat(ConstraintCatalog.weight(ConstraintCatalog.get(ConstraintCatalog.USELESS_DUTY), saved)).isEqualTo(1);
        assertThat(ConstraintCatalog.get(ConstraintCatalog.OVERSTAFFING).tunable()).isFalse();
    }
}
