package app.dispofold.solver;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import app.dispofold.model.PlanAssignment;
import app.dispofold.model.ShiftCategory;
import app.dispofold.model.ShiftType;
import app.dispofold.planning.PlanningInput;

/**
 * Key figures of the blocks of a plan, as the control room counts them: single duties (a block of one day), single
 * free days (one day without duty and without absence between two duties), the mean block length, switches between
 * night and non-night duties (consecutive duties of a person, free days between them skipped) and the people with
 * one, and single nights (a night between two days without night). Duties of the previous month count as the start
 * of a block or a switch; only blocks, free days and switches that end in the month are counted.
 */
record PlanMetrics(int singleDuties, int singleFreeDays, double meanBlock, int switches, int peopleSwitching,
        int singleNights) {

    static PlanMetrics of(PlanningInput input, List<PlanAssignment> assignments) {
        YearMonth month = input.period().month();
        Map<String, ShiftCategory> category = new HashMap<>();
        for (ShiftType s : input.dataset().shifts()) {
            category.put(s.id(), s.category());
        }
        Map<String, TreeMap<LocalDate, Boolean>> duties = new HashMap<>();
        input.period().previousDuties().forEach(d -> {
            if (category.containsKey(d.shiftId())) {
                duties.computeIfAbsent(d.personId(), k -> new TreeMap<>()).put(d.date(),
                        category.get(d.shiftId()) == ShiftCategory.NIGHT);
            }
        });
        for (PlanAssignment a : assignments) {
            if (a.shiftId() != null && !a.compRest() && category.containsKey(a.shiftId())) {
                duties.computeIfAbsent(a.personId(), k -> new TreeMap<>()).put(a.date(),
                        category.get(a.shiftId()) == ShiftCategory.NIGHT);
            }
        }
        Set<String> absent = new HashSet<>();
        input.period().absences().forEach(a -> absent.add(a.personId() + a.date()));
        int singles = 0, freeDays = 0, blocks = 0, blockDays = 0, switches = 0, people = 0, singleNights = 0;
        for (Map.Entry<String, TreeMap<LocalDate, Boolean>> e : duties.entrySet()) {
            TreeMap<LocalDate, Boolean> days = e.getValue();
            int own = 0;
            LocalDate prev = null;
            for (Map.Entry<LocalDate, Boolean> d : days.entrySet()) {
                LocalDate date = d.getKey();
                boolean night = d.getValue();
                if (YearMonth.from(date).equals(month)) {
                    boolean before = days.containsKey(date.minusDays(1));
                    boolean after = days.containsKey(date.plusDays(1));
                    singles += !before && !after ? 1 : 0;
                    if (!after) {
                        int n = 1;
                        while (days.containsKey(date.minusDays(n))) {
                            n++;
                        }
                        blocks++;
                        blockDays += n;
                    }
                    if (night && !Boolean.TRUE.equals(days.get(date.minusDays(1)))
                            && !Boolean.TRUE.equals(days.get(date.plusDays(1)))) {
                        singleNights++;
                    }
                    if (prev != null && prev.plusDays(2).equals(date)
                            && !absent.contains(e.getKey() + prev.plusDays(1))) {
                        freeDays++;
                    }
                    if (prev != null && days.get(prev) != night) {
                        switches++;
                        own++;
                    }
                }
                prev = date;
            }
            people += own > 0 ? 1 : 0;
        }
        return new PlanMetrics(singles, freeDays, blocks == 0 ? 0 : (double) blockDays / blocks, switches, people,
                singleNights);
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "single duties %d, single free days %d, mean block %.2f, switches %d, people switching %d,"
                        + " single nights %d", singleDuties, singleFreeDays, meanBlock, switches, peopleSwitching,
                singleNights);
    }
}
