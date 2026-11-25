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

    /**
     * Staffing against the minimum demand of the solved input (exact roster): planned duty hours of the month over the
     * minimum demand in person hours, person hours above the minimum inside the demand, the targets (scaled, work part)
     * over the minimum demand and the people ending more than 8 h below their real target.
     */
    static String staffing(PlanningInput solved) {
        Roster roster = new RosterBuilder(solved, true).build();
        long required = 0, above = 0, overUpper = 0, room = 0, planned = 0, targets = 0;
        for (DemandSegment s : roster.getSegments()) {
            required += s.required() * s.minutes();
            long n = roster.getCells().stream().filter(c -> c.isWork() && c.getAreaId().equals(s.areaId())
                    && c.getStart() < s.end() && c.getEnd() > s.start()).count();
            above += Math.max(0, n - s.required()) * s.minutes();
            overUpper += Math.max(0, n - s.upper()) * s.minutes();
            room += (s.upper() - s.required()) * s.minutes();
        }
        // the excess of each segment shared out over the duties in it, by kind of shift: the base shifts (an id
        // without a digit) and their variants
        Map<String, Double> byKind = new TreeMap<>();
        for (DemandSegment s : roster.getSegments()) {
            List<DutyCell> in = roster.getCells().stream().filter(c -> c.isWork() && c.getAreaId().equals(s.areaId())
                    && c.getStart() < s.end() && c.getEnd() > s.start()).toList();
            if (in.size() > s.required()) {
                double each = (double) (in.size() - s.required()) * s.minutes() / in.size() / 60;
                for (DutyCell c : in) {
                    byKind.merge(c.getShift().getId().matches(".*\\d.*") ? "variant" : "base", each, Double::sum);
                }
            }
        }
        Map<String, Double> dutyHours = new TreeMap<>();
        for (DutyCell c : roster.getCells()) {
            if (c.isInMonth() && c.isWork()) {
                dutyHours.merge(c.getShift().getId().matches(".*\\d.*") ? "variant" : "base",
                        c.getDurationMinutes() / 60.0, Double::sum);
            }
        }
        long duties = roster.getCells().stream().filter(c -> c.isInMonth() && c.isWork()).count();
        long shortDuties = roster.getCells().stream().filter(c -> c.isInMonth() && c.isWork()
                && c.getDurationMinutes() < 600).count();
        String byArea = String.format(java.util.Locale.ROOT, "duties %d, under 10 h %d; ", duties, shortDuties)
                + String.format(java.util.Locale.ROOT, "excess hours base %.0f of %.0f duty hours, variants %.0f of %.0f",
                byKind.getOrDefault("base", 0.0), dutyHours.getOrDefault("base", 0.0),
                byKind.getOrDefault("variant", 0.0), dutyHours.getOrDefault("variant", 0.0));
        Map<Staff, Long> counted = new HashMap<>();
        for (DutyCell c : roster.getCells()) {
            if (c.isInMonth()) {
                counted.merge(c.getStaff(), (long) c.getCountedMinutes(), Long::sum);
                if (c.isWork()) {
                    planned += c.getDurationMinutes();
                }
            }
        }
        int below = 0;
        for (Staff s : roster.getStaff()) {
            targets += s.getScaledTargetMinutes();
            below += counted.getOrDefault(s, 0L) < s.getTargetMinutes() - 8 * 60 ? 1 : 0;
        }
        return String.format(java.util.Locale.ROOT,
                "planned over minimum %+.1f %%, above minimum inside demand %+.1f %%, scaled targets %.0f h"
                        + " (minimum demand %.0f h), people more than 8 h below target %d, above maximum %.0f h,"
                        + " maximum over minimum %.0f h",
                100.0 * (planned - required) / required, 100.0 * above / required, targets / 60.0, required / 60.0,
                below, overUpper / 60.0, room / 60.0) + " " + byArea;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "single duties %d, single free days %d, mean block %.2f, switches %d, people switching %d,"
                        + " single nights %d", singleDuties, singleFreeDays, meanBlock, switches, peopleSwitching,
                singleNights);
    }
}
