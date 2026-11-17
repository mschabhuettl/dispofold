package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

import app.dispofold.model.ConstraintHit;
import app.dispofold.model.Dataset;
import app.dispofold.model.FamilyRule;
import app.dispofold.model.NightDistribution;
import app.dispofold.model.Person;
import app.dispofold.model.PersonExplanation;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.Restriction;
import app.dispofold.model.RestrictionKind;
import app.dispofold.model.Rules;
import app.dispofold.model.ShiftCategory;
import app.dispofold.model.ShiftMix;
import app.dispofold.planning.PlanningInput;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.Test;

/**
 * The night setting: HABIT keeps a usual night share (the person is not in the night comparison), EVEN ignores only
 * the night share. In both modes a person who never does nights is not in the night comparison.
 */
class NightDistributionTest {

    private static final ConstraintVerifier<RosterConstraints, Roster> VERIFIER = ConstraintVerifier.build(
            new RosterConstraints(), Roster.class, DutyCell.class);
    /** Areas AN and DR, no restrictions in the small fixture. */
    private static final String PERSON = "P0010";
    private static final ShiftMix MIX = new ShiftMix(
            Map.of(ShiftCategory.EARLY, 0.45, ShiftCategory.DAY, 0.45, ShiftCategory.NIGHT, 0.1),
            Map.of("AN", 0.8, "DR", 0.2));

    static Rules rules(NightDistribution mode) {
        return Fixtures.rules(mode);
    }

