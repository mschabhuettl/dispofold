package app.dispofold.solver;

import static ai.timefold.solver.core.api.score.stream.ConstraintCollectors.count;
import static ai.timefold.solver.core.api.score.stream.ConstraintCollectors.countBi;
import static ai.timefold.solver.core.api.score.stream.ConstraintCollectors.loadBalance;
import static ai.timefold.solver.core.api.score.stream.ConstraintCollectors.sum;
import static ai.timefold.solver.core.api.score.stream.ConstraintCollectors.toConnectedRanges;
import static ai.timefold.solver.core.api.score.stream.ConstraintCollectors.toConsecutiveSequences;
import static ai.timefold.solver.core.api.score.stream.Joiners.equal;
import static ai.timefold.solver.core.api.score.stream.Joiners.lessThan;
import static ai.timefold.solver.core.api.score.stream.Joiners.overlapping;

import java.time.LocalDate;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.Constraint;
import ai.timefold.solver.core.api.score.stream.ConstraintFactory;
import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.core.api.score.stream.common.ConnectedRange;
import ai.timefold.solver.core.api.score.stream.common.ConnectedRangeChain;
import ai.timefold.solver.core.api.score.stream.common.Sequence;
import ai.timefold.solver.core.api.score.stream.common.SequenceChain;
import ai.timefold.solver.core.api.score.stream.uni.UniConstraintStream;

/**
 * All planning rules. Weights come from {@link Roster#getConstraintWeightOverrides()}; the score levels are
 * hard (law and qualification), medium (uncovered demand in person minutes) and soft (preferences).
 */
public class RosterConstraints implements ConstraintProvider {

    private static final HardMediumSoftScore HARD = HardMediumSoftScore.ONE_HARD;
    private static final HardMediumSoftScore MEDIUM = HardMediumSoftScore.ONE_MEDIUM;
    private static final HardMediumSoftScore SOFT = HardMediumSoftScore.ONE_SOFT;

    @Override
    public Constraint[] defineConstraints(ConstraintFactory f) {
        return new Constraint[] {
                dailyRest(f), weeklyRest(f), maxDutyLength(f), maxWeekHours(f), averageWeekHours(f),
                qualification(f), absence(f), employment(f), neverRestriction(f), compRestBalance(f), missingShift(f),
                demand(f),
                targetHours(f), fairNights(f), fairWeekends(f), fairHolidays(f), fairUnpopular(f), wishes(f),
                blockLength(f), isolatedDayOff(f), forwardRotation(f), splitWeekend(f), compRestUse(f),
                shiftVariety(f), areaVariety(f), family(f), onRequest(f), overstaffing(f), uselessDuty(f)
        };
    }

    private static UniConstraintStream<DutyCell> work(ConstraintFactory f) {
        return f.forEach(DutyCell.class).filter(DutyCell::isWork);
    }

    private static UniConstraintStream<DutyCell> monthWork(ConstraintFactory f) {
        return f.forEach(DutyCell.class).filter(c -> c.isInMonth() && c.isWork());
    }

    // Hard

    Constraint dailyRest(ConstraintFactory f) {
        return work(f)
                .join(work(f), equal(DutyCell::getStaff), lessThan(DutyCell::getDay),
                        // b starts before a's rest is over (also inside a, e.g. after a long night).
                        overlapping(DutyCell::getStart, a -> a.getEnd() + a.getStaff().getLimits().dailyRest(),
                                DutyCell::getStart, b -> b.getStart() + 1))
                .filter((a, b) -> a.isInMonth() || b.isInMonth())
                .penalize(HARD, (a, b) -> a.getEnd() + a.getStaff().getLimits().dailyRest() - b.getStart())
                .justifyWith((a, b, s) -> hit(b, "restMinutes", Math.max(0, b.getStart() - a.getEnd()), "previousShiftId",
                        a.getShift().getId(), "previousDate", a.getDate().toString()))
                .asConstraint(ConstraintCatalog.DAILY_REST);
    }

