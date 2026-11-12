package app.dispofold.solver;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import app.dispofold.model.Absence;
import app.dispofold.model.AbsenceType;
import app.dispofold.model.Dataset;
import app.dispofold.model.DemandRule;
import app.dispofold.model.FamilyRule;
import app.dispofold.model.FixedAssignment;
import app.dispofold.model.HistoryEntry;
import app.dispofold.model.Holiday;
import app.dispofold.model.Period;
import app.dispofold.model.Person;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.PreviousDuty;
import app.dispofold.model.Restriction;
import app.dispofold.model.RestrictionKind;
import app.dispofold.model.Rules;
import app.dispofold.model.ShiftCategory;
import app.dispofold.model.ShiftType;
import app.dispofold.model.Wish;
import app.dispofold.planning.PlanningInput;

/** Turns a {@link PlanningInput} into a {@link Roster}. All lists are built in a stable order. */
public final class RosterBuilder {

    private final PlanningInput input;
    private final boolean exact;
    private final Dataset dataset;
    private final Period period;
    private final Rules rules;
    private final YearMonth month;
    private final Set<LocalDate> holidays = new HashSet<>();
    private final Map<String, ShiftOption> options = new LinkedHashMap<>();
    private final ShiftOption compRest = ShiftOption.compRest();
    private final TimeLine timeLine;

    /** A roster to solve: assignments that break the value ranges are dropped unless pinned. */
    public RosterBuilder(PlanningInput input) {
        this(input, false);
    }

    /**
     * @param exact keep every given assignment as it is, also where it breaks a rule, so that an analysis
     *        shows exactly the roster the planners see
     */
    public RosterBuilder(PlanningInput input, boolean exact) {
        this.input = input;
        this.exact = exact;
        this.dataset = input.dataset();
        this.period = input.period();
        this.rules = input.rules() != null ? input.rules() : Rules.defaults();
        this.month = period.month();
        this.timeLine = new TimeLine(
                ZoneId.of(rules.timeZone() != null ? rules.timeZone() : Rules.DEFAULT_TIME_ZONE));
        for (Holiday h : list(dataset.holidays())) {
            holidays.add(h.date());
        }
        for (ShiftType s : list(dataset.shifts())) {
            options.put(s.id(), option(s, rules));
        }
    }

