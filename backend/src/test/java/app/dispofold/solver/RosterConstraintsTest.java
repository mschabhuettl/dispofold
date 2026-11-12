package app.dispofold.solver;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.dispofold.model.ShiftCategory;
import app.dispofold.model.Wish;
import app.dispofold.model.WishKind;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;

import org.junit.jupiter.api.Test;

/** One positive and one negative case per constraint, with hand-built cells. */
class RosterConstraintsTest {

    private static final ConstraintVerifier<RosterConstraints, Roster> VERIFIER =
            ConstraintVerifier.build(new RosterConstraints(), Roster.class, DutyCell.class);

    /** Monday. */
    private static final LocalDate MON = LocalDate.of(2027, 1, 4);
    private static final Week WEEK = new Week(MON.toString(), Times.minute(MON), Times.minute(MON.plusWeeks(1)));

    private static final ShiftOption EARLY = option("E", "AN", "06:30", 480, ShiftCategory.EARLY, false);
    private static final ShiftOption DAY = option("D", "AN", "07:00", 720, ShiftCategory.DAY, false);
    private static final ShiftOption LATE = option("L", "AN", "14:00", 480, ShiftCategory.LATE, false);
    private static final ShiftOption NIGHT = option("N", "DR", "19:00", 720, ShiftCategory.NIGHT, true);
    private static final ShiftOption LONG = option("X", "DR", "07:00", 780, ShiftCategory.DAY, false);
    private static final ShiftOption ELEVEN = option("Y", "AN", "07:00", 660, ShiftCategory.DAY, false);
    private static final ShiftOption UNPOPULAR = new ShiftOption("U", "AN", 420, 720, ShiftCategory.DAY, false,
            true, false);
    private static final ShiftOption ER = ShiftOption.compRest();

    static ShiftOption option(String id, String area, String start, int minutes, ShiftCategory category,
            boolean night) {
        return new ShiftOption(id, area, Times.minuteOfDay(LocalTime.parse(start)), minutes, category, night,
                false, false);
    }

    static Staff staff(String id, Set<String> areas) {
        return staff(id, areas, 0, 0, Staff.History.NONE, 0);
    }

    static Staff staff(String id, Set<String> areas, int target, int compRest, Staff.History history,
            int wishes) {
        return staff(id, areas, target, compRest, history, wishes, Staff.Fairness.of(40, 30, true, true),
                Map.of());
    }

    /** Fairness weight 1000 per duty for 30 available days at 40 h. */
    static Staff staff(String id, Set<String> areas, int target, int compRest, Staff.History history,
            int wishes, Staff.Fairness fairness, Map<String, Integer> previous) {
        return new Staff(id, 40, areas, 480, target, compRest, new Staff.Limits(660, 2160, 720, 3600, 2, 4),
                history, wishes, fairness, Staff.Average.NONE, previous);
    }

    static DemandSegment segment(String id, String area, LocalDate day, long start, long end, int required,
            Integer max) {
        return new DemandSegment(id, area, day, start, end, required, max, TimeLine.UTC.local(start),
                TimeLine.UTC.local(end));
    }

    static DutyCell cell(Staff s, LocalDate date, ShiftOption shift) {
        DutyCell c = new DutyCell(s, date, true, List.of());
        c.setShift(shift);
        return c;
    }

    private static final Staff A = staff("A", Set.of("AN", "DR"));

    // Hard

    @Test
    void dailyRest() {
        VERIFIER.verifyThat(RosterConstraints::dailyRest)
                .given(cell(A, MON, LATE), cell(A, MON.plusDays(1), EARLY))
                .penalizesBy(660 - 510);
        VERIFIER.verifyThat(RosterConstraints::dailyRest)
                .given(cell(A, MON, DAY), cell(A, MON.plusDays(1), EARLY))
                .penalizesBy(0);
    }

    @Test
    void dailyRestCatchesADutyInsideTheRestOfALongNight() {
        ShiftOption longNight = option("LN", "DR", "19:00", 720, ShiftCategory.NIGHT, true);
        ShiftOption small = option("S", "DR", "01:00", 240, ShiftCategory.EARLY, true);
        // Night ends 07:00, the next duty starts 01:00 inside it: 6 h overlap plus 11 h rest missing.
        VERIFIER.verifyThat(RosterConstraints::dailyRest)
                .given(cell(A, MON, longNight), cell(A, MON.plusDays(1), small))
                .penalizesBy(420 + 660 - 60);
    }

