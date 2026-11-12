package app.dispofold.api;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;

import app.dispofold.model.ConstraintHit;
import app.dispofold.model.ConstraintInfo;
import app.dispofold.model.CoverageSegment;
import app.dispofold.model.Person;
import app.dispofold.model.PersonExplanation;
import app.dispofold.model.PersonStats;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.PlanAssignment;
import app.dispofold.planning.PlanningFacade;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

/**
 * Test double, enabled only by {@link FakeFacadeProfile}. Solving assigns everybody the first shift of their first
 * area on every day, after {@link #solveMillis}; pinned cells stay.
 */
@Alternative
@ApplicationScoped
public class FakePlanningFacade implements PlanningFacade {

    public static volatile long solveMillis = 100;
    /** Hard violations of the final solution. */
    public static volatile int finalHardViolations = 0;
    public static volatile boolean fail;

    private final Map<YearMonth, Boolean> stopFlags = new ConcurrentHashMap<>();
    private volatile SolveOptions lastOptions;
    private volatile PlanningInput lastInput;

    SolveOptions lastOptions() {
        return lastOptions;
    }

    PlanningInput lastInput() {
        return lastInput;
    }

    @Override
    public List<ConstraintInfo> constraintCatalog() {
        return List.of(new ConstraintInfo("rest", "HARD", 1), new ConstraintInfo("wishes", "SOFT", 10));
    }

    @Override
    public void solve(PlanningInput input, SolveOptions options, Consumer<SolveProgress> onProgress,
            Consumer<Throwable> onError) {
        YearMonth month = input.period().month();
        lastOptions = options;
        lastInput = input;
        stopFlags.put(month, false);
        Thread.ofVirtual().start(() -> {
            long start = System.currentTimeMillis();
            List<PlanAssignment> cells = solution(input);
            while (System.currentTimeMillis() - start < solveMillis && !stopFlags.get(month)) {
                onProgress.accept(new SolveProgress("0hard/-10medium/-5soft", 1,
                        System.currentTimeMillis() - start, false, cells));
                sleep(20);
            }
            if (fail) {
                stopFlags.remove(month);
                onError.accept(new IllegalStateException("synthetic failure"));
                return;
            }
            stopFlags.remove(month);
            onProgress.accept(new SolveProgress(finalHardViolations == 0 ? "0hard/0medium/-5soft"
                    : -finalHardViolations + "hard/0medium/-5soft", finalHardViolations,
                    System.currentTimeMillis() - start, true, cells));
        });
    }

    private static List<PlanAssignment> solution(PlanningInput input) {
        YearMonth month = input.period().month();
        List<PlanAssignment> cells = new ArrayList<>();
        for (Person p : input.dataset().people()) {
            String shift = input.dataset().shifts().stream().filter(s -> s.areaId().equals(p.areaIds().get(0)))
                    .findFirst().orElseThrow().id();
            for (int d = 1; d <= month.lengthOfMonth(); d++) {
                LocalDate date = month.atDay(d);
                PlanAssignment pinned = input.assignments().stream()
                        .filter(a -> a.pinned() && a.personId().equals(p.id()) && a.date().equals(date))
                        .findFirst().orElse(null);
                cells.add(pinned != null ? pinned : new PlanAssignment(p.id(), date, shift, false, false));
            }
        }
        return cells;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop(YearMonth month) {
        stopFlags.computeIfPresent(month, (m, f) -> true);
    }

    @Override
    public boolean isSolving(YearMonth month) {
        return stopFlags.containsKey(month);
    }

    @Override
    public PlanAnalysis analyze(PlanningInput input) {
        List<PersonStats> people = input.dataset().people().stream().map(p -> stats(input, p.id())).toList();
        return new PlanAnalysis("0hard/0medium/-" + input.assignments().size() + "soft", 0, List.of(), List.of(),
                people);
    }

    private static PersonStats stats(PlanningInput input, String personId) {
        int duties = (int) input.assignments().stream()
                .filter(a -> a.personId().equals(personId) && a.shiftId() != null).count();
        int nights = (int) input.assignments().stream()
                .filter(a -> a.personId().equals(personId) && "DR-N".equals(a.shiftId())).count();
        int historyNights = input.dataset().history().stream().filter(h -> h.personId().equals(personId))
                .mapToInt(h -> h.nights()).sum();
        return new PersonStats(personId, 170, 0, duties * 12.0, 0, 0, duties, nights, 2, 2, 1, nights, 1, 1, 0, 0,
                0, 0, historyNights, 0, 0, 0, 0, 0, 31, 0, 40, false);
    }

    @Override
    public PersonExplanation explain(PlanningInput input, String personId) {
        return new PersonExplanation(stats(input, personId),
                List.of(new ConstraintHit("wishes", "SOFT", null, -10, "hit.wishes", Map.of("unmet", 1))));
    }

    @Override
    public List<CoverageSegment> coverage(PlanningInput input, String areaId) {
        YearMonth m = input.period().month();
        return List.of(new CoverageSegment(areaId, m.atDay(1).atTime(7, 0), m.atDay(1).atTime(19, 0), 1, null, 1));
    }
}
