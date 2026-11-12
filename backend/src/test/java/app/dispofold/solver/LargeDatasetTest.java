package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * The real-size month (invented): the model is built in reasonable time, off the calling thread, and the starting
 * roster already has no hard violation.
 */
class LargeDatasetTest {

    private static final Logger LOG = Logger.getLogger(LargeDatasetTest.class);

    @Test
    void modelAndStartingRosterAreFast() {
        PlanningInput input = Fixtures.input(Fixtures.LARGE);
        long t0 = System.nanoTime();
        Roster roster = new RosterBuilder(input).build();
        long built = System.nanoTime();
        InitialRoster.fill(roster);
        long filled = System.nanoTime();
        ScoreExplainer.Explanation score = new ScoreExplainer().explain(roster);
        LOG.infof("Large month: %d people, %d shifts, %d segments, %d cells; model %d ms, start roster %d ms, %s",
                roster.getStaff().size(), roster.getShiftOptions().size(), roster.getSegments().size(),
                roster.getCells().size(), (built - t0) / 1_000_000, (filled - built) / 1_000_000, score.score());
        assertThat(roster.getShiftOptions()).hasSizeGreaterThan(200);
        assertThat((filled - t0) / 1_000_000).isLessThan(15_000);
        assertThat(score.score().hardScore()).isZero();
    }

    @Test
    void solveReturnsBeforeTheModelIsBuilt() throws Exception {
        PlanningInput input = Fixtures.input(Fixtures.LARGE);
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        CountDownLatch gate = new CountDownLatch(1);
        TimefoldPlanningFacade.beforeModel = gate;
        try {
            CompletableFuture<SolveProgress> done = new CompletableFuture<>();
            facade.solve(input, new SolveOptions(1, 600, 0, 0), p -> {
                if (p.finished()) {
                    done.complete(p);
                }
            }, done::completeExceptionally);
            // The model is not built yet (the solver thread waits at the gate), but the call has returned.
            assertThat(facade.isSolving(input.period().month())).isTrue();
            assertThat(done).isNotDone();
            // A stop during the construction ends the solve without a local search.
            facade.stop(input.period().month());
            gate.countDown();
            SolveProgress result = done.get(2, TimeUnit.MINUTES);
            assertThat(result.finished()).isTrue();
            assertThat(result.assignments()).isNotEmpty();
        } finally {
            TimefoldPlanningFacade.beforeModel = null;
        }
    }
}
