package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.dispofold.api.TestData;
import app.dispofold.model.Absence;
import app.dispofold.model.AbsenceCredit;
import app.dispofold.model.AbsenceType;
import app.dispofold.model.Dataset;
import app.dispofold.model.Period;
import app.dispofold.model.PersonStats;
import app.dispofold.model.Rules;
import app.dispofold.planning.PlanningInput;

/**
 * What an absence is credited with. Type "K" credits the replaced duty, "U" weekly hours / 5 on workdays. P0004
 * works 40 h (8 h a workday). August 2027: Saturday the 7th, Wednesday the 11th, Thursday the 12th.
 */
class AbsenceCreditTest {

    static final LocalDate SATURDAY = LocalDate.of(2027, 8, 7);
    static final LocalDate WEDNESDAY = LocalDate.of(2027, 8, 11);
    static final LocalDate THURSDAY = LocalDate.of(2027, 8, 12);

    static Dataset dataset(AbsenceCredit vacation) {
        Dataset d = TestData.dataset(RosterBuilderTest.MONTH);
        List<AbsenceType> types = new ArrayList<>(d.absenceTypes());
        types.removeIf(t -> t.code().equals("U"));
        types.add(new AbsenceType("U", "Urlaub", true, false, false, "#A3C585", vacation));
        types.add(new AbsenceType("K", "Krankenstand", true, false, false, "#E39B8F", AbsenceCredit.PLANNED_DUTY));
        return new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(), d.holidays(),
                types, d.people(), d.restrictions(), d.history(), d.periods());
    }

    static Period period(Dataset d, Absence... absences) {
        Period p = d.periods().get(0);
        return new Period(p.month(), List.of(absences), p.wishes(), p.fixed(), p.previousDuties());
    }

    static DutyCell cell(Dataset d, Period p, LocalDate day) {
        return RosterBuilderTest.cell(RosterBuilderTest.build(d, p, List.of()), "P0004", day);
    }

    @Test
    void theReplacedDutyIsCreditedOnAnyDay() {
        Dataset d = dataset(null);
        // sick leave on a planned Saturday duty of 12 h: 12 h
        assertThat(cell(d, period(d, new Absence("P0004", SATURDAY, "K", 12.0)), SATURDAY).getCountedMinutes())
                .isEqualTo(720);
        // sick leave on a workday without a duty in the plan: nothing
        assertThat(cell(d, period(d, new Absence("P0004", WEDNESDAY, "K", 0.0)), WEDNESDAY).getCountedMinutes())
                .isZero();
        // vacation on a duty day, the type set to the planned duty: the duty's 12 h instead of 8 h
        Dataset planned = dataset(AbsenceCredit.PLANNED_DUTY);
        assertThat(cell(planned, period(planned, new Absence("P0004", THURSDAY, "U", 12.0)), THURSDAY)
                .getCountedMinutes()).isEqualTo(720);
        // the default type keeps weekly hours / 5 on workdays, whatever the plan had
        DutyCell vacation = cell(d, period(d, new Absence("P0004", THURSDAY, "U", 12.0)), THURSDAY);
        assertThat(vacation.getCountedMinutes()).isEqualTo(480);
        assertThat(vacation.isCreditFallback()).isFalse();
    }

    @Test
    void withoutTheReplacedDutyWeeklyHoursFifthCountAndThePersonPageSaysSo() {
        Dataset d = dataset(null);
        // a month planned ahead or an absence from an import: no duty known, weekly hours / 5 on a workday
        Period p = period(d, new Absence("P0004", WEDNESDAY, "K"), new Absence("P0004", SATURDAY, "K"));
        DutyCell wednesday = cell(d, p, WEDNESDAY);
        assertThat(wednesday.getCountedMinutes()).isEqualTo(480);
        assertThat(wednesday.isCreditFallback()).isTrue();
        assertThat(cell(d, p, SATURDAY).getCountedMinutes()).isZero();
        PersonStats stats = new TimefoldPlanningFacade().explain(new PlanningInput(d, p, Rules.defaults(), List.of()),
                "P0004").stats();
        assertThat(stats.creditFallbackDays()).isEqualTo(2);
        assertThat(stats.creditedHours()).isEqualTo(8.0);
    }
}
