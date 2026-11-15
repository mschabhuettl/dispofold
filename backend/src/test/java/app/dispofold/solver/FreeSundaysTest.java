package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.dispofold.model.Candidate;
import app.dispofold.model.Dataset;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.Restriction;
import app.dispofold.model.RestrictionKind;
import app.dispofold.model.Rules;
import app.dispofold.model.ShiftCategory;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;

import org.junit.jupiter.api.Test;

/** Free Sundays per calendar month (works agreement, adjustable): January 2027 has five Sundays. */
class FreeSundaysTest {

    private static final ConstraintVerifier<RosterConstraints, Roster> VERIFIER = ConstraintVerifier.build(
            new RosterConstraints(), Roster.class, DutyCell.class);
    private static final LocalDate SUN = LocalDate.of(2027, 1, 3);
    private static final ShiftOption DAY = new ShiftOption("D", "AN", 7 * 60, 720, ShiftCategory.DAY, false, false,
            false);
    private static final ShiftOption NIGHT = new ShiftOption("N", "AN", 19 * 60, 720, ShiftCategory.NIGHT, true,
            false, false);
    private static final long MOVES = 150_000;

    private static Staff staff(int maxSundays) {
        return new Staff("A", 40, Set.of("AN"), 480, 0, 0,
                new Staff.Limits(660, 2160, 720, 3600, 2, 4, Integer.MAX_VALUE, maxSundays), Staff.History.NONE, 0,
                Staff.Fairness.of(40, 30, true, true), Staff.Average.NONE, Map.of());
    }

    private static DutyCell cell(Staff s, LocalDate date, ShiftOption shift) {
        DutyCell c = new DutyCell(s, date, true, List.of(DAY, NIGHT));
        c.setShift(shift);
        return c;
    }

    @Test
    void sundaysWorkedAboveTheAllowedNumberAreHard() {
        Staff s = staff(1);
        // Two Sundays with a duty: one too many.
        VERIFIER.verifyThat(RosterConstraints::freeSundays).given(cell(s, SUN, DAY), cell(s, SUN.plusDays(7), DAY))
                .penalizesBy(1);
        // A night from Saturday into Sunday morning takes the Sunday too; a Saturday day duty does not.
        VERIFIER.verifyThat(RosterConstraints::freeSundays)
                .given(cell(s, SUN, DAY), cell(s, SUN.plusDays(6), NIGHT)).penalizesBy(1);
        VERIFIER.verifyThat(RosterConstraints::freeSundays)
                .given(cell(s, SUN, DAY), cell(s, SUN.plusDays(6), DAY)).penalizesBy(0);
        // Two duties touching the same Sunday count once.
        VERIFIER.verifyThat(RosterConstraints::freeSundays)
                .given(cell(s, SUN.minusDays(1), NIGHT), cell(s, SUN, NIGHT)).penalizesBy(0);
        // Rule switched off.
        VERIFIER.verifyThat(RosterConstraints::freeSundays)
                .given(cell(staff(Integer.MAX_VALUE), SUN, DAY), cell(staff(Integer.MAX_VALUE), SUN.plusDays(7), DAY))
                .penalizesBy(0);
    }

    private static Rules freeSundays(int n) {
        Rules r = Rules.defaults();
        return new Rules(r.minDailyRestHours(), r.minWeeklyRestHours(), r.maxDutyHours(), r.maxWeekHours(),
                r.maxAverageWeekHours(), r.minBlock(), r.maxBlock(), r.maxWishesPerPerson(),
                r.holidayWorkEarnsCompRest(), r.nightStart(), r.nightEnd(), r.nightMinHours(), r.accountCarryShare(),
                r.historyMonths(), r.weights(), r.timeZone(), r.averagingWeeks(), r.maxOverTargetPercent(), n);
    }

    /** Sundays of the month each person works on (a night into Sunday morning included). */
    private static Map<String, Integer> sundaysWorked(PlanningInput input) {
        Map<String, Set<Long>> sundays = new HashMap<>();
        for (DutyCell c : new RosterBuilder(input, true).build().getCells()) {
            if (c.getSundayTouched() >= 0) {
                sundays.computeIfAbsent(c.getStaff().getId(), k -> new HashSet<>()).add(c.getSundayTouched());
            }
        }
        Map<String, Integer> counts = new HashMap<>();
        sundays.forEach((id, days) -> counts.put(id, days.size()));
        return counts;
    }