    @Test
    void dailyRestCountsPreviousDuties() {
        DutyCell previous = new DutyCell(A, MON.minusDays(1), false, List.of(LATE));
        previous.setShift(LATE);
        VERIFIER.verifyThat(RosterConstraints::dailyRest)
                .given(previous, cell(A, MON, EARLY))
                .penalizesBy(150);
    }

    @Test
    void weeklyRest() {
        List<Object> facts = new ArrayList<>(List.of(WEEK));
        for (int d = 0; d < 7; d++) {
            facts.add(cell(A, MON.plusDays(d), DAY));
        }
        // Longest rest is 12 h between two day duties.
        VERIFIER.verifyThat(RosterConstraints::weeklyRest).given(facts.toArray()).penalizesBy(2160 - 720);
        facts.subList(6, 8).clear();
        // Friday 19:00 to Monday 00:00 is free.
        VERIFIER.verifyThat(RosterConstraints::weeklyRest).given(facts.toArray()).penalizesBy(0);
    }

    @Test
    void maxDutyLength() {
        VERIFIER.verifyThat(RosterConstraints::maxDutyLength).given(cell(A, MON, LONG)).penalizesBy(60);
        VERIFIER.verifyThat(RosterConstraints::maxDutyLength).given(cell(A, MON, DAY)).penalizesBy(0);
    }

    @Test
    void maxWeekHours() {
        List<Object> facts = new ArrayList<>(List.of(WEEK));
        for (int d = 0; d < 6; d++) {
            facts.add(cell(A, MON.plusDays(d), ELEVEN));
        }
        VERIFIER.verifyThat(RosterConstraints::maxWeekHours).given(facts.toArray()).penalizesBy(6 * 660 - 3600);
        facts.remove(6);
        VERIFIER.verifyThat(RosterConstraints::maxWeekHours).given(facts.toArray()).penalizesBy(0);
    }

    @Test
    void averageWeekHours() {
        // 50 weeks with 2,300 h history, 1 week of 60 h now: 2,360 h over 51 weeks allowed at 46 h = 2,346 h.
        Staff.Average average = new Staff.Average(2300 * 60, 51, true, 46 * 60);
        Staff s = new Staff("W", 40, Set.of("AN"), 480, 0, 0, new Staff.Limits(660, 2160, 720, 3600, 2, 4),
                Staff.History.NONE, 0, Staff.Fairness.of(40, 30, true, true), average, Map.of());
        List<Object> cells = new ArrayList<>();
        for (int d = 0; d < 5; d++) {
            cells.add(cell(s, MON.plusDays(d), DAY));
        }
        VERIFIER.verifyThat(RosterConstraints::averageWeekHours).given(cells.toArray())
                .penalizesBy((2300 + 60 - 2346) * 60);
        // Without a complete history the average is only shown.
        Staff partial = new Staff("X", 40, Set.of("AN"), 480, 0, 0, new Staff.Limits(660, 2160, 720, 3600, 2, 4),
                Staff.History.NONE, 0, Staff.Fairness.of(40, 30, true, true),
                new Staff.Average(2300 * 60, 51, false, 46 * 60), Map.of());
        VERIFIER.verifyThat(RosterConstraints::averageWeekHours).given(cell(partial, MON, DAY)).penalizesBy(0);
    }

    @Test
    void employment() {
        DutyCell before = cell(A, MON, DAY);
        before.setOutsideEmployment(true);
        VERIFIER.verifyThat(RosterConstraints::employment).given(before).penalizes(1);
        DutyCell free = cell(A, MON, null);
        free.setOutsideEmployment(true);
        VERIFIER.verifyThat(RosterConstraints::employment).given(free, cell(A, MON.plusDays(1), DAY)).penalizes(0);
    }

