package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import app.dispofold.model.Dataset;
import app.dispofold.model.DemandRule;
import app.dispofold.model.PersonExplanation;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.PlanAssignment;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import ai.timefold.solver.core.config.solver.EnvironmentMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Solves the small synthetic dataset through the facade. */
class TimefoldPlanningFacadeTest {

    static SolveProgress solve(TimefoldPlanningFacade facade, PlanningInput input, SolveOptions options)
            throws Exception {
        CompletableFuture<SolveProgress> done = new CompletableFuture<>();
        facade.solve(input, options, p -> {
            if (p.finished()) {
                done.complete(p);
            }
        }, done::completeExceptionally);
        return done.get(10, TimeUnit.MINUTES);
    }

    static PlanningInput withAssignments(PlanningInput input, List<PlanAssignment> assignments) {
        return new PlanningInput(input.dataset(), input.period(), input.rules(), assignments);
    }

    @Test
    void solvesExplainsAndAnalyzes() throws Exception {
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        PlanningInput input = Fixtures.input(Fixtures.SMALL);
        SolveProgress result = solve(facade, input, new SolveOptions(1, 15, 0, 0));

        int people = input.dataset().people().size();
        assertThat(result.assignments()).hasSize(people * input.period().month().lengthOfMonth());
        assertThat(result.hardViolations()).isZero();
        assertThat(facade.isSolving(input.period().month())).isFalse();
        // Fixed duties come back pinned and unchanged.
        input.period().fixed().forEach(f -> assertThat(result.assignments()).anySatisfy(a -> {
            assertThat(a.personId()).isEqualTo(f.personId());
            assertThat(a.date()).isEqualTo(f.date());
            assertThat(a.shiftId()).isEqualTo(f.shiftId());
            assertThat(a.pinned()).isTrue();
        }));

        PlanningInput solved = withAssignments(input, result.assignments());
        PlanAnalysis analysis = facade.analyze(solved);
        assertThat(analysis.score()).isEqualTo(result.score());
        assertThat(analysis.hardViolations()).isZero();
        assertThat(analysis.constraints()).hasSize(ConstraintCatalog.ALL.size());
        assertThat(analysis.people()).hasSize(people);
        assertThat(analysis.gaps()).isEmpty();
        assertThat(analysis.score()).startsWith("0hard/0medium/");

        String personId = input.dataset().people().get(0).id();
        PersonExplanation explanation = facade.explain(solved, personId);
        assertThat(explanation.stats().personId()).isEqualTo(personId);
        assertThat(explanation.stats().plannedHours()).isPositive();
        assertThat(explanation.hits()).isNotEmpty().allSatisfy(h -> assertThat(h.code()).startsWith("hit."));

        String area = input.dataset().areas().get(0).id();
        assertThat(facade.coverage(solved, area)).isNotEmpty()
                .allSatisfy(s -> assertThat(s.planned()).isGreaterThanOrEqualTo(s.required()));
    }

