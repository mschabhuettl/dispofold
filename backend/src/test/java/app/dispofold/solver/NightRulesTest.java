package app.dispofold.solver;

import static app.dispofold.solver.RosterConstraintsTest.cell;
import static app.dispofold.solver.RosterConstraintsTest.option;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.dispofold.model.HistoryEntry;
import app.dispofold.model.ShiftCategory;
import app.dispofold.model.ShiftMix;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;

import org.junit.jupiter.api.Test;

/** The rules "Wechsel zwischen Tag und Nacht" and "Nachtblöcke" and the night share of the usual mix over months. */
class NightRulesTest {

    private static final ConstraintVerifier<RosterConstraints, Roster> VERIFIER =
            ConstraintVerifier.build(new RosterConstraints(), Roster.class, DutyCell.class);

    private static final LocalDate MON = LocalDate.of(2027, 1, 4);
    private static final ShiftOption DAY = option("D", "AN", "07:00", 720, ShiftCategory.DAY, false);
    private static final ShiftOption NIGHT = option("N", "AN", "19:00", 720, ShiftCategory.NIGHT, true);
    /** A late duty that reaches into the night window is no night: the category decides. */
    private static final ShiftOption LATE = option("L", "AN", "16:00", 480, ShiftCategory.LATE, true);

    /** Defaults: 2 free days after nights, 0 before, night blocks of 2 to 4. */
    private static final Staff A = staff(2, 0);

    static Staff staff(int nightRest, int dayToNight) {
        return new Staff("A", 40, Set.of("AN"), 480, 0, 0, new Staff.Limits(660, 2160, 720, 3600, 2, 4,
                Integer.MAX_VALUE, Integer.MAX_VALUE, nightRest, dayToNight, 2, 4), Staff.History.NONE, 0,
                Staff.Fairness.of(40, 30, true, true), Staff.Average.NONE, Map.of());
    }

    @Test
    void afterNightsTwoFreeDaysArePreferred() {
        // A night on Monday, then a day duty after 0, 1, 2 or 3 free days: 2, 1, 0 and 0 days missing.
        long[] missing = { 2, 1, 0, 0 };
        for (int free = 0; free < 4; free++) {
            VERIFIER.verifyThat(RosterConstraints::dayNightSwitch)
                    .given(cell(A, MON, NIGHT), cell(A, MON.plusDays(1 + free), DAY))
                    .penalizesBy(missing[free]);
        }
        // Nights followed by nights are no switch.
        VERIFIER.verifyThat(RosterConstraints::dayNightSwitch)
                .given(cell(A, MON, NIGHT), cell(A, MON.plusDays(1), NIGHT), cell(A, MON.plusDays(2), NIGHT))
                .penalizesBy(0);
    }

    @Test
    void beforeNightsNoFreeDayIsNeededByDefaultButCanBeSet() {
        for (int free = 0; free < 4; free++) {
            VERIFIER.verifyThat(RosterConstraints::dayNightSwitch)
                    .given(cell(A, MON, DAY), cell(A, MON.plusDays(1 + free), NIGHT)).penalizesBy(0);
        }
        Staff b = staff(2, 2);
        long[] missing = { 2, 1, 0, 0 };
        for (int free = 0; free < 4; free++) {
            VERIFIER.verifyThat(RosterConstraints::dayNightSwitch)
                    .given(cell(b, MON, DAY), cell(b, MON.plusDays(1 + free), NIGHT)).penalizesBy(missing[free]);
        }
        Staff off = staff(0, 0);
        VERIFIER.verifyThat(RosterConstraints::dayNightSwitch)
                .given(cell(off, MON, NIGHT), cell(off, MON.plusDays(1), DAY)).penalizesBy(0);
    }

    @Test
    void absenceAndCompRestCountAsFreeDays() {
        DutyCell absent = cell(A, MON.plusDays(1), null);
        absent.setAbsence("U", true);
        VERIFIER.verifyThat(RosterConstraints::dayNightSwitch)
                .given(cell(A, MON, NIGHT), absent, cell(A, MON.plusDays(2), ShiftOption.compRest()),
                        cell(A, MON.plusDays(3), DAY))
                .penalizesBy(0);
        VERIFIER.verifyThat(RosterConstraints::dayNightSwitch)
                .given(cell(A, MON, NIGHT), absent, cell(A, MON.plusDays(2), DAY))
                .penalizesBy(1);
    }

    @Test
    void dutiesOfThePreviousMonthCount() {
        LocalDate first = LocalDate.of(2027, 2, 1);
        DutyCell night = new DutyCell(A, first.minusDays(1), false, List.of(NIGHT));
        night.setShift(NIGHT);
        VERIFIER.verifyThat(RosterConstraints::dayNightSwitch).given(night, cell(A, first, DAY)).penalizesBy(2);
        VERIFIER.verifyThat(RosterConstraints::dayNightSwitches).given(night, cell(A, first.plusDays(4), DAY))
                .penalizesBy(1);
        // Both duties before the month: not counted.
        DutyCell day = new DutyCell(A, first.minusDays(3), false, List.of(DAY));
        day.setShift(DAY);
        VERIFIER.verifyThat(RosterConstraints::dayNightSwitches).given(day, night).penalizesBy(0);
    }

