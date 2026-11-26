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

    /** Duties for the fairness comparisons: nights (unpopular), a weekend with the holiday on its Sunday. */
    static PlanningInput fairnessInput() {
        Dataset d = TestData.dataset(MONTH);
        List<PlanAssignment> cells = List.of(new PlanAssignment("P0002", MONTH.atDay(2), "DR-N", false, true),
                new PlanAssignment("P0002", MONTH.atDay(3), "DR-N", false, true),
                new PlanAssignment("P0002", MONTH.atDay(4), "DR-N", false, true),
                new PlanAssignment("P0004", MONTH.atDay(7), "AN-T", false, true),
                new PlanAssignment("P0004", MONTH.atDay(8), "AN-T", false, true),
                new PlanAssignment("P0003", MONTH.atDay(8), "DR-T", false, true),
                new PlanAssignment("P0001", MONTH.atDay(14), "AN-T", false, true));
        return new PlanningInput(d, d.periods().get(0), Rules.defaults(), cells);
    }

    /**
     * Recorded before the fairness loads were added to the figures: the analysis does not change the scoring. Recorded
     * again with the rules of version 0.28: the difference of 36000 is the block length alone (three blocks of one
     * duty, 6000 to 18000 each); the new rules have no hit here. Since 0.30 the tolerance of 8 hours around the target
     * leaves part of the target hours out (no person here has a usual mix, and no shift a share): with the defaults of
     * 0.29 (tolerance 0, usual mix 100) the score is the one recorded before.
     */
    @Test
    void theScoreOfAFixedPlanIsUnchanged() {
        assertThat(FACADE.analyze(fairnessInput()).score()).isEqualTo("-1hard/-61200000000000medium/-44023876soft");
        PlanningInput before = fairnessInput();
        Rules old = before.rules().withWeights(java.util.Map.of(ConstraintCatalog.SHIFT_MIX, 100L))
                .withTargetToleranceHours(0);
        assertThat(FACADE.analyze(new PlanningInput(before.dataset(), before.period(), old, before.assignments()))
                .score()).isEqualTo("-1hard/-61200000000000medium/-48775236soft");
    }

    /** The loads in the figures are those the rules compare: the hits name the same loads, in thousandths. */
    @Test
    void theFiguresShowTheLoadsOfTheFairnessRules() {
        PlanningInput input = fairnessInput();
        var analysis = FACADE.analyze(input);
        for (PersonStats s : analysis.people()) {
            for (ConstraintHit h : FACADE.explain(input, s.personId()).hits()) {
                Double load = switch (h.code()) {
                    case "hit.fairWeekends" -> s.weekendLoad();
                    case "hit.fairHolidays" -> s.holidayLoad();
                    case "hit.fairUnpopular" -> s.unpopularLoad();
                    case "hit.fairNights" -> s.nightLoad();
                    default -> Double.NaN;
                };
                if (!load.isNaN()) {
                    assertThat(((Number) h.params().get("load")).doubleValue() / 1000).as(h.code())
                            .isEqualTo(load, org.assertj.core.api.Assertions.offset(0.01));
                }
            }
        }
        PersonStats p4 = analysis.people().stream().filter(s -> s.personId().equals("P0004")).findFirst()
                .orElseThrow();
        // one weekend and one holiday on 31 available days: 30 / 31 at 40 h
        assertThat(p4.weekendLoad()).isEqualTo(0.97);
        assertThat(p4.holidayLoad()).isEqualTo(0.97);
        assertThat(FACADE.explain(input, "P0004").hits()).anyMatch(h -> h.code().equals("hit.fairWeekends"));
        // P0001 has no unpopular shift in its area: not in that comparison
        PersonStats p1 = FACADE.explain(input, "P0001").stats();
        assertThat(p1.unpopularLoad()).isNull();
        assertThat(p1.weekendLoad()).isNotNull();
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
