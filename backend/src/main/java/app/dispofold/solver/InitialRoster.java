package app.dispofold.solver;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Greedy starting roster for local search. Day by day it covers the open demand of every area with the duty
 * that covers most of it, preferring people who are furthest below their target hours, and keeps daily rest,
 * weekly rest, weekly hours and the maximum block length. Timefold's construction heuristic is not used for
 * this: it scores each cell against the monthly target and would fill the first days with everybody.
 */
final class InitialRoster {

    private final Roster roster;
    private final Map<Staff, DutyCell[]> byStaff = new HashMap<>();
    private final Map<Staff, Long> counted = new HashMap<>();
    private final Map<String, Area> segmentsByArea = new HashMap<>();

    /** An area's demand segments in time order (they do not overlap) with the staffing planned so far. */
    private static final class Area {
        final DemandSegment[] segments;
        final long[] ends;
        final int[] covered;

        Area(List<DemandSegment> list) {
            list.sort(Comparator.comparingLong(DemandSegment::start));
            segments = list.toArray(DemandSegment[]::new);
            ends = list.stream().mapToLong(DemandSegment::end).toArray();
            covered = new int[segments.length];
        }

        /** Index of the first segment that ends after {@code start}. */
        int first(long start) {
            int i = java.util.Arrays.binarySearch(ends, start);
            return i >= 0 ? i + 1 : -i - 1;
        }
    }
    private final long firstDay;
    private final int days;

    private InitialRoster(Roster roster) {
        this.roster = roster;
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (DutyCell c : roster.getCells()) {
            min = Math.min(min, c.getDay());
            max = Math.max(max, c.getDay());
        }
        this.firstDay = min;
        this.days = (int) (max - min + 1);
        for (DutyCell c : roster.getCells()) {
            byStaff.computeIfAbsent(c.getStaff(), s -> new DutyCell[days])[(int) (c.getDay() - firstDay)] = c;
            if (c.isInMonth()) {
                counted.merge(c.getStaff(), (long) c.getCountedMinutes(), Long::sum);
            }
        }
        Map<String, List<DemandSegment>> lists = new HashMap<>();
        for (DemandSegment s : roster.getSegments()) {
            lists.computeIfAbsent(s.areaId(), a -> new ArrayList<>()).add(s);
        }
        lists.forEach((area, list) -> segmentsByArea.put(area, new Area(list)));
        for (DutyCell c : roster.getCells()) {
            if (c.isWork()) {
                cover(c, 1);
            }
        }
    }

    /** Fills empty, unpinned cells of the month. Cells that already hold a shift are kept. */
    static void fill(Roster roster) {
        new InitialRoster(roster).run();
    }

    private void run() {
        List<String> areas = new ArrayList<>(segmentsByArea.keySet());
        // Areas with few qualified people first, so that they are not used up elsewhere.
        Map<String, Long> qualified = new HashMap<>();
        for (Staff s : roster.getStaff()) {
            s.getAreaIds().forEach(a -> qualified.merge(a, 1L, Long::sum));
        }
        areas.sort(Comparator.comparingLong((String a) -> qualified.getOrDefault(a, 0L)).thenComparing(a -> a));
        for (int d = 0; d < days; d++) {
            for (String area : areas) {
                while (assignBest(d, area)) {
                    // repeat until the area's demand of this day is covered or nobody can take a duty
                }
            }
        }
        for (Staff staff : roster.getStaff()) {
            planCompRest(staff);
        }
        fillToTarget();
    }

    /** Compensatory rest days on free days, spread over the month, as long as the target is not reached. */
    private void planCompRest(Staff staff) {
        DutyCell[] cells = byStaff.get(staff);
        if (cells == null) {
            return;
        }
        List<DutyCell> free = new ArrayList<>();
        for (DutyCell c : cells) {
            if (c != null && c.isInMonth() && !c.isPinned() && c.getShift() == null
                    && c.getOptions().stream().anyMatch(ShiftOption::isCompRest)) {
                free.add(c);
            }
        }
        long planned = roster.getCells().stream().filter(c -> c.getStaff() == staff && c.isInMonth() && c.isCompRest())
                .count();
        int wanted = (int) Math.min(staff.getCompRestBalance() - planned, free.size());
        for (int i = 0; i < wanted && remaining(staff) >= staff.getCreditMinutes(); i++) {
            DutyCell c = free.get((int) ((i + 0.5) * free.size() / wanted));
            if (c.getShift() == null) {
                c.setShift(c.getOptions().stream().filter(ShiftOption::isCompRest).findFirst().orElseThrow());
                counted.merge(staff, (long) c.getCountedMinutes(), Long::sum);
            }
        }
    }