    @Test
    void missingShiftIsReportedNotHidden() {
        DutyCell c = cell(A, MON, null);
        c.setMissingShiftId("OLD");
        VERIFIER.verifyThat(RosterConstraints::missingShift).given(c).penalizes(1);
        VERIFIER.verifyThat(RosterConstraints::missingShift).given(cell(A, MON, null)).penalizes(0);
    }

    @Test
    void qualification() {
        Staff b = staff("B", Set.of("AN"));
        VERIFIER.verifyThat(RosterConstraints::qualification).given(cell(b, MON, NIGHT)).penalizes(1);
        VERIFIER.verifyThat(RosterConstraints::qualification).given(cell(b, MON, DAY)).penalizes(0);
    }

    @Test
    void absence() {
        DutyCell worked = cell(A, MON, DAY);
        worked.setAbsence("U", true);
        DutyCell free = cell(A, MON.plusDays(1), null);
        free.setAbsence("U", true);
        VERIFIER.verifyThat(RosterConstraints::absence).given(worked, free).penalizes(1);
        DutyCell compRest = cell(A, MON, ER);
        compRest.setAbsence("K", true);
        VERIFIER.verifyThat(RosterConstraints::absence).given(compRest).penalizes(1);
        VERIFIER.verifyThat(RosterConstraints::absence).given(free).penalizes(0);
    }

    @Test
    void neverRestriction() {
        DutyCell c = cell(A, MON, DAY);
        c.setNever(Set.of("D"));
        VERIFIER.verifyThat(RosterConstraints::neverRestriction).given(c).penalizes(1);
        c.setNever(Set.of("L"));
        VERIFIER.verifyThat(RosterConstraints::neverRestriction).given(c).penalizes(0);
    }

    @Test
    void compRestBalance() {
        Staff b = staff("B", Set.of("AN"), 0, 1, Staff.History.NONE, 0);
        VERIFIER.verifyThat(RosterConstraints::compRestBalance)
                .given(cell(b, MON, ER), cell(b, MON.plusDays(1), ER)).penalizesBy(1);
        VERIFIER.verifyThat(RosterConstraints::compRestBalance).given(cell(b, MON, ER)).penalizesBy(0);
    }

    // Medium

    @Test
    void demand() {
        long start = Times.minute(MON) + 7 * 60;
        DemandSegment segment = segment("s", "AN", MON, start, start + 720, 2, null);
        VERIFIER.verifyThat(RosterConstraints::demand).given(segment, cell(A, MON, DAY)).penalizesBy(720);
        VERIFIER.verifyThat(RosterConstraints::demand)
                .given(segment, cell(A, MON, DAY), cell(staff("B", Set.of("AN")), MON, DAY)).penalizesBy(0);
    }

    @Test
    void demandWithoutAnyDutyIsPenalized() {
        long start = Times.minute(MON) + 7 * 60;
        DemandSegment uncovered = segment("s", "AN", MON, start, start + 720, 2, null);
        DemandSegment other = segment("t", "DR", MON, start, start + 60, 1, null);
        VERIFIER.verifyThat(RosterConstraints::demand).given(uncovered).penalizesBy(1440);
        // A duty in another area does not help.
        VERIFIER.verifyThat(RosterConstraints::demand).given(uncovered, other, cell(A, MON, NIGHT))
                .penalizesBy(1440 + 60);
    }

    // Soft

    @Test
    void targetHours() {
        Staff b = staff("B", Set.of("AN"), 1440, 0, Staff.History.NONE, 0);
        // 12 h planned, 24 h target: 48 quarter hours, squared.
        VERIFIER.verifyThat(RosterConstraints::targetHours).given(cell(b, MON, DAY), cell(b, MON.plusDays(1), null))
                .penalizesBy(2304);
        VERIFIER.verifyThat(RosterConstraints::targetHours)
                .given(cell(b, MON, DAY), cell(b, MON.plusDays(1), DAY)).penalizesBy(0);
    }

    @Test
    void targetHoursCountsCreditedDays() {
        Staff b = staff("B", Set.of("AN"), 960, 1, Staff.History.NONE, 0);
        DutyCell absent = cell(b, MON, null);
        absent.setAbsence("U", true);
        absent.setWorkday(true);
        VERIFIER.verifyThat(RosterConstraints::targetHours).given(absent, cell(b, MON.plusDays(1), ER))
                .penalizesBy(0);
    }