    /** The small fixture with changed people, more restrictions and the given rules and assignments. */
    static PlanningInput input(Rules rules, UnaryOperator<Person> change, List<Restriction> more,
            List<PlanAssignment> assignments) {
        PlanningInput base = Fixtures.input(Fixtures.SMALL);
        Dataset d = base.dataset();
        List<Restriction> restrictions = new ArrayList<>(d.restrictions());
        restrictions.addAll(more);
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), d.people().stream().map(change).toList(), restrictions, d.history(),
                d.periods());
        return new PlanningInput(changed, base.period(), rules, assignments);
    }

    static PlanningInput input(NightDistribution mode, UnaryOperator<Person> change, List<Restriction> more) {
        return input(rules(mode), change, more, List.of());
    }

    static UnaryOperator<Person> mix(String id, ShiftMix mix) {
        return p -> !p.id().equals(id) ? p : new Person(p.id(), p.firstName(), p.lastName(), p.shortName(),
                p.weeklyHours(), p.areaIds(), p.hoursAccount(), p.compRestBalance(), p.familyRules(), p.externalId(),
                p.entryDate(), p.exitDate(), mix);
    }

    static UnaryOperator<Person> family(String id, FamilyRule rule) {
        return p -> !p.id().equals(id) ? p : new Person(p.id(), p.firstName(), p.lastName(), p.shortName(),
                p.weeklyHours(), p.areaIds(), p.hoursAccount(), p.compRestBalance(), List.of(rule), p.externalId(),
                p.entryDate(), p.exitDate(), p.shiftMix());
    }

    static Staff staff(PlanningInput input, String id) {
        return new RosterBuilder(input).build().getStaff().stream().filter(s -> s.getId().equals(id)).findFirst()
                .orElseThrow();
    }

    static Restriction never(String id, String shiftId, ShiftCategory category, int weekday) {
        return new Restriction(id, shiftId, category, weekday, RestrictionKind.NEVER, null, null);
    }

    @Test
    void habitKeepsTheMixAndLeavesThePersonOutOfTheNightComparison() {
        Staff s = staff(input(NightDistribution.HABIT, mix(PERSON, MIX), List.of()), PERSON);
        assertThat(s.getMix()).isEqualTo(MIX);
        assertThat(s.isNightsOutsideMix()).isFalse();
        assertThat(s.isInNightPool()).isFalse();
        assertThat(s.getNightExclusion()).isEqualTo(Staff.NIGHTS_MIX);
        assertThat(s.hasCategoryMix()).isTrue();
        // Without a mix nothing changes: in the comparison.
        assertThat(staff(input(NightDistribution.HABIT, p -> p, List.of()), PERSON).isInNightPool()).isTrue();
    }

    @Test
    void evenIgnoresOnlyTheNightShare() {
        Staff s = staff(input(NightDistribution.EVEN, mix(PERSON, MIX), List.of()), PERSON);
        // Early and day 45 % each become 50 % each of the duties that are not nights; the area mix stays.
        assertThat(s.getMix().categoriesOrEmpty()).containsOnlyKeys(ShiftCategory.EARLY, ShiftCategory.DAY)
                .allSatisfy((k, v) -> assertThat(v).isEqualTo(0.5, offset(1e-9)));
        assertThat(s.getMix().areasOrEmpty()).isEqualTo(MIX.areas());
        assertThat(s.isNightsOutsideMix()).isTrue();
        assertThat(s.isInNightPool()).isTrue();
        // Shift variety still left out: a category mix remains.
        assertThat(s.hasCategoryMix()).isTrue();

        // Only nights: no category mix remains, so shift variety applies again.
        Staff nights = staff(input(NightDistribution.EVEN,
                mix(PERSON, new ShiftMix(Map.of(ShiftCategory.NIGHT, 1.0), Map.of())), List.of()), PERSON);
        assertThat(nights.hasCategoryMix()).isFalse();
        assertThat(nights.isInNightPool()).isTrue();
    }

    @Test
    void inEvenTheOtherSharesCountAmongTheDutiesThatAreNotNights() {
        Staff s = RosterConstraintsTest.staff("A", Set.of("AN"));
        s.setMix(new ShiftMix(Map.of(ShiftCategory.DAY, 0.5, ShiftCategory.LATE, 0.5), Map.of("AN", 1.0)));
        s.setNightsOutsideMix(true);
        ShiftOption day = new ShiftOption("D", "AN", 7 * 60, 720, ShiftCategory.DAY, false, false, false);
        ShiftOption late = new ShiftOption("L", "AN", 13 * 60, 480, ShiftCategory.LATE, false, false, false);
        ShiftOption night = new ShiftOption("N", "AN", 19 * 60, 720, ShiftCategory.NIGHT, true, false, false);
        List<DutyCell> cells = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            cells.add(RosterConstraintsTest.cell(s, LocalDate.of(2027, 1, 1 + i), i < 4 ? day : i < 8 ? late : night));
        }
        // 4 day, 4 late, 2 nights: the nights do not count, 4 and 4 of 8 is the mix.
        assertThat(RosterConstraints.mixDeviation(s, cells)).isZero();
        // 5 day and 3 late instead of 4 and 4: one duty off in both categories.
        cells.get(4).setShift(day);
        assertThat(RosterConstraints.mixDeviation(s, cells)).isEqualTo(2 * 10 * 10);
        // In HABIT the same duties are compared with all 10 duties and the night share 0: 2 late too few, 2 nights
        // too many.
        s.setNightsOutsideMix(false);
        assertThat(RosterConstraints.mixDeviation(s, cells)).isEqualTo(2 * 20 * 20);
    }

    @Test
    void peopleOutsideTheNightPoolAreNotCompared() {
        LocalDate mon = LocalDate.of(2027, 1, 4);
        ShiftOption night = RosterConstraintsTest.option("DR-N", "DR", "19:00", 720, ShiftCategory.NIGHT, true);
        Staff a = RosterConstraintsTest.staff("A", Set.of("DR"));
        Staff b = RosterConstraintsTest.staff("B", Set.of("DR"));
        Staff c = RosterConstraintsTest.staff("C", Set.of("DR"));
        // In the comparison, C (no nights) pulls the mean down: loads 2000, 0, 0.
        VERIFIER.verifyThat(RosterConstraints::fairNights)
                .given(a, b, c, RosterConstraintsTest.cell(a, mon, night),
                        RosterConstraintsTest.cell(a, mon.plusDays(1), night))
                .penalizesBy(1633);
        // Outside it, C neither counts with no nights nor with nights of its own.
        c.setNightExclusion(Staff.NIGHTS_NEVER);
        VERIFIER.verifyThat(RosterConstraints::fairNights)
                .given(a, b, c, RosterConstraintsTest.cell(a, mon, night),
                        RosterConstraintsTest.cell(a, mon.plusDays(1), night))
                .penalizesBy(1414);
        VERIFIER.verifyThat(RosterConstraints::fairNights)
                .given(a, b, c, RosterConstraintsTest.cell(a, mon, night), RosterConstraintsTest.cell(b, mon, night),
                        RosterConstraintsTest.cell(c, mon.plusDays(1), night),
                        RosterConstraintsTest.cell(c, mon.plusDays(2), night))
                .penalizesBy(0);
    }

    @Test
    void shiftVarietySkipsOnlyPeopleWithARemainingCategoryMix() {
        LocalDate mon = LocalDate.of(2027, 1, 4);
        ShiftOption day = RosterConstraintsTest.option("AN-T", "AN", "07:00", 720, ShiftCategory.DAY, false);
        Staff withMix = RosterConstraintsTest.staff("M", Set.of("AN"));
        withMix.setMix(new ShiftMix(Map.of(ShiftCategory.DAY, 1.0), Map.of()));
        // EVEN with a mix of only nights: nothing remains.
        Staff nightsOnly = RosterConstraintsTest.staff("N", Set.of("AN"));
        nightsOnly.setMix(new ShiftMix(Map.of(), Map.of()));
        VERIFIER.verifyThat(RosterConstraints::shiftVariety)
                .given(RosterConstraintsTest.cell(withMix, mon, day),
                        RosterConstraintsTest.cell(withMix, mon.plusDays(1), day))
                .penalizesBy(0);
        VERIFIER.verifyThat(RosterConstraints::shiftVariety)
                .given(RosterConstraintsTest.cell(nightsOnly, mon, day),
                        RosterConstraintsTest.cell(nightsOnly, mon.plusDays(1), day))
                .penalizesBy(1);
    }

    @Test
    void neverOnEveryNightShiftTakesThePersonOutInBothModes() {
        for (NightDistribution mode : NightDistribution.values()) {
            PlanningInput plain = input(mode, p -> p, List.of());
            // NEVER by category NIGHT on every day.
            assertThat(staff(plain, "P0004").getNightExclusion()).as(mode.name()).isEqualTo(Staff.NIGHTS_NEVER);
            // NEVER on the only night shift of the person's area (AN-N, DR-N).
            assertThat(staff(plain, "P0001").getNightExclusion()).isEqualTo(Staff.NIGHTS_NEVER);
            assertThat(staff(plain, "P0012").getNightExclusion()).isEqualTo(Staff.NIGHTS_NEVER);
            // No night shift in the person's areas.
            assertThat(staff(plain, "P0008").getNightExclusion()).isEqualTo(Staff.NIGHTS_NEVER);
            // NEVER on nights for part of the month, on one weekday or on one of two night shifts: in the pool.
            assertThat(staff(plain, "P0003").isInNightPool()).isTrue();
            assertThat(staff(plain, "P0013").isInNightPool()).isTrue();
            assertThat(staff(input(mode, p -> p, List.of(never(PERSON, "AN-N", null, 0))), PERSON).isInNightPool())
                    .isTrue();
            // Each night shift of both areas.
            assertThat(staff(input(mode, p -> p, List.of(never(PERSON, "AN-N", null, 0),
                    never(PERSON, "DR-N", null, 0))), PERSON).getNightExclusion()).isEqualTo(Staff.NIGHTS_NEVER);
            // NEVER wins over a usual mix.
            assertThat(staff(input(mode, mix(PERSON, MIX), List.of(never(PERSON, null, ShiftCategory.NIGHT, 0))),
                    PERSON).getNightExclusion()).isEqualTo(Staff.NIGHTS_NEVER);
        }
    }

    @Test
    void aFamilyRuleWithoutNightsOnAllWeekdaysTakesThePersonOut() {
        for (NightDistribution mode : NightDistribution.values()) {
            // P0014 has "keine Nacht" on Thursdays only.
            assertThat(staff(input(mode, p -> p, List.of()), "P0014").isInNightPool()).isTrue();
            assertThat(staff(input(mode, family(PERSON, new FamilyRule(List.of(1, 2, 3, 4, 5, 6, 7), null, null,
                    true, null)), List.of()), PERSON).getNightExclusion()).isEqualTo(Staff.NIGHTS_NEVER);
            assertThat(staff(input(mode, family(PERSON, new FamilyRule(List.of(), null, null, true, null)),
                    List.of()), PERSON).getNightExclusion()).isEqualTo(Staff.NIGHTS_NEVER);
            assertThat(staff(input(mode, family(PERSON, new FamilyRule(List.of(1, 2, 3, 4, 5, 6), null, null, true,
                    null)), List.of()), PERSON).isInNightPool()).isTrue();
            // Every day, but only a latest end: nights are allowed.
            assertThat(staff(input(mode, family(PERSON, new FamilyRule(List.of(1, 2, 3, 4, 5, 6, 7),
                    java.time.LocalTime.of(18, 0), null, false, null)), List.of()), PERSON).isInNightPool()).isTrue();
        }
    }

    @Test
    void theExplanationShowsTheNightComparison() {
        List<PlanAssignment> nights = new ArrayList<>();
        for (int day = 4; day <= 8; day++) {
            nights.add(new PlanAssignment(PERSON, LocalDate.of(2027, 1, day), "DR-N", false, true));
        }
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();

        PersonExplanation habit = facade.explain(input(rules(NightDistribution.HABIT), mix(PERSON, MIX), List.of(),
                nights), PERSON);
        assertThat(habit.stats().nightPool()).isFalse();
        assertThat(habit.stats().nightReason()).isEqualTo(Staff.NIGHTS_MIX);
        assertThat(habit.stats().nightLoad()).isNull();
        assertThat(habit.stats().nightMean()).isNotNull();
        assertThat(habit.hits()).noneMatch(h -> h.code().equals("hit.fairNights"));

        PersonExplanation even = facade.explain(input(rules(NightDistribution.EVEN), mix(PERSON, MIX), List.of(),
                nights), PERSON);
        assertThat(even.stats().nightPool()).isTrue();
        assertThat(even.stats().nightReason()).isNull();
        assertThat(even.stats().nightLoad()).isGreaterThan(even.stats().nightMean());
        ConstraintHit hit = even.hits().stream().filter(h -> h.code().equals("hit.fairNights")).findFirst()
                .orElseThrow();
        // The hit and the figures use the same load and mean (the hit in thousandths).
        assertThat(((Number) hit.params().get("load")).doubleValue() / 1000).isEqualTo(even.stats().nightLoad(),
                offset(0.01));
        assertThat(((Number) hit.params().get("mean")).doubleValue() / 1000).isEqualTo(even.stats().nightMean(),
                offset(0.01));
        assertThat(hit.params()).containsEntry("count", 5);

        // Never nights: outside, with the reason, in both modes.
        for (NightDistribution mode : NightDistribution.values()) {
            PersonExplanation never = facade.explain(input(rules(mode), p -> p, List.of(), List.of()), "P0004");
            assertThat(never.stats().nightPool()).isFalse();
            assertThat(never.stats().nightReason()).isEqualTo(Staff.NIGHTS_NEVER);
        }
    }

    @Test
    void rulesSavedWithoutTheSettingMeanHabit() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ObjectNode json = mapper.valueToTree(Rules.defaults());
        json.remove("nightDistribution");
        Rules old = mapper.treeToValue(json, Rules.class);
        assertThat(old.nightDistribution()).isNull();
        assertThat(old.nightMode()).isEqualTo(NightDistribution.HABIT);
        assertThat(rules(NightDistribution.HABIT)).isEqualTo(Rules.defaults());
        assertThat(staff(input(old, mix(PERSON, MIX), List.of(), List.of()), PERSON).getNightExclusion())
                .isEqualTo(Staff.NIGHTS_MIX);
        assertThat(mapper.treeToValue(mapper.valueToTree(rules(NightDistribution.EVEN)), Rules.class).nightMode())
                .isEqualTo(NightDistribution.EVEN);
    }
}