    Constraint weeklyRest(ConstraintFactory f) {
        return work(f)
                .join(Week.class, overlapping(DutyCell::getStart, DutyCell::getEnd, Week::start, Week::end))
                .groupBy((c, w) -> c.getStaff(), (c, w) -> w,
                        toConnectedRanges((c, w) -> c, DutyCell::getStart, DutyCell::getEnd))
                .filter((s, w, chain) -> longestRest(w, chain) < s.getLimits().weeklyRest())
                .penalize(HARD, (s, w, chain) -> s.getLimits().weeklyRest() - longestRest(w, chain))
                .justifyWith((s, w, chain, score) -> Hit.of(s.getId(), weekStart(w), "restMinutes",
                        longestRest(w, chain), "weekStart", weekStart(w).toString()))
                .asConstraint(ConstraintCatalog.WEEKLY_REST);
    }

    /** Longest stretch without duty inside the week; duties reaching outside the week are cut at its edges. */
    static long longestRest(Week week, ConnectedRangeChain<DutyCell, Long, Long> chain) {
        long free = week.start();
        long longest = 0;
        for (ConnectedRange<DutyCell, Long, Long> range : chain.getConnectedRanges()) {
            longest = Math.max(longest, range.getStart() - free);
            free = Math.max(free, range.getEnd());
        }
        return Math.max(longest, week.end() - free);
    }

    /**
     * The nominal length of the shift counts, not the real minutes: a regular 12 h night shift lasts 13 real hours
     * in the night the clock goes back, which is not a violation.
     */
    Constraint maxDutyLength(ConstraintFactory f) {
        return monthWork(f)
                .filter(c -> c.getShift().getDurationMinutes() > c.getStaff().getLimits().maxDuty())
                .penalize(HARD, c -> c.getShift().getDurationMinutes() - c.getStaff().getLimits().maxDuty())
                .justifyWith((c, s) -> hit(c, "shiftId", c.getShift().getId(), "minutes",
                        c.getShift().getDurationMinutes()))
                .asConstraint(ConstraintCatalog.MAX_DUTY);
    }

    Constraint maxWeekHours(ConstraintFactory f) {
        return work(f)
                .join(Week.class, overlapping(DutyCell::getStart, DutyCell::getEnd, Week::start, Week::end))
                .groupBy((c, w) -> c.getStaff(), (c, w) -> w, sum((c, w) -> c.overlap(w.start(), w.end())))
                .filter((s, w, minutes) -> minutes > s.getLimits().maxWeek())
                .penalize(HARD, (s, w, minutes) -> minutes - s.getLimits().maxWeek())
                .justifyWith((s, w, minutes, score) -> Hit.of(s.getId(), weekStart(w), "minutes", minutes,
                        "weekStart", weekStart(w).toString()))
                .asConstraint(ConstraintCatalog.MAX_WEEK);
    }

    /**
     * Average weekly hours over the averaging period (history months plus this month), where the history covers the
     * whole period. Penalty: minutes above the allowed total.
     */
    Constraint averageWeekHours(ConstraintFactory f) {
        return monthWork(f)
                .filter(c -> c.getStaff().getAverage().complete())
                .groupBy(DutyCell::getStaff, sum(DutyCell::getDurationMinutes))
                .filter((s, minutes) -> averageExcess(s, minutes) > 0)
                .penalize(HARD, RosterConstraints::averageExcess)
                .justifyWith((s, minutes, score) -> Hit.of(s.getId(), null, "minutes",
                        s.getAverage().minutes() + minutes, "weeks", s.getAverage().weeks(),
                        "maxWeekMinutes", s.getAverage().maxWeekMinutes()))
                .asConstraint(ConstraintCatalog.AVERAGE_WEEK);
    }

    static long averageExcess(Staff s, long monthMinutes) {
        Staff.Average a = s.getAverage();
        return a.minutes() + monthMinutes - Math.round(a.maxWeekMinutes() * a.weeks());
    }

