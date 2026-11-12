package app.dispofold.solver;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.dispofold.model.AbsenceType;
import app.dispofold.model.ConstraintHit;
import app.dispofold.model.ConstraintInfo;
import app.dispofold.model.ConstraintSummary;
import app.dispofold.model.CoverageGap;
import app.dispofold.model.CoverageSegment;
import app.dispofold.model.Holiday;
import app.dispofold.model.HistoryEntry;
import app.dispofold.model.Person;
import app.dispofold.model.PersonStats;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.Rules;
import app.dispofold.planning.PlanningInput;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;

/** Key figures, coverage and constraint matches of a roster. */
public final class RosterAnalyzer {

    private RosterAnalyzer() {
    }

    public static PlanAnalysis analysis(PlanningInput input, Roster roster, ScoreExplainer.Explanation score) {
        Map<String, Long> weights = rules(input).weights();
        List<ConstraintSummary> constraints = new ArrayList<>();
        for (ConstraintInfo info : ConstraintCatalog.ALL) {
            ScoreExplainer.Total c = score.total(info.id());
            constraints.add(new ConstraintSummary(info.id(), info.level(),
                    ConstraintCatalog.weight(info, weights), level(c.score(), info.level()), c.matches().size()));
        }
        List<PersonStats> people = new ArrayList<>();
        for (Staff s : roster.getStaff()) {
            people.add(stats(input, roster, s.getId()));
        }
        return new PlanAnalysis(score.score().toString(), hardViolations(score), constraints, gaps(roster), people);
    }

    public static int hardViolations(ScoreExplainer.Explanation score) {
        int n = 0;
        for (ConstraintInfo info : ConstraintCatalog.ALL) {
            if (ConstraintCatalog.HARD.equals(info.level())) {
                n += score.total(info.id()).matches().size();
            }
        }
        return n;
    }