    @Test
    void twoFreeSundaysAreKeptWhenPossibleAndZeroSwitchesTheRuleOff() throws Exception {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        PlanningInput input = new PlanningInput(base.dataset(), base.period(), freeSundays(2), List.of());
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        SolveProgress result = TimefoldPlanningFacadeTest.solve(facade, input, new SolveOptions(1, 600, 0, MOVES));
        PlanningInput solved = TimefoldPlanningFacadeTest.withAssignments(input, result.assignments());
        PlanAnalysis analysis = facade.analyze(solved);
        assertThat(analysis.hardViolations()).isZero();
        assertThat(analysis.gaps()).isEmpty();
        // Five Sundays, two of them free: at most three worked.
        assertThat(sundaysWorked(solved).values()).allSatisfy(n -> assertThat(n).isLessThanOrEqualTo(3));

        // Switched off, the same plan with four Sundays for someone is no violation.
        PlanningInput off = new PlanningInput(base.dataset(), base.period(), freeSundays(0), fourSundays());
        assertThat(facade.analyze(off).constraints()).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo(ConstraintCatalog.FREE_SUNDAYS);
            assertThat(c.matches()).isZero();
        });
    }

    /** P0017 works the day shift on four of the five Sundays, set by hand. */
    private static List<PlanAssignment> fourSundays() {
        List<PlanAssignment> given = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            given.add(new PlanAssignment("P0017", SUN.plusWeeks(i), "AN-T", false, true));
        }
        return given;
    }

    @Test
    void handSetSundaysShowAsAViolationAndAbsencesCountAsFree() {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        PlanningInput input = new PlanningInput(base.dataset(), base.period(), freeSundays(2), fourSundays());
        PlanAnalysis analysis = facade.analyze(input);
        assertThat(analysis.constraints()).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo(ConstraintCatalog.FREE_SUNDAYS);
            assertThat(c.matches()).isEqualTo(1);
            assertThat(c.score()).isEqualTo(-1);
        });
        // P0020 is on leave on Sunday 3 January: that Sunday is free, so two more Sundays leave two free.
        RosterBuilder builder = new RosterBuilder(new PlanningInput(base.dataset(), base.period(), freeSundays(2),
                List.of(new PlanAssignment("P0020", SUN.plusWeeks(1), "AN-T", false, true),
                        new PlanAssignment("P0020", SUN.plusWeeks(2), "AN-T", false, true),
                        new PlanAssignment("P0020", SUN.plusWeeks(3), "AN-T", false, true))), true);
        Roster roster = builder.build();
        assertThat(roster.getCells().stream().filter(c -> c.getStaff().getId().equals("P0020")
                && c.getDate().equals(SUN)).findFirst().orElseThrow().getAbsenceCode()).isNotNull();
        assertThat(new ScoreExplainer().explain(roster).total(ConstraintCatalog.FREE_SUNDAYS).matches()).isEmpty();
    }

    @Test
    void candidatesLeaveOutPeopleWithoutFreeSundaysToSpare() {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        Dataset d = base.dataset();
        List<Restriction> restrictions = new ArrayList<>(d.restrictions());
        restrictions.add(new Restriction("P0017", "AN-T", null, 0, RestrictionKind.ON_REQUEST, null, null));
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), d.people(), restrictions, d.history(), d.periods());
        // Three Sundays already worked: the fourth would leave only one free.
        List<PlanAssignment> three = fourSundays().subList(0, 3);
        PlanningInput input = new PlanningInput(changed, base.period(), freeSundays(2), three);
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        LocalDate fourth = SUN.plusWeeks(3);
        assertThat(fourth.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertThat(facade.candidates(input, "AN", fourth.atTime(7, 0), fourth.atTime(19, 0)))
                .extracting(Candidate::personId).doesNotContain("P0017");
        assertThat(facade.candidates(input, "AN", fourth.plusDays(1).atTime(7, 0), fourth.plusDays(1).atTime(19, 0)))
                .extracting(Candidate::personId).contains("P0017");
    }
}
