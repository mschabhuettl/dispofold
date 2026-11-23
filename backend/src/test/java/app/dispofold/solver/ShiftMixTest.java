package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.dispofold.model.AccountBalancing;
import app.dispofold.model.Dataset;
import app.dispofold.model.Person;
import app.dispofold.model.PersonStats;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.Rules;
import app.dispofold.model.ShiftCategory;
import app.dispofold.model.ShiftMix;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import org.junit.jupiter.api.Test;

/** A person's usual mix of categories and areas shapes the plan; people without one are planned as before. */
class ShiftMixTest {

    /** A fixed number of moves: the same plan on every machine. */
    private static final long MOVES = 150_000;
    /** Area AN and DR, no restrictions in the small fixture. */
    private static final String PERSON = "P0010";

    private static PlanningInput withMix(ShiftMix mix) {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        Dataset d = base.dataset();
        List<Person> people = new ArrayList<>();
        for (Person p : d.people()) {
            people.add(!p.id().equals(PERSON) ? p : new Person(p.id(), p.firstName(), p.lastName(), p.shortName(),
                    p.weeklyHours(), p.areaIds(), p.hoursAccount(), p.compRestBalance(), p.familyRules(),
                    p.externalId(), p.entryDate(), p.exitDate(), mix));
        }
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), people, d.restrictions(), d.history(), d.periods());
        // the fixed share of the hours account, as the plans this test was tuned on (the fixture month is a January)
        Rules r = base.rules();
        Rules share = new Rules(r.minDailyRestHours(), r.minWeeklyRestHours(), r.maxDutyHours(), r.maxWeekHours(),
                r.maxAverageWeekHours(), r.minBlock(), r.maxBlock(), r.maxWishesPerPerson(),
                r.holidayWorkEarnsCompRest(), r.nightStart(), r.nightEnd(), r.nightMinHours(), r.accountCarryShare(),
                r.historyMonths(), r.weights(), r.timeZone(), r.averagingWeeks(), r.maxOverTargetPercent(),
                r.minFreeSundays(), r.nightDistribution(), r.averageTolerancePercent(), r.freeInsideAbsence(),
                r.averagingPeriod(), AccountBalancing.MONTHLY_SHARE, null);
        return new PlanningInput(changed, base.period(), share, List.of());
    }

    private static PersonStats solve(ShiftMix mix) throws Exception {
        PlanningInput input = withMix(mix);
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        SolveProgress result = TimefoldPlanningFacadeTest.solve(facade, input, new SolveOptions(1, 600, 0, MOVES));
        PlanAnalysis analysis = facade.analyze(TimefoldPlanningFacadeTest.withAssignments(input,
                result.assignments()));
        assertThat(analysis.hardViolations()).isZero();
        assertThat(analysis.gaps()).isEmpty();
        return analysis.people().stream().filter(p -> p.personId().equals(PERSON)).findFirst().orElseThrow();
    }

    private static double share(PersonStats p, ShiftCategory category) {
        return p.plannedMix() == null ? 0 : p.plannedMix().categoriesOrEmpty().getOrDefault(category, 0.0);
    }

    @Test
    void theMixShapesThePlanAndEditingItChangesIt() throws Exception {
        PersonStats days = solve(new ShiftMix(Map.of(ShiftCategory.DAY, 0.9, ShiftCategory.NIGHT, 0.1),
                Map.of("AN", 0.8, "DR", 0.2)));
        assertThat(share(days, ShiftCategory.DAY)).isGreaterThanOrEqualTo(0.7);
        assertThat(share(days, ShiftCategory.NIGHT)).isLessThanOrEqualTo(0.25);
        assertThat(days.plannedMix().areasOrEmpty().getOrDefault("AN", 0.0)).isGreaterThanOrEqualTo(0.6);

        // The planner edits the mix: mostly nights now.
        PersonStats nights = solve(new ShiftMix(Map.of(ShiftCategory.NIGHT, 0.7, ShiftCategory.DAY, 0.3),
                Map.of()));
        assertThat(share(nights, ShiftCategory.NIGHT)).isGreaterThanOrEqualTo(0.5);
        assertThat(share(nights, ShiftCategory.NIGHT)).isGreaterThan(share(days, ShiftCategory.NIGHT) + 0.3);
    }

    @Test
    void deviationIsSquaredInTenthsOfADuty() {
        Staff s = new Staff("A", 40, java.util.Set.of("AN"), 480, 0, 0,
                new Staff.Limits(660, 2160, 720, 3600, 2, 4, Integer.MAX_VALUE, Integer.MAX_VALUE), Staff.History.NONE, 0,
                Staff.Fairness.of(40, 30, true, true), Staff.Average.NONE, Map.of());
        s.setMix(new ShiftMix(Map.of(ShiftCategory.DAY, 0.9, ShiftCategory.NIGHT, 0.1), Map.of()));
        ShiftOption day = new ShiftOption("D", "AN", 7 * 60, 720, ShiftCategory.DAY, false, false, false);
        ShiftOption night = new ShiftOption("N", "AN", 19 * 60, 720, ShiftCategory.NIGHT, true, false, false);
        List<DutyCell> tenDays = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            DutyCell c = new DutyCell(s, java.time.LocalDate.of(2027, 1, 1 + i), true, List.of(day, night));
            c.setShift(i < 9 ? day : night);
            tenDays.add(c);
        }
        assertThat(RosterConstraints.mixDeviation(s, tenDays)).isZero();
        // 5 day and 5 night duties instead of 9 and 1: 4 duties off in both categories.
        for (int i = 5; i < 10; i++) {
            tenDays.get(i).setShift(night);
        }
        assertThat(RosterConstraints.mixDeviation(s, tenDays)).isEqualTo(2 * 40 * 40);
    }

    @Test
    void removingAnAreaRescalesTheRestOrClearsTheAreaMix() {
        ShiftMix mix = new ShiftMix(Map.of(ShiftCategory.DAY, 1.0), Map.of("AN", 0.6, "DR", 0.2, "TV", 0.2));
        assertThat(mix.withoutArea("AN").areasOrEmpty()).containsOnlyKeys("DR", "TV")
                .allSatisfy((k, v) -> assertThat(v).isEqualTo(0.5, org.assertj.core.data.Offset.offset(1e-9)));
        assertThat(new ShiftMix(Map.of(), Map.of("AN", 1.0, "DR", 0.0)).withoutArea("AN").areasOrEmpty()).isEmpty();
        assertThat(mix.withoutArea("XX")).isSameAs(mix);
    }
}