    private static final Staff B = staff("B", Set.of("AN", "DR"));

    @Test
    void fairNightsIsZeroWhenLoadsAreEqual() {
        // Loads 2000 and 0, mean 1000: root of 2 x 1000^2.
        VERIFIER.verifyThat(RosterConstraints::fairNights)
                .given(A, B, cell(A, MON, NIGHT), cell(A, MON.plusDays(1), NIGHT)).penalizesBy(1414);
        VERIFIER.verifyThat(RosterConstraints::fairNights)
                .given(A, B, cell(A, MON, NIGHT), cell(B, MON.plusDays(1), NIGHT)).penalizesBy(0);
        // Someone without night shifts in their areas is not compared.
        Staff dayOnly = staff("D", Set.of("AN"), 0, 0, Staff.History.NONE, 0,
                Staff.Fairness.of(40, 30, false, false), Map.of());
        VERIFIER.verifyThat(RosterConstraints::fairNights)
                .given(A, B, dayOnly, cell(A, MON, NIGHT), cell(B, MON.plusDays(1), NIGHT)).penalizesBy(0);
    }

    @Test
    void fairNightsCountsHistoryAndAvailableDays() {
        // Two nights in the history months equal two nights now.
        Staff veteran = staff("V", Set.of("DR"), 0, 0, new Staff.History(2, 0, 0, 0, 0, 0, 1), 0);
        VERIFIER.verifyThat(RosterConstraints::fairNights)
                .given(veteran, B, cell(B, MON, NIGHT), cell(B, MON.plusDays(1), NIGHT)).penalizesBy(0);
        // Half the available days: one night weighs as much as two of a person available all month.
        Staff half = staff("H", Set.of("DR"), 0, 0, Staff.History.NONE, 0, Staff.Fairness.of(40, 15, true, true),
                Map.of());
        VERIFIER.verifyThat(RosterConstraints::fairNights)
                .given(half, B, cell(half, MON, NIGHT), cell(B, MON, NIGHT), cell(B, MON.plusDays(1), NIGHT))
                .penalizesBy(0);
    }

    @Test
    void fairWeekends() {
        LocalDate sat = MON.plusDays(5);
        // One weekend (two days) against none.
        VERIFIER.verifyThat(RosterConstraints::fairWeekends)
                .given(A, B, cell(A, sat, DAY), cell(A, sat.plusDays(1), DAY)).penalizesBy(707);
        VERIFIER.verifyThat(RosterConstraints::fairWeekends)
                .given(A, B, cell(A, sat, DAY), cell(B, sat.plusDays(1), DAY)).penalizesBy(0);
    }

    @Test
    void aWeekendThatStartedLastMonthIsNotCountedAgain() {
        LocalDate sundayFirst = LocalDate.of(2027, 8, 1);
        VERIFIER.verifyThat(RosterConstraints::fairWeekends)
                .given(A, B, cell(A, sundayFirst, DAY)).penalizesBy(0);
    }

    @Test
    void fairHolidays() {
        DutyCell c = cell(A, MON, DAY);
        c.setHoliday(true);
        VERIFIER.verifyThat(RosterConstraints::fairHolidays).given(A, B, c).penalizesBy(707);
        VERIFIER.verifyThat(RosterConstraints::fairHolidays).given(A, B, cell(A, MON, DAY)).penalizesBy(0);
    }

    @Test
    void fairUnpopular() {
        VERIFIER.verifyThat(RosterConstraints::fairUnpopular).given(A, B, cell(A, MON, UNPOPULAR)).penalizesBy(707);
        VERIFIER.verifyThat(RosterConstraints::fairUnpopular).given(A, B, cell(A, MON, DAY)).penalizesBy(0);
    }

