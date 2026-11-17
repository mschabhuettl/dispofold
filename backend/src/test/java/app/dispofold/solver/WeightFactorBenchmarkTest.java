package app.dispofold.solver;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import app.dispofold.model.ConstraintSummary;
import app.dispofold.model.PersonStats;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.Rules;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Benchmark of the "Wichtigkeit" factors of the rules page: the demo month with the default weights of some
 * constraints times a factor, rounded as the page does. Slow, so it only runs with {@code -Dsolver.weightFactor=0.5}
 * (or 1, 2) and {@code -Dsolver.weightFactor.ids=blockLength,isolatedDayOff}; {@code -Dsolver.weightFactor.seconds}
 * sets the time limit (default 300), {@code -Dsolver.weightFactor.seed} the seed (default 1) and
 * {@code -Dsolver.weightFactor.mix=apply} gives two thirds of the people clear usual mixes (as {@link DemoSolveTest}).
 * Prints one line of key figures.
 */
@EnabledIfSystemProperty(named = "solver.weightFactor", matches = ".+")
class WeightFactorBenchmarkTest {

    private static final Logger LOG = Logger.getLogger(WeightFactorBenchmarkTest.class);

    @Test
    void demoMonthWithFactor() throws Exception {
        double factor = Double.parseDouble(System.getProperty("solver.weightFactor"));
        String ids = System.getProperty("solver.weightFactor.ids", "");
        int seconds = Integer.getInteger("solver.weightFactor.seconds", 300);
        Map<String, Long> weights = new HashMap<>();
        Arrays.stream(ids.split(",")).filter(id -> !id.isBlank()).forEach(id -> weights.put(id,
                Math.round(ConstraintCatalog.get(id).defaultWeight() * factor)));
        long seed = Long.getLong("solver.weightFactor.seed", 1L);
        PlanningInput demo = Fixtures.input(Fixtures.DEMO);
        if (System.getProperty("solver.weightFactor.mix", "").equals("apply")) {
            demo = DemoSolveTest.withMixes(demo, DemoSolveTest.clearMixes(demo.dataset()));
        }
        PlanningInput input = new PlanningInput(demo.dataset(), demo.period(), withWeights(demo.rules(), weights),
                demo.assignments());

        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        SolveProgress result = TimefoldPlanningFacadeTest.solve(facade, input, new SolveOptions(seed, seconds, 0, 0));
        PlanAnalysis a = facade.analyze(TimefoldPlanningFacadeTest.withAssignments(input, result.assignments()));

        Map<String, ConstraintSummary> byId = new HashMap<>();
        a.constraints().forEach(c -> byId.put(c.id(), c));
        // the fairness rules' own measure: root of the summed squared load deviations (score / weight)
        Function<String, Double> unfairness = id -> byId.get(id).weight() == 0 ? 0
                : -(double) byId.get(id).score() / byId.get(id).weight();
        int wishes = a.people().stream().mapToInt(PersonStats::wishes).sum();
        int met = a.people().stream().mapToInt(PersonStats::wishesMet).sum();
        double meanDeviation = a.people().stream()
                .mapToDouble(p -> Math.abs(p.plannedHours() + p.creditedHours() - p.scaledGoalHours())).average()
                .orElse(0);
        double maxOver = a.people().stream()
                .mapToDouble(p -> p.plannedHours() + p.creditedHours() - p.goalHours()).max().orElse(0);
        LOG.infof("FACTOR %s x %s seed %d: score %s | uncovered %.1f h | overstaffed %.1f h | single days off %d"
                + " | block days %d | wishes met %d/%d | hour deviation mean %.2f h, max over goal %.1f h"
                + " | unfairness nights %.0f weekends %.0f holidays %.0f unpopular %.0f", factor, ids, seed, a.score(),
                a.uncoveredHours(), a.overstaffedHours(), a.singleDaysOff(), a.blockDays(), met, wishes, meanDeviation,
                maxOver, unfairness.apply(ConstraintCatalog.FAIR_NIGHTS), unfairness.apply(ConstraintCatalog.FAIR_WEEKENDS),
                unfairness.apply(ConstraintCatalog.FAIR_HOLIDAYS), unfairness.apply(ConstraintCatalog.FAIR_UNPOPULAR));
    }

    /** The rules with other weights, via JSON so that it does not depend on the fields of the record. */
    private static Rules withWeights(Rules rules, Map<String, Long> weights) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        ObjectNode node = mapper.valueToTree(rules);
        node.set("weights", mapper.valueToTree(weights));
        return mapper.treeToValue(node, Rules.class);
    }
}