    Constraint employment(ConstraintFactory f) {
        return f.forEach(DutyCell.class)
                .filter(c -> c.isInMonth() && c.isOutsideEmployment())
                .penalize(HARD)
                .justifyWith((c, s) -> hit(c))
                .asConstraint(ConstraintCatalog.EMPLOYMENT);
    }

    Constraint missingShift(ConstraintFactory f) {
        return f.forEachIncludingUnassigned(DutyCell.class)
                .filter(c -> c.isInMonth() && c.getMissingShiftId() != null)
                .penalize(HARD)
                .justifyWith((c, s) -> hit(c, "shiftId", c.getMissingShiftId()))
                .asConstraint(ConstraintCatalog.MISSING_SHIFT);
    }

    Constraint qualification(ConstraintFactory f) {
        return monthWork(f)
                .filter(c -> !c.getStaff().getAreaIds().contains(c.getAreaId()))
                .penalize(HARD)
                .justifyWith((c, s) -> hit(c, "areaId", c.getAreaId()))
                .asConstraint(ConstraintCatalog.QUALIFICATION);
    }

    Constraint absence(ConstraintFactory f) {
        return f.forEach(DutyCell.class)
                .filter(c -> c.isInMonth() && c.getAbsenceCode() != null)
                .penalize(HARD)
                .justifyWith((c, s) -> hit(c, "typeCode", c.getAbsenceCode()))
                .asConstraint(ConstraintCatalog.ABSENCE);
    }

    Constraint neverRestriction(ConstraintFactory f) {
        return monthWork(f)
                .filter(c -> c.getNever().contains(c.getShift().getId()))
                .penalize(HARD)
                .justifyWith((c, s) -> hit(c, "shiftId", c.getShift().getId()))
                .asConstraint(ConstraintCatalog.NEVER);
    }

    Constraint compRestBalance(ConstraintFactory f) {
        return f.forEach(DutyCell.class)
                .filter(c -> c.isInMonth() && c.isCompRest())
                .groupBy(DutyCell::getStaff, count())
                .filter((s, n) -> n > s.getCompRestBalance())
                .penalize(HARD, (s, n) -> n - s.getCompRestBalance())
                .justifyWith((s, n, score) -> Hit.of(s.getId(), null, "planned", n,
                        "balance", s.getCompRestBalance()))
                .asConstraint(ConstraintCatalog.COMP_REST_BALANCE);
    }

    // Medium

    Constraint demand(ConstraintFactory f) {
        return f.forEach(DemandSegment.class)
                .join(work(f), equal(DemandSegment::areaId, DutyCell::getAreaId),
                        overlapping(DemandSegment::start, DemandSegment::end, DutyCell::getStart, DutyCell::getEnd))
                .groupBy((s, c) -> s, countBi())
                .complement(DemandSegment.class, s -> 0L)
                .filter((s, n) -> n < s.required())
                .penalize(MEDIUM, (s, n) -> (s.required() - n) * s.minutes())
                .justifyWith((s, n, score) -> Hit.of(null, s.day(), "areaId", s.areaId(), "planned", n,
                        "required", s.required(), "from", s.from().toString(),
                        "to", s.to().toString()))
                .asConstraint(ConstraintCatalog.DEMAND);
    }

    // Soft

    Constraint targetHours(ConstraintFactory f) {
        return f.forEachIncludingUnassigned(DutyCell.class)
                .filter(DutyCell::isInMonth)
                .groupBy(DutyCell::getStaff, sum(DutyCell::getCountedMinutes))
                .filter((s, minutes) -> deviationBuckets(s, minutes) != 0)
                .penalize(SOFT, (s, minutes) -> square(deviationBuckets(s, minutes)))
                .justifyWith((s, minutes, score) -> Hit.of(s.getId(), null, "minutes", minutes,
                        "targetMinutes", s.getTargetMinutes()))
                .asConstraint(ConstraintCatalog.TARGET_HOURS);
    }

    /** Deviation from the target in quarter hours. */
    static long deviationBuckets(Staff s, long minutes) {
        return Math.abs(minutes - s.getTargetMinutes()) / 15;
    }

