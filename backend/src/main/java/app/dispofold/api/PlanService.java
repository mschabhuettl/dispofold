package app.dispofold.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response.Status;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.model.Dataset;
import app.dispofold.model.FixedAssignment;
import app.dispofold.model.HistoryEntry;
import app.dispofold.model.Period;
import app.dispofold.model.Plan;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.PlanStatus;
import app.dispofold.model.PreviousDuty;
import app.dispofold.planning.PlanningFacade;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;
import app.dispofold.store.Store;
import app.dispofold.store.Store.StoredPlan;
import io.quarkus.runtime.StartupEvent;

/**
 * Plans per month: solve lifecycle, edits, month closing and the planning input built from the stored documents.
 * Changes to one month are serialised per month; months do not wait for each other, and slow work (building the
 * solver model, solving) runs on the solver thread, not on the request thread.
 */
@ApplicationScoped
public class PlanService {

    private static final Logger LOG = Logger.getLogger(PlanService.class);
    private static final long SAVE_INTERVAL_NANOS = 2_000_000_000L;

    /**
     * @param stale the inputs (master data, planning month, rules, previous month) changed after the last solve
     * @param previousMonthChanged the previous month's plan changed after this month was planned
     * @param previousFromPlan the previous duties come from the stored plan of the previous month (else from the
     *        import)
     */
    public record PlanState(Plan plan, boolean solving, long elapsedMillis, int hardViolations, boolean stale,
            boolean previousMonthChanged, boolean previousFromPlan) {
    }

    /**
     * @param moveLimit stop after this many evaluated moves; with the same seed and data the plan is then
     *        reproducible (the time limit stays as a safety net, the early stop on no improvement is off)
     * @param coldStart start from an empty plan (pinned cells stay) instead of the stored plan
     */
    public record SolveRequest(Long seed, Integer timeLimitSeconds, Long moveLimit, Boolean coldStart) {
    }

    /** A running solve. Callbacks of the solver thread synchronise on it. */
    private static final class Run {
        final long seed;
        volatile boolean stopRequested;
        volatile long elapsedMillis;
        volatile int hardViolations;
        boolean finished;
        long lastSave = System.nanoTime();

        Run(long seed) {
            this.seed = seed;
        }
    }

    @Inject
    Store store;