    public Roster build() {
        List<Staff> staff = new ArrayList<>();
        List<DutyCell> cells = new ArrayList<>();
        Map<String, AbsenceType> absenceTypes = new HashMap<>();
        for (AbsenceType t : list(dataset.absenceTypes())) {
            absenceTypes.put(t.code(), t);
        }
        Map<String, Absence> absences = new HashMap<>();
        for (Absence a : list(period.absences())) {
            absences.putIfAbsent(key(a.personId(), a.date()), a);
        }
        Map<String, FixedAssignment> fixed = new HashMap<>();
        for (FixedAssignment a : list(period.fixed())) {
            fixed.putIfAbsent(key(a.personId(), a.date()), a);
        }
        Map<String, PlanAssignment> assignments = new HashMap<>();
        for (PlanAssignment a : list(input.assignments())) {
            assignments.putIfAbsent(key(a.personId(), a.date()), a);
        }
        Map<String, List<PreviousDuty>> previous = groupByPerson(list(period.previousDuties()), PreviousDuty::personId);
        Map<String, List<Restriction>> restrictions = groupByPerson(list(dataset.restrictions()),
                Restriction::personId);
        Map<String, List<Wish>> wishes = groupByPerson(list(period.wishes()), Wish::personId);
        Map<String, List<HistoryEntry>> history = groupByPerson(list(dataset.history()), HistoryEntry::personId);

        List<Person> people = new ArrayList<>(list(dataset.people()));
        people.sort(Comparator.comparing(Person::id));
        for (Person p : people) {
            List<Wish> counted = countedWishes(wishes.getOrDefault(p.id(), List.of()), rules.maxWishesPerPerson());
            int preEnteredCompRest = 0;
            for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
                Absence a = absences.get(key(p.id(), d));
                if (a != null && absenceTypes.containsKey(a.typeCode())
                        && absenceTypes.get(a.typeCode()).compensatoryRest()) {
                    preEnteredCompRest++;
                }
            }
            List<HistoryEntry> own = history.getOrDefault(p.id(), List.of());
            int balance = compRestBefore(month, p, own);
            List<PreviousDuty> before = previous.getOrDefault(p.id(), List.of());
            Staff s = staff(p, own, counted.size(), Math.max(0, balance - preEnteredCompRest),
                    available(p, absences), previousCounts(before));
            staff.add(s);
            cells.addAll(previousCells(s, before));
            cells.addAll(monthCells(p, s, restrictions.getOrDefault(p.id(), List.of()), counted, absences,
                    absenceTypes, fixed, assignments));
        }
        cells.forEach(c -> c.setTimes(timeLine));
        sortForConstruction(cells, staff.size());
        List<ShiftOption> all = new ArrayList<>(options.values());
        all.add(compRest);
        return new Roster(month, timeLine, staff, all, segments(), weeks(), cells,
                ConstraintCatalog.overrides(rules.weights()));
    }

    /**
     * The construction heuristic fills cells in list order. Day by day, with the order of people rotated every
     * day, so that the first people in the list do not take all duties of the month.
     */
    private static void sortForConstruction(List<DutyCell> cells, int people) {
        Map<Staff, Integer> index = new HashMap<>();
        for (DutyCell c : cells) {
            index.putIfAbsent(c.getStaff(), index.size());
        }
        int step = Math.max(1, people / 3);
        cells.sort(Comparator.comparing(DutyCell::getDate)
                .thenComparingInt(c -> Math.floorMod(index.get(c.getStaff()) - (int) c.getDay() * step, people)));
    }

    // Staff

    private Staff staff(Person p, List<HistoryEntry> history, int wishCount, int compRestBalance, int available,
            Map<String, Integer> previousCounts) {
        int credit = (int) Math.round(p.weeklyHours() / 5 * 60);
        int target = credit * workdays(p) - (int) Math.round(rules.accountCarryShare() * accountBefore(p, history) * 60);
        Staff.Limits limits = new Staff.Limits(
                (int) Math.round(Math.max(Rules.MIN_DAILY_REST_HOURS, rules.minDailyRestHours()) * 60),
                (int) Math.round(rules.minWeeklyRestHours() * 60), (int) Math.round(rules.maxDutyHours() * 60),
                (int) Math.round(rules.maxWeekHours() * 60), rules.minBlock(), rules.maxBlock());
        Staff.History h = history(history, month, rules.historyMonths());
        boolean nights = false;
        boolean unpopular = false;
        for (ShiftOption o : options.values()) {
            if (list(p.areaIds()).contains(o.getAreaId())) {
                nights |= o.isNight();
                unpopular |= o.isUnpopular();
            }
        }
        Staff.Fairness fairness = Staff.Fairness.of(p.weeklyHours(),
                available == 0 ? 0 : available + h.months() * MONTH_DAYS, nights, unpopular);
        return new Staff(p.id(), p.weeklyHours(), Set.copyOf(list(p.areaIds())), credit, target, compRestBalance,
                limits, h, wishCount, fairness, average(history), previousCounts);
    }

    /** Average length of a month in days. */
    static final double MONTH_DAYS = 365.25 / 12;

    /**
     * Hours account at the start of the month: the account after the previous month from the history (an imported
     * actual value wins over a closed plan, see the store), else the person's account of the import.
     */
    double accountBefore(Person p, List<HistoryEntry> history) {
        return accountBefore(month, p, history);
    }

    static double accountBefore(YearMonth month, Person p, List<HistoryEntry> history) {
        String previous = month.minusMonths(1).toString();
        return history.stream().filter(e -> p.id().equals(e.personId()) && previous.equals(e.month())
                && e.hoursAccountAfter() != null).map(HistoryEntry::hoursAccountAfter).findFirst()
                .orElse(p.hoursAccount());
    }

    /** Open compensatory rest days at the start of the month, like {@link #accountBefore}. */
    static int compRestBefore(YearMonth month, Person p, List<HistoryEntry> history) {
        String previous = month.minusMonths(1).toString();
        return history.stream().filter(e -> p.id().equals(e.personId()) && previous.equals(e.month())
                && e.compRestBalanceAfter() != null).map(HistoryEntry::compRestBalanceAfter).findFirst()
                .orElse(p.compRestBalance());
    }

    /** Days of the month the person is employed and not absent. */
    private int available(Person p, Map<String, Absence> absences) {
        int n = 0;
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            if (p.employedOn(d) && !absences.containsKey(key(p.id(), d))) {
                n++;
            }
        }
        return n;
    }

    /** Duties of the previous month per area and per area and category, for variety across months. */
    private Map<String, Integer> previousCounts(List<PreviousDuty> duties) {
        Map<String, Integer> counts = new HashMap<>();
        for (PreviousDuty d : duties) {
            ShiftOption o = options.get(d.shiftId());
            if (o != null && YearMonth.from(d.date()).equals(month.minusMonths(1))) {
                counts.merge(o.getAreaId(), 1, Integer::sum);
                counts.merge(Staff.varietyKey(o), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Worked hours of the history months inside the averaging period. The rule is hard when the history covers
     * the whole period before this month, otherwise the average is only shown.
     */
    private Staff.Average average(List<HistoryEntry> history) {
        int months = (int) Math.round(rules.averagingWeeks() * 7 / MONTH_DAYS) - 1;
        long minutes = 0;
        int found = 0;
        for (HistoryEntry e : history) {
            YearMonth m = YearMonth.parse(e.month());
            if (m.isBefore(month) && !m.isBefore(month.minusMonths(months))) {
                minutes += Math.round(e.hours() * 60);
                found++;
            }
        }
        double weeks = (found * MONTH_DAYS + month.lengthOfMonth()) / 7;
        return new Staff.Average(minutes, weeks, months > 0 && found >= months,
                (int) Math.round(rules.maxAverageWeekHours() * 60));
    }

    static Staff.History history(List<HistoryEntry> entries, YearMonth month, int months) {
        int nights = 0, weekends = 0, hol = 0, unpopular = 0, wishes = 0, met = 0, counted = 0;
        for (HistoryEntry e : entries) {
            YearMonth m = YearMonth.parse(e.month());
            if (m.isBefore(month) && !m.isBefore(month.minusMonths(months))) {
                nights += e.nights();
                weekends += e.weekendsWorked();
                hol += e.holidays();
                unpopular += e.unpopular();
                wishes += e.wishes();
                met += e.wishesMet();
                counted++;
            }
        }
        return new Staff.History(nights, weekends, hol, unpopular, wishes, met, counted);
    }

    /** The first {@code max} wishes by date. */
    static List<Wish> countedWishes(List<Wish> wishes, int max) {
        List<Wish> sorted = new ArrayList<>(wishes);
        sorted.sort(Comparator.comparing(Wish::date));
        return sorted.subList(0, Math.min(Math.max(0, max), sorted.size()));
    }

    /** Monday to Friday that are not public holidays. */
    static int workdays(YearMonth month, Set<LocalDate> holidays) {
        return workdays(month, holidays, null);
    }

    /** Workdays of the person's employment in this month (the target is pro rata). */
    int workdays(Person p) {
        return workdays(month, holidays, p);
    }

    static int workdays(YearMonth month, Set<LocalDate> holidays, Person p) {
        int n = 0;
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() <= 5 && !holidays.contains(d) && (p == null || p.employedOn(d))) {
                n++;
            }
        }
        return n;
    }

    // Cells

    private List<DutyCell> previousCells(Staff s, List<PreviousDuty> duties) {
        Map<LocalDate, DutyCell> byDate = new java.util.TreeMap<>();
        // Rest, weekly rules, blocks and rotation look back at most to the Monday of the first week or a week.
        LocalDate first = month.atDay(1);
        LocalDate from = first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        from = from.isBefore(first.minusDays(7)) ? from : first.minusDays(7);
        for (PreviousDuty d : duties) {
            ShiftOption o = options.get(d.shiftId());
            if (o == null || !d.date().isBefore(first) || d.date().isBefore(from) || byDate.containsKey(d.date())) {
                continue;
            }
            DutyCell c = new DutyCell(s, d.date(), false, List.of(o));
            c.setShift(o);
            c.setPinned(true);
            if (d.date().equals(first.minusDays(1)) && d.date().getDayOfWeek() == DayOfWeek.SATURDAY) {
                c.setWeekend(d.date().toEpochDay());
            }
            byDate.put(d.date(), c);
        }
        return new ArrayList<>(byDate.values());
    }

    private List<DutyCell> monthCells(Person p, Staff s, List<Restriction> restrictions, List<Wish> wishes,
            Map<String, Absence> absences, Map<String, AbsenceType> absenceTypes,
            Map<String, FixedAssignment> fixed, Map<String, PlanAssignment> assignments) {
        List<DutyCell> result = new ArrayList<>();
        for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
            int weekday = d.getDayOfWeek().getValue();
            Set<String> never = new HashSet<>();
            Set<String> onRequest = new HashSet<>();
            for (Restriction r : restrictions) {
                if (r.appliesOn(d)) {
                    Set<String> target = r.kind() == RestrictionKind.NEVER ? never : onRequest;
                    if (r.shiftId() != null) {
                        target.add(r.shiftId());
                    } else {
                        options.values().stream().filter(o -> o.getCategory() == r.category())
                                .forEach(o -> target.add(o.getId()));
                    }
                }
            }
            List<ShiftOption> range = new ArrayList<>();
            for (ShiftOption o : options.values()) {
                if (s.getAreaIds().contains(o.getAreaId()) && !never.contains(o.getId())) {
                    range.add(o);
                }
            }
            if (s.getCompRestBalance() > 0) {
                range.add(compRest);
            }
            DutyCell c = new DutyCell(s, d, true, range);
            c.setOutsideEmployment(!p.employedOn(d));
            c.setNever(Set.copyOf(never));
            c.setOnRequest(Set.copyOf(onRequest));
            c.setHoliday(holidays.contains(d));
            c.setWorkday(weekday <= 5 && !c.isHoliday());
            c.setMonthEnd(d.equals(month.atEndOfMonth()));
            // A weekend that starts in the previous month counts with the previous duties; one that ends in the
            // next month is not known yet.
            if (weekday == 6 && d.plusDays(1).getMonth() == d.getMonth()) {
                c.setWeekend(d.toEpochDay());
            } else if (weekday == 7) {
                c.setWeekend(d.toEpochDay() - 1);
            }
            LocalDate date = d;
            c.setWishes(wishes.stream().filter(w -> w.date().equals(date)).toList());
            family(c, list(p.familyRules()), weekday);

            Absence absence = absences.get(key(p.id(), d));
            if (absence != null) {
                AbsenceType type = absenceTypes.get(absence.typeCode());
                c.setAbsence(absence.typeCode(), type != null && type.reducesTarget());
            }
            FixedAssignment fix = fixed.get(key(p.id(), d));
            PlanAssignment assignment = assignments.get(key(p.id(), d));
            ShiftOption given = assignment == null ? null
                    : assignment.compRest() ? compRest
                    : assignment.shiftId() == null ? null : options.get(assignment.shiftId());
            if (assignment != null && assignment.pinned()) {
                // A cell pinned by planners wins over the period's fixed duty.
                pin(c, given);
                c.setPlannerPinned(true);
                missing(c, assignment.compRest() ? null : assignment.shiftId());
            } else if (fix != null) {
                pin(c, fix.shiftId() == null ? null : options.get(fix.shiftId()));
                c.setPlannerPinned(true);
                missing(c, fix.shiftId());
            } else if (exact && given != null) {
                if (!range.contains(given)) {
                    range.add(given);
                }
                c.setShift(given);
            } else if (absence != null || !p.employedOn(d)) {
                pin(c, null);
            } else if (given != null && range.contains(given)) {
                c.setShift(given);
            }
            result.add(c);
        }
        return result;
    }

    /** A fixed shift that no longer exists (e.g. after a new import) is reported, not turned into a day off. */
    private void missing(DutyCell c, String shiftId) {
        if (shiftId != null && !options.containsKey(shiftId)) {
            c.setMissingShiftId(shiftId);
        }
    }

    private static void pin(DutyCell c, ShiftOption value) {
        c.setShift(value);
        c.setPinned(true);
        c.setOptions(value == null ? List.of() : List.of(value));
    }

    private static void family(DutyCell c, List<FamilyRule> rules, int weekday) {
        int latestEnd = -1;
        int earliestStart = -1;
        boolean noNights = false;
        for (FamilyRule r : rules) {
            if (r.weekdays() == null || !r.weekdays().contains(weekday)) {
                continue;
            }
            if (r.latestEnd() != null) {
                int m = Times.minuteOfDay(r.latestEnd());
                latestEnd = latestEnd < 0 ? m : Math.min(latestEnd, m);
            }
            if (r.earliestStart() != null) {
                earliestStart = Math.max(earliestStart, Times.minuteOfDay(r.earliestStart()));
            }
            noNights |= r.noNights();
        }
        c.setFamily(latestEnd, earliestStart, noNights);
    }

    // Shifts

    static ShiftOption option(ShiftType s, Rules rules) {
        int start = Times.minuteOfDay(s.start());
        int end = Times.minuteOfDay(s.end());
        int duration = end > start ? end - start : end + DutyCell.DAY_MINUTES - start;
        boolean night = nightMinutes(start, duration, rules.nightStart(), rules.nightEnd())
                >= Math.round(rules.nightMinHours() * 60);
        ShiftCategory category = s.category() != null ? s.category() : category(start, duration, night);
        return new ShiftOption(s.id(), s.areaId(), start, duration, category, night, s.unpopular(), false);
    }

    /** Minutes of the duty inside the night window (which may cross midnight). */
    static int nightMinutes(int start, int duration, LocalTime nightStart, LocalTime nightEnd) {
        int from = Times.minuteOfDay(nightStart);
        int to = Times.minuteOfDay(nightEnd);
        int length = to > from ? to - from : to + DutyCell.DAY_MINUTES - from;
        int total = 0;
        for (int day = -1; day <= 1; day++) {
            int windowStart = day * DutyCell.DAY_MINUTES + from;
            total += Math.max(0, Math.min(start + duration, windowStart + length) - Math.max(start, windowStart));
        }
        return total;
    }

    /** Category when the catalogue does not give one: night, early (start before 7:00), day (end by 19:00), late. */
    static ShiftCategory category(int start, int duration, boolean night) {
        if (night) {
            return ShiftCategory.NIGHT;
        }
        if (start < 7 * 60) {
            return ShiftCategory.EARLY;
        }
        return start + duration <= 19 * 60 ? ShiftCategory.DAY : ShiftCategory.LATE;
    }

    // Weeks and demand

    private List<Week> weeks() {
        List<Week> weeks = new ArrayList<>();
        LocalDate monday = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (; !monday.isAfter(month.atEndOfMonth()); monday = monday.plusWeeks(1)) {
            weeks.add(new Week(monday.toString(), timeLine.midnight(monday), timeLine.midnight(monday.plusWeeks(1))));
        }
        return weeks;
    }

    private record Window(LocalDate day, long start, long end, int min, Integer max) {
    }

    private List<DemandSegment> segments() {
        Map<String, List<DemandRule>> rulesByArea = groupByPerson(list(dataset.demand()), DemandRule::areaId);
        Map<String, List<ShiftOption>> shiftsByArea = new LinkedHashMap<>();
        for (ShiftOption o : options.values()) {
            shiftsByArea.computeIfAbsent(o.getAreaId(), k -> new ArrayList<>()).add(o);
        }
        List<DemandSegment> result = new ArrayList<>();
        for (Map.Entry<String, List<DemandRule>> e : rulesByArea.entrySet()) {
            String area = e.getKey();
            List<Window> windows = new ArrayList<>();
            for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
                for (DemandRule r : rulesFor(e.getValue(), d, holidays.contains(d))) {
                    int from = Times.minuteOfDay(r.from());
                    int to = Times.minuteOfDay(r.to());
                    long start = timeLine.at(d, from);
                    long end = timeLine.at(d, to > from ? to : to + DutyCell.DAY_MINUTES);
                    windows.add(new Window(d, start, end, r.min(), r.max()));
                }
            }
            TreeSet<Long> points = new TreeSet<>();
            for (Window w : windows) {
                points.add(w.start());
                points.add(w.end());
            }
            for (LocalDate d = month.atDay(1).minusDays(1); !d.isAfter(month.atEndOfMonth().plusDays(1));
                    d = d.plusDays(1)) {
                for (ShiftOption o : shiftsByArea.getOrDefault(area, List.of())) {
                    points.add(timeLine.at(d, o.getStartMinute()));
                    points.add(timeLine.at(d, o.getStartMinute() + o.getDurationMinutes()));
                }
            }
            Long previous = null;
            for (long p : points) {
                if (previous != null) {
                    addSegment(result, area, windows, previous, p);
                }
                previous = p;
            }
        }
        return result;
    }

    /** Overlapping windows add up. A segment without any window is not demanded. */
    private void addSegment(List<DemandSegment> result, String area, List<Window> windows, long from, long to) {
        int min = 0;
        Integer max = 0;
        LocalDate day = null;
        for (Window w : windows) {
            if (w.start() <= from && w.end() >= to) {
                min += w.min();
                max = max == null || w.max() == null ? null : max + w.max();
                day = day == null ? w.day() : day;
            }
        }
        if (day != null && (min > 0 || max != null)) {
            result.add(new DemandSegment(area + "@" + from, area, day, from, to, min, max, timeLine.local(from),
                    timeLine.local(to)));
        }
    }

    /** Date rules replace everything; on public holidays HOL rules replace the weekday rules if the area has any. */
    static List<DemandRule> rulesFor(List<DemandRule> areaRules, LocalDate date, boolean holiday) {
        List<DemandRule> dated = areaRules.stream().filter(r -> date.equals(r.date())).toList();
        if (!dated.isEmpty()) {
            return dated;
        }
        boolean hasHolidayRules = areaRules.stream().anyMatch(r -> r.date() == null && hasDay(r, "HOL"));
        String dayType = holiday && hasHolidayRules ? "HOL" : WEEKDAYS[date.getDayOfWeek().getValue() - 1];
        return areaRules.stream().filter(r -> r.date() == null && hasDay(r, dayType)).toList();
    }

    private static final String[] WEEKDAYS = {"MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"};

    private static boolean hasDay(DemandRule r, String day) {
        return r.days() != null && r.days().contains(day);
    }

    // Helpers

    private static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static String key(String personId, LocalDate date) {
        return personId + "/" + date;
    }

    private static <T> Map<String, List<T>> groupByPerson(List<T> items, java.util.function.Function<T, String> key) {
        Map<String, List<T>> map = new LinkedHashMap<>();
        for (T item : items) {
            map.computeIfAbsent(key.apply(item), k -> new ArrayList<>()).add(item);
        }
        return map;
    }
}
