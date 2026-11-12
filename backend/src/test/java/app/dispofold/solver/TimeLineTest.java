package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import app.dispofold.model.ShiftCategory;

import ai.timefold.solver.core.api.score.stream.test.ConstraintVerifier;

import org.junit.jupiter.api.Test;

/** Real minutes across the changes to and from summer time (Europe/Vienna, 2027-03-28 and 2027-10-31). */
class TimeLineTest {

    private static final TimeLine VIENNA = new TimeLine(ZoneId.of("Europe/Vienna"));
    private static final LocalDate SPRING = LocalDate.of(2027, 3, 28);
    private static final LocalDate AUTUMN = LocalDate.of(2027, 10, 31);
    private static final ShiftOption NIGHT = RosterConstraintsTest.option("N", "DR", "20:00", 600,
            ShiftCategory.NIGHT, true);
    private static final ShiftOption LATE = RosterConstraintsTest.option("L", "AN", "12:00", 600,
            ShiftCategory.LATE, false);
    private static final ShiftOption EARLY = RosterConstraintsTest.option("E", "AN", "05:20", 600,
            ShiftCategory.EARLY, false);
    private static final Staff A = RosterConstraintsTest.staff("A", Set.of("AN", "DR"));

    private static DutyCell cell(LocalDate date, ShiftOption shift) {
        DutyCell c = new DutyCell(A, date, true, List.of(shift));
        c.setShift(shift);
        c.setTimes(VIENNA);
        return c;
    }

    @Test
    void daysHave23And25Hours() {
        assertThat(VIENNA.midnight(SPRING.plusDays(1)) - VIENNA.midnight(SPRING)).isEqualTo(23 * 60);
        assertThat(VIENNA.midnight(AUTUMN.plusDays(1)) - VIENNA.midnight(AUTUMN)).isEqualTo(25 * 60);
        assertThat(VIENNA.midnight(SPRING.plusDays(3)) - VIENNA.midnight(SPRING.plusDays(2))).isEqualTo(24 * 60);
        assertThat(VIENNA.regular(SPRING.minusDays(3))).isTrue();
        assertThat(VIENNA.regular(SPRING.minusDays(1))).isFalse();
        assertThat(VIENNA.local(VIENNA.at(AUTUMN, 5 * 60))).isEqualTo(LocalDateTime.of(2027, 10, 31, 5, 0));
    }

    @Test
    void nightDutiesLastOneHourLessOrMore() {
        assertThat(cell(SPRING.minusDays(1), NIGHT).getDurationMinutes()).isEqualTo(540);
        assertThat(cell(AUTUMN.minusDays(1), NIGHT).getDurationMinutes()).isEqualTo(660);
        assertThat(cell(AUTUMN.plusDays(1), NIGHT).getDurationMinutes()).isEqualTo(600);
        assertThat(cell(AUTUMN.minusDays(1), NIGHT).getCountedMinutes()).isEqualTo(660);
    }

    @Test
    void maxDutyLengthUsesTheNominalLength() {
        ShiftOption twelve = RosterConstraintsTest.option("N12", "DR", "19:00", 720, ShiftCategory.NIGHT, true);
        DutyCell autumn = cell(AUTUMN.minusDays(1), twelve);
        // 13 real hours are worked and counted, but the 12 h shift is not too long.
        assertThat(autumn.getDurationMinutes()).isEqualTo(780);
        assertThat(autumn.getCountedMinutes()).isEqualTo(780);
        ConstraintVerifier.build(new RosterConstraints(), Roster.class, DutyCell.class)
                .verifyThat(RosterConstraints::maxDutyLength).given(autumn).penalizesBy(0);
    }

    @Test
    void dailyRestCountsRealMinutes() {
        ConstraintVerifier<RosterConstraints, Roster> verifier = ConstraintVerifier.build(new RosterConstraints(),
                Roster.class, DutyCell.class);
        // Late duty until 22:00, early duty at 05:20: 7:20 h on the clock, 8:20 h in the autumn night.
        verifier.verifyThat(RosterConstraints::dailyRest)
                .given(cell(AUTUMN.minusDays(1), LATE), cell(AUTUMN, EARLY)).penalizesBy(660 - 500);
        // In the spring night only 6:20 h.
        verifier.verifyThat(RosterConstraints::dailyRest)
                .given(cell(SPRING.minusDays(1), LATE), cell(SPRING, EARLY)).penalizesBy(660 - 380);
    }
}
