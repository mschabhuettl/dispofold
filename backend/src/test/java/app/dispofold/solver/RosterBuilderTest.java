package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import app.dispofold.api.TestData;
import app.dispofold.model.Dataset;
import app.dispofold.model.HistoryEntry;
import app.dispofold.model.Period;
import app.dispofold.model.Person;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.PreviousDuty;
import app.dispofold.model.Restriction;
import app.dispofold.model.RestrictionKind;
import app.dispofold.model.Rules;
import app.dispofold.model.ShiftCategory;
import app.dispofold.planning.PlanningInput;

import org.junit.jupiter.api.Test;

/** How the planning input becomes the solver model. */
class RosterBuilderTest {

    /** August 2027 starts on a Sunday. */
    static final YearMonth MONTH = YearMonth.of(2027, 8);

    static Roster build(Dataset d, Period p, List<PlanAssignment> assignments) {
        return new RosterBuilder(new PlanningInput(d, p, Rules.defaults(), assignments), true).build();
    }

    static Dataset withPeople(Dataset d, List<Person> people) {
        return new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(), d.holidays(),
                d.absenceTypes(), people, d.restrictions(), d.history(), d.periods());
    }

    static Staff staff(Roster r, String id) {
        return r.getStaff().stream().filter(s -> s.getId().equals(id)).findFirst().orElseThrow();
    }

    static DutyCell cell(Roster r, String id, LocalDate date) {
        return r.getCells().stream().filter(c -> c.getStaff().getId().equals(id) && c.getDate().equals(date))
                .findFirst().orElseThrow();
    }

    @Test
    void targetIsProRataOverTheEmployment() {
        Dataset d = TestData.dataset(MONTH);
        List<Person> people = new ArrayList<>(d.people());
        Person p = people.get(5);
        people.set(5, new Person(p.id(), p.firstName(), p.lastName(), p.shortName(), p.weeklyHours(), p.areaIds(),
                0, 0, p.familyRules(), p.externalId(), MONTH.atDay(16), null));
        Roster r = build(withPeople(d, people), d.periods().get(0), List.of());

        // 40 h: 8 h per workday; August 2027 has 22 workdays, 12 of them from the 16th.
        assertThat(staff(r, p.id()).getTargetMinutes()).isEqualTo(12 * 480);
        assertThat(staff(r, "P0005").getTargetMinutes()).isEqualTo(22 * 480);
        DutyCell before = cell(r, p.id(), MONTH.atDay(10));
        assertThat(before.isOutsideEmployment()).isTrue();
        assertThat(before.isPinned()).isTrue();
        assertThat(before.getShift()).isNull();
        assertThat(cell(r, p.id(), MONTH.atDay(16)).isPinned()).isFalse();
        // Fairness compares per available day: fewer days, more weight per duty.
        assertThat(staff(r, p.id()).getFairness().weight()).isGreaterThan(staff(r, "P0005").getFairness().weight());
    }

    @Test
    void balancesComeFromThePreviousMonthsHistory() {
        Dataset d = TestData.dataset(MONTH);
        List<HistoryEntry> history = new ArrayList<>(d.history());
        history.add(new HistoryEntry("P0004", MONTH.minusMonths(1).toString(), HistoryEntry.PLAN, 0, 0, 0, 0, 0, 0,
                160, 20.0, 3, 0, 0));
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), d.people(), d.restrictions(), history, d.periods());
        Roster r = build(changed, d.periods().get(0), List.of());

        // 20 h on the account, a quarter of it is evened out this month.
        assertThat(staff(r, "P0004").getTargetMinutes()).isEqualTo(22 * 480 - 5 * 60);
        assertThat(staff(r, "P0004").getCompRestBalance()).isEqualTo(3);
    }

    @Test
    void weekendAcrossTheMonthStartUsesThePreviousDuties() {
        Dataset d = TestData.dataset(MONTH);
        Period p = d.periods().get(0);
        LocalDate saturday = MONTH.atDay(1).minusDays(1);
        Period withPrevious = new Period(p.month(), p.absences(), p.wishes(), p.fixed(),
                List.of(new PreviousDuty("P0004", saturday, "DR-T")));
        Roster r = build(d, withPrevious, List.of());

        DutyCell sunday = cell(r, "P0004", MONTH.atDay(1));
        DutyCell previous = cell(r, "P0004", saturday);
        assertThat(sunday.getWeekend()).isEqualTo(saturday.toEpochDay());
        assertThat(previous.getWeekend()).isEqualTo(saturday.toEpochDay());
        // A Saturday at the end of a month whose Sunday is in the next month is not known as a weekend yet.
        YearMonth july = YearMonth.of(2027, 7);
        Dataset dj = TestData.dataset(july);
        Roster rj = build(dj, dj.periods().get(0), List.of());
        assertThat(cell(rj, "P0004", july.atEndOfMonth()).getWeekend()).isEqualTo(-1);
    }

    @Test
    void restrictionsByCategoryAndValidity() {
        Dataset d = TestData.dataset(MONTH);
        List<Restriction> restrictions = List.of(
                new Restriction("P0004", null, ShiftCategory.NIGHT, 0, RestrictionKind.NEVER, null, null),
                new Restriction("P0006", "DR-T", null, 0, RestrictionKind.ON_REQUEST, MONTH.atDay(10),
                        MONTH.atDay(12)));
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), d.people(), restrictions, d.history(), d.periods());
        Roster r = build(changed, d.periods().get(0), List.of());

        assertThat(cell(r, "P0004", MONTH.atDay(5)).getOptions()).extracting(ShiftOption::getId)
                .contains("DR-T").doesNotContain("DR-N");
        assertThat(cell(r, "P0006", MONTH.atDay(11)).getOnRequest()).containsExactly("DR-T");
        assertThat(cell(r, "P0006", MONTH.atDay(13)).getOnRequest()).isEmpty();
    }

    @Test
    void aPinnedShiftThatNoLongerExistsIsReported() {
        Dataset d = TestData.dataset(MONTH);
        Roster r = build(d, d.periods().get(0),
                List.of(new PlanAssignment("P0005", MONTH.atDay(4), "GONE", false, true)));
        DutyCell c = cell(r, "P0005", MONTH.atDay(4));
        assertThat(c.getMissingShiftId()).isEqualTo("GONE");
        assertThat(c.isPinned()).isTrue();
    }

    @Test
    void previousDutiesAreKeptFromTheMondayOfTheFirstWeekOrAWeek() {
        Dataset d = TestData.dataset(MONTH);
        Period p = d.periods().get(0);
        List<PreviousDuty> month = new ArrayList<>();
        for (LocalDate day = MONTH.minusMonths(1).atDay(1); day.isBefore(MONTH.atDay(1)); day = day.plusDays(1)) {
            month.add(new PreviousDuty("P0004", day, "DR-T"));
        }
        Roster r = build(d, new Period(p.month(), p.absences(), p.wishes(), p.fixed(), month), List.of());
        List<LocalDate> kept = r.getCells().stream().filter(c -> !c.isInMonth()).map(DutyCell::getDate).toList();
        // August 1st is a Sunday; its week starts on 26 July, a week back is the 25th.
        assertThat(kept).containsExactlyInAnyOrderElementsOf(
                MONTH.atDay(1).minusDays(7).datesUntil(MONTH.atDay(1)).toList());
        // The whole previous month counts for variety.
        assertThat(staff(r, "P0004").previous("DR")).isEqualTo(31);
        assertThat(staff(r, "P0004").previous("DR/DAY")).isEqualTo(31);
    }
}
