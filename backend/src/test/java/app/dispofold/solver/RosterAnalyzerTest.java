package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import app.dispofold.api.TestData;
import app.dispofold.model.Absence;
import app.dispofold.model.AbsenceType;
import app.dispofold.model.ConstraintHit;
import app.dispofold.model.Dataset;
import app.dispofold.model.Period;
import app.dispofold.model.PersonStats;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.Rules;
import app.dispofold.planning.PlanningInput;

import org.junit.jupiter.api.Test;

/** Explanations and key figures. */
class RosterAnalyzerTest {

    static final YearMonth MONTH = YearMonth.of(2027, 8);
    static final TimefoldPlanningFacade FACADE = new TimefoldPlanningFacade();

    @Test
    void fairnessIsExplainedByTheDeviationFromTheMean() {
        Dataset d = TestData.dataset(MONTH);
        List<PlanAssignment> cells = List.of(new PlanAssignment("P0002", MONTH.atDay(2), "DR-N", false, true),
                new PlanAssignment("P0002", MONTH.atDay(3), "DR-N", false, true),
                new PlanAssignment("P0002", MONTH.atDay(4), "DR-N", false, true));
        PlanningInput input = new PlanningInput(d, d.periods().get(0), Rules.defaults(), cells);

        List<ConstraintHit> busy = FACADE.explain(input, "P0002").hits();
        assertThat(busy).filteredOn(h -> h.code().equals("hit.fairNights")).singleElement().satisfies(h -> {
            assertThat(h.params()).containsEntry("count", 3);
            assertThat((Long) h.params().get("load")).isGreaterThan((Long) h.params().get("mean"));
            assertThat(h.score()).isNegative();
        });
        // Below the mean: no hit, although the person can do nights.
        assertThat(FACADE.explain(input, "P0004").hits()).noneMatch(h -> h.code().equals("hit.fairNights"));
    }

    @Test
    void timeOffInLieuIsBookedAgainstTheAccount() {
        Dataset d = TestData.dataset(MONTH);
        List<AbsenceType> types = new ArrayList<>(d.absenceTypes());
        types.add(new AbsenceType("ZA", "Zeitausgleich", true, true, false, null));
        Period p = d.periods().get(0);
        List<Absence> absences = new ArrayList<>(p.absences());
        absences.add(new Absence("P0005", MONTH.atDay(4), "ZA"));
        absences.add(new Absence("P0006", MONTH.atDay(4), "U"));
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), types, d.people(), d.restrictions(), d.history(), d.periods());
        PlanningInput input = new PlanningInput(changed,
                new Period(p.month(), absences, p.wishes(), p.fixed(), p.previousDuties()), Rules.defaults(),
                List.of());

        PersonStats za = FACADE.explain(input, "P0005").stats();
        PersonStats vacation = FACADE.explain(input, "P0006").stats();
        // Both days reduce the target by 8 h; only time off in lieu also takes the 8 h from the account.
        assertThat(za.creditedHours()).isEqualTo(8);
        assertThat(za.chargedHours()).isEqualTo(8);
        assertThat(za.accountAfter()).isEqualTo(vacation.accountAfter() - 8);
        assertThat(za.availableDays()).isEqualTo(30);
    }
}