    @Test
    void wishesEvenOutTheRate() {
        Staff b = staff("B", Set.of("AN"), 0, 0, Staff.History.NONE, 2);
        DutyCell off = cell(b, MON, DAY);
        off.setWishes(List.of(new Wish("B", MON, WishKind.OFF, null)));
        DutyCell duty = cell(b, MON.plusDays(1), null);
        duty.setWishes(List.of(new Wish("B", MON.plusDays(1), WishKind.DUTY, null)));
        // Both unmet: 100 * 2^2 / 2.
        VERIFIER.verifyThat(RosterConstraints::wishes).given(off, duty).penalizesBy(200);
        // Someone whose wishes were often unmet before pays more for the next unmet one.
        Staff unlucky = staff("C", Set.of("AN"), 0, 0, new Staff.History(0, 0, 0, 0, 2, 0, 1), 2);
        DutyCell c = cell(unlucky, MON, DAY);
        c.setWishes(List.of(new Wish("C", MON, WishKind.OFF, null)));
        VERIFIER.verifyThat(RosterConstraints::wishes).given(c).penalizesBy(100 * (9 - 4) / 4);
        off.setShift(null);
        duty.setShift(DAY);
        VERIFIER.verifyThat(RosterConstraints::wishes).given(off, duty).penalizesBy(0);
    }

    @Test
    void blockLength() {
        VERIFIER.verifyThat(RosterConstraints::blockLength).given(cell(A, MON, DAY)).penalizesBy(1);
        List<Object> five = new ArrayList<>();
        for (int d = 0; d < 5; d++) {
            five.add(cell(A, MON.plusDays(d), DAY));
        }
        VERIFIER.verifyThat(RosterConstraints::blockLength).given(five.toArray()).penalizesBy(1);
        VERIFIER.verifyThat(RosterConstraints::blockLength)
                .given(cell(A, MON, DAY), cell(A, MON.plusDays(1), DAY)).penalizesBy(0);
        DutyCell last = cell(A, MON, DAY);
        last.setMonthEnd(true);
        VERIFIER.verifyThat(RosterConstraints::blockLength).given(last).penalizesBy(0);
    }

    @Test
    void isolatedDayOff() {
        VERIFIER.verifyThat(RosterConstraints::isolatedDayOff)
                .given(cell(A, MON, DAY), cell(A, MON.plusDays(1), DAY), cell(A, MON.plusDays(3), DAY))
                .penalizesBy(1);
        VERIFIER.verifyThat(RosterConstraints::isolatedDayOff)
                .given(cell(A, MON, DAY), cell(A, MON.plusDays(3), DAY)).penalizesBy(0);
    }

    @Test
    void forwardRotation() {
        VERIFIER.verifyThat(RosterConstraints::forwardRotation)
                .given(cell(A, MON, LATE), cell(A, MON.plusDays(1), DAY)).penalizesBy(1);
        VERIFIER.verifyThat(RosterConstraints::forwardRotation)
                .given(cell(A, MON, EARLY), cell(A, MON.plusDays(1), LATE)).penalizesBy(0);
    }

    @Test
    void splitWeekend() {
        LocalDate sat = MON.plusDays(5);
        DutyCell saturday = cell(A, sat, DAY);
        DutyCell sunday = cell(A, sat.plusDays(1), null);
        saturday.setWeekend(sat.toEpochDay());
        sunday.setWeekend(sat.toEpochDay());
        VERIFIER.verifyThat(RosterConstraints::splitWeekend).given(saturday, sunday).penalizesBy(1);
        sunday.setShift(DAY);
        VERIFIER.verifyThat(RosterConstraints::splitWeekend).given(saturday, sunday).penalizesBy(0);
        sunday.setShift(null);
        sunday.setAbsence("U", true);
        VERIFIER.verifyThat(RosterConstraints::splitWeekend).given(saturday, sunday).penalizesBy(0);
    }

    @Test
    void compRestUse() {
        VERIFIER.verifyThat(RosterConstraints::compRestUse).given(cell(A, MON, ER)).rewardsWith(1);
        VERIFIER.verifyThat(RosterConstraints::compRestUse).given(cell(A, MON, DAY)).rewardsWith(0);
    }

