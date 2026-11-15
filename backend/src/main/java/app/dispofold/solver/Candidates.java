package app.dispofold.solver;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.dispofold.model.Candidate;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;

/**
 * People who could be asked to take an uncovered duty. The solver never assigns a duty that is only on request,
 * so these are the people for whom an overlapping shift of the area is on request that day, whose day is free
 * (no duty, no absence, employed, not fixed by planners) and for whom the duty adds no hard violation.
 */
final class Candidates {

    private Candidates() {
    }

    static List<Candidate> find(Roster roster, String areaId, LocalDateTime from, LocalDateTime to,
            ScoreExplainer scorer) {
        TimeLine timeLine = roster.getTimeLine();
        long start = timeLine.at(from.toLocalDate(), from.getHour() * 60 + from.getMinute());
        long end = timeLine.at(to.toLocalDate(), to.getHour() * 60 + to.getMinute());
        Map<Staff, List<DutyCell>> cellsByStaff = new LinkedHashMap<>();
        for (DutyCell c : roster.getCells()) {
            cellsByStaff.computeIfAbsent(c.getStaff(), s -> new ArrayList<>()).add(c);
        }
        List<Candidate> result = new ArrayList<>();
        for (Staff staff : roster.getStaff()) {
            if (!staff.getAreaIds().contains(areaId)) {
                continue;
            }
            List<DutyCell> own = cellsByStaff.getOrDefault(staff, List.of());
            // Hard rules only depend on the person's own cells, so the check scores a roster of this person alone.
            Roster single = null;
            long hardBefore = 0;
            for (DutyCell c : own) {
                if (!c.isInMonth() || c.isPinned() || c.getShift() != null || c.getAbsenceCode() != null
                        || c.isOutsideEmployment()) {
                    continue;
                }
                List<ShiftOption> requested = roster.getShiftOptions().stream()
                        .filter(o -> !o.isCompRest() && areaId.equals(o.getAreaId())
                                && c.getOnRequest().contains(o.getId()))
                        .toList();
                if (requested.isEmpty()) {
                    continue;
                }
                // These shifts are not in the cell's value range (the solver must not choose them): add them for
                // the check, with their real times on days with a change of the clock.
                List<ShiftOption> range = c.getOptions();
                List<ShiftOption> widened = new ArrayList<>(range);
                widened.addAll(requested);
                c.setOptions(widened);
                c.setTimes(timeLine);
                try {
                    for (ShiftOption o : requested) {
                        if (c.startOf(o) >= end || c.endOf(o) <= start) {
                            continue;
                        }
                        if (single == null) {
                            single = new Roster(roster.getMonth(), timeLine, List.of(staff),
                                    roster.getShiftOptions(), List.of(), roster.getWeeks(), own,
                                    roster.getConstraintWeightOverrides());
                            hardBefore = scorer.score(single).hardScore();
                        }
                        HardMediumSoftScore after;
                        c.setShift(o);
                        try {
                            after = scorer.score(single);
                        } finally {
                            c.setShift(null);
                        }
                        if (after.hardScore() >= hardBefore) {
                            result.add(new Candidate(staff.getId(), c.getDate(), o.getId(), hours(counted(own)),
                                    hours(staff.getTargetMinutes())));
                        }
                    }
                } finally {
                    c.setOptions(range);
                    c.setTimes(timeLine);
                }
            }
        }
        result.sort(Comparator.comparingDouble((Candidate k) -> k.plannedHours() - k.targetHours())
                .thenComparing(Candidate::personId).thenComparing(Candidate::shiftId));
        return result;
    }

    private static long counted(List<DutyCell> cells) {
        return cells.stream().filter(DutyCell::isInMonth).mapToLong(DutyCell::getCountedMinutes).sum();
    }

    private static double hours(long minutes) {
        return Math.round(minutes / 6.0) / 10.0;
    }
}
