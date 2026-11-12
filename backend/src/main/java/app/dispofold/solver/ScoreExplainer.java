package app.dispofold.solver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.dispofold.planning.SolveOptions;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.ConstraintRef;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.impl.score.constraint.ConstraintMatch;
import ai.timefold.solver.core.impl.score.constraint.ConstraintMatchPolicy;
import ai.timefold.solver.core.impl.score.constraint.ConstraintMatchTotal;
import ai.timefold.solver.core.impl.score.director.AbstractScoreDirector;
import ai.timefold.solver.core.impl.score.director.ScoreDirectorFactory;
import ai.timefold.solver.core.impl.solver.DefaultSolverFactory;

/**
 * Calculates a roster's score together with all constraint matches. The score analysis API of Timefold is a
 * commercial feature, so this uses the score director of the community edition directly.
 */
public final class ScoreExplainer {

    /** All matches of one constraint. */
    public record Total(HardMediumSoftScore score, List<Match> matches) {
    }

    public record Match(HardMediumSoftScore score, Hit hit) {
    }

    /** The score and the totals by constraint id. */
    public record Explanation(HardMediumSoftScore score, Map<String, Total> totals) {
        public Total total(String constraintId) {
            return totals.getOrDefault(constraintId, new Total(HardMediumSoftScore.ZERO, List.of()));
        }
    }

    private final ScoreDirectorFactory<Roster, HardMediumSoftScore> factory;

    public ScoreExplainer() {
        SolverFactory<Roster> solverFactory = SolverFactory.create(
                SolverSetup.config(new SolveOptions(0, 1, 0, 0), EnvironmentMode.PHASE_ASSERT));
        this.factory = ((DefaultSolverFactory<Roster>) solverFactory).getScoreDirectorFactory();
    }

    public Explanation explain(Roster roster) {
        try (AbstractScoreDirector<Roster, HardMediumSoftScore, ?> director = factory.createScoreDirectorBuilder()
                .withConstraintMatchPolicy(ConstraintMatchPolicy.ENABLED_WITHOUT_INDICTMENTS).build()) {
            director.setWorkingSolution(roster);
            HardMediumSoftScore score = director.calculateScore().raw();
            Map<String, Total> totals = new LinkedHashMap<>();
            for (Map.Entry<ConstraintRef, ConstraintMatchTotal<HardMediumSoftScore>> e
                    : director.getConstraintMatchTotalMap().entrySet()) {
                List<Match> matches = new ArrayList<>();
                for (ConstraintMatch<HardMediumSoftScore> m : e.getValue().getConstraintMatchSet()) {
                    matches.add(new Match(m.getScore(), m.getJustification()));
                }
                totals.put(e.getKey().id(), new Total(e.getValue().getScore(), matches));
            }
            return new Explanation(score, totals);
        }
    }
}