    @Test
    void analysisShowsAManualRuleBreak() {
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        PlanningInput input = Fixtures.input(Fixtures.SMALL);
        var absence = input.period().absences().get(0);
        String shiftId = input.dataset().shifts().get(0).id();
        PlanAnalysis analysis = facade.analyze(withAssignments(input,
                List.of(new PlanAssignment(absence.personId(), absence.date(), shiftId, false, true))));
        assertThat(analysis.constraints()).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo(ConstraintCatalog.ABSENCE);
            assertThat(c.matches()).isEqualTo(1);
        });
        assertThat(analysis.hardViolations()).isPositive();
        // Nothing planned: all demand is a gap, also in the score.
        assertThat(analysis.gaps()).isNotEmpty();
        assertThat(analysis.score()).doesNotContain("/0medium/");
    }

    @Test
    void rosterCellWinsOverPeriodFixedDuty() {
        PlanningInput input = Fixtures.input(Fixtures.SMALL);
        var fix = input.period().fixed().get(0);
        PlanAssignment changed = new PlanAssignment(fix.personId(), fix.date(), null, false, true);
        PlanningInput edited = withAssignments(input, List.of(changed));
        Roster roster = new RosterBuilder(edited, true).build();
        DutyCell cell = roster.getCells().stream()
                .filter(c -> c.isInMonth() && c.getStaff().getId().equals(fix.personId())
                        && c.getDate().equals(fix.date()))
                .findFirst().orElseThrow();
        assertThat(cell.getShift()).isNull();
        assertThat(cell.isPinned()).isTrue();
        assertThat(RosterAnalyzer.assignments(roster)).contains(changed);
        // Without a roster cell the fixed duty applies.
        DutyCell seeded = new RosterBuilder(input).build().getCells().stream()
                .filter(c -> c.isInMonth() && c.getStaff().getId().equals(fix.personId())
                        && c.getDate().equals(fix.date()))
                .findFirst().orElseThrow();
        assertThat(seeded.getShift().getId()).isEqualTo(fix.shiftId());
        // An unpinned cell (e.g. from an earlier solve) does not override a fixed duty added later.
        DutyCell later = new RosterBuilder(withAssignments(input,
                List.of(new PlanAssignment(fix.personId(), fix.date(), null, false, false))), true).build()
                .getCells().stream()
                .filter(c -> c.isInMonth() && c.getStaff().getId().equals(fix.personId())
                        && c.getDate().equals(fix.date()))
                .findFirst().orElseThrow();
        assertThat(later.getShift().getId()).isEqualTo(fix.shiftId());
        assertThat(later.isPinned()).isTrue();
    }

    @Test
    void stopRightAfterSolveIsNotLost() throws Exception {
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        PlanningInput input = Fixtures.input(Fixtures.SMALL);
        CompletableFuture<SolveProgress> done = new CompletableFuture<>();
        long started = System.currentTimeMillis();
        facade.solve(input, new SolveOptions(1, 600, 0, 0), p -> {
            if (p.finished()) {
                done.complete(p);
            }
        }, done::completeExceptionally);
        facade.stop(input.period().month());
        assertThat(done.get(1, TimeUnit.MINUTES).finished()).isTrue();
        assertThat(System.currentTimeMillis() - started).isLessThan(30_000);
    }

    @Test
    void sameSeedAndMoveLimitGiveTheSamePlan() throws Exception {
        PlanningInput input = Fixtures.input(Fixtures.SMALL);
        SolveOptions options = new SolveOptions(42, 600, 0, 20_000);
        SolveProgress first = solve(new TimefoldPlanningFacade(), input, options);
        SolveProgress second = solve(new TimefoldPlanningFacade(), input, options);
        assertThat(second.score()).isEqualTo(first.score());
        assertThat(second.assignments()).isEqualTo(first.assignments());
    }

    /** The months with a change of the clock: real minutes everywhere, no score corruption, no hard violation. */
    @ParameterizedTest
    @ValueSource(strings = { Fixtures.SMALL_MARCH, Fixtures.SMALL_OCTOBER })
    void clockChangeMonthsSolveWithoutScoreCorruption(String fixture) throws Exception {
        PlanningInput input = Fixtures.input(fixture);
        SolveProgress result = solve(new TimefoldPlanningFacade(EnvironmentMode.FULL_ASSERT), input,
                new SolveOptions(5, 600, 0, 2_000));
        assertThat(result.finished()).isTrue();
        assertThat(result.hardViolations()).isZero();
    }

    @Test
    void fullAssertFindsNoScoreCorruption() throws Exception {
        PlanningInput input = Fixtures.input(Fixtures.SMALL);
        SolveProgress result = solve(new TimefoldPlanningFacade(EnvironmentMode.FULL_ASSERT), input,
                new SolveOptions(3, 600, 0, 3_000));
        assertThat(result.finished()).isTrue();
    }

    @Test
    void impossibleDemandGivesGapsButNoHardViolation() throws Exception {
        PlanningInput input = Fixtures.input(Fixtures.SMALL);
        Dataset d = input.dataset();
        List<DemandRule> tripled = new ArrayList<>();
        for (DemandRule r : d.demand()) {
            tripled.add(new DemandRule(r.areaId(), r.days(), r.date(), r.from(), r.to(), r.min() * 3, null));
        }
        Dataset heavy = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), tripled,
                d.holidays(), d.absenceTypes(), d.people(), d.restrictions(), d.history(), d.periods());
        PlanningInput impossible = new PlanningInput(heavy, input.period(), input.rules(), List.of());
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        SolveProgress result = solve(facade, impossible, new SolveOptions(1, 15, 0, 0));
        assertThat(result.hardViolations()).isZero();
        PlanAnalysis analysis = facade.analyze(withAssignments(impossible, result.assignments()));
        assertThat(analysis.hardViolations()).isZero();
        assertThat(analysis.gaps()).isNotEmpty();
        assertThat(analysis.gaps()).allSatisfy(g -> assertThat(g.planned()).isLessThan(g.required()));
    }

    @Test
    void stopEndsTheSolveWithTheBestSoFar() throws Exception {
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        PlanningInput input = Fixtures.input(Fixtures.SMALL);
        CompletableFuture<SolveProgress> done = new CompletableFuture<>();
        long started = System.currentTimeMillis();
        facade.solve(input, new SolveOptions(1, 600, 0, 0), p -> {
            if (p.finished()) {
                done.complete(p);
            }
        }, done::completeExceptionally);
        assertThat(facade.isSolving(input.period().month())).isTrue();
        Thread.sleep(3000);
        facade.stop(input.period().month());
        SolveProgress result = done.get(1, TimeUnit.MINUTES);
        assertThat(System.currentTimeMillis() - started).isLessThan(60_000);
        assertThat(result.assignments()).isNotEmpty();
    }
}
