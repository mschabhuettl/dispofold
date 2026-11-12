package app.dispofold.solver;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

import app.dispofold.model.ConstraintInfo;
import app.dispofold.model.CoverageSegment;
import app.dispofold.model.PersonExplanation;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.planning.PlanningFacade;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import org.jboss.logging.Logger;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.Solver;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;

/** Solves and evaluates rosters with Timefold. One solve per month at a time, each on its own thread. */
@ApplicationScoped
public class TimefoldPlanningFacade implements PlanningFacade {

    private static final Logger LOG = Logger.getLogger(TimefoldPlanningFacade.class);
    /** Progress events are throttled to this interval; the final one always goes out. */
    private static final long PROGRESS_INTERVAL_MILLIS = 1000;

    private final Map<YearMonth, Job> running = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "roster-solver");
        t.setDaemon(true);
        return t;
    });
    private final EnvironmentMode environmentMode;
    private volatile ScoreExplainer explainer;

    public TimefoldPlanningFacade() {
        this(EnvironmentMode.PHASE_ASSERT);
    }

    TimefoldPlanningFacade(EnvironmentMode environmentMode) {
        this.environmentMode = environmentMode;
    }

    @PreDestroy
    void shutdown() {
        running.values().forEach(job -> {
            job.stopRequested = true;
            if (job.solver != null) {
                job.solver.terminateEarly();
            }
        });
        executor.shutdownNow();
    }

    @Override
    public List<ConstraintInfo> constraintCatalog() {
        return ConstraintCatalog.ALL;
    }

    /** Test hook: the solver thread waits here before it builds the model. */
    static volatile CountDownLatch beforeModel;

    /** A solve of one month; the solver exists once the model is built. */
    private static final class Job {
        volatile Solver<Roster> solver;
        volatile boolean stopRequested;
    }

    /**
     * Returns at once. Building the model, the starting roster and the solver happens on the solver thread, so a
     * large month does not hold up the request or other users.
     */
    @Override
    public void solve(PlanningInput input, SolveOptions options, Consumer<SolveProgress> onProgress,
            Consumer<Throwable> onError) {
        YearMonth month = input.period().month();
        Job job = new Job();
        if (running.putIfAbsent(month, job) != null) {
            throw new IllegalStateException("A solve for " + month + " is already running");
        }
        long started = System.currentTimeMillis();
        executor.submit(() -> {
            try {
                CountDownLatch gate = beforeModel;
                if (gate != null) {
                    gate.await();
                }
                Roster roster = new RosterBuilder(input).build();
                InitialRoster.fill(roster);
                LOG.infof("Solving %s: %d people, %d segments, seed %d, model built in %d ms", month,
                        roster.getStaff().size(), roster.getSegments().size(), options.seed(),
                        System.currentTimeMillis() - started);
                Roster best = roster;
                if (!job.stopRequested) {
                    Solver<Roster> solver = SolverFactory
                            .<Roster>create(SolverSetup.config(options, environmentMode)).buildSolver();
                    long[] lastSent = {0};
                    solver.addEventListener(event -> {
                        long now = System.currentTimeMillis();
                        if (now - lastSent[0] >= PROGRESS_INTERVAL_MILLIS) {
                            lastSent[0] = now;
                            onProgress.accept(progress(event.getNewBestSolution(), now - started, false));
                        }
                    });
                    job.solver = solver;
                    if (!job.stopRequested) {
                        best = solver.solve(roster);
                    }
                }
                if (best.getScore() == null) {
                    best.setScore(explainer().explain(best).score());
                }
                long elapsed = System.currentTimeMillis() - started;
                LOG.infof("Solved %s in %d ms: %s", month, elapsed, best.getScore());
                running.remove(month);
                onProgress.accept(progress(best, elapsed, true));
            } catch (Throwable e) {
                running.remove(month);
                // Messages may contain data, so only the exception class is logged.
                LOG.errorf("Solving %s failed: %s", month, e.getClass().getName());
                onError.accept(e);
            }
        });
    }

    private SolveProgress progress(Roster roster, long elapsed, boolean finished) {
        HardMediumSoftScore score = roster.getScore();
        int hardViolations = score.hardScore() == 0 ? 0
                : RosterAnalyzer.hardViolations(explainer().explain(roster));
        return new SolveProgress(score.toString(), hardViolations, elapsed, finished,
                RosterAnalyzer.assignments(roster));
    }

    @Override
    public void stop(YearMonth month) {
        Job job = running.get(month);
        if (job == null) {
            return;
        }
        job.stopRequested = true;
        // Timefold clears an early termination request when solve() starts, so a stop that comes before the
        // solver thread is running would be lost. Repeat it until the solve has ended.
        executor.submit(() -> {
            while (running.get(month) == job) {
                Solver<Roster> solver = job.solver;
                if (solver != null) {
                    solver.terminateEarly();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
    }

    @Override
    public boolean isSolving(YearMonth month) {
        return running.containsKey(month);
    }

    @Override
    public PlanAnalysis analyze(PlanningInput input) {
        Roster roster = new RosterBuilder(input, true).build();
        return RosterAnalyzer.analysis(input, roster, analyzeScore(roster));
    }

    @Override
    public PersonExplanation explain(PlanningInput input, String personId) {
        Roster roster = new RosterBuilder(input, true).build();
        return new PersonExplanation(RosterAnalyzer.stats(input, roster, personId),
                RosterAnalyzer.hits(analyzeScore(roster), roster, personId));
    }

    @Override
    public List<CoverageSegment> coverage(PlanningInput input, String areaId) {
        return RosterAnalyzer.coverage(new RosterBuilder(input, true).build(), areaId);
    }

    private ScoreExplainer.Explanation analyzeScore(Roster roster) {
        return explainer().explain(roster);
    }

    private ScoreExplainer explainer() {
        if (explainer == null) {
            synchronized (this) {
                if (explainer == null) {
                    explainer = new ScoreExplainer();
                }
            }
        }
        return explainer;
    }
}
