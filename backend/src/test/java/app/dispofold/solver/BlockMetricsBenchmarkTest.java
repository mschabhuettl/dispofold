package app.dispofold.solver;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.Rules;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The block figures of a solved month ({@link PlanMetrics}), the gaps and the wall time, as one line. Slow, so it only
 * runs with {@code -Dsolver.metrics=true}; {@code -Dsolver.metrics.fixture} the fixture (default the large month),
 * {@code -Dsolver.metrics.seconds} the time limit (default 300), {@code -Dsolver.metrics.unimproved} the unimproved
 * limit in seconds (default 0), {@code -Dsolver.metrics.moves} a move limit (default 0: none; the thoroughness
 * NORMAL is 1000000), {@code -Dsolver.metrics.seed} the seed (default 1),
 * {@code -Dsolver.metrics.weights=blockLength:6000,...} weights, {@code -Dsolver.metrics.mix=apply} clear usual mixes
 * for two thirds of the people (as {@link DemoSolveTest}) and {@code -Dsolver.metrics.nightMixMonths} the window of
 * the usual night share (default: the rules' default).
 */
@EnabledIfSystemProperty(named = "solver.metrics", matches = "true")
class BlockMetricsBenchmarkTest {

    private static final Logger LOG = Logger.getLogger(BlockMetricsBenchmarkTest.class);

    @Test
    void metrics() throws Exception {
        PlanningInput input = Fixtures.input(System.getProperty("solver.metrics.fixture", Fixtures.LARGE));
        if (System.getProperty("solver.metrics.mix", "").equals("apply")) {
            input = DemoSolveTest.withMixes(input, DemoSolveTest.clearMixes(input.dataset()));
        }
        Map<String, Long> weights = new HashMap<>();
        for (String pair : System.getProperty("solver.metrics.weights", "").split(",")) {
            if (!pair.isBlank()) {
                String[] kv = pair.split(":");
                weights.put(kv[0].trim(), Long.parseLong(kv[1].trim()));
            }
        }
        Rules r = input.rules();
        input = new PlanningInput(input.dataset(), input.period(), new Rules(r.minDailyRestHours(),
                r.minWeeklyRestHours(), r.maxDutyHours(), r.maxWeekHours(), r.maxAverageWeekHours(), r.minBlock(),
                r.maxBlock(), r.maxWishesPerPerson(), r.holidayWorkEarnsCompRest(), r.nightStart(), r.nightEnd(),
                r.nightMinHours(), r.accountCarryShare(), r.historyMonths(), weights, r.timeZone(),
                r.averagingWeeks(), r.maxOverTargetPercent(), r.minFreeSundays(), r.nightDistribution(),
                r.averageTolerancePercent(), r.freeInsideAbsence(), r.averagingPeriod(), r.accountBalancing(),
                r.carryMinusRest(), null, null, null, null, Integer.getInteger("solver.metrics.nightMixMonths"), null),
                input.assignments());
        int seconds = Integer.getInteger("solver.metrics.seconds", 300);
        SolveOptions options = new SolveOptions(Long.getLong("solver.metrics.seed", 1L), seconds,
                Integer.getInteger("solver.metrics.unimproved", 0), Long.getLong("solver.metrics.moves", 0L));
        CompletableFuture<SolveProgress> done = new CompletableFuture<>();
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        facade.solve(input, options, p -> {
            if (p.finished()) {
                done.complete(p);
            }
        }, done::completeExceptionally);
        SolveProgress result = done.get(seconds + 300L, TimeUnit.SECONDS);
        PlanAnalysis analysis = facade.analyze(TimefoldPlanningFacadeTest.withAssignments(input, result.assignments()));
        LOG.infof("Metrics: %s, gaps %d, hard %d, wall %d s, score %s", PlanMetrics.of(input, result.assignments()),
                analysis.gaps().size(), analysis.hardViolations(), result.elapsedMillis() / 1000, result.score());
    }
}