    @Inject
    Instance<PlanningFacade> facades;

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "app.solver.time-limit-seconds")
    int defaultTimeLimit;

    @ConfigProperty(name = "app.solver.unimproved-seconds")
    int unimprovedSeconds;

    @ConfigProperty(name = "app.solver.seed")
    long defaultSeed;

    @ConfigProperty(name = "app.solver.move-limit")
    long defaultMoveLimit;

    private final Map<YearMonth, Run> runs = new ConcurrentHashMap<>();
    private final Map<YearMonth, Object> locks = new ConcurrentHashMap<>();

    private Object lock(YearMonth month) {
        return locks.computeIfAbsent(month, m -> new Object());
    }

    /** Nothing solves right after a start; plans left in SOLVING by a restart become STOPPED. */
    void resetInterrupted(@Observes StartupEvent event) {
        for (StoredPlan p : store.plans()) {
            if (p.plan().status() == PlanStatus.SOLVING) {
                store.savePlan(with(p.plan(), PlanStatus.STOPPED, p.plan().score(), p.plan().assignments()),
                        p.hardViolations(), p.elapsedMillis());
            }
        }
    }

    public PlanningFacade facade() {
        if (!facades.isResolvable()) {
            throw ApiErrors.error(Status.SERVICE_UNAVAILABLE, "plan.unavailable", "Die Planung ist nicht verfügbar");
        }
        return facades.get();
    }

    public Dataset dataset() {
        return store.dataset().orElseThrow(() -> ApiErrors.error(Status.CONFLICT, "dataset.missing",
                "Keine Stammdaten, bitte zuerst importieren"));
    }

    public Period period(YearMonth month) {
        return store.period(month).orElseGet(() -> new Period(month, List.of(), List.of(), List.of(), List.of()));
    }

    public Plan plan(YearMonth month) {
        return store.plan(month).map(StoredPlan::plan)
                .orElseGet(() -> new Plan(Plan.FORMAT, 1, month, PlanStatus.EMPTY, null, 0, null, List.of()));
    }

    public boolean isSolving(YearMonth month) {
        return runs.containsKey(month) || facades.isResolvable() && facades.get().isSolving(month);
    }

    public boolean isClosed(YearMonth month) {
        return plan(month).status() == PlanStatus.CLOSED;
    }

    public PlanState state(YearMonth month) {
        Run run = runs.get(month);
        StoredPlan stored = store.plan(month).orElse(null);
        Plan plan = stored == null ? plan(month) : stored.plan();
        boolean planned = stored != null && stored.inputHash() != null;
        boolean stale = planned && plan.status() != PlanStatus.CLOSED && store.dataset().isPresent()
                && !stored.inputHash().equals(inputHash(month));
        boolean previousChanged = planned && stored.previousHash() != null
                && !stored.previousHash().equals(previousHash(month));
        boolean fromPlan = previousPlan(month) != null;
        if (run != null) {
            return new PlanState(plan, true, run.elapsedMillis, run.hardViolations, stale, previousChanged, fromPlan);
        }
        return new PlanState(plan, isSolving(month), stored == null ? 0 : stored.elapsedMillis(),
                stored == null ? 0 : stored.hardViolations(), stale, previousChanged, fromPlan);
    }

    // Planning input

    /**
     * Planning input from the stored dataset (history merged), period, rules and the given cells. The previous
     * duties come from the stored plan of the previous month (all its days), and only when there is none from the
     * imported period.
     */
    public PlanningInput input(YearMonth month, List<PlanAssignment> assignments) {
        Dataset d = dataset();
        Dataset full = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), d.people(), d.restrictions(), store.mergedHistory(d.history()),
                List.of());
        Period p = period(month);
        Period withPrevious = new Period(p.month(), p.absences(), p.wishes(), p.fixed(), previousDuties(month, p));
        return new PlanningInput(full, withPrevious, store.rules(), assignments);
    }

    public PlanningInput input(YearMonth month) {
        return input(month, plan(month).assignments());
    }

    private Plan previousPlan(YearMonth month) {
        return store.plan(month.minusMonths(1)).map(StoredPlan::plan)
                .filter(p -> p.assignments() != null && !p.assignments().isEmpty()).orElse(null);
    }

    private List<PreviousDuty> previousDuties(YearMonth month, Period period) {
        Plan previous = previousPlan(month);
        if (previous == null) {
            return period.previousDuties();
        }
        return previous.assignments().stream().filter(a -> a.shiftId() != null)
                .map(a -> new PreviousDuty(a.personId(), a.date(), a.shiftId())).toList();
    }

    /** Fingerprint of everything a solve of the month depends on except the month's own cells. */
    String inputHash(YearMonth month) {
        PlanningInput in = input(month, List.of());
        // Only the history before the month matters; closing later months must not make this one stale.
        Dataset d = in.dataset();
        List<HistoryEntry> before = d.history().stream()
                .filter(h -> h.month() != null && h.month().compareTo(month.toString()) < 0).toList();
        Dataset relevant = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), d.people(), d.restrictions(), before, d.periods());
        return hash(List.of(relevant, in.period(), in.rules()));
    }

    String previousHash(YearMonth month) {
        Plan previous = previousPlan(month);
        return previous == null ? "" : hash(previousDuties(month, period(month)));
    }

    private String hash(Object value) {
        try {
            byte[] json = mapper.writeValueAsBytes(value);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("cannot fingerprint the planning input");
        }
    }

    // Solving

    public PlanState solve(YearMonth month, SolveRequest request) {
        synchronized (lock(month)) {
            requireOpen(month);
            requireNotSolving(month);
            PlanningFacade facade = facade();
            Plan current = plan(month);
            boolean cold = request != null && Boolean.TRUE.equals(request.coldStart());
            List<PlanAssignment> start = cold
                    ? current.assignments().stream().filter(PlanAssignment::pinned).toList()
                    : current.assignments();
            PlanningInput input = input(month, start);
            long seed = request != null && request.seed() != null ? request.seed() : defaultSeed;
            int limit = request != null && request.timeLimitSeconds() != null && request.timeLimitSeconds() > 0
                    ? request.timeLimitSeconds() : defaultTimeLimit;
            long moveLimit = request != null && request.moveLimit() != null && request.moveLimit() > 0
                    ? request.moveLimit() : defaultMoveLimit;
            StoredPlan before = store.plan(month).orElse(null);
            store.savePlan(new Plan(Plan.FORMAT, 1, month, PlanStatus.SOLVING, current.score(), seed, Instant.now(),
                    current.assignments()), before == null ? 0 : before.hardViolations(), 0);
            store.savePlanInputs(month, inputHash(month), previousHash(month));
            Run run = new Run(seed);
            runs.put(month, run);
            try {
                facade.solve(input,
                        new SolveOptions(seed, limit, moveLimit > 0 ? 0 : unimprovedSeconds, moveLimit),
                        progress -> onProgress(month, run, progress), error -> onError(month, run, error));
            } catch (RuntimeException e) {
                onError(month, run, e);
                throw ApiErrors.error(Status.INTERNAL_SERVER_ERROR, "plan.startFailed",
                        "Die Planung konnte nicht gestartet werden");
            }
            return state(month);
        }
    }

    public PlanState stop(YearMonth month) {
        synchronized (lock(month)) {
            Run run = runs.get(month);
            if (run != null) {
                run.stopRequested = true;
                facade().stop(month);
            }
        }
        return state(month);
    }

    private void onProgress(YearMonth month, Run run, SolveProgress progress) {
        synchronized (run) {
            if (run.finished) {
                return;
            }
            run.elapsedMillis = progress.elapsedMillis();
            run.hardViolations = progress.hardViolations();
            long now = System.nanoTime();
            if (!progress.finished() && now - run.lastSave < SAVE_INTERVAL_NANOS) {
                return;
            }
            PlanStatus status = !progress.finished() ? PlanStatus.SOLVING
                    : run.stopRequested ? PlanStatus.STOPPED : PlanStatus.SOLVED;
            run.lastSave = now;
            // If the final save fails, the exception reaches the facade, which reports it through onError.
            store.savePlan(new Plan(Plan.FORMAT, 1, month, status, progress.score(), run.seed, Instant.now(),
                    sorted(progress.assignments())), progress.hardViolations(), progress.elapsedMillis());
            if (progress.finished()) {
                run.finished = true;
                runs.remove(month, run);
            }
        }
    }

    private void onError(YearMonth month, Run run, Throwable error) {
        synchronized (run) {
            if (run.finished) {
                return;
            }
            run.finished = true;
            // Exception messages may contain data; log the month and the exception type only.
            LOG.errorf("Solving %s failed: %s", month, error.getClass().getName());
            try {
                Plan p = plan(month);
                store.savePlan(with(p, PlanStatus.FAILED, p.score(), p.assignments()), run.hardViolations,
                        run.elapsedMillis);
            } finally {
                runs.remove(month, run);
            }
        }
    }

    // Edits

    /** Sets one cell; a planner's cell is normally pinned. */
    public Plan setCell(YearMonth month, PlanAssignment cell) {
        synchronized (lock(month)) {
            requireOpen(month);
            requireNotSolving(month);
            Dataset d = dataset();
            if (cell.personId() == null || d.people().stream().noneMatch(p -> p.id().equals(cell.personId()))) {
                throw ApiErrors.error(Status.BAD_REQUEST, "cell.unknownPerson",
                        params("personId", cell.personId()), "Unbekannte Person");
            }
            if (cell.date() == null || !YearMonth.from(cell.date()).equals(month)) {
                throw ApiErrors.error(Status.BAD_REQUEST, "cell.outsideMonth",
                        params("date", String.valueOf(cell.date()), "month", month.toString()),
                        "Datum liegt nicht im Monat");
            }
            if (cell.shiftId() != null && d.shifts().stream().noneMatch(s -> s.id().equals(cell.shiftId()))) {
                throw ApiErrors.error(Status.BAD_REQUEST, "cell.unknownShift", params("shiftId", cell.shiftId()),
                        "Unbekannte Schicht");
            }
            if (cell.shiftId() != null && cell.compRest()) {
                throw ApiErrors.error(Status.BAD_REQUEST, "cell.compRestWithShift",
                        "Ein Ersatzruhetag hat keinen Dienst");
            }
            Plan p = plan(month);
            List<PlanAssignment> cells = new ArrayList<>(p.assignments().stream()
                    .filter(a -> !(a.personId().equals(cell.personId()) && a.date().equals(cell.date()))).toList());
            cells.add(cell);
            PlanStatus status = p.status() == PlanStatus.EMPTY ? PlanStatus.STOPPED : p.status();
            return saveRescored(month, with(p, status, p.score(), cells));
        }
    }

    /**
     * Saves a changed period if it is based on the stored revision. Fixed duties that were added or changed are
     * written into the plan's cells (pinned), cells whose fixed duty was removed are unpinned, so that the plan
     * follows the input.
     *
     * @param expectedRevision the revision the change is based on; null only for imports
     * @return the new revision
     */
    public long savePeriod(Period period, Long expectedRevision) {
        synchronized (lock(period.month())) {
            requireOpen(period.month());
            Map<String, FixedAssignment> before = fixedByCell(period(period.month()));
            Map<String, FixedAssignment> after = fixedByCell(period);
            if (!before.equals(after)) {
                requireNotSolving(period.month());
            }
            long revision;
            try {
                revision = store.savePeriod(period, expectedRevision);
            } catch (Store.ConflictException e) {
                throw ApiErrors.error(Status.CONFLICT, "period.conflict",
                        params("month", period.month().toString(),
                                "revision", store.storedPeriod(period.month()).map(Store.StoredPeriod::revision)
                                        .orElse(0L)),
                        "Der Planungsmonat wurde inzwischen geändert, bitte neu laden");
            }
            if (before.equals(after) || store.plan(period.month()).isEmpty()) {
                return revision;
            }
            Plan p = plan(period.month());
            Map<String, PlanAssignment> cells = new LinkedHashMap<>();
            p.assignments().forEach(a -> cells.put(a.personId() + "/" + a.date(), a));
            // Only cells that still show the old fixed duty (or none) follow; a cell a planner pinned to something
            // else keeps the planner's value.
            before.forEach((key, f) -> {
                PlanAssignment a = cells.get(key);
                if (!after.containsKey(key) && a != null && showsFixed(a, f)) {
                    cells.put(key, new PlanAssignment(a.personId(), a.date(), a.shiftId(), a.compRest(), false));
                }
            });
            after.forEach((key, f) -> {
                PlanAssignment a = cells.get(key);
                FixedAssignment old = before.get(key);
                if (!f.equals(old) && (a == null || !a.pinned() || old != null && showsFixed(a, old))) {
                    cells.put(key, new PlanAssignment(f.personId(), f.date(), f.shiftId(), false, true));
                }
            });
            saveRescored(period.month(), with(p, p.status(), p.score(), List.copyOf(cells.values())));
            return revision;
        }
    }

    private static boolean showsFixed(PlanAssignment a, FixedAssignment f) {
        return !a.compRest() && Objects.equals(a.shiftId(), f.shiftId());
    }

    private static Map<String, FixedAssignment> fixedByCell(Period period) {
        return period.fixed().stream().collect(Collectors.toMap(f -> f.personId() + "/" + f.date(), f -> f,
                (a, b) -> b));
    }

    /** Releases all pins except those that come from {@link Period#fixed()}. */
    public Plan unpin(YearMonth month) {
        synchronized (lock(month)) {
            requireOpen(month);
            requireNotSolving(month);
            Set<String> fixed = period(month).fixed().stream().map(f -> f.personId() + "/" + f.date())
                    .collect(Collectors.toSet());
            Plan p = plan(month);
            List<PlanAssignment> cells = p.assignments().stream()
                    .map(a -> a.pinned() && !fixed.contains(a.personId() + "/" + a.date())
                            ? new PlanAssignment(a.personId(), a.date(), a.shiftId(), a.compRest(), false)
                            : a)
                    .toList();
            Plan unpinned = with(p, p.status(), p.score(), cells);
            StoredPlan stored = store.plan(month).orElse(null);
            store.savePlan(unpinned, stored == null ? 0 : stored.hardViolations(),
                    stored == null ? 0 : stored.elapsedMillis());
            return unpinned;
        }
    }

    /** Replaces the plan by an imported plan document. */
    public Plan importPlan(YearMonth month, Plan plan) {
        synchronized (lock(month)) {
            requireOpen(month);
            requireNotSolving(month);
            if (plan == null || !Plan.FORMAT.equals(plan.format()) || plan.version() != 1) {
                throw ApiErrors.error(Status.BAD_REQUEST, "planImport.format",
                        params("format", Plan.FORMAT, "version", 1), "Kein Plan im passenden Format");
            }
            if (!month.equals(plan.month())) {
                throw ApiErrors.error(Status.BAD_REQUEST, "planImport.month", params("month", month.toString()),
                        "Der Plan gehört nicht zu diesem Monat");
            }
            Dataset d = dataset();
            Set<String> people = d.people().stream().map(p -> p.id()).collect(Collectors.toSet());
            Set<String> shifts = d.shifts().stream().map(s -> s.id()).collect(Collectors.toSet());
            List<PlanAssignment> cells = plan.assignments() == null ? List.of() : plan.assignments();
            Set<String> seen = new HashSet<>();
            for (PlanAssignment a : cells) {
                if (a == null || !seen.add(a.personId() + "/" + a.date())) {
                    throw ApiErrors.error(Status.BAD_REQUEST, "planImport.duplicate",
                            "Leere oder doppelte Zeile im Plan");
                }
                if (a.shiftId() != null && a.compRest() || !people.contains(a.personId())
                        || a.shiftId() != null && !shifts.contains(a.shiftId()) || a.date() == null
                        || !YearMonth.from(a.date()).equals(month)) {
                    throw ApiErrors.error(Status.BAD_REQUEST, "planImport.row",
                            params("personId", a.personId(), "date", String.valueOf(a.date()),
                                    "shiftId", a.shiftId()),
                            "Ungültige Zeile im Plan");
                }
            }
            PlanStatus status = plan.status() == null || plan.status() == PlanStatus.SOLVING
                    || plan.status() == PlanStatus.CLOSED ? PlanStatus.STOPPED : plan.status();
            return saveRescored(month,
                    new Plan(Plan.FORMAT, 1, month, status, plan.score(), plan.seed(), Instant.now(), cells));
        }
    }

    // Month closing

    /**
     * Closes the month: writes per person the key figures, the hours account and the compensatory rest balance
     * after the month into the history (source "plan"; imported actual values of the same month still win) and
     * makes the month read-only until it is reopened.
     */
    public List<HistoryEntry> closeMonth(YearMonth month) {
        synchronized (lock(month)) {
            requireOpen(month);
            requireNotSolving(month);
            Plan p = plan(month);
            if (p.assignments().isEmpty()) {
                throw ApiErrors.error(Status.CONFLICT, "plan.empty", params("month", month.toString()),
                        "Für diesen Monat gibt es keinen Plan");
            }
            // The balances carry from month to month: a planned previous month must be closed first.
            Plan previous = previousPlan(month);
            if (previous != null && previous.status() != PlanStatus.CLOSED) {
                throw ApiErrors.error(Status.CONFLICT, "plan.previousOpen",
                        params("month", month.toString(), "previous", month.minusMonths(1).toString()),
                        "Zuerst den Vormonat abschließen");
            }
            PlanAnalysis analysis = facade().analyze(input(month));
            List<HistoryEntry> entries = analysis.people().stream()
                    .map(s -> new HistoryEntry(s.personId(), month.toString(), HistoryEntry.PLAN, s.nights(),
                            s.weekendsWorked(), s.holidays(), s.unpopular(), s.wishes(), s.wishesMet(),
                            s.plannedHours(), s.accountAfter(), s.compRestBalanceAfter(), s.compRestPlanned(),
                            s.compRestEarned()))
                    .toList();
            store.saveHistory(month.toString(), entries);
            StoredPlan stored = store.plan(month).orElseThrow();
            store.savePlan(with(p, PlanStatus.CLOSED, p.score(), p.assignments()), stored.hardViolations(),
                    stored.elapsedMillis());
            return entries;
        }
    }

    /** Reopens a closed month; its closing entries leave the history until it is closed again. */
    public PlanState reopen(YearMonth month) {
        synchronized (lock(month)) {
            Plan p = plan(month);
            if (p.status() != PlanStatus.CLOSED) {
                throw ApiErrors.error(Status.CONFLICT, "plan.notClosed", params("month", month.toString()),
                        "Der Monat ist nicht abgeschlossen");
            }
            // A closed next month was built on this month's balances: it must be reopened first.
            if (isClosed(month.plusMonths(1))) {
                throw ApiErrors.error(Status.CONFLICT, "plan.nextClosed",
                        params("month", month.toString(), "next", month.plusMonths(1).toString()),
                        "Zuerst den Folgemonat wieder öffnen");
            }
            store.saveHistory(month.toString(), List.of());
            StoredPlan stored = store.plan(month).orElseThrow();
            store.savePlan(with(p, PlanStatus.STOPPED, p.score(), p.assignments()), stored.hardViolations(),
                    stored.elapsedMillis());
            return state(month);
        }
    }

    /**
     * Runs the action while holding the locks of all given months (in month order, so that two callers cannot
     * deadlock); used by the import, which checks and saves several months as a whole.
     */
    public <T> T withMonths(List<YearMonth> months, java.util.function.Supplier<T> action) {
        List<YearMonth> sorted = months.stream().distinct().sorted().toList();
        return withMonths(sorted, 0, action);
    }

    private <T> T withMonths(List<YearMonth> months, int i, java.util.function.Supplier<T> action) {
        if (i == months.size()) {
            return action.get();
        }
        synchronized (lock(months.get(i))) {
            return withMonths(months, i + 1, action);
        }
    }

    // Helpers

    private Plan saveRescored(YearMonth month, Plan plan) {
        PlanAnalysis analysis = facade().analyze(input(month, plan.assignments()));
        Plan scored = with(plan, plan.status(), analysis.score(), plan.assignments());
        store.savePlan(scored, analysis.hardViolations(),
                store.plan(month).map(StoredPlan::elapsedMillis).orElse(0L));
        return scored;
    }

    void requireNotSolving(YearMonth month) {
        if (isSolving(month)) {
            throw ApiErrors.error(Status.CONFLICT, "plan.solving", params("month", month.toString()),
                    "Für diesen Monat läuft gerade eine Planung");
        }
    }

    void requireOpen(YearMonth month) {
        if (isClosed(month)) {
            throw ApiErrors.error(Status.CONFLICT, "plan.closed", params("month", month.toString()),
                    "Der Monat ist abgeschlossen; zum Ändern erst wieder öffnen");
        }
    }

    /** Parameters for an error; null values are left out. */
    static Map<String, Object> params(Object... keysAndValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (keysAndValues[i + 1] != null) {
                params.put((String) keysAndValues[i], keysAndValues[i + 1]);
            }
        }
        return params;
    }

    private static Plan with(Plan p, PlanStatus status, String score, List<PlanAssignment> cells) {
        return new Plan(Plan.FORMAT, 1, p.month(), status, score, p.seed(), Instant.now(), sorted(cells));
    }

    private static List<PlanAssignment> sorted(List<PlanAssignment> cells) {
        return cells.stream().filter(Objects::nonNull)
                .sorted(Comparator.comparing(PlanAssignment::personId).thenComparing(PlanAssignment::date))
                .toList();
    }
}
