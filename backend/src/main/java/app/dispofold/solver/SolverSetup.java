package app.dispofold.solver;

import java.util.ArrayList;
import java.util.List;

import app.dispofold.planning.SolveOptions;

import ai.timefold.solver.core.config.heuristic.selector.move.composite.UnionMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.ChangeMoveSelectorConfig;
import ai.timefold.solver.core.config.heuristic.selector.move.generic.SwapMoveSelectorConfig;
import ai.timefold.solver.core.config.localsearch.LocalSearchPhaseConfig;
import ai.timefold.solver.core.config.localsearch.decider.acceptor.LocalSearchAcceptorConfig;
import ai.timefold.solver.core.config.localsearch.decider.forager.LocalSearchForagerConfig;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.config.solver.SolverConfig;
import ai.timefold.solver.core.config.solver.termination.TerminationConfig;
import ai.timefold.solver.core.impl.heuristic.selector.common.decorator.SelectionFilter;
import ai.timefold.solver.core.impl.heuristic.selector.move.generic.SelectorBasedSwapMove;
import ai.timefold.solver.core.impl.score.director.ScoreDirector;

/** Builds the solver configuration in code, independent of application.properties. */
public final class SolverSetup {

    private SolverSetup() {
    }

    public static SolverConfig config(SolveOptions options, EnvironmentMode mode) {
        List<TerminationConfig> limits = new ArrayList<>();
        if (options.timeLimitSeconds() > 0) {
            limits.add(new TerminationConfig().withSecondsSpentLimit((long) options.timeLimitSeconds()));
        }
        if (options.unimprovedSeconds() > 0) {
            limits.add(new TerminationConfig().withUnimprovedSecondsSpentLimit((long) options.unimprovedSeconds()));
        }
        if (options.moveLimit() > 0) {
            limits.add(new TerminationConfig().withMoveCountLimit(options.moveLimit()));
        }
        // Without any limit local search would run forever; one hour is the safety net.
        TerminationConfig termination = limits.isEmpty()
                ? new TerminationConfig().withHoursSpentLimit(1L)
                : new TerminationConfig().withTerminationConfigList(limits);

        LocalSearchPhaseConfig localSearch = new LocalSearchPhaseConfig()
                .withMoveSelectorConfig(new UnionMoveSelectorConfig().withMoveSelectors(
                        new ChangeMoveSelectorConfig(),
                        new SwapMoveSelectorConfig().withFilterClass(SameDaySwap.class)))
                .withAcceptorConfig(new LocalSearchAcceptorConfig().withLateAcceptanceSize(400))
                .withForagerConfig(new LocalSearchForagerConfig().withAcceptedCountLimit(1));

        return new SolverConfig()
                .withSolutionClass(Roster.class)
                .withEntityClasses(DutyCell.class)
                .withConstraintProviderClass(RosterConstraints.class)
                .withEnvironmentMode(mode)
                .withRandomSeed(options.seed())
                .withTerminationConfig(termination)
                .withPhases(localSearch);
    }

    /** Swaps only make sense between two people on the same day. */
    public static class SameDaySwap implements SelectionFilter<Roster, SelectorBasedSwapMove<Roster>> {
        @Override
        public boolean accept(ScoreDirector<Roster> scoreDirector, SelectorBasedSwapMove<Roster> move) {
            DutyCell a = (DutyCell) move.getLeftEntity();
            DutyCell b = (DutyCell) move.getRightEntity();
            return a.getDate().equals(b.getDate()) && a.getShift() != b.getShift()
                    && (a.getShift() == null || b.getOptions().contains(a.getShift()))
                    && (b.getShift() == null || a.getOptions().contains(b.getShift()));
        }
    }
}
