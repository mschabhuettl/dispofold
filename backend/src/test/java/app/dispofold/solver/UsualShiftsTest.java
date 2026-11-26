package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.dispofold.model.Dataset;
import app.dispofold.model.DemandRule;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.Rules;
import app.dispofold.model.ShiftCategory;
import app.dispofold.model.ShiftType;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import org.junit.jupiter.api.Test;

/** The rule usualShifts: a long shift used often in the history against two short ones used rarely. */
class UsualShiftsTest {

    private static final Set<String> SHORT = Set.of("AN-A", "AN-B");

    /**
     * The small dataset with the call takers' day 07:00-19:00 covered by two people (at most three) on every day,
     * and besides the usual 12-hour shift AN-T (90 % of the history) two short ones, 07:00-13:00 and 13:00-19:00,
     * that were hardly ever worked (1 % each); the other shifts of the area are gone, those of other areas have no
     * share and count as usual.
     */
    static PlanningInput input(Map<String, Long> weights) {
        return input(weights, true);
    }

    /** Without shares: every shift counts as usual. */
    static PlanningInput input(Map<String, Long> weights, boolean shares) {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        Dataset d = base.dataset();
        List<ShiftType> shifts = new ArrayList<>();
        for (ShiftType s : d.shifts()) {
            if (s.id().equals("AN-T")) {
                shifts.add(s.withUsage(shares ? 0.9 : null));
            } else if (!s.areaId().equals("AN")) {
                shifts.add(s);
            }
        }
        shifts.add(new ShiftType("AN-A", "AN", "A8", "Annahme kurz früh", LocalTime.of(7, 0), LocalTime.of(13, 0),
                ShiftCategory.DAY, null, false, shares ? 0.01 : null));
        shifts.add(new ShiftType("AN-B", "AN", "A9", "Annahme kurz spät", LocalTime.of(13, 0), LocalTime.of(19, 0),
                ShiftCategory.DAY, null, false, shares ? 0.01 : null));
        List<String> all = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "HOL");
        List<DemandRule> demand = new ArrayList<>(d.demand().stream().filter(r -> !r.areaId().equals("AN")).toList());
        demand.add(new DemandRule("AN", all, null, LocalTime.of(7, 0), LocalTime.of(19, 0), 2, 3));
        Set<String> ids = new java.util.HashSet<>(shifts.stream().map(ShiftType::id).toList());
        // restrictions, wishes and fixed duties on the dropped shifts go with them
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), shifts, demand, d.holidays(),
                d.absenceTypes(), d.people(), d.restrictions().stream()
                        .filter(x -> x.shiftId() == null || ids.contains(x.shiftId())).toList(),
                d.history(), d.periods());
        Rules r = base.rules().withWeights(weights);
        return new PlanningInput(changed, new app.dispofold.model.Period(base.period().month(),
                base.period().absences(), List.of(), List.of(), base.period().previousDuties().stream()
                        .filter(p -> ids.contains(p.shiftId())).toList()),
                r, List.of());
    }

    /** Duties on the short shifts in a solved month. */
    static long shortDuties(PlanningInput input) throws Exception {
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        SolveProgress result = TimefoldPlanningFacadeTest.solve(facade, input, new SolveOptions(1, 600, 0, 100_000));
        PlanAnalysis analysis = facade.analyze(TimefoldPlanningFacadeTest.withAssignments(input,
                result.assignments()));
        assertThat(analysis.hardViolations()).isZero();
        assertThat(analysis.gaps()).isEmpty();
        return result.assignments().stream().filter(a -> a.shiftId() != null && SHORT.contains(a.shiftId())).count();
    }

    @Test
    void withSharesAndMergesTheSameSeedGivesTheSamePlanWithoutScoreCorruption() throws Exception {
        PlanningInput input = input(Map.of());
        SolveOptions options = new SolveOptions(7, 600, 0, 3_000);
        SolveProgress first = TimefoldPlanningFacadeTest.solve(new TimefoldPlanningFacade(), input, options);
        SolveProgress second = TimefoldPlanningFacadeTest.solve(new TimefoldPlanningFacade(), input, options);
        assertThat(second.score()).isEqualTo(first.score());
        assertThat(second.assignments()).isEqualTo(first.assignments());
        // every move undone exactly, the merges' composite moves included
        SolveProgress asserted = TimefoldPlanningFacadeTest.solve(
                new TimefoldPlanningFacade(ai.timefold.solver.core.config.solver.EnvironmentMode.FULL_ASSERT), input,
                new SolveOptions(7, 600, 0, 1_000));
        assertThat(asserted.finished()).isTrue();
    }

    @Test
    void theLongUsualShiftIsPreferredToTwoShortRareOnes() throws Exception {
        long without = shortDuties(input(Map.of(), false));
        long with = shortDuties(input(Map.of()));
        // without shares one long or two short duties cost the same and the plan has some short ones; with shares
        // the short ones are rare and the long usual shift covers the day
        assertThat(without).isPositive();
        assertThat(with).isLessThanOrEqualTo(1).isLessThan(without);
    }
}
