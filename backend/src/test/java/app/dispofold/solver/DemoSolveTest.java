package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import app.dispofold.model.PlanAnalysis;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Acceptance test: the demo month with about 150 people is feasible with all demand covered within the time
 * limit. Slow, so it only runs with {@code -Dsolver.longTest=true}; {@code -Dsolver.longTest.seconds} sets the
 * time limit (default 300), {@code -Dsolver.longTest.unimproved} the unimproved limit in seconds (default 0: off),
 * {@code -Dsolver.longTest.fixture} another fixture file, e.g. large-dataset-2027-01.synthetic.json,
 * {@code -Dsolver.longTest.moves} a move limit (reproducible run).
 */
@EnabledIfSystemProperty(named = "solver.longTest", matches = "true")
class DemoSolveTest {

    private static final Logger LOG = Logger.getLogger(DemoSolveTest.class);

    @Test
    void demoMonthIsFeasibleAndCovered() throws Exception {
        PlanningInput input = Fixtures.input(System.getProperty("solver.longTest.fixture", Fixtures.DEMO));
        int seconds = Integer.getInteger("solver.longTest.seconds", 300);
        int unimproved = Integer.getInteger("solver.longTest.unimproved", 0);
        long moves = Long.getLong("solver.longTest.moves", 0L);
        long[] feasibleAfter = {-1};
        CompletableFuture<SolveProgress> done = new CompletableFuture<>();
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        facade.solve(input, new SolveOptions(1, seconds, unimproved, moves), p -> {
            if (feasibleAfter[0] < 0 && p.score().startsWith("0hard/0medium/")) {
                feasibleAfter[0] = p.elapsedMillis();
            }
            if (p.finished()) {
                done.complete(p);
            }
        }, done::completeExceptionally);
        SolveProgress result = done.get(seconds + 120, TimeUnit.SECONDS);

        PlanAnalysis analysis = facade.analyze(TimefoldPlanningFacadeTest.withAssignments(input, result.assignments()));
        LOG.infof("Demo month: %d people, feasible and covered after %d ms, final score %s after %d ms",
                input.dataset().people().size(), feasibleAfter[0], result.score(), result.elapsedMillis());
        analysis.constraints().forEach(c -> LOG.infof("  %s: %d (%d matches)", c.id(), c.score(), c.matches()));
        assertThat(analysis.hardViolations()).isZero();
        assertThat(analysis.gaps()).isEmpty();
        assertThat(result.score()).startsWith("0hard/0medium/");
        assertThat(feasibleAfter[0]).isBetween(0L, seconds * 1000L);
    }
}
