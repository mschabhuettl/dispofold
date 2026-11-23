package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import app.dispofold.model.Absence;
import app.dispofold.model.AbsenceType;
import app.dispofold.model.Dataset;
import app.dispofold.model.FixedAssignment;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.Period;
import app.dispofold.model.Rules;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import org.junit.jupiter.api.Test;

/**
 * A holiday, Saturday or Sunday between two workdays with an absence that lowers the target is free. January 2027:
 * Friday 1 and Wednesday 6 are holidays.
 */
class FreeInsideAbsenceTest {

    private static final YearMonth JANUARY = YearMonth.of(2027, 1);
    private static final String PERSON = "P0001";

    private static LocalDate jan(int day) {
        return JANUARY.atDay(day);
    }

    private static List<Absence> absences(String type, LocalDate... days) {
        List<Absence> list = new ArrayList<>();
        for (LocalDate d : days) {
            list.add(new Absence(PERSON, d, type));
        }
        return list;
    }

    /** Every day from and to, weekends included. */
    private static List<Absence> range(String type, LocalDate from, LocalDate to) {
        List<Absence> list = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            list.add(new Absence(PERSON, d, type));
        }
        return list;
    }

    /** Monday to Friday from and to, as vacation is usually entered. */
    private static List<Absence> weekdays(String type, LocalDate from, LocalDate to) {
        return range(type, from, to).stream().filter(a -> a.date().getDayOfWeek().getValue() <= 5).toList();
    }

    /**
     * The small fixture with these absences of P0001 in January, these in the neighbouring months (null: the data
     * has no neighbouring months), an absence type "X" that does not lower the target, and these fixed duties.
     */
    private static PlanningInput input(List<Absence> own, List<Absence> neighbours, Boolean rule,
            List<FixedAssignment> fixed, List<PlanAssignment> assignments) {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        Dataset d = base.dataset();
        List<AbsenceType> types = new ArrayList<>(d.absenceTypes());
        types.add(new AbsenceType("X", "Ohne Wirkung", false, false, false, "#999999"));
        List<Absence> all = new ArrayList<>(base.period().absences().stream()
                .filter(a -> !a.personId().equals(PERSON)).toList());
        all.addAll(own);
        Period p = base.period();
        Period period = new Period(p.month(), all, p.wishes(), fixed, p.previousDuties());
        List<Period> periods = neighbours == null ? List.of()
                : List.of(new Period(JANUARY.minusMonths(1), neighbours.stream()
                        .filter(a -> a.date().isBefore(jan(1))).toList(), List.of(), List.of(), List.of()),
                        new Period(JANUARY.plusMonths(1), neighbours.stream()
                                .filter(a -> a.date().isAfter(JANUARY.atEndOfMonth())).toList(), List.of(),
                                List.of(), List.of()));
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), types, d.people(), d.restrictions(), d.history(), periods);
        return new PlanningInput(changed, period, rule(rule), assignments);
    }

    private static PlanningInput input(List<Absence> own) {
        return input(own, null, null, List.of(), List.of());
    }

    private static Rules rule(Boolean on) {
        Rules r = Rules.defaults();
        return new Rules(r.minDailyRestHours(), r.minWeeklyRestHours(), r.maxDutyHours(), r.maxWeekHours(),
                r.maxAverageWeekHours(), r.minBlock(), r.maxBlock(), r.maxWishesPerPerson(),
                r.holidayWorkEarnsCompRest(), r.nightStart(), r.nightEnd(), r.nightMinHours(), r.accountCarryShare(),
                r.historyMonths(), r.weights(), r.timeZone(), r.averagingWeeks(), r.maxOverTargetPercent(),
                r.minFreeSundays(), r.nightDistribution(), null, on, null);
    }

    private static List<DutyCell> cells(PlanningInput input) {
        return new RosterBuilder(input).build().getCells().stream()
                .filter(c -> c.isInMonth() && c.getStaff().getId().equals(PERSON)).toList();
    }

    private static Set<LocalDate> free(PlanningInput input) {
        Set<LocalDate> days = new TreeSet<>();
        for (DutyCell c : cells(input)) {
            if (c.isInsideAbsence()) {
                days.add(c.getDate());
            }
        }
        return days;
    }

    @Test
    void aHolidayBetweenVacationDaysIsFree() {
        // Vacation Monday, Tuesday, Thursday, Friday; Wednesday 6 is a holiday.
        PlanningInput input = input(absences("U", jan(4), jan(5), jan(7), jan(8)));
        assertThat(free(input)).containsExactly(jan(6));
        DutyCell wednesday = cells(input).stream().filter(c -> c.getDate().equals(jan(6))).findFirst().orElseThrow();
        // The solver never plans it, and the day is not an absence: no code, nothing credited.
        assertThat(wednesday.isPinned()).isTrue();
        assertThat(wednesday.getShift()).isNull();
        assertThat(wednesday.getAbsenceCode()).isNull();
        assertThat(wednesday.getCountedMinutes()).isZero();
    }

    @Test
    void theWeekendBetweenTwoVacationWeeksIsFreeAndAfterASingleWeekItIsNot() {
        assertThat(free(input(weekdays("U", jan(11), jan(22))))).containsExactly(jan(16), jan(17));
        // A single week Monday to Friday: the weekends before and after stay plannable.
        assertThat(free(input(weekdays("U", jan(11), jan(15))))).isEmpty();
    }

    @Test
    void anyTypesThatLowerTheTargetCountButOthersDoNot() {
        List<Absence> mixed = new ArrayList<>(absences("U", jan(15)));
        mixed.addAll(absences("K", jan(18)));
        assertThat(free(input(mixed))).containsExactly(jan(16), jan(17));
        List<Absence> zeitausgleich = new ArrayList<>(absences("ZA", jan(15)));
        zeitausgleich.addAll(absences("ER", jan(18)));
        assertThat(free(input(zeitausgleich))).containsExactly(jan(16), jan(17));
        List<Absence> other = new ArrayList<>(absences("U", jan(15)));
        other.addAll(absences("X", jan(18)));
        assertThat(free(input(other))).isEmpty();
    }

    /**
     * March 2027 with these absences of P0001 (dates in March and April), April in the data or not, and these
     * extra holidays. Easter Monday 29 March is a holiday; Friday 26 has no absence.
     */
    private static Set<LocalDate> march(List<LocalDate> vacation, boolean april, LocalDate... extraHolidays) {
        PlanningInput base = Fixtures.input(Fixtures.SMALL_MARCH);
        Dataset d = base.dataset();
        List<app.dispofold.model.Holiday> holidays = new ArrayList<>(d.holidays());
        for (LocalDate h : extraHolidays) {
            holidays.add(new app.dispofold.model.Holiday(h, "Extra"));
        }
        List<Absence> own = new ArrayList<>(base.period().absences().stream()
                .filter(a -> !a.personId().equals(PERSON)).toList());
        List<Absence> next = new ArrayList<>();
        for (LocalDate v : vacation) {
            (v.getMonthValue() == 3 ? own : next).add(new Absence(PERSON, v, "U"));
        }
        Period p = base.period();
        PlanningInput in = new PlanningInput(new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(),
                d.demand(), holidays, d.absenceTypes(), d.people(), d.restrictions(), d.history(),
                april ? List.of(new Period(YearMonth.of(2027, 4), next, List.of(), List.of(), List.of())) : List.of()),
                new Period(p.month(), own, p.wishes(), p.fixed(), p.previousDuties()), Rules.defaults(), List.of());
        Set<LocalDate> days = new TreeSet<>();
        for (DutyCell c : new RosterBuilder(in).build().getCells()) {
            if (c.isInMonth() && c.getStaff().getId().equals(PERSON) && c.isInsideAbsence()) {
                days.add(c.getDate());
            }
        }
        return days;
    }

    private static LocalDate mar(int day) {
        return LocalDate.of(2027, 3, day);
    }

    private static LocalDate apr(int day) {
        return LocalDate.of(2027, 4, day);
    }

    @Test
    void aHolidayInAWeekWhoseOtherWorkdaysAreAllAbsentIsFree() {
        // Easter Monday, vacation Tuesday to Friday: free, although no absence lies before it.
        assertThat(march(List.of(mar(30), mar(31), apr(1), apr(2)), true)).containsExactly(mar(29));
        // Without April in the data the week is not known to be covered.
        assertThat(march(List.of(mar(30), mar(31), apr(1), apr(2)), false)).isEmpty();
        // One workday of the week without an absence: the holiday stays plannable.
        assertThat(march(List.of(mar(30), mar(31), apr(1)), true)).isEmpty();
        // The weekend before is not made free by this condition.
        assertThat(march(List.of(mar(30), mar(31), apr(1), apr(2)), true)).doesNotContain(mar(27), mar(28));
    }

    @Test
    void aWeekWithTwoHolidaysAndTheRestCovered() {
        // Monday and Tuesday holidays, vacation Wednesday to Friday: both free.
        assertThat(march(List.of(mar(31), apr(1), apr(2)), true, mar(30))).containsExactly(mar(29), mar(30));
    }

    @Test
    void aPlannerPinnedDutyIsKeptAndReportedAndAnUnpinnedOneIsDroppedWhenSolving() {
        List<Absence> own = absences("U", jan(4), jan(5), jan(7), jan(8));
        PlanAssignment pinned = new PlanAssignment(PERSON, jan(6), "AN-T", false, true);
        PlanningInput in = input(own, null, null, List.of(), List.of(pinned));
        assertThat(cells(in).stream().filter(c -> c.getDate().equals(jan(6))).findFirst().orElseThrow().getShift()
                .getId()).isEqualTo("AN-T");
        assertThat(new TimefoldPlanningFacade().analyze(in).constraints()).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo(ConstraintCatalog.FREE_INSIDE_ABSENCE);
            assertThat(c.matches()).isEqualTo(1);
        });
        PlanAssignment loose = new PlanAssignment(PERSON, jan(6), "AN-T", false, false);
        PlanningInput solve = input(own, null, null, List.of(), List.of(loose));
        assertThat(cells(solve).stream().filter(c -> c.getDate().equals(jan(6))).findFirst().orElseThrow()
                .getShift()).isNull();
        // The analysis shows the roster as it is: the duty stays and is a violation.
        assertThat(new TimefoldPlanningFacade().analyze(solve).constraints()).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo(ConstraintCatalog.FREE_INSIDE_ABSENCE);
            assertThat(c.matches()).isEqualTo(1);
        });
    }

    @Test
    void aDayWithItsOwnAbsenceIsAnAbsenceNotAFreeDay() {
        // Vacation through the weekend: Saturday and Sunday are absences themselves.
        assertThat(free(input(range("U", jan(11), jan(18))))).isEmpty();
    }

    @Test
    void theMonthBoundaryUsesTheNeighbouringMonthsWhenTheDataHasThem() {
        // Thursday 31 December and Monday 4 January: Friday 1 (holiday), Saturday 2 and Sunday 3 lie between.
        List<Absence> own = absences("U", jan(4));
        List<Absence> december = absences("U", jan(1).minusDays(1));
        assertThat(free(input(own, december, null, List.of(), List.of()))).containsExactly(jan(1), jan(2), jan(3));
        // Without the previous month in the data the days are not free.
        assertThat(free(input(own, null, null, List.of(), List.of()))).isEmpty();
        assertThat(free(input(own, List.of(), null, List.of(), List.of()))).isEmpty();
        // Friday 29 January and Monday 1 February: the last weekend of January.
        List<Absence> end = absences("U", jan(29));
        assertThat(free(input(end, absences("U", jan(31).plusDays(1)), null, List.of(), List.of())))
                .containsExactly(jan(30), jan(31));
        assertThat(free(input(end, null, null, List.of(), List.of()))).isEmpty();
    }

    @Test
    void aFixedDutyOnAFreeDayIsKeptAndReportedAndTheSwitchTurnsTheRuleOff() {
        List<Absence> own = absences("U", jan(4), jan(5), jan(7), jan(8));
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        PlanningInput fixed = input(own, null, null, List.of(new FixedAssignment(PERSON, jan(6), "AN-T")),
                List.of());
        DutyCell wednesday = cells(fixed).stream().filter(c -> c.getDate().equals(jan(6))).findFirst().orElseThrow();
        assertThat(wednesday.getShift().getId()).isEqualTo("AN-T");
        assertThat(facade.analyze(fixed).constraints()).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo(ConstraintCatalog.FREE_INSIDE_ABSENCE);
            assertThat(c.level()).isEqualTo(ConstraintCatalog.HARD);
            assertThat(c.matches()).isEqualTo(1);
        });
        // Switched off: the day is plannable and the duty no violation.
        PlanningInput off = input(own, null, false, List.of(new FixedAssignment(PERSON, jan(6), "AN-T")),
                List.of());
        assertThat(free(off)).isEmpty();
        assertThat(facade.analyze(off).constraints()).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo(ConstraintCatalog.FREE_INSIDE_ABSENCE);
            assertThat(c.matches()).isZero();
        });
    }

    @Test
    void theTargetDoesNotChange() {
        List<Absence> own = absences("U", jan(4), jan(5), jan(7), jan(8));
        Staff on = new RosterBuilder(input(own)).build().getStaff().stream()
                .filter(s -> s.getId().equals(PERSON)).findFirst().orElseThrow();
        Staff off = new RosterBuilder(input(own, null, false, List.of(), List.of())).build().getStaff().stream()
                .filter(s -> s.getId().equals(PERSON)).findFirst().orElseThrow();
        assertThat(on.getTargetMinutes()).isEqualTo(off.getTargetMinutes());
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        assertThat(facade.analyze(input(own)).people().stream().filter(s -> s.personId().equals(PERSON))
                .findFirst().orElseThrow().creditedHours())
                .isEqualTo(facade.analyze(input(own, null, false, List.of(), List.of())).people().stream()
                        .filter(s -> s.personId().equals(PERSON)).findFirst().orElseThrow().creditedHours());
    }

    @Test
    void theSolverNeverPlansAFreeDay() throws Exception {
        // The holiday case and two vacation weeks for P0001; the fixture's own absences give more free days.
        List<Absence> own = new ArrayList<>(absences("U", jan(4), jan(5), jan(7), jan(8)));
        own.addAll(weekdays("U", jan(18), jan(29)));
        PlanningInput input = input(own);
        assertThat(free(input)).containsExactly(jan(6), jan(23), jan(24));
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        SolveProgress result = TimefoldPlanningFacadeTest.solve(facade, input, new SolveOptions(1, 600, 0, 150_000));
        PlanningInput solved = TimefoldPlanningFacadeTest.withAssignments(input, result.assignments());
        PlanAnalysis analysis = facade.analyze(solved);
        assertThat(analysis.hardViolations()).isZero();
        Set<LocalDate> worked = new TreeSet<>();
        for (DutyCell c : new RosterBuilder(solved, true).build().getCells()) {
            if (c.isInMonth() && c.isWork() && c.isInsideAbsence()) {
                worked.add(c.getDate());
            }
        }
        assertThat(worked).isEmpty();
        assertThat(result.assignments()).noneMatch(a -> a.personId().equals(PERSON) && a.shiftId() != null
                && Set.of(jan(6), jan(23), jan(24)).contains(a.date()));
    }
}