    /** One counted duty (load = the person's weight) or the person itself (load 0) in a fairness comparison. */
    record Load(Staff staff, long load) {
    }

    Constraint fairNights(ConstraintFactory f) {
        return fairness(monthWork(f).filter(c -> c.getShift().isNight()).map(RosterConstraints::load),
                f, s -> s.getFairness().nights(), s -> s.getHistory().nights(), ConstraintCatalog.FAIR_NIGHTS);
    }

    Constraint fairWeekends(ConstraintFactory f) {
        // A weekend that started in the previous month is counted there (its Saturday is in the history).
        return fairness(monthWork(f).filter(c -> c.isWeekendDay() && !startsBeforeMonth(c))
                .groupBy(DutyCell::getStaff, c -> c.getWeekday() == 7 ? c.getDay() - 1 : c.getDay())
                .map((s, saturday) -> new Load(s, s.getFairness().weight())),
                f, s -> true, s -> s.getHistory().weekends(), ConstraintCatalog.FAIR_WEEKENDS);
    }

    Constraint fairHolidays(ConstraintFactory f) {
        return fairness(monthWork(f).filter(DutyCell::isHoliday).map(RosterConstraints::load),
                f, s -> true, s -> s.getHistory().holidays(), ConstraintCatalog.FAIR_HOLIDAYS);
    }

    Constraint fairUnpopular(ConstraintFactory f) {
        return fairness(monthWork(f).filter(c -> c.getShift().isUnpopular()).map(RosterConstraints::load),
                f, s -> s.getFairness().unpopular(), s -> s.getHistory().unpopular(),
                ConstraintCatalog.FAIR_UNPOPULAR);
    }

    /** Sunday the 1st: its weekend began in the previous month. */
    static boolean startsBeforeMonth(DutyCell c) {
        return c.getWeekday() == 7 && c.getDate().getDayOfMonth() == 1;
    }

    private static Load load(DutyCell c) {
        return new Load(c.getStaff(), c.getStaff().getFairness().weight());
    }

    /**
     * Compares the loads (this month's and the history months' duties, weighted per available day and weekly
     * hours) of everybody who can take such duties. The penalty is the root of the summed squared deviations from
     * the mean, so it is 0 when all loads are equal. One match for the whole comparison; the explanation per person
     * is the deviation from the mean (see RosterAnalyzer).
     */
    private static Constraint fairness(UniConstraintStream<Load> duties, ConstraintFactory f,
            java.util.function.Predicate<Staff> eligible, java.util.function.ToLongFunction<Staff> history,
            String id) {
        return duties.filter(l -> l.load() > 0)
                .concat(f.forEach(Staff.class).filter(s -> s.getFairness().weight() > 0 && eligible.test(s))
                        .map(s -> new Load(s, 0)))
                .groupBy(loadBalance(Load::staff, Load::load,
                        l -> history.applyAsLong(l.staff()) * l.staff().getFairness().weight()))
                .filter(b -> b.unfairness().signum() > 0)
                .penalize(SOFT, b -> b.unfairness().setScale(0, java.math.RoundingMode.HALF_UP).longValue())
                .justifyWith((b, score) -> Hit.of(null, null, "people", b.loads().size()))
                .asConstraint(id);
    }

    Constraint wishes(ConstraintFactory f) {
        return f.forEachIncludingUnassigned(DutyCell.class)
                .filter(c -> !c.getWishes().isEmpty())
                .groupBy(DutyCell::getStaff, sum(DutyCell::unmetWishes))
                .filter((s, unmet) -> unmet > 0)
                .penalize(SOFT, (s, unmet) -> wishPenalty(s, unmet))
                .justifyWith((s, unmet, score) -> Hit.of(s.getId(), null, "unmet", unmet,
                        "wishes", s.getWishes(), "historyWishes", s.getHistory().wishes(),
                        "historyWishesMet", s.getHistory().wishesMet()))
                .asConstraint(ConstraintCatalog.WISHES);
    }