    /** All matches whose justification names the person, in catalogue order. */
    public static List<ConstraintHit> hits(ScoreExplainer.Explanation score, Roster roster, String personId) {
        List<ConstraintHit> hits = new ArrayList<>(fairnessHits(score, roster, personId));
        for (ConstraintInfo info : ConstraintCatalog.ALL) {
            for (ScoreExplainer.Match m : score.total(info.id()).matches()) {
                if (personId.equals(m.hit().personId())) {
                    hits.add(new ConstraintHit(info.id(), info.level(), m.hit().date(),
                            level(m.score(), info.level()), "hit." + info.id(), m.hit().params()));
                }
            }
        }
        hits.sort(java.util.Comparator.comparing(ConstraintHit::date,
                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
        return hits;
    }

    /** What one fairness comparison counts for a person in this month. */
    private record Counter(String id, java.util.function.Predicate<Staff> eligible,
            java.util.function.ToIntFunction<Staff.History> history,
            java.util.function.Function<List<DutyCell>, Integer> count) {
    }

    private static final List<Counter> COUNTERS = List.of(
            new Counter(ConstraintCatalog.FAIR_NIGHTS, s -> s.getFairness().nights(), Staff.History::nights,
                    cells -> (int) cells.stream().filter(c -> c.getShift().isNight()).count()),
            new Counter(ConstraintCatalog.FAIR_WEEKENDS, s -> true, Staff.History::weekends,
                    cells -> (int) cells.stream()
                            .filter(c -> c.isWeekendDay() && !RosterConstraints.startsBeforeMonth(c))
                            .map(c -> c.getWeekday() == 7 ? c.getDay() - 1 : c.getDay()).distinct().count()),
            new Counter(ConstraintCatalog.FAIR_HOLIDAYS, s -> true, Staff.History::holidays,
                    cells -> (int) cells.stream().filter(DutyCell::isHoliday).count()),
            new Counter(ConstraintCatalog.FAIR_UNPOPULAR, s -> s.getFairness().unpopular(), Staff.History::unpopular,
                    cells -> (int) cells.stream().filter(c -> c.getShift().isUnpopular()).count()));

    /**
     * The fairness comparisons have one match for everybody. For a person they are explained by the deviation of the
     * person's load from the mean: a hit only where the person carries more than the mean, with the person's share
     * of the penalty (by squared deviation).
     */
    static List<ConstraintHit> fairnessHits(ScoreExplainer.Explanation score, Roster roster, String personId) {
        Map<Staff, List<DutyCell>> work = new HashMap<>();
        for (DutyCell c : roster.getCells()) {
            if (c.isInMonth() && c.isWork()) {
                work.computeIfAbsent(c.getStaff(), k -> new ArrayList<>()).add(c);
            }
        }
        List<ConstraintHit> hits = new ArrayList<>();
        for (Counter counter : COUNTERS) {
            Map<Staff, Long> loads = new java.util.LinkedHashMap<>();
            for (Staff s : roster.getStaff()) {
                int count = counter.count().apply(work.getOrDefault(s, List.of()));
                if (s.getFairness().weight() > 0 && (counter.eligible().test(s) || count > 0)) {
                    loads.put(s, (count + (long) counter.history().applyAsInt(s.getHistory()))
                            * s.getFairness().weight());
                }
            }
            Staff person = loads.keySet().stream().filter(s -> s.getId().equals(personId)).findFirst().orElse(null);
            if (person == null) {
                continue;
            }
            double mean = loads.values().stream().mapToLong(Long::longValue).average().orElse(0);
            double squares = loads.values().stream().mapToDouble(l -> (l - mean) * (l - mean)).sum();
            long load = loads.get(person);
            if (load <= mean || squares == 0) {
                continue;
            }
            long total = level(score.total(counter.id()).score(), ConstraintCatalog.SOFT);
            Map<String, Object> params = new java.util.LinkedHashMap<>();
            params.put("count", counter.count().apply(work.getOrDefault(person, List.of())));
            params.put("history", counter.history().applyAsInt(person.getHistory()));
            params.put("load", load);
            params.put("mean", Math.round(mean));
            hits.add(new ConstraintHit(counter.id(), ConstraintCatalog.SOFT, null,
                    Math.round(total * (load - mean) * (load - mean) / squares), "hit." + counter.id(),
                    java.util.Collections.unmodifiableMap(params)));
        }
        return hits;
    }

    static long level(HardMediumSoftScore score, String level) {
        return switch (level) {
            case ConstraintCatalog.HARD -> score.hardScore();
            case ConstraintCatalog.MEDIUM -> score.mediumScore();
            default -> score.softScore();
        };
    }

    // Coverage

    public static List<CoverageSegment> coverage(Roster roster, String areaId) {
        List<DutyCell> duties = roster.getCells().stream()
                .filter(c -> c.isWork() && areaId.equals(c.getAreaId())).toList();
        List<CoverageSegment> result = new ArrayList<>();
        for (DemandSegment s : roster.getSegments()) {
            if (!s.areaId().equals(areaId)) {
                continue;
            }
            int planned = 0;
            for (DutyCell c : duties) {
                if (c.getStart() < s.end() && c.getEnd() > s.start()) {
                    planned++;
                }
            }
            result.add(new CoverageSegment(areaId, s.from(), s.to(), s.required(),
                    s.max(), planned));
        }
        return result;
    }

    /** Uncovered segments; touching segments with the same staffing are merged. */
    public static List<CoverageGap> gaps(Roster roster) {
        Set<String> areas = new java.util.LinkedHashSet<>();
        for (DemandSegment s : roster.getSegments()) {
            areas.add(s.areaId());
        }
        List<CoverageGap> gaps = new ArrayList<>();
        for (String area : areas) {
            CoverageGap open = null;
            for (CoverageSegment s : coverage(roster, area)) {
                if (s.planned() >= s.required()) {
                    continue;
                }
                if (open != null && open.to().equals(s.from()) && open.required() == s.required()
                        && open.planned() == s.planned()) {
                    open = new CoverageGap(area, open.from(), s.to(), s.required(), s.planned());
                    gaps.set(gaps.size() - 1, open);
                } else {
                    open = new CoverageGap(area, s.from(), s.to(), s.required(), s.planned());
                    gaps.add(open);
                }
            }
        }
        return gaps;
    }

    // Person figures

    public static PersonStats stats(PlanningInput input, Roster roster, String personId) {
        Rules rules = rules(input);
        YearMonth month = roster.getMonth();
        Staff staff = roster.getStaff().stream().filter(s -> s.getId().equals(personId)).findFirst().orElseThrow();
        Set<LocalDate> holidays = new HashSet<>();
        for (Holiday h : input.dataset().holidays() == null ? List.<Holiday>of() : input.dataset().holidays()) {
            holidays.add(h.date());
        }
        Map<String, AbsenceType> types = new HashMap<>();
        if (input.dataset().absenceTypes() != null) {
            input.dataset().absenceTypes().forEach(t -> types.put(t.code(), t));
        }
        Person person = input.dataset().people().stream().filter(p -> p.id().equals(personId)).findFirst()
                .orElseThrow();
        List<HistoryEntry> history = input.dataset().history() == null ? List.of() : input.dataset().history();
        double accountBefore = RosterBuilder.accountBefore(month, person, history);

        List<DutyCell> cells = roster.getCells().stream()
                .filter(c -> c.isInMonth() && c.getStaff().getId().equals(personId)).toList();
        long plannedMinutes = 0;
        long creditedMinutes = 0;
        long chargedMinutes = 0;
        int available = 0;
        int duties = 0, nights = 0, holidayDuties = 0, unpopular = 0, wishes = 0, wishesMet = 0;
        int compRestPlanned = 0, compRestEarned = 0, singleDays = 0;
        Set<Long> weekendsWorked = new HashSet<>();
        Set<Long> fullWeekends = new HashSet<>();
        boolean[] worked = new boolean[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            DutyCell c = cells.get(i);
            if (c.getWeekend() >= 0 && !(c.getWeekday() == 7 && c.getDate().getDayOfMonth() == 1)) {
                fullWeekends.add(c.getWeekend());
            }
            if (!c.isOutsideEmployment() && c.getAbsenceCode() == null) {
                available++;
            }
            AbsenceType type = c.getAbsenceCode() == null ? null : types.get(c.getAbsenceCode());
            if (type != null && type.chargesAccount() && !c.isWork()) {
                chargedMinutes += c.getCountedMinutes();
            }
            if (c.isWork()) {
                worked[i] = true;
                duties++;
                plannedMinutes += c.getDurationMinutes();
                nights += c.getShift().isNight() ? 1 : 0;
                unpopular += c.getShift().isUnpopular() ? 1 : 0;
                if (c.isHoliday()) {
                    holidayDuties++;
                    if (rules.holidayWorkEarnsCompRest() && c.getWeekday() != 7) {
                        compRestEarned++;
                    }
                }
                if (c.isWeekendDay() && !RosterConstraints.startsBeforeMonth(c)) {
                    weekendsWorked.add(c.getWeekday() == 7 ? c.getDay() - 1 : c.getDay());
                }
            } else {
                creditedMinutes += c.getCountedMinutes();
            }
            if (c.isCompRest() || (c.getAbsenceCode() != null && types.containsKey(c.getAbsenceCode())
                    && types.get(c.getAbsenceCode()).compensatoryRest())) {
                compRestPlanned++;
            }
            wishes += c.getWishes().size();
            wishesMet += c.getWishes().size() - c.unmetWishes();
        }
        for (int i = 0; i < cells.size(); i++) {
            boolean before = i == 0 ? previousWorked(roster, personId, month) : worked[i - 1];
            boolean after = i < cells.size() - 1 && worked[i + 1];
            if (worked[i] && !before && !after && i < cells.size() - 1) {
                singleDays++;
            }
        }
        int weekendsOff = 0;
        for (long saturday : fullWeekends) {
            if (!weekendsWorked.contains(saturday)) {
                weekendsOff++;
            }
        }
        double target = staff.getCreditMinutes() * RosterBuilder.workdays(month, holidays, person) / 60.0;
        double planned = plannedMinutes / 60.0;
        double credited = creditedMinutes / 60.0;
        double charged = chargedMinutes / 60.0;
        int balance = RosterBuilder.compRestBefore(month, person, history);
        Staff.History h = staff.getHistory();
        Staff.Average a = staff.getAverage();
        double average = a.weeks() <= 0 ? 0 : (a.minutes() + plannedMinutes) / 60.0 / a.weeks();
        return new PersonStats(personId, round(target), round(credited), round(planned), accountBefore,
                round(accountBefore + planned + credited - target - charged), duties, nights, weekendsWorked.size(),
                weekendsOff, holidayDuties, unpopular, wishes, wishesMet, compRestPlanned, compRestEarned,
                balance - compRestPlanned + compRestEarned, singleDays, h.nights(), h.weekends(), h.holidays(),
                h.unpopular(), h.wishes(), h.wishesMet(), available, round(charged), round(average), a.complete());
    }

    private static boolean previousWorked(Roster roster, String personId, YearMonth month) {
        LocalDate last = month.atDay(1).minusDays(1);
        return roster.getCells().stream().anyMatch(c -> !c.isInMonth() && c.getDate().equals(last)
                && c.getStaff().getId().equals(personId) && c.isWork());
    }

    private static double round(double hours) {
        return Math.round(hours * 100) / 100.0;
    }

    private static Rules rules(PlanningInput input) {
        return input.rules() != null ? input.rules() : Rules.defaults();
    }

    // Conversion

    public static List<PlanAssignment> assignments(Roster roster) {
        List<PlanAssignment> result = new ArrayList<>();
        for (DutyCell c : roster.getCells()) {
            if (c.isInMonth()) {
                result.add(new PlanAssignment(c.getStaff().getId(), c.getDate(),
                        c.isWork() ? c.getShift().getId() : null, c.isCompRest(), c.isPlannerPinned()));
            }
        }
        return result;
    }
}
