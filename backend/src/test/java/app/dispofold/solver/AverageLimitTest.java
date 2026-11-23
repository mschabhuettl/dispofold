package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import app.dispofold.model.AveragingPeriod;
import app.dispofold.model.ConstraintHit;
import app.dispofold.model.Dataset;
import app.dispofold.model.HistoryEntry;
import app.dispofold.model.PersonExplanation;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.Person;
import app.dispofold.model.Rules;
import app.dispofold.planning.PlanningInput;

import org.junit.jupiter.api.Test;

/**
 * The average rule measures each person against their own weekly hours plus a tolerance, at most the rules'
 * maximum (46.16 h). Rolling: January 2027 has 31 days, a history month counts with 365.25 / 12 days. Calendar
 * half-year (the default): the months of the half-year with their real days.
 */
class AverageLimitTest {

    private static final double MONTH_WEEKS = 365.25 / 12 / 7;
    private static final double JANUARY_WEEKS = 31 / 7.0;

    /** P0001 with these weekly hours and this history (both replace the fixture's). */
    private static PlanningInput input(double weeklyHours, List<HistoryEntry> history, Double tolerance,
            List<PlanAssignment> assignments) {
        return input(Fixtures.SMALL, weeklyHours, history, tolerance, AveragingPeriod.ROLLING, assignments);
    }