    /**
     * Unmet wishes squared over the number of wishes (this and earlier months): the extra cost of one more
     * unmet wish grows with the share already unmet, so the solver evens out the rate between people.
     */
    static long wishPenalty(Staff s, long unmet) {
        long total = s.getWishes() + s.getHistory().wishes();
        long before = s.getHistory().wishes() - s.getHistory().wishesMet();
        long after = before + unmet;
        return 100 * (after * after - before * before) / Math.max(1, total);
    }

    Constraint blockLength(ConstraintFactory f) {
        return work(f)
                .groupBy(DutyCell::getStaff, toConsecutiveSequences(c -> (int) c.getDay()))
                .flattenLast(SequenceChain::getConsecutiveSequences)
                .filter((s, seq) -> seq.getLastItem().isInMonth() && blockExcess(s, seq) > 0)
                .penalize(SOFT, RosterConstraints::blockExcess)
                .justifyWith((s, seq, score) -> Hit.of(s.getId(), seq.getFirstItem().getDate(),
                        "duties", seq.getCount()))
                .asConstraint(ConstraintCatalog.BLOCK_LENGTH);
    }

    /** Days a block is shorter than minBlock (not for a block running into the next month) or longer than maxBlock. */
    static long blockExcess(Staff s, Sequence<DutyCell, Integer> seq) {
        int n = seq.getCount();
        if (n > s.getLimits().maxBlock()) {
            return n - s.getLimits().maxBlock();
        }
        if (n < s.getLimits().minBlock() && !seq.getLastItem().isMonthEnd()) {
            return s.getLimits().minBlock() - n;
        }
        return 0;
    }

    Constraint isolatedDayOff(ConstraintFactory f) {
        return work(f)
                .groupBy(DutyCell::getStaff, toConsecutiveSequences(c -> (int) c.getDay()))
                .flattenLast(SequenceChain::getBreaks)
                .filter((s, gap) -> gap.getLength() == 2 && gap.getNextSequenceStart().isInMonth())
                .penalize(SOFT)
                .justifyWith((s, gap, score) -> Hit.of(s.getId(), gap.getPreviousSequenceEnd().getDate().plusDays(1)))
                .asConstraint(ConstraintCatalog.ISOLATED_DAY_OFF);
    }

    Constraint forwardRotation(ConstraintFactory f) {
        return work(f)
                .join(work(f), equal(DutyCell::getStaff), equal(c -> c.getDay() + 1, DutyCell::getDay))
                .filter((a, b) -> b.isInMonth()
                        && b.getShift().getCategory().ordinal() < a.getShift().getCategory().ordinal())
                .penalize(SOFT)
                .justifyWith((a, b, s) -> hit(b, "fromShiftId", a.getShift().getId(), "toShiftId",
                        b.getShift().getId()))
                .asConstraint(ConstraintCatalog.FORWARD_ROTATION);
    }

    Constraint splitWeekend(ConstraintFactory f) {
        return f.forEachIncludingUnassigned(DutyCell.class)
                .filter(c -> c.getWeekend() >= 0)
                .groupBy(DutyCell::getStaff, DutyCell::getWeekend, sum(c -> c.isWork() ? 1 : 0),
                        sum(c -> c.getAbsenceCode() != null ? 1 : 0))
                .filter((s, saturday, worked, absent) -> worked == 1 && absent == 0)
                .penalize(SOFT)
                .justifyWith((s, saturday, worked, absent, score) -> Hit.of(s.getId(),
                        LocalDate.ofEpochDay(saturday)))
                .asConstraint(ConstraintCatalog.SPLIT_WEEKEND);
    }

    Constraint compRestUse(ConstraintFactory f) {
        return f.forEach(DutyCell.class)
                .filter(c -> c.isInMonth() && c.isCompRest())
                .reward(SOFT)
                .justifyWith((c, s) -> hit(c))
                .asConstraint(ConstraintCatalog.COMP_REST_USE);
    }