    @Test
    void shiftVarietyCountsAreaCategoryAndPreviousMonth() {
        // DAY and UNPOPULAR are both day duties in the same area: 3 pairs.
        VERIFIER.verifyThat(RosterConstraints::shiftVariety)
                .given(cell(A, MON, DAY), cell(A, MON.plusDays(1), UNPOPULAR), cell(A, MON.plusDays(2), DAY))
                .penalizesBy(3);
        VERIFIER.verifyThat(RosterConstraints::shiftVariety)
                .given(cell(A, MON, DAY), cell(A, MON.plusDays(1), LATE)).penalizesBy(0);
        // Two such duties last month: one more now adds pairs(3) - pairs(2) = 2.
        Staff repeated = staff("R", Set.of("AN"), 0, 0, Staff.History.NONE, 0,
                Staff.Fairness.of(40, 30, true, true), Map.of("AN/DAY", 2));
        VERIFIER.verifyThat(RosterConstraints::shiftVariety).given(cell(repeated, MON, DAY)).penalizesBy(2);
    }

    @Test
    void areaVariety() {
        VERIFIER.verifyThat(RosterConstraints::areaVariety)
                .given(cell(A, MON, DAY), cell(A, MON.plusDays(1), LATE)).penalizesBy(1);
        Staff single = staff("S", Set.of("AN"));
        VERIFIER.verifyThat(RosterConstraints::areaVariety)
                .given(cell(single, MON, DAY), cell(single, MON.plusDays(1), LATE)).penalizesBy(0);
        Staff previous = staff("P", Set.of("AN", "DR"), 0, 0, Staff.History.NONE, 0,
                Staff.Fairness.of(40, 30, true, true), Map.of("AN", 3));
        VERIFIER.verifyThat(RosterConstraints::areaVariety).given(cell(previous, MON, DAY)).penalizesBy(3);
    }

    @Test
    void family() {
        DutyCell c = cell(A, MON, LATE);
        c.setFamily(19 * 60, -1, false);
        VERIFIER.verifyThat(RosterConstraints::family).given(c).penalizesBy(1);
        c.setShift(DAY);
        VERIFIER.verifyThat(RosterConstraints::family).given(c).penalizesBy(0);
        DutyCell night = cell(A, MON, NIGHT);
        night.setFamily(-1, -1, true);
        VERIFIER.verifyThat(RosterConstraints::family).given(night).penalizesBy(1);
        DutyCell early = cell(A, MON, EARLY);
        early.setFamily(-1, 7 * 60, false);
        VERIFIER.verifyThat(RosterConstraints::family).given(early).penalizesBy(1);
    }

    @Test
    void onRequest() {
        DutyCell c = cell(A, MON, DAY);
        c.setOnRequest(Set.of("D"));
        VERIFIER.verifyThat(RosterConstraints::onRequest).given(c).penalizesBy(1);
        c.setShift(LATE);
        VERIFIER.verifyThat(RosterConstraints::onRequest).given(c).penalizesBy(0);
    }

    @Test
    void overstaffing() {
        long start = Times.minute(MON) + 8 * 60;
        DemandSegment segment = segment("s", "AN", MON, start, start + 120, 1, 1);
        Staff b = staff("B", Set.of("AN"));
        VERIFIER.verifyThat(RosterConstraints::overstaffing).given(segment, cell(A, MON, DAY), cell(b, MON, DAY))
                .penalizesBy(120);
        VERIFIER.verifyThat(RosterConstraints::overstaffing).given(segment, cell(A, MON, DAY)).penalizesBy(0);
        // Without a maximum, staffing above the required one counts.
        DemandSegment open = segment("t", "AN", MON, start, start + 120, 1, null);
        VERIFIER.verifyThat(RosterConstraints::overstaffing).given(open, cell(A, MON, DAY), cell(b, MON, DAY))
                .penalizesBy(120);
        VERIFIER.verifyThat(RosterConstraints::overstaffing).given(open, cell(A, MON, DAY)).penalizesBy(0);
    }

    @Test
    void uselessDuty() {
        long start = Times.minute(MON) + 8 * 60;
        DemandSegment segment = segment("s", "AN", MON, start, start + 120, 1, null);
        VERIFIER.verifyThat(RosterConstraints::uselessDuty).given(segment, cell(A, MON, NIGHT)).penalizesBy(1);
        VERIFIER.verifyThat(RosterConstraints::uselessDuty).given(segment, cell(A, MON, DAY)).penalizesBy(0);
    }
}
