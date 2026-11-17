package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import app.dispofold.model.Candidate;
import app.dispofold.model.Dataset;
import app.dispofold.model.DemandRule;
import app.dispofold.model.PersonStats;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.Restriction;
import app.dispofold.model.RestrictionKind;
import app.dispofold.model.Rules;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import org.junit.jupiter.api.Test;

/** The hard cap on a person's counted hours of the month: target plus a tolerance. */
class MonthHoursTest {

    private static Rules tolerance(double percent) {
        Rules r = Rules.defaults();
        return new Rules(r.minDailyRestHours(), r.minWeeklyRestHours(), r.maxDutyHours(), r.maxWeekHours(),
                r.maxAverageWeekHours(), r.minBlock(), r.maxBlock(), r.maxWishesPerPerson(),
                r.holidayWorkEarnsCompRest(), r.nightStart(), r.nightEnd(), r.nightMinHours(), r.accountCarryShare(),
                r.historyMonths(), r.weights(), r.timeZone(), r.averagingWeeks(), percent, r.minFreeSundays(),
                r.nightDistribution());
    }

    /** The small dataset with three times the demand: far more demand than people can cover. */
    private static PlanningInput heavy(double percent) {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        Dataset d = base.dataset();
        List<DemandRule> tripled = new ArrayList<>();
        for (DemandRule r : d.demand()) {
            tripled.add(new DemandRule(r.areaId(), r.days(), r.date(), r.from(), r.to(), r.min() * 3, null));
        }
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), tripled,
                d.holidays(), d.absenceTypes(), d.people(), d.restrictions(), d.history(), d.periods());
        return new PlanningInput(changed, base.period(), tolerance(percent), List.of());
    }

    private static double counted(PersonStats p) {
        return p.plannedHours() + p.creditedHours();
    }

    @Test
    void aLargeGapPushesNobodyOverTheCap() throws Exception {
        for (double percent : new double[] { 10, 0 }) {
            PlanningInput input = heavy(percent);
            TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
            SolveProgress result = TimefoldPlanningFacadeTest.solve(facade, input, new SolveOptions(1, 15, 0, 0));
            PlanAnalysis analysis = facade.analyze(TimefoldPlanningFacadeTest.withAssignments(input,
                    result.assignments()));
            assertThat(analysis.gaps()).as("demand is far too high").isNotEmpty();
            assertThat(analysis.hardViolations()).isZero();
            // Absences are credited, so they lower what is left for duties. The cap is on the goal of the month:
            // the target corrected by the share of the hours account, as for the soft target rule.
            double share = input.rules().accountCarryShare();
            assertThat(analysis.people()).as("tolerance %s %%", percent).allSatisfy(p -> assertThat(counted(p))
                    .isLessThanOrEqualTo((p.targetHours() - share * p.accountBefore()) * (1 + percent / 100) + 0.1));
            assertThat(analysis.people()).anySatisfy(p -> assertThat(p.creditedHours()).isPositive());
        }
    }

    @Test
    void handSetDutiesBeyondTheCapAreAHardViolationAndNoCandidate() {
        PlanningInput base = heavy(10);
        // P0017 (30 h a week) works 12 h on every second day from the 4th: 14 duties, 168 h.
        List<PlanAssignment> given = new ArrayList<>();
        for (int day = 4; day <= 30; day += 2) {
            given.add(new PlanAssignment("P0017", LocalDate.of(2027, 1, day), "AN-T", false, true));
        }
        Dataset d = base.dataset();
        List<Restriction> restrictions = new ArrayList<>(d.restrictions());
        restrictions.add(new Restriction("P0017", "AN-T", null, 0, RestrictionKind.ON_REQUEST, null, null));
        Dataset withRequest = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), d.people(), restrictions, d.history(), d.periods());
        PlanningInput input = new PlanningInput(withRequest, base.period(), base.rules(), given);
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();

        PlanAnalysis analysis = facade.analyze(input);
        assertThat(analysis.constraints()).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo(ConstraintCatalog.MAX_MONTH);
            assertThat(c.matches()).isEqualTo(1);
        });
        assertThat(facade.explain(input, "P0017").hits()).anySatisfy(h -> assertThat(h.code())
                .isEqualTo("hit.maxMonthHours"));

        // The 5th would be allowed by rest and weekly hours, but goes further over the cap.
        LocalDate fifth = LocalDate.of(2027, 1, 5);
        List<Candidate> found = facade.candidates(input, "AN", fifth.atTime(7, 0), fifth.atTime(19, 0));
        assertThat(found).extracting(Candidate::personId).doesNotContain("P0017");
        PlanningInput generous = new PlanningInput(withRequest, base.period(), tolerance(1000), given);
        assertThat(facade.candidates(generous, "AN", fifth.atTime(7, 0), fifth.atTime(19, 0)))
                .extracting(Candidate::personId).contains("P0017");
    }
}