    /** Same area and category again and again, counting the previous month too. */
    Constraint shiftVariety(ConstraintFactory f) {
        return monthWork(f)
                .groupBy(DutyCell::getStaff, c -> Staff.varietyKey(c.getShift()), count())
                .filter((s, key, n) -> pairs(n + s.previous(key)) > pairs(s.previous(key)))
                .penalize(SOFT, (s, key, n) -> pairs(n + s.previous(key)) - pairs(s.previous(key)))
                .justifyWith((s, key, n, score) -> Hit.of(s.getId(), null, "count", n, "previous", s.previous(key),
                        "areaId", key.substring(0, key.indexOf('/')), "category", key.substring(key.indexOf('/') + 1)))
                .asConstraint(ConstraintCatalog.SHIFT_VARIETY);
    }

    /** People qualified for several areas work in all of them, counting the previous month too. */
    Constraint areaVariety(ConstraintFactory f) {
        return monthWork(f)
                .filter(c -> c.getStaff().isMultiArea())
                .groupBy(DutyCell::getStaff, DutyCell::getAreaId, count())
                .filter((s, area, n) -> pairs(n + s.previous(area)) > pairs(s.previous(area)))
                .penalize(SOFT, (s, area, n) -> pairs(n + s.previous(area)) - pairs(s.previous(area)))
                .justifyWith((s, area, n, score) -> Hit.of(s.getId(), null, "count", n, "previous", s.previous(area),
                        "areaId", area))
                .asConstraint(ConstraintCatalog.AREA_VARIETY);
    }

    /** Pairs of equal duties: n (n - 1) / 2. */
    static long pairs(long n) {
        return n * (n - 1) / 2;
    }

    Constraint family(ConstraintFactory f) {
        return monthWork(f)
                .filter(DutyCell::violatesFamilyRule)
                .penalize(SOFT)
                .justifyWith((c, s) -> hit(c, "shiftId", c.getShift().getId()))
                .asConstraint(ConstraintCatalog.FAMILY);
    }

    Constraint onRequest(ConstraintFactory f) {
        return monthWork(f)
                .filter(c -> c.getOnRequest().contains(c.getShift().getId()))
                .penalize(SOFT)
                .justifyWith((c, s) -> hit(c, "shiftId", c.getShift().getId()))
                .asConstraint(ConstraintCatalog.ON_REQUEST);
    }

    Constraint overstaffing(ConstraintFactory f) {
        return f.forEach(DemandSegment.class)
                .join(work(f), equal(DemandSegment::areaId, DutyCell::getAreaId),
                        overlapping(DemandSegment::start, DemandSegment::end, DutyCell::getStart, DutyCell::getEnd))
                .groupBy((s, c) -> s, countBi())
                .filter((s, n) -> n > s.upper())
                .penalize(SOFT, (s, n) -> (n - s.upper()) * s.minutes())
                .justifyWith((s, n, score) -> Hit.of(null, s.day(), "areaId", s.areaId(),
                        "planned", n, "upper", s.upper(), "from", s.from().toString(),
                        "to", s.to().toString()))
                .asConstraint(ConstraintCatalog.OVERSTAFFING);
    }

    Constraint uselessDuty(ConstraintFactory f) {
        return monthWork(f)
                .ifNotExists(DemandSegment.class, equal(DutyCell::getAreaId, DemandSegment::areaId),
                        overlapping(DutyCell::getStart, DutyCell::getEnd, DemandSegment::start, DemandSegment::end))
                .penalize(SOFT)
                .justifyWith((c, s) -> hit(c, "shiftId", c.getShift().getId()))
                .asConstraint(ConstraintCatalog.USELESS_DUTY);
    }

    // Helpers

    /** A hit on one cell. */
    private static Hit hit(DutyCell c, Object... params) {
        return Hit.of(c.getStaff().getId(), c.getDate(), params);
    }

    private static long square(long x) {
        return x * x;
    }

    private static LocalDate weekStart(Week w) {
        return LocalDate.parse(w.id());
    }

}