    /**
     * Adds duties for people below their target, where the area is least overstaffed, until the next duty would
     * overshoot the target by more than half.
     */
    private void fillToTarget() {
        List<Staff> open = new ArrayList<>(roster.getStaff());
        while (!open.isEmpty()) {
            Staff staff = open.stream()
                    .max(Comparator.comparingDouble(s -> (double) remaining(s) / Math.max(1, s.getTargetMinutes())))
                    .orElseThrow();
            if (!addDuty(staff)) {
                open.remove(staff);
            }
        }
    }

    private boolean addDuty(Staff staff) {
        DutyCell[] cells = byStaff.get(staff);
        if (cells == null) {
            return false;
        }
        long remaining = remaining(staff);
        DutyCell bestCell = null;
        ShiftOption bestShift = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int d = 0; d < days; d++) {
            DutyCell cell = cells[d];
            if (cell == null || !cell.isInMonth() || cell.isPinned() || cell.getShift() != null) {
                continue;
            }
            for (ShiftOption option : cell.getOptions()) {
                if (option.isCompRest() || 2L * remaining < option.getDurationMinutes()) {
                    continue;
                }
                long start = cell.startOf(option);
                long end = cell.endOf(option);
                if (!allowed(cells, d, staff, option, start, end)) {
                    continue;
                }
                double score = -surplus(option.getAreaId(), start, end) + blockBonus(cells, d, staff);
                if (score > bestScore) {
                    bestScore = score;
                    bestCell = cell;
                    bestShift = option;
                }
            }
        }
        if (bestCell == null) {
            return false;
        }
        bestCell.setShift(bestShift);
        counted.merge(staff, (long) bestCell.getCountedMinutes(), Long::sum);
        cover(bestCell, 1);
        return true;
    }

    /** Average staffing above the required staffing over the duty, weighted by minutes. */
    private double surplus(String area, long start, long end) {
        Area a = segmentsByArea.get(area);
        if (a == null) {
            return 100;
        }
        double sum = 0;
        long minutes = 0;
        for (int i = a.first(start); i < a.segments.length && a.segments[i].start() < end; i++) {
            DemandSegment s = a.segments[i];
            long m = Math.min(s.end(), end) - Math.max(s.start(), start);
            sum += m * (a.covered[i] + 1.0 - s.required()) / Math.max(1, s.required());
            minutes += m;
        }
        return minutes == 0 ? 100 : sum / minutes;
    }

    private boolean assignBest(int d, String area) {
        LocalDate date = LocalDate.ofEpochDay(firstDay + d);
        boolean open = false;
        Area a = segmentsByArea.get(area);
        for (int i = 0; i < a.segments.length; i++) {
            if (a.segments[i].day().equals(date) && a.covered[i] < a.segments[i].required()) {
                open = true;
                break;
            }
        }
        if (!open) {
            return false;
        }
        DutyCell bestCell = null;
        ShiftOption bestShift = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (DutyCell[] cells : byStaff.values()) {
            DutyCell cell = cells[d];
            if (cell == null || !cell.isInMonth() || cell.isPinned() || cell.getShift() != null) {
                continue;
            }
            Staff staff = cell.getStaff();
            for (ShiftOption option : cell.getOptions()) {
                if (option.isCompRest() || !area.equals(option.getAreaId())) {
                    continue;
                }
                long start = cell.startOf(option);
                long end = cell.endOf(option);
                long gain = gain(area, start, end);
                if (gain == 0 || !allowed(cells, d, staff, option, start, end)) {
                    continue;
                }
                double score = 4.0 * gain / option.getDurationMinutes()
                        + 2.0 * remaining(staff) / Math.max(1, staff.getTargetMinutes())
                        + blockBonus(cells, d, staff);
                if (score > bestScore) {
                    bestScore = score;
                    bestCell = cell;
                    bestShift = option;
                }
            }
        }
        if (bestCell == null) {
            return false;
        }
        bestCell.setShift(bestShift);
        counted.merge(bestCell.getStaff(), (long) bestCell.getCountedMinutes(), Long::sum);
        cover(bestCell, 1);
        return true;
    }

    private long remaining(Staff staff) {
        return staff.getTargetMinutes() - counted.getOrDefault(staff, 0L);
    }

    /** Continuing a block is good, starting a block right after a single day off is not. */
    private static double blockBonus(DutyCell[] cells, int d, Staff staff) {
        boolean prev = d > 0 && cells[d - 1] != null && cells[d - 1].isWork();
        if (prev) {
            return run(cells, d - 1) < staff.getLimits().minBlock() ? 0.5 : 0.2;
        }
        boolean beforePrev = d > 1 && cells[d - 2] != null && cells[d - 2].isWork();
        return beforePrev ? -0.3 : 0;
    }

    /** Number of consecutive work days ending at day index {@code d}. */
    private static int run(DutyCell[] cells, int d) {
        int n = 0;
        while (d >= 0 && cells[d] != null && cells[d].isWork()) {
            n++;
            d--;
        }
        return n;
    }

    private long gain(String area, long start, long end) {
        Area a = segmentsByArea.get(area);
        long gain = 0;
        for (int i = a.first(start); i < a.segments.length && a.segments[i].start() < end; i++) {
            DemandSegment s = a.segments[i];
            if (a.covered[i] < s.required()) {
                gain += Math.min(s.end(), end) - Math.max(s.start(), start);
            }
        }
        return gain;
    }

    private boolean allowed(DutyCell[] cells, int d, Staff staff, ShiftOption option, long start, long end) {
        Staff.Limits limits = staff.getLimits();
        // The maximum duty length is checked against the nominal shift length, like the hard rule.
        if (option.getDurationMinutes() > limits.maxDuty()) {
            return false;
        }
        for (int i = Math.max(0, d - 2); i <= Math.min(days - 1, d + 2); i++) {
            DutyCell other = cells[i];
            if (i == d || other == null || !other.isWork()) {
                continue;
            }
            if (other.getEnd() <= start ? start - other.getEnd() < limits.dailyRest()
                    : other.getStart() - end < limits.dailyRest()) {
                return false;
            }
        }
        int before = d > 0 ? run(cells, d - 1) : 0;
        int after = 0;
        for (int i = d + 1; i < days && cells[i] != null && cells[i].isWork(); i++) {
            after++;
        }
        if (before + 1 + after > limits.maxBlock()) {
            return false;
        }
        for (Week w : roster.getWeeks()) {
            if (w.end() <= start || w.start() >= end) {
                continue;
            }
            List<long[]> duties = new ArrayList<>();
            long minutes = Math.min(end, w.end()) - Math.max(start, w.start());
            duties.add(new long[] { start, end });
            for (DutyCell c : cells) {
                if (c != null && c.isWork() && c.getEnd() > w.start() && c.getStart() < w.end()) {
                    duties.add(new long[] { c.getStart(), c.getEnd() });
                    minutes += c.overlap(w.start(), w.end());
                }
            }
            if (minutes > limits.maxWeek() || longestRest(w, duties) < limits.weeklyRest()) {
                return false;
            }
        }
        return true;
    }

    private static long longestRest(Week w, List<long[]> duties) {
        duties.sort(Comparator.comparingLong(a -> a[0]));
        long free = w.start();
        long longest = 0;
        for (long[] duty : duties) {
            longest = Math.max(longest, duty[0] - free);
            free = Math.max(free, duty[1]);
        }
        return Math.max(longest, w.end() - free);
    }

    private void cover(DutyCell c, int delta) {
        Area a = segmentsByArea.get(c.getAreaId());
        if (a == null) {
            return;
        }
        for (int i = a.first(c.getStart()); i < a.segments.length && a.segments[i].start() < c.getEnd(); i++) {
            a.covered[i] += delta;
        }
    }
}