    private static PlanningInput input(String fixture, double weeklyHours, List<HistoryEntry> history,
            Double tolerance, AveragingPeriod period, List<PlanAssignment> assignments) {
        PlanningInput base = Fixtures.input(fixture);
        Dataset d = base.dataset();
        List<Person> people = new ArrayList<>();
        for (Person p : d.people()) {
            people.add(!p.id().equals("P0001") ? p : new Person(p.id(), p.firstName(), p.lastName(), p.shortName(),
                    weeklyHours, p.areaIds(), p.hoursAccount(), p.compRestBalance(), p.familyRules(), p.externalId(),
                    p.entryDate(), p.exitDate(), p.shiftMix()));
        }
        List<HistoryEntry> all = new ArrayList<>(d.history().stream().filter(e -> !e.personId().equals("P0001"))
                .toList());
        all.addAll(history);
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), people, d.restrictions(), all, d.periods());
        return new PlanningInput(changed, base.period(), rules(tolerance, period), assignments);
    }

    private static Rules rules(Double percent, AveragingPeriod period) {
        Rules r = Rules.defaults();
        return new Rules(r.minDailyRestHours(), r.minWeeklyRestHours(), r.maxDutyHours(), r.maxWeekHours(),
                r.maxAverageWeekHours(), r.minBlock(), r.maxBlock(), r.maxWishesPerPerson(),
                r.holidayWorkEarnsCompRest(), r.nightStart(), r.nightEnd(), r.nightMinHours(), r.accountCarryShare(),
                r.historyMonths(), r.weights(), r.timeZone(), r.averagingWeeks(), r.maxOverTargetPercent(),
                r.minFreeSundays(), r.nightDistribution(), percent, null, period);
    }

    /** The months before January 2027 with these hours each and, if not null, these weekly hours. */
    private static List<HistoryEntry> history(int months, double hours, Double weeklyHours) {
        return history(YearMonth.of(2027, 1), months, hours, weeklyHours);
    }

    private static List<HistoryEntry> history(YearMonth planned, int months, double hours, Double weeklyHours) {
        List<HistoryEntry> entries = new ArrayList<>();
        for (int i = 1; i <= months; i++) {
            entries.add(new HistoryEntry("P0001", planned.minusMonths(i).toString(), HistoryEntry.IMPORT,
                    0, 0, 0, 0, 0, 0, hours, null, null, 0, 0, weeklyHours));
        }
        return entries;
    }

    @Test
    void theCalendarHalfYearCountsItsOwnMonthsWithTheirDays() {
        // January: only the planned month counts, and the average is a rule at once.
        Staff.Average january = average(input(Fixtures.SMALL, 40, history(11, 160, null), null, null, List.of()));
        assertThat(january.minutes()).isZero();
        assertThat(january.weeks()).isCloseTo(31 / 7.0, within(1e-9));
        assertThat(january.complete()).isTrue();
        assertThat(january.maxWeekMinutes() / 60).isCloseTo(44, within(1e-9));
        // March with January and February: the half-year so far (31 + 28 + 31 days), complete.
        YearMonth march = YearMonth.of(2027, 3);
        Staff.Average full = average(input(Fixtures.SMALL_MARCH, 20, history(march, 6, 100, null), null, null,
                List.of()));
        assertThat(full.minutes()).isEqualTo(2 * 100 * 60);
        assertThat(full.weeks()).isCloseTo(90 / 7.0, within(1e-9));
        assertThat(full.complete()).isTrue();
        assertThat(full.maxWeekMinutes() / 60).isCloseTo(22, within(1e-9));
        // Without February the history is incomplete: shown only.
        List<HistoryEntry> gap = history(march, 2, 100, null).subList(1, 2);
        assertThat(average(input(Fixtures.SMALL_MARCH, 20, gap, null, null, List.of())).complete()).isFalse();
        // October: July to September count, June and earlier do not; a change of weekly hours in August counts.
        YearMonth october = YearMonth.of(2027, 10);
        List<HistoryEntry> history = new ArrayList<>(history(october, 6, 100, null));
        history.set(1, new HistoryEntry("P0001", "2027-08", HistoryEntry.IMPORT, 0, 0, 0, 0, 0, 0, 100, null, null,
                0, 0, 20.0));
        Staff.Average autumn = average(input(Fixtures.SMALL_OCTOBER, 40, history, null, null, List.of()));
        assertThat(autumn.minutes()).isEqualTo(3 * 100 * 60);
        assertThat(autumn.weeks()).isCloseTo((31 + 31 + 30 + 31) / 7.0, within(1e-9));
        assertThat(autumn.complete()).isTrue();
        assertThat(autumn.maxWeekMinutes() / 60).isCloseTo((31 * 44 + 31 * 22 + 30 * 44 + 31 * 44) / 123.0,
                within(1e-9));
        // July: a new half-year, June and earlier do not count, and the history is complete.
        YearMonth july = YearMonth.of(2027, 7);
        PlanningInput october2 = input(Fixtures.SMALL_OCTOBER, 40, history(october, 6, 100, null), null, null,
                List.of());
        Staff.Average first = new RosterBuilder(new PlanningInput(october2.dataset(), new app.dispofold.model.Period(
                july, List.of(), List.of(), List.of(), List.of()), october2.rules(), List.of())).build().getStaff()
                .stream().filter(s -> s.getId().equals("P0001")).findFirst().orElseThrow().getAverage();
        assertThat(first.minutes()).isZero();
        assertThat(first.weeks()).isCloseTo(31 / 7.0, within(1e-9));
        assertThat(first.complete()).isTrue();
        // Rolling over 52 weeks, the same person counts all six months, with incomplete history.
        Staff.Average rolling = average(input(Fixtures.SMALL_OCTOBER, 40, history, null, AveragingPeriod.ROLLING,
                List.of()));
        assertThat(rolling.minutes()).isEqualTo(6 * 100 * 60);
        assertThat(rolling.complete()).isFalse();
    }

    private static Staff.Average average(PlanningInput input) {
        return new RosterBuilder(input).build().getStaff().stream().filter(s -> s.getId().equals("P0001"))
                .findFirst().orElseThrow().getAverage();
    }

    private static double limitHours(PlanningInput input) {
        return average(input).maxWeekMinutes() / 60;
    }

    @Test
    void theLimitIsTheOwnWeeklyHoursPlusTheToleranceAtMostTheMaximum() {
        List<HistoryEntry> full = history(11, 160, null);
        // Default tolerance 10 %.
        assertThat(limitHours(input(40, full, null, List.of()))).isCloseTo(44, within(1e-9));
        assertThat(limitHours(input(20, full, null, List.of()))).isCloseTo(22, within(1e-9));
        // 42 h plus 10 % would be 46.2 h: the maximum 46.16 h holds (in whole minutes, 2,770, as before).
        assertThat(limitHours(input(42, full, null, List.of()))).isCloseTo(Math.round(46.16 * 60) / 60.0, within(1e-9));
        // Without tolerance the own weekly hours are the limit.
        assertThat(limitHours(input(40, full, 0.0, List.of()))).isCloseTo(40, within(1e-9));
        assertThat(limitHours(input(20, full, 0.0, List.of()))).isCloseTo(20, within(1e-9));
        // A person without weekly hours keeps the maximum.
        assertThat(limitHours(input(0, full, null, List.of()))).isCloseTo(Math.round(46.16 * 60) / 60.0, within(1e-9));
    }

    @Test
    void aChangeOfWeeklyHoursCountsEachMonthWithItsWeeks() {
        // Six months at 20 h, five at 40 h (no weekly hours in the entry: the current 40 h), January at 40 h.
        List<HistoryEntry> history = new ArrayList<>();
        history.addAll(history(11, 100, null).subList(5, 11).stream().map(e -> new HistoryEntry(e.personId(),
                e.month(), e.source(), 0, 0, 0, 0, 0, 0, e.hours(), null, null, 0, 0, 20.0)).toList());
        history.addAll(history(5, 170, null));
        double expected = (6 * MONTH_WEEKS * 22 + 5 * MONTH_WEEKS * 44 + JANUARY_WEEKS * 44)
                / (11 * MONTH_WEEKS + JANUARY_WEEKS);
        assertThat(limitHours(input(40, history, null, List.of()))).isCloseTo(expected, within(1e-9));
        assertThat(average(input(40, history, null, List.of())).complete()).isTrue();
    }

    @Test
    void aViolationNamesTheOwnLimitAndAnIncompleteHistoryIsOnlyShown() {
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        // 20 h person with eleven months of 100 h: allowed 22 h per week over 51.7 weeks, about 1,137 h; the history
        // has 1,100 h, so January has room for about 37 h. Five day duties of 12 h (60 h) break it.
        List<PlanAssignment> duties = new ArrayList<>();
        for (int day : new int[] {11, 13, 15, 18, 20}) {
            duties.add(new PlanAssignment("P0001", YearMonth.of(2027, 1).atDay(day), "AN-T", false, true));
        }
        PlanningInput full = input(20, history(11, 100, null), null, duties);
        PersonExplanation explanation = facade.explain(full, "P0001");
        assertThat(explanation.stats().averageLimitHours()).isEqualTo(22.0);
        assertThat(explanation.stats().averageComplete()).isTrue();
        ConstraintHit hit = explanation.hits().stream()
                .filter(h -> h.constraintId().equals(ConstraintCatalog.AVERAGE_WEEK)).findFirst().orElseThrow();
        assertThat(((Number) hit.params().get("maxWeekMinutes")).doubleValue()).isCloseTo(22 * 60, within(1e-6));
        assertThat(hit.level()).isEqualTo(ConstraintCatalog.HARD);

        // With six history months the average is only shown, with the own limit.
        PlanningInput partial = input(20, history(6, 100, null), null, duties);
        PersonExplanation shown = facade.explain(partial, "P0001");
        assertThat(shown.stats().averageComplete()).isFalse();
        assertThat(shown.stats().averageLimitHours()).isEqualTo(22.0);
        assertThat(shown.hits()).noneMatch(h -> h.constraintId().equals(ConstraintCatalog.AVERAGE_WEEK));
    }
}
