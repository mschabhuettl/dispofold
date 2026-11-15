package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.dispofold.model.Candidate;
import app.dispofold.model.Dataset;
import app.dispofold.model.DemandRule;
import app.dispofold.model.FixedAssignment;
import app.dispofold.model.Period;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.Restriction;
import app.dispofold.model.RestrictionKind;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import org.junit.jupiter.api.Test;

/** Duties only on request: never chosen by the solver, kept when planners set them, offered for gaps. */
class OnRequestTest {

    private static final LocalDate MONDAY = LocalDate.of(2027, 1, 4);

    private static Restriction onRequest(String personId, String shiftId, int weekday, LocalDate from,
            LocalDate to) {
        return new Restriction(personId, shiftId, null, weekday, RestrictionKind.ON_REQUEST, from, to);
    }

    private static Dataset with(Dataset d, List<Restriction> added, List<DemandRule> demand) {
        List<Restriction> restrictions = new ArrayList<>(d.restrictions());
        restrictions.addAll(added);
        return new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), demand, d.holidays(),
                d.absenceTypes(), d.people(), restrictions, d.history(), d.periods());
    }

    private static DutyCell cell(Roster r, String personId, LocalDate date) {
        return r.getCells().stream().filter(c -> c.isInMonth() && c.getStaff().getId().equals(personId)
                && c.getDate().equals(date)).findFirst().orElseThrow();
    }

    @Test
    void neverChosenEvenWithALargeGapButKeptWhenSetByPlanners() throws Exception {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        Dataset d = base.dataset();
        List<DemandRule> tripled = new ArrayList<>();
        for (DemandRule r : d.demand()) {
            tripled.add(new DemandRule(r.areaId(), r.days(), r.date(), r.from(), r.to(), r.min() * 3, null));
        }
        // P0017 (area AN only) works every AN shift only on request; P0016 the ones it may work at all.
        List<Restriction> added = new ArrayList<>();
        for (String shift : List.of("AN-F", "AN-T", "AN-S", "AN-N")) {
            added.add(onRequest("P0017", shift, 0, null, null));
        }
        added.add(onRequest("P0016", "AN-T", 0, null, null));
        added.add(onRequest("P0016", "AN-N", 0, null, null));
        Dataset heavy = with(d, added, tripled);
        Period p = base.period();
        List<FixedAssignment> fixed = new ArrayList<>(p.fixed());
        LocalDate fixedDay = LocalDate.of(2027, 1, 13);
        fixed.add(new FixedAssignment("P0016", fixedDay, "AN-T"));
        Period period = new Period(p.month(), p.absences(), p.wishes(), fixed, p.previousDuties());
        LocalDate handDay = LocalDate.of(2027, 1, 11);
        // A hand-set duty and an unpinned one from an earlier plan.
        List<PlanAssignment> given = List.of(new PlanAssignment("P0017", handDay, "AN-T", false, true),
                new PlanAssignment("P0017", handDay.plusDays(3), "AN-T", false, false));
        PlanningInput input = new PlanningInput(heavy, period, base.rules(), given);

        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        SolveProgress result = TimefoldPlanningFacadeTest.solve(facade, input, new SolveOptions(1, 15, 0, 0));

        List<PlanAssignment> duties = result.assignments().stream()
                .filter(a -> Set.of("P0016", "P0017").contains(a.personId()) && a.shiftId() != null).toList();
        assertThat(duties).extracting(PlanAssignment::personId, PlanAssignment::date, PlanAssignment::shiftId,
                PlanAssignment::pinned).containsExactlyInAnyOrder(
                        tuple("P0017", handDay, "AN-T", true),
                        tuple("P0016", fixedDay, "AN-T", true));

        PlanAnalysis analysis = facade.analyze(TimefoldPlanningFacadeTest.withAssignments(input,
                result.assignments()));
        assertThat(analysis.gaps()).isNotEmpty();
        assertThat(analysis.hardViolations()).isZero();
        assertThat(analysis.constraints()).anySatisfy(c -> {
            assertThat(c.id()).isEqualTo(ConstraintCatalog.ON_REQUEST);
            assertThat(c.matches()).isEqualTo(2);
            assertThat(c.weight()).isEqualTo(1000);
        });
        assertThat(facade.explain(TimefoldPlanningFacadeTest.withAssignments(input, result.assignments()), "P0017")
                .hits()).anySatisfy(h -> {
                    assertThat(h.code()).isEqualTo("hit.onRequest");
                    assertThat(h.date()).isEqualTo(handDay);
                });
    }

    @Test
    void weekdayAndValidityLimitTheValueRange() {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        Dataset d = with(base.dataset(), List.of(onRequest("P0002", "DR-T", 1, LocalDate.of(2027, 1, 10),
                LocalDate.of(2027, 1, 20))), base.dataset().demand());
        LocalDate inside = LocalDate.of(2027, 1, 11);
        PlanningInput input = new PlanningInput(d, base.period(), base.rules(),
                List.of(new PlanAssignment("P0002", inside, "DR-T", false, false),
                        new PlanAssignment("P0002", inside.plusDays(7), "DR-T", false, true)));
        Roster r = new RosterBuilder(input).build();

        assertThat(cell(r, "P0002", inside).getOptions()).extracting(ShiftOption::getId).doesNotContain("DR-T")
                .contains("DR-N", "AN-T");
        // The unpinned duty from an earlier plan is dropped, the pinned one stays.
        assertThat(cell(r, "P0002", inside).getShift()).isNull();
        assertThat(cell(r, "P0002", inside.plusDays(7)).getShift().getId()).isEqualTo("DR-T");
        // Other Mondays and other weekdays inside the validity keep the shift.
        assertThat(cell(r, "P0002", MONDAY).getOptions()).extracting(ShiftOption::getId).contains("DR-T");
        assertThat(cell(r, "P0002", LocalDate.of(2027, 1, 25)).getOptions()).extracting(ShiftOption::getId)
                .contains("DR-T");
        assertThat(cell(r, "P0002", inside.plusDays(1)).getOptions()).extracting(ShiftOption::getId)
                .contains("DR-T");
        // The analysis shows the roster as it is.
        assertThat(cell(new RosterBuilder(input, true).build(), "P0002", inside).getShift().getId())
                .isEqualTo("DR-T");
    }

    @Test
    void savedWeightForOnRequestIsIgnored() {
        Map<String, Long> weights = new HashMap<>();
        weights.put(ConstraintCatalog.ON_REQUEST, 0L);
        weights.put(ConstraintCatalog.FAMILY, 7L);
        assertThat(ConstraintCatalog.weight(ConstraintCatalog.get(ConstraintCatalog.ON_REQUEST), weights))
                .isEqualTo(1000);
        assertThat(ConstraintCatalog.weight(ConstraintCatalog.get(ConstraintCatalog.FAMILY), weights)).isEqualTo(7);
        assertThat(ConstraintCatalog.get(ConstraintCatalog.ON_REQUEST).tunable()).isFalse();
    }

    @Test
    void candidatesAreFreeEmployedWithoutNewHardViolationsAndSorted() {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        // P0020 has AN-T on request on Mondays (fixture) and is on leave on 4 January.
        Dataset d = with(base.dataset(), List.of(onRequest("P0017", "AN-T", 0, null, null),
                onRequest("P0018", "AN-T", 0, null, null)), base.dataset().demand());
        // P0017 works the night before 4 January: AN-T at 07:00 would break the daily rest.
        List<PlanAssignment> given = List.of(new PlanAssignment("P0017", MONDAY.minusDays(1), "AN-N", false, true),
                new PlanAssignment("P0017", MONDAY.plusDays(15), "AN-F", false, false));
        PlanningInput input = new PlanningInput(d, base.period(), base.rules(), given);
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();

        // 4 January: P0020 absent, P0017 short of rest, P0018 not employed yet (entry on the 16th).
        assertThat(facade.candidates(input, "AN", MONDAY.atTime(7, 0), MONDAY.atTime(14, 30))).isEmpty();

        LocalDate later = MONDAY.plusDays(14);
        List<Candidate> found = facade.candidates(input, "AN", later.atTime(7, 0), later.atTime(14, 30));
        assertThat(found).extracting(Candidate::personId).containsExactlyInAnyOrder("P0017", "P0018", "P0020");
        assertThat(found).allSatisfy(c -> {
            assertThat(c.shiftId()).isEqualTo("AN-T");
            assertThat(c.date()).isEqualTo(later);
        });
        assertThat(found).isSortedAccordingTo(Comparator.comparingDouble(c -> c.plannedHours() - c.targetHours()));
        Candidate p17 = found.stream().filter(c -> c.personId().equals("P0017")).findFirst().orElseThrow();
        assertThat(p17.plannedHours()).isEqualTo(17.0);

        // A day with a duty is not free; a span the shift does not overlap has no candidates.
        LocalDate busy = MONDAY.plusDays(15);
        assertThat(facade.candidates(input, "AN", busy.atTime(7, 0), busy.atTime(14, 30)))
                .extracting(Candidate::personId).doesNotContain("P0017").contains("P0018");
        assertThat(facade.candidates(input, "AN", later.atTime(19, 30), later.atTime(21, 0))).isEmpty();
        assertThat(facade.candidates(input, "DR", later.atTime(7, 0), later.atTime(14, 30))).isEmpty();
    }
}