    @Test
    void eachSwitchCountsOnce() {
        VERIFIER.verifyThat(RosterConstraints::dayNightSwitches)
                .given(cell(A, MON, DAY), cell(A, MON.plusDays(1), NIGHT), cell(A, MON.plusDays(2), NIGHT),
                        cell(A, MON.plusDays(5), DAY), cell(A, MON.plusDays(6), DAY))
                .penalizesBy(2);
        VERIFIER.verifyThat(RosterConstraints::dayNightSwitches)
                .given(cell(A, MON, DAY), cell(A, MON.plusDays(1), LATE), cell(A, MON.plusDays(3), DAY))
                .penalizesBy(0);
    }

    @Test
    void nightBlocksOfTwoToFour() {
        // Blocks of 1 to 5 nights: 1, 0, 0, 0 and 1 night off the preferred length.
        long[] off = { 1, 0, 0, 0, 1 };
        for (int n = 1; n <= 5; n++) {
            List<Object> cells = new ArrayList<>();
            for (int d = 0; d < n; d++) {
                cells.add(cell(A, MON.plusDays(d), NIGHT));
            }
            // A day duty right after the block does not lengthen it.
            cells.add(cell(A, MON.plusDays(n), DAY));
            VERIFIER.verifyThat(RosterConstraints::nightBlock).given(cells.toArray()).penalizesBy(off[n - 1]);
        }
        DutyCell last = cell(A, MON, NIGHT);
        last.setMonthEnd(true);
        VERIFIER.verifyThat(RosterConstraints::nightBlock).given(last).penalizesBy(0);
    }

    @Test
    void theNightShareIsACeilingOverTheWindow() {
        Staff s = staff(2, 0);
        s.setMix(new ShiftMix(Map.of(ShiftCategory.DAY, 0.7, ShiftCategory.NIGHT, 0.3), Map.of()));
        List<DutyCell> tenDays = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            DutyCell c = new DutyCell(s, LocalDate.of(2027, 3, 1 + i), true, List.of(DAY, NIGHT));
            c.setShift(DAY);
            tenDays.add(c);
        }
        // Window 1 (no months before), as 0.28.0: 3 nights expected, 0 planned.
        assertThat(RosterConstraints.mixDeviation(s, tenDays)).isEqualTo(2 * 30 * 30);
        // Two months before with 20 duties of the same length and no nights: the 9 nights missed are not made up,
        // and a month without nights is fine.
        s.setNightWindow(new Staff.NightWindow(2, 0, 20 * 720));
        assertThat(RosterConstraints.mixDeviation(s, tenDays)).isZero();
        // Up to the share of the window (9) plus the tolerance (1) nights are fine; the day share applies to the rest.
        for (int i = 0; i < 9; i++) {
            tenDays.get(i).setShift(NIGHT);
        }
        assertThat(RosterConstraints.mixDeviation(s, tenDays)).isZero();
        // 9 nights done before: the ceiling is 0 + 1, so 3 nights are 2 too many.
        s.setNightWindow(new Staff.NightWindow(2, 9, 20 * 720));
        for (int i = 3; i < 9; i++) {
            tenDays.get(i).setShift(DAY);
        }
        assertThat(RosterConstraints.mixDeviation(s, tenDays)).isEqualTo(20 * 20);
        s.setNightMixTolerance(0);
        assertThat(RosterConstraints.mixDeviation(s, tenDays)).isEqualTo(30 * 30);
    }

    @Test
    void theFloorMakesUpMissedNights() {
        Staff s = staff(2, 0);
        s.setMix(new ShiftMix(Map.of(ShiftCategory.DAY, 0.7, ShiftCategory.NIGHT, 0.3), Map.of()));
        s.setNightMixFloor(true);
        s.setNightMixTolerance(0);
        s.setNightWindow(new Staff.NightWindow(2, 0, 20 * 720));
        List<DutyCell> tenDays = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            DutyCell c = new DutyCell(s, LocalDate.of(2027, 3, 1 + i), true, List.of(DAY, NIGHT));
            c.setShift(i < 9 ? NIGHT : DAY);
            tenDays.add(c);
        }
        // As 0.28.0: 9 nights expected after none before.
        assertThat(RosterConstraints.mixDeviation(s, tenDays)).isZero();
        for (int i = 0; i < 10; i++) {
            tenDays.get(i).setShift(DAY);
        }
        assertThat(RosterConstraints.mixDeviation(s, tenDays)).isEqualTo(90 * 90);
    }

    @Test
    void theWindowTakesTheHistoryMonthsBeforeTheMonth() {
        List<HistoryEntry> history = List.of(entry("2027-01", 5, 100), entry("2027-02", 3, 120),
                entry("2026-12", 9, 150), entry("2027-03", 7, 160));
        YearMonth march = YearMonth.of(2027, 3);
        assertThat(RosterBuilder.nightWindow(history, march, 3)).isEqualTo(new Staff.NightWindow(2, 8, 220 * 60));
        assertThat(RosterBuilder.nightWindow(history, march, 1)).isEqualTo(Staff.NightWindow.NONE);
        assertThat(RosterBuilder.nightWindow(List.of(), march, 3)).isEqualTo(Staff.NightWindow.NONE);
    }

    private static HistoryEntry entry(String month, int nights, double hours) {
        return new HistoryEntry("A", month, HistoryEntry.PLAN, nights, 0, 0, 0, 0, 0, hours, null, null, 0, 0);
    }
}
