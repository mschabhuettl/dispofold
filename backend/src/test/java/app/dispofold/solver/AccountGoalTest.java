package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.Test;

import app.dispofold.model.AccountBalancing;
import app.dispofold.model.AveragingPeriod;
import app.dispofold.model.HistoryEntry;
import app.dispofold.model.Person;
import app.dispofold.model.Rules;

/**
 * The goal of a month from the target and the hours account. Target 168 h (10,080 min) throughout; tolerance 10 %, so
 * the goal is at most 184.8 h.
 */
class AccountGoalTest {

    static final int TARGET = 168 * 60;

    static Rules rules(AccountBalancing balancing, Boolean carryMinus, AveragingPeriod period) {
        Rules r = Rules.defaults();
        return new Rules(r.minDailyRestHours(), r.minWeeklyRestHours(), r.maxDutyHours(), r.maxWeekHours(),
                r.maxAverageWeekHours(), r.minBlock(), r.maxBlock(), r.maxWishesPerPerson(),
                r.holidayWorkEarnsCompRest(), r.nightStart(), r.nightEnd(), r.nightMinHours(), r.accountCarryShare(),
                r.historyMonths(), r.weights(), r.timeZone(), r.averagingWeeks(), r.maxOverTargetPercent(),
                r.minFreeSundays(), r.nightDistribution(), null, null, period, balancing, carryMinus);
    }

    static final Rules DEFAULT = Rules.defaults();

    static Person person(double account) {
        return new Person("P1", "A", "B", "AB", 40, List.of("AN"), account, 0, List.of(), null, null, null, null, null);
    }

    /** The history entry of the month before {@code month} with this account after it. */
    static List<HistoryEntry> after(String month, double account) {
        return List.of(new HistoryEntry("P1", month, HistoryEntry.PLAN, 0, 0, 0, 0, 0, 0, 160, account, 0, 0, 0, 40.0));
    }

    static AccountGoal goal(Rules rules, String month, double imported, List<HistoryEntry> history) {
        return AccountGoal.of(rules, YearMonth.parse(month), person(imported), history, TARGET);
    }

    @Test
    void theDefaultEvensOutTheAccountByTheEndOfTheHalfYear() {
        assertThat(DEFAULT.balancesByPeriodEnd()).isTrue();
        // February: +30 h after January, five months left: 6 h less
        AccountGoal february = goal(DEFAULT, "2027-02", 0, after("2027-01", 30));
        assertThat(february.accountBefore()).isEqualTo(30);
        assertThat(february.goalMinutes()).isEqualTo(TARGET - 6 * 60);
        assertThat(february.restMinutes()).isZero();
        // April: three months left, a third: -12 h gives 4 h more
        assertThat(goal(DEFAULT, "2027-04", 0, after("2027-03", -12)).goalMinutes()).isEqualTo(TARGET + 4 * 60);
        // June: the whole rest
        assertThat(goal(DEFAULT, "2027-06", 0, after("2027-05", 9.5)).goalMinutes()).isEqualTo(TARGET - 570);
        // December too (second half-year)
        assertThat(goal(DEFAULT, "2027-12", 0, after("2027-11", -3)).goalMinutes()).isEqualTo(TARGET + 180);
        // the hard cap is the goal plus 10 %
        AccountGoal june = goal(DEFAULT, "2027-06", 0, after("2027-05", 9.5));
        assertThat(june.limitMinutes()).isEqualTo((int) Math.round((TARGET - 570) * 1.1));
    }

    @Test
    void aNewPeriodStartsAtZeroAndOnlyACarriedMinusBalanceComesAlong() {
        // July: +20 h after June is settled outside the plan, the goal is the target
        AccountGoal july = goal(DEFAULT, "2027-07", 0, after("2027-06", 20));
        assertThat(july.accountBefore()).isZero();
        assertThat(july.goalMinutes()).isEqualTo(TARGET);
        assertThat(AccountGoal.closedBalance(DEFAULT, YearMonth.of(2027, 7), person(0), after("2027-06", 20)))
                .isEqualTo(20);
        // January: -12 h are dropped by default, carried with the setting (a sixth: 2 h more)
        assertThat(goal(DEFAULT, "2027-01", 0, after("2026-12", -12)).accountBefore()).isZero();
        Rules carry = rules(null, true, null);
        AccountGoal january = goal(carry, "2027-01", 0, after("2026-12", -12));
        assertThat(january.accountBefore()).isEqualTo(-12);
        assertThat(january.goalMinutes()).isEqualTo(TARGET + 120);
        // a plus balance never carries
        assertThat(goal(carry, "2027-01", 0, after("2026-12", 12)).accountBefore()).isZero();
        // the imported account counts as the closed balance when the first planned month starts a period
        assertThat(goal(DEFAULT, "2027-07", 35, List.of()).accountBefore()).isZero();
        assertThat(AccountGoal.closedBalance(DEFAULT, YearMonth.of(2027, 7), person(35), List.of())).isEqualTo(35);
        // in the middle of a period: the end of the previous one from the history, else unknown
        assertThat(AccountGoal.closedBalance(DEFAULT, YearMonth.of(2027, 9), person(0), after("2027-06", 7)))
                .isEqualTo(7);
        assertThat(AccountGoal.closedBalance(DEFAULT, YearMonth.of(2027, 9), person(0), List.of())).isNull();
    }

    @Test
    void aRestThatDoesNotFitIsANoteNotAViolation() {
        // June with -30 h: 198 h would be needed, the goal stops at 184.8 h, 13.2 h are left
        AccountGoal minus = goal(DEFAULT, "2027-06", 0, after("2027-05", -30));
        assertThat(minus.capMinutes()).isEqualTo(11088);
        assertThat(minus.goalMinutes()).isEqualTo(11088);
        assertThat(minus.restMinutes()).isEqualTo(TARGET + 30 * 60 - 11088);
        assertThat(minus.limitMinutes()).isEqualTo(11088);
        // June with +200 h: the goal cannot go below 0, 32 h of plus remain
        AccountGoal plus = goal(DEFAULT, "2027-06", 0, after("2027-05", 200));
        assertThat(plus.goalMinutes()).isZero();
        assertThat(plus.restMinutes()).isEqualTo(TARGET - 200 * 60);
        assertThat(plus.limitMinutes()).isZero();
    }

    @Test
    void theFixedShareAndTheRollingPeriodWorkAsBefore() {
        for (Rules r : List.of(rules(AccountBalancing.MONTHLY_SHARE, null, null),
                rules(null, null, AveragingPeriod.ROLLING))) {
            assertThat(r.balancesByPeriodEnd()).isFalse();
            // a quarter of the account, the account carries across July, the cap is the goal plus 10 %
            AccountGoal july = goal(r, "2027-07", 0, after("2027-06", 20));
            assertThat(july.accountBefore()).isEqualTo(20);
            assertThat(july.goalMinutes()).isEqualTo(TARGET - 5 * 60);
            assertThat(july.limitMinutes()).isEqualTo((int) Math.round((TARGET - 5 * 60) * 1.1));
            assertThat(july.restMinutes()).isZero();
            assertThat(AccountGoal.closedBalance(r, YearMonth.of(2027, 7), person(0), after("2027-06", 20))).isNull();
        }
    }
}
