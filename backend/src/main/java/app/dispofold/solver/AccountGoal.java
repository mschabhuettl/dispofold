package app.dispofold.solver;

import java.time.YearMonth;
import java.util.List;

import app.dispofold.model.HistoryEntry;
import app.dispofold.model.Person;
import app.dispofold.model.Rules;

/**
 * The goal of a month from the target and the hours account, as the solver plans it and the analysis shows it.
 * <ul>
 * <li>Fixed share ({@code MONTHLY_SHARE}, and always with the rolling averaging period): goal = target - share ×
 * account; the hard cap is the goal plus {@code maxOverTargetPercent}. The account carries from month to
 * month.</li>
 * <li>By the end of the period ({@code PERIOD_END}, the default with the calendar half-year): the account starts
 * at 0 in January and July (a minus balance of the closed period only when the rules carry it; a plus balance never,
 * it is settled outside the plan); goal = target - account / months left in the half-year, this one included
 * (January a sixth, June the whole rest). The goal stays between 0 and the target plus
 * {@code maxOverTargetPercent}; what does not fit is the rest, a note on the plan and no rule violation. The hard
 * cap is the smaller of goal and target plus that share.</li>
 * </ul>
 *
 * @param accountBefore the account the month starts with (after the reset at the start of a period)
 * @param goalMinutes the goal of the month
 * @param limitMinutes the hard cap of the counted minutes ({@code maxMonthHours})
 * @param restMinutes with PERIOD_END: the part of the correction the goal could not take (positive: hours that
 *        should be worked more, negative: plus hours that could not be reduced); 0 otherwise
 * @param capMinutes with PERIOD_END: the most the goal may be (target plus the share); 0 otherwise
 */
public record AccountGoal(double accountBefore, int goalMinutes, int limitMinutes, int restMinutes, int capMinutes) {

    /** Months of the calendar half-year still to plan, this one included: 6 in January and July, 1 in June. */
    static int monthsLeft(YearMonth month) {
        return 6 - (month.getMonthValue() - 1) % 6;
    }

    static boolean periodStart(YearMonth month) {
        return monthsLeft(month) == 6;
    }

    /** The goal of a person whose target of the month is {@code targetMinutes}. */
    static AccountGoal of(Rules rules, YearMonth month, Person p, List<HistoryEntry> history, int targetMinutes) {
        double before = accountBefore(rules, month, p, history);
        double over = 1 + rules.overTargetPercent() / 100;
        if (!rules.balancesByPeriodEnd()) {
            int goal = targetMinutes - (int) Math.round(rules.accountCarryShare() * before * 60);
            return new AccountGoal(before, goal, (int) Math.max(0, Math.round(goal * over)), 0, 0);
        }
        int need = targetMinutes - (int) Math.round(before * 60 / monthsLeft(month));
        int cap = (int) Math.round(Math.max(0, targetMinutes) * over);
        int goal = Math.max(0, Math.min(cap, need));
        int limit = (int) Math.max(0, Math.round(Math.min(goal, targetMinutes) * over));
        return new AccountGoal(before, goal, Math.max(limit, goal), need - goal, cap);
    }

    /**
     * The account at the start of the month: the account after the previous month (history, else the person's
     * account of the import); by the end of the period it starts at 0 in January and July, or with the minus balance
     * of the closed period when the rules carry it.
     */
    static double accountBefore(Rules rules, YearMonth month, Person p, List<HistoryEntry> history) {
        double raw = RosterBuilder.accountBefore(month, p, history);
        if (rules.balancesByPeriodEnd() && periodStart(month)) {
            return raw < 0 && rules.carriesMinusRest() ? raw : 0;
        }
        return raw;
    }

    /**
     * By the end of the period: the account at the end of the previous half-year (what was settled outside the plan
     * or carried), from the history, at the start of a period also the person's account of the import; null when
     * unknown or with the fixed share.
     */
    static Double closedBalance(Rules rules, YearMonth month, Person p, List<HistoryEntry> history) {
        if (!rules.balancesByPeriodEnd()) {
            return null;
        }
        YearMonth start = month.minusMonths(6 - monthsLeft(month));
        if (start.equals(month)) {
            return RosterBuilder.accountBefore(month, p, history);
        }
        String end = start.minusMonths(1).toString();
        return history.stream().filter(e -> p.id().equals(e.personId()) && end.equals(e.month())
                && e.hoursAccountAfter() != null).map(HistoryEntry::hoursAccountAfter).findFirst().orElse(null);
    }
}
