package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.dispofold.model.Dataset;
import app.dispofold.model.DemandRule;
import app.dispofold.model.PersonStats;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import org.junit.jupiter.api.Test;

/** The soft target follows the work the demand needs, so it does not drown the block and rest rules. */
class ScaledTargetTest {

    private static final LocalDate MON = LocalDate.of(2027, 1, 4);

    private static Staff staff(String id, int target) {
        return new Staff(id, 40, Set.of("AN"), 480, target, 0,
                new Staff.Limits(660, 2160, 720, 3600, 2, 4, Integer.MAX_VALUE), Staff.History.NONE, 0,
                Staff.Fairness.of(40, 30, true, true), Staff.Average.NONE, Map.of());
    }

    private static DemandSegment segment(long minutes, int required) {
        long start = Times.minute(MON) + 7 * 60;
        return new DemandSegment("s" + minutes, "AN", MON, start, start + minutes, required, null,
                TimeLine.UTC.local(start), TimeLine.UTC.local(start + minutes));
    }

    @Test
    void targetsShrinkByTheShareOfTheDemandKeepingCreditedDays() {
        Staff full = staff("A", 9600);
        Staff part = staff("B", 4800);
        DutyCell absent = new DutyCell(full, MON, true, List.of());
        absent.setAbsence("U", true);
        absent.setWorkday(true);
        absent.setPinned(true);
        // Work parts 9600 - 480 and 4800 add up to 13 920 minutes; the demand needs 6960 (half of it).
        RosterBuilder.scaleTargets(List.of(full, part), List.of(absent), List.of(segment(6960, 1)));
        assertThat(full.getScaledTargetMinutes()).isEqualTo(480 + 4560);
        assertThat(part.getScaledTargetMinutes()).isEqualTo(2400);
        // The hard cap stays on the real target.
        assertThat(full.getTargetMinutes()).isEqualTo(9600);
    }

    @Test
    void demandAtCapacityChangesNothing() {
        Staff full = staff("A", 9600);
        Staff part = staff("B", 4800);
        RosterBuilder.scaleTargets(List.of(full, part), List.of(), List.of(segment(7200, 2)));
        assertThat(full.getScaledTargetMinutes()).isEqualTo(9600);
        assertThat(part.getScaledTargetMinutes()).isEqualTo(4800);
    }

    /** The small dataset with half the demand (at least 1): clearly more people than work. */
    static PlanningInput halfDemand() {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        Dataset d = base.dataset();
        List<DemandRule> half = d.demand().stream().map(r -> new DemandRule(r.areaId(), r.days(), r.date(), r.from(),
                r.to(), Math.max(1, r.min() / 2), null)).toList();
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), half, d.holidays(),
                d.absenceTypes(), d.people(), d.restrictions(), d.history(), d.periods());
        return new PlanningInput(changed, base.period(), base.rules(), List.of());
    }

    @Test
    void belowCapacityTheScaledTargetsAreReachedEvenly() throws Exception {
        PlanningInput input = halfDemand();
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        SolveProgress result = TimefoldPlanningFacadeTest.solve(facade, input, new SolveOptions(1, 15, 0, 0));
        PlanAnalysis analysis = facade.analyze(TimefoldPlanningFacadeTest.withAssignments(input,
                result.assignments()));
        assertThat(analysis.gaps()).isEmpty();
        assertThat(analysis.hardViolations()).isZero();
        List<PersonStats> people = analysis.people();
        assertThat(people).allSatisfy(p -> assertThat(p.scaledGoalHours()).isLessThan(p.goalHours()));
        // Most people end within one 12 h duty of their scaled goal, and nobody goes over the real goal's cap.
        long near = people.stream()
                .filter(p -> Math.abs(p.plannedHours() + p.creditedHours() - p.scaledGoalHours()) <= 12).count();
        assertThat(near).isGreaterThanOrEqualTo(people.size() * 3 / 4);
        assertThat(people).allSatisfy(p -> assertThat(p.plannedHours() + p.creditedHours())
                .isLessThanOrEqualTo(p.goalHours() * 1.1 + 0.1));
        // Part-timers proportionally less: the scaled goal follows the goal.
        PersonStats full = people.stream().filter(p -> p.personId().equals("P0001")).findFirst().orElseThrow();
        PersonStats part = people.stream().filter(p -> p.personId().equals("P0007")).findFirst().orElseThrow();
        assertThat(part.scaledGoalHours() / part.goalHours())
                .isCloseTo(full.scaledGoalHours() / full.goalHours(), org.assertj.core.data.Offset.offset(0.15));
    }
}
