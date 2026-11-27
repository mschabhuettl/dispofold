package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.dispofold.model.Dataset;
import app.dispofold.model.DemandRule;
import app.dispofold.model.DutyHistory;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.Person;
import app.dispofold.model.ShiftCategory;
import app.dispofold.model.ShiftType;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import org.junit.jupiter.api.Test;

/** The rules personShifts and personLengths: duties of a length and on shifts the person worked in the history. */
class PersonShiftsTest {

    /** The call takers who worked only the 10-hour shift AN-Z in the history (the others only AN-T). */
    private static final Set<String> TEN = Set.of("P0001", "P0003", "P0005", "P0007", "P0010", "P0014", "P0017",
            "P0019");

    /**
     * The small dataset with the call takers' day covered by one 12-hour duty 07:00-19:00 (AN-T) and one 10-hour duty
     * 07:00-17:00 (AN-Z) on every day, exactly the minimum, and nothing else in the area; each call taker worked one of
     * the two in the history (or, without histories, nobody has one).
     */
    static PlanningInput input(boolean histories) {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        Dataset d = base.dataset();
        List<ShiftType> shifts = new ArrayList<>(d.shifts().stream()
                .filter(s -> s.id().equals("AN-T") || !s.areaId().equals("AN")).toList());
        shifts.add(new ShiftType("AN-Z", "AN", "A8", "Annahme 10 h", LocalTime.of(7, 0), LocalTime.of(17, 0),
                ShiftCategory.DAY, null, false));
        List<String> all = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "HOL");
        List<DemandRule> demand = new ArrayList<>(d.demand().stream().filter(r -> !r.areaId().equals("AN")).toList());
        demand.add(new DemandRule("AN", all, null, LocalTime.of(7, 0), LocalTime.of(17, 0), 2, 2));
        demand.add(new DemandRule("AN", all, null, LocalTime.of(17, 0), LocalTime.of(19, 0), 1, 1));
        List<Person> people = d.people().stream().map(p -> !histories || !p.areaIds().contains("AN") ? p
                : p.withDutyHistory(TEN.contains(p.id())
                        ? new DutyHistory(Map.of("AN-Z", 30), Map.of("10", 30))
                        : new DutyHistory(Map.of("AN-T", 30), Map.of("12", 30)))).toList();
        Set<String> ids = new java.util.HashSet<>(shifts.stream().map(ShiftType::id).toList());
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), shifts, demand, d.holidays(),
                d.absenceTypes(), people, d.restrictions().stream()
                        .filter(x -> x.shiftId() == null || ids.contains(x.shiftId())).toList(),
                d.history(), d.periods());
        return new PlanningInput(changed, new app.dispofold.model.Period(base.period().month(),
                base.period().absences(), List.of(), List.of(), base.period().previousDuties().stream()
                        .filter(p -> ids.contains(p.shiftId())).toList()),
                base.rules().withWeights(Map.of()), List.of());
    }

    /** Duties of the people with 10-hour histories on AN-T and on AN-Z in a solved month. */
    static Map<String, Long> duties(PlanningInput input) throws Exception {
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        SolveProgress result = TimefoldPlanningFacadeTest.solve(facade, input, new SolveOptions(1, 600, 0, 100_000));
        PlanAnalysis analysis = facade.analyze(TimefoldPlanningFacadeTest.withAssignments(input,
                result.assignments()));
        assertThat(analysis.hardViolations()).isZero();
        assertThat(analysis.gaps()).isEmpty();
        Map<String, Long> count = new HashMap<>();
        result.assignments().stream().filter(a -> TEN.contains(a.personId()) && a.shiftId() != null
                && a.shiftId().startsWith("AN-")).forEach(a -> count.merge(a.shiftId(), 1L, Long::sum));
        return count;
    }

    @Test
    void aPersonWithTenHourDutiesGetsTheTenHourShift() throws Exception {
        Map<String, Long> without = duties(input(false));
        Map<String, Long> with = duties(input(true));
        long longWithout = without.getOrDefault("AN-T", 0L);
        long longWith = with.getOrDefault("AN-T", 0L);
        // without histories the 12-hour duties go to anyone (about half); with them the people who worked 10 hours keep
        // to 10 hours, but for days when too few of the others are there (absences, rest, hours)
        assertThat(longWithout).isPositive();
        assertThat(longWith).as("12 h duties of 10 h people with histories %s, without %s", with, without)
                .isLessThanOrEqualTo(6);
        assertThat(2 * longWith).isLessThan(longWithout);
        assertThat(with.getOrDefault("AN-Z", 0L)).isPositive();
    }

    @Test
    void theUnusualShiftsComeFromATellingHistoryAndAWeight() {
        PlanningInput input = input(true);
        Roster roster = new RosterBuilder(input, true).build();
        Staff ten = roster.getStaff().stream().filter(s -> s.getId().equals("P0001")).findFirst().orElseThrow();
        assertThat(ten.getUnusualLengths()).contains("AN-T").doesNotContain("AN-Z");
        assertThat(ten.getUnusualShifts()).contains("AN-T").doesNotContain("AN-Z");
        // a person outside the call takers has no history: nothing unusual
        Staff none = roster.getStaff().stream().filter(s -> s.getId().equals("P0008")).findFirst().orElseThrow();
        assertThat(none.getUnusualShifts()).isEmpty();
        assertThat(none.getUnusualLengths()).isEmpty();
        // weight 0: the rule is off and nothing is unusual
        PlanningInput off = new PlanningInput(input.dataset(), input.period(), input.rules().withWeights(
                Map.of(ConstraintCatalog.PERSON_SHIFTS, 0L, ConstraintCatalog.PERSON_LENGTHS, 0L)), List.of());
        Staff offTen = new RosterBuilder(off, true).build().getStaff().stream()
                .filter(s -> s.getId().equals("P0001")).findFirst().orElseThrow();
        assertThat(offTen.getUnusualShifts()).isEmpty();
        assertThat(offTen.getUnusualLengths()).isEmpty();
        // fewer than the minimum of duties: no history
        Dataset d = input.dataset();
        Dataset thinData = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), d.people().stream().map(p -> p.dutyHistory() == null ? p
                        : p.withDutyHistory(new DutyHistory(Map.of("AN-Z", 3), Map.of("10", 3)))).toList(),
                d.restrictions(), d.history(), d.periods());
        PlanningInput thin = new PlanningInput(thinData, input.period(), input.rules(), List.of());
        Staff thinTen = new RosterBuilder(thin, true).build().getStaff().stream()
                .filter(s -> s.getId().equals("P0001")).findFirst().orElseThrow();
        assertThat(thinTen.getUnusualLengths()).isEmpty();
    }
}
