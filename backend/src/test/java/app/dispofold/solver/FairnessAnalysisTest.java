package app.dispofold.solver;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

import app.dispofold.model.ConstraintSummary;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.Rules;
import app.dispofold.planning.PlanningInput;
import app.dispofold.planning.SolveOptions;
import app.dispofold.planning.SolveProgress;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.score.stream.ConstraintRef;
import ai.timefold.solver.core.api.solver.SolverFactory;
import ai.timefold.solver.core.config.solver.EnvironmentMode;
import ai.timefold.solver.core.impl.score.constraint.ConstraintMatchPolicy;
import ai.timefold.solver.core.impl.score.constraint.ConstraintMatchTotal;
import ai.timefold.solver.core.impl.score.director.AbstractScoreDirector;
import ai.timefold.solver.core.impl.score.director.ScoreDirectorFactory;
import ai.timefold.solver.core.impl.solver.DefaultSolverFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Why the "Wichtigkeit" of the fairness rules barely moves the fairness figures. Solves the demo month (with usual
 * mixes, as the weight benchmark) with the four fairness weights times {@code -Dsolver.fairAnalysis=<factor>}, keeps
 * the best plan at fixed times and re-scores every plan under the default weights: soft score by constraint, the
 * fairness measure against its history floor and an ideal month, and the score change of all same-day swaps of one
 * night, weekend or unpopular duty on the final plan. {@code -Dsolver.fairAnalysis.file} saves the plans as JSON;
 * {@code -Dsolver.fairAnalysis=load} analyses a saved file without solving.
 */
@EnabledIfSystemProperty(named = "solver.fairAnalysis", matches = ".+")
class FairnessAnalysisTest {

    private static final Logger LOG = Logger.getLogger(FairnessAnalysisTest.class);
    private static final List<String> FAIR = List.of(ConstraintCatalog.FAIR_NIGHTS, ConstraintCatalog.FAIR_WEEKENDS,
            ConstraintCatalog.FAIR_HOLIDAYS, ConstraintCatalog.FAIR_UNPOPULAR);
    private static final List<ToIntFunction<Staff.History>> HISTORY = List.of(Staff.History::nights,
            Staff.History::weekends, Staff.History::holidays, Staff.History::unpopular);
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void analyse() throws Exception {
        String mode = System.getProperty("solver.fairAnalysis");
        String file = System.getProperty("solver.fairAnalysis.file", "");
        PlanningInput demo = Fixtures.input(Fixtures.DEMO);
        demo = DemoSolveTest.withMixes(demo, DemoSolveTest.clearMixes(demo.dataset()));
        Map<Integer, List<PlanAssignment>> plans;
        if (mode.equals("load")) {
            plans = JSON.readValue(new File(file), new TypeReference<TreeMap<Integer, List<PlanAssignment>>>() {
            });
        } else {
            plans = solve(demo, Double.parseDouble(mode));
            if (!file.isEmpty()) {
                JSON.writeValue(new File(file), plans);
            }
        }
        List<Integer> times = new ArrayList<>(plans.keySet());
        for (int t : times) {
            report(demo, plans.get(t), "t=" + t + "s");
        }
        swaps(demo, plans.get(times.get(times.size() - 1)));
    }

    /** The best plan at each checkpoint (seconds since the start) and at the end (key: elapsed seconds). */
    private static Map<Integer, List<PlanAssignment>> solve(PlanningInput demo, double factor) throws Exception {
        int seconds = Integer.getInteger("solver.fairAnalysis.seconds", 300);
        long seed = Long.getLong("solver.fairAnalysis.seed", 1L);
        Map<String, Long> weights = new HashMap<>();
        FAIR.forEach(id -> weights.put(id, Math.round(ConstraintCatalog.get(id).defaultWeight() * factor)));
        LOG.infof("FAIR solve: factor %s, weights %s, seed %d, %d s", factor, weights, seed, seconds);
        PlanningInput input = new PlanningInput(demo.dataset(), demo.period(), withWeights(demo.rules(), weights),
                demo.assignments());
        List<Integer> checkpoints = new ArrayList<>(List.of(10, 30, 60, 120, 180, 240));
        checkpoints.removeIf(c -> c >= seconds);
        Map<Integer, List<PlanAssignment>> plans = new TreeMap<>();
        SolveProgress[] last = {null};
        CompletableFuture<SolveProgress> done = new CompletableFuture<>();
        new TimefoldPlanningFacade().solve(input, new SolveOptions(seed, seconds, 0, 0), p -> {
            // A best plan is reported when it is found, so the best at a checkpoint is the last one before it.
            while (!checkpoints.isEmpty() && p.elapsedMillis() > checkpoints.get(0) * 1000L) {
                int c = checkpoints.remove(0);
                if (last[0] != null) {
                    plans.put(c, last[0].assignments());
                    LOG.infof("FAIR progress %3d s: %s (found at %d ms)", c, last[0].score(), last[0].elapsedMillis());
                }
            }
            last[0] = p;
            if (p.finished()) {
                LOG.infof("FAIR progress end: %s at %d ms", p.score(), p.elapsedMillis());
                plans.put((int) (p.elapsedMillis() / 1000), p.assignments());
                done.complete(p);
            }
        }, done::completeExceptionally);
        done.get(seconds + 300L, TimeUnit.SECONDS);
        return plans;
    }

    /** Key figures and the soft score by constraint under the default weights. */
    private static void report(PlanningInput demo, List<PlanAssignment> plan, String label) {
        PlanningInput input = TimefoldPlanningFacadeTest.withAssignments(demo, plan);
        PlanAnalysis a = new TimefoldPlanningFacade().analyze(input);
        long soft = a.constraints().stream().filter(c -> c.level().equals(ConstraintCatalog.SOFT))
                .mapToLong(ConstraintSummary::score).sum();
        LOG.infof("FAIR %s: score %s (default weights) | uncovered %.1f h | overstaffed %.1f h | single days off %d"
                + " | block days %d", label, a.score(), a.uncoveredHours(), a.overstaffedHours(), a.singleDaysOff(),
                a.blockDays());
        a.constraints().stream().filter(c -> c.score() != 0)
                .forEach(c -> LOG.infof("FAIR %s   %-18s %-6s weight %10d score %12d matches %5d share %5.1f %%",
                        label, c.id(), c.level(), c.weight(), c.score(), c.matches(),
                        c.level().equals(ConstraintCatalog.SOFT) ? 100.0 * c.score() / soft : 0));
        Roster roster = new RosterBuilder(input, true).build();
        for (int i = 0; i < FAIR.size(); i++) {
            fairness(roster, i, label);
        }
    }

    /**
     * The measure of one fairness comparison: U = root of the summed squared deviations of the loads (thousandths
     * per duty and 30 available days at 40 h) from their mean, as Timefold's loadBalance. Compared with U of the
     * history alone, of the month alone and of an ideal month (the same number of duties, each given to the person
     * with the lowest load), and the change of U when one duty moves between two people.
     */
    private static void fairness(Roster roster, int index, String label) {
        Map<String, Double> loads = RosterAnalyzer.comparisonLoads(roster, index);
        Map<String, Staff> staff = new HashMap<>();
        roster.getStaff().forEach(s -> staff.put(s.getId(), s));
        List<String> ids = new ArrayList<>(loads.keySet());
        Collections.sort(ids);
        int n = ids.size();
        if (n == 0) {
            // e.g. no shift marked "Fair verteilen": nobody is compared
            LOG.infof("FAIR %s   %-14s people   0", label, FAIR.get(index));
            return;
        }
        double[] total = new double[n];
        double[] hist = new double[n];
        double[] month = new double[n];
        double[] w = new double[n];
        int duties = 0;
        for (int i = 0; i < n; i++) {
            Staff s = staff.get(ids.get(i));
            w[i] = s.getFairness().weight();
            total[i] = loads.get(ids.get(i)) * 1000;
            hist[i] = HISTORY.get(index).applyAsInt(s.getHistory()) * w[i];
            month[i] = total[i] - hist[i];
            duties += (int) Math.round(month[i] / w[i]);
        }
        double[] ideal = hist.clone();
        for (int k = 0; k < duties; k++) {
            int best = 0;
            for (int i = 1; i < n; i++) {
                if (ideal[i] + w[i] < ideal[best] + w[best]) {
                    best = i;
                }
            }
            ideal[best] += w[best];
        }
        double u = unfairness(total);
        // One duty from the highest to the lowest load, and from each person above the mean to each below it.
        int max = 0;
        int min = 0;
        for (int i = 0; i < n; i++) {
            max = total[i] > total[max] ? i : max;
            min = total[i] < total[min] ? i : min;
        }
        double mean = Arrays.stream(total).average().orElse(0);
        List<Double> deltas = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (total[i] > mean && total[j] < mean && month[i] > 0) {
                    deltas.add(moved(total, i, j, w) - u);
                }
            }
        }
        Collections.sort(deltas);
        long weight = ConstraintCatalog.get(FAIR.get(index)).defaultWeight();
        LOG.infof("FAIR %s   %-14s people %3d | duties %4d | weight per duty mean %.0f | history share of load %.0f %%"
                + " | U %.0f (soft %.0f) | U history only %.0f | U month only %.0f | U ideal month %.0f"
                + " | dU one duty max->min %.1f, above->below mean median %.1f (soft %.0f) | load sd %.0f",
                label, FAIR.get(index), n, duties, Arrays.stream(w).average().orElse(0),
                100 * Arrays.stream(hist).sum() / Arrays.stream(total).sum(), u, u * weight, unfairness(hist),
                unfairness(month), unfairness(ideal), moved(total, max, min, w) - u,
                deltas.isEmpty() ? 0 : deltas.get(deltas.size() / 2),
                deltas.isEmpty() ? 0 : deltas.get(deltas.size() / 2) * weight, u / Math.sqrt(n));
    }

    private static double moved(double[] loads, int from, int to, double[] w) {
        double[] l = loads.clone();
        l[from] -= w[from];
        l[to] += w[to];
        return unfairness(l);
    }

    private static double unfairness(double[] loads) {
        double mean = Arrays.stream(loads).average().orElse(0);
        return Math.sqrt(Arrays.stream(loads).map(l -> (l - mean) * (l - mean)).sum());
    }

    /**
     * All same-day swaps (the solver's swap move) that move one night, weekend day or unpopular duty to another
     * person, scored under the default weights: how much the fairness rule gains, what the other rules lose, and the
     * factor on the fairness weight at which the swap would pay off.
     */
    private static void swaps(PlanningInput demo, List<PlanAssignment> plan) {
        Roster roster = new RosterBuilder(TimefoldPlanningFacadeTest.withAssignments(demo, plan), true).build();
        ScoreDirectorFactory<Roster, HardMediumSoftScore> factory = ((DefaultSolverFactory<Roster>) SolverFactory.<Roster>create(
                SolverSetup.config(new SolveOptions(0, 1, 0, 0), EnvironmentMode.NO_ASSERT))).getScoreDirectorFactory();
        Map<String, Predicate<DutyCell>> kinds = new LinkedHashMap<>();
        kinds.put(ConstraintCatalog.FAIR_NIGHTS, c -> c.getShift().isNight() && c.getStaff().isInNightPool());
        kinds.put(ConstraintCatalog.FAIR_WEEKENDS, c -> c.isWeekendDay() && !RosterConstraints.startsBeforeMonth(c));
        kinds.put(ConstraintCatalog.FAIR_UNPOPULAR, c -> c.getShift().isUnpopular());
        int limit = Integer.getInteger("solver.fairAnalysis.swaps", 3000);
        try (AbstractScoreDirector<Roster, HardMediumSoftScore, ?> director = factory.createScoreDirectorBuilder()
                .withConstraintMatchPolicy(ConstraintMatchPolicy.ENABLED_WITHOUT_INDICTMENTS).build()) {
            director.setWorkingSolution(roster);
            director.calculateScore();
            Map<String, HardMediumSoftScore> base = totals(director);
            Map<java.time.LocalDate, List<DutyCell>> byDay = new HashMap<>();
            roster.getCells().forEach(c -> byDay.computeIfAbsent(c.getDate(), k -> new ArrayList<>()).add(c));
            for (Map.Entry<String, Predicate<DutyCell>> kind : kinds.entrySet()) {
                String id = kind.getKey();
                List<DutyCell[]> candidates = new ArrayList<>();
                for (DutyCell a : roster.getCells()) {
                    if (!a.isInMonth() || a.isPinned() || !a.isWork() || !kind.getValue().test(a)) {
                        continue;
                    }
                    for (DutyCell b : byDay.get(a.getDate())) {
                        if (b.getStaff() != a.getStaff() && !b.isPinned() && b.getShift() != a.getShift()
                                && b.getOptions().contains(a.getShift())
                                && (b.getShift() == null || a.getOptions().contains(b.getShift()))
                                && (!b.isWork() || !kind.getValue().test(b))) {
                            candidates.add(new DutyCell[] {a, b});
                        }
                    }
                }
                Collections.shuffle(candidates, new Random(1));
                List<double[]> results = new ArrayList<>();
                Map<String, Long> costs = new TreeMap<>();
                long started = System.currentTimeMillis();
                for (DutyCell[] pair : candidates.subList(0, Math.min(limit, candidates.size()))) {
                    swap(director, pair[0], pair[1]);
                    director.calculateScore();
                    Map<String, HardMediumSoftScore> after = totals(director);
                    swap(director, pair[0], pair[1]);
                    director.calculateScore();
                    long hard = 0;
                    long medium = 0;
                    long other = 0;
                    Map<String, Long> delta = new HashMap<>();
                    for (String c : union(base, after)) {
                        HardMediumSoftScore d = after.getOrDefault(c, HardMediumSoftScore.ZERO)
                                .subtract(base.getOrDefault(c, HardMediumSoftScore.ZERO));
                        hard += d.hardScore();
                        medium += d.mediumScore();
                        if (!c.equals(id)) {
                            other += d.softScore();
                            delta.put(c, d.softScore());
                        }
                    }
                    long fair = after.getOrDefault(id, HardMediumSoftScore.ZERO).softScore()
                            - base.getOrDefault(id, HardMediumSoftScore.ZERO).softScore();
                    results.add(new double[] {hard, medium, fair, other});
                    if (fair > 0 && hard >= 0 && medium >= 0) {
                        delta.forEach((c, d) -> costs.merge(c, d, Long::sum));
                    }
                }
                summary(id, candidates.size(), results, costs, System.currentTimeMillis() - started);
            }
        }
    }

    private static void summary(String id, int candidates, List<double[]> results, Map<String, Long> costs,
            long millis) {
        List<double[]> gains = results.stream().filter(r -> r[2] > 0).toList();
        List<double[]> neutral = gains.stream().filter(r -> r[0] >= 0 && r[1] >= 0).toList();
        long improving = results.stream().filter(r -> r[0] > 0 || r[0] == 0 && (r[1] > 0 || r[1] == 0
                && r[2] + r[3] > 0)).count();
        List<Double> fair = neutral.stream().map(r -> r[2]).sorted().toList();
        List<Double> other = neutral.stream().map(r -> r[3]).sorted().toList();
        // Factor on the fairness weight at which the swap pays off: fair * f + other > 0.
        List<Double> payOff = neutral.stream().map(r -> r[3] >= 0 ? 0 : -r[3] / r[2]).sorted().toList();
        long[] paysAt = new long[5];
        double[] factors = {0.25, 1, 4, 16, 100};
        for (double[] r : neutral) {
            for (int k = 0; k < factors.length; k++) {
                if (r[2] * factors[k] + r[3] > 0) {
                    paysAt[k]++;
                }
            }
        }
        LOG.infof("FAIR swaps %-14s candidates %d, evaluated %d in %d ms | improving the score %d | fairness gain %d,"
                + " of these hard and medium not worse %d | their fairness gain median %.0f max %.0f | other soft"
                + " change median %.0f, best %.0f | pay-off factor median %.1f, 10 %% %.1f, min %.2f | paying off at"
                + " x0.25/x1/x4/x16/x100: %s", id, candidates, results.size(), millis, improving, gains.size(),
                neutral.size(), quantile(fair, 0.5), quantile(fair, 1), quantile(other, 0.5), quantile(other, 1),
                quantile(payOff, 0.5), quantile(payOff, 0.1), quantile(payOff, 0), Arrays.toString(paysAt));
        costs.entrySet().stream().filter(e -> e.getValue() != 0).sorted(Map.Entry.comparingByValue())
                .limit(8).forEach(e -> LOG.infof("FAIR swaps %-14s   cost by %-18s mean %.0f", id, e.getKey(),
                        (double) e.getValue() / Math.max(1, neutral.size())));
    }

    private static double quantile(List<Double> sorted, double q) {
        return sorted.isEmpty() ? Double.NaN : sorted.get((int) Math.min(sorted.size() - 1, q * sorted.size()));
    }

    private static List<String> union(Map<String, ?> a, Map<String, ?> b) {
        List<String> keys = new ArrayList<>(a.keySet());
        b.keySet().stream().filter(k -> !a.containsKey(k)).forEach(keys::add);
        return keys;
    }

    private static void swap(AbstractScoreDirector<Roster, HardMediumSoftScore, ?> director, DutyCell a, DutyCell b) {
        ShiftOption sa = a.getShift();
        ShiftOption sb = b.getShift();
        director.beforeVariableChanged(a, "shift");
        a.setShift(sb);
        director.afterVariableChanged(a, "shift");
        director.beforeVariableChanged(b, "shift");
        b.setShift(sa);
        director.afterVariableChanged(b, "shift");
        director.triggerVariableListeners();
    }

    private static Map<String, HardMediumSoftScore> totals(
            AbstractScoreDirector<Roster, HardMediumSoftScore, ?> director) {
        Map<String, HardMediumSoftScore> totals = new HashMap<>();
        for (Map.Entry<ConstraintRef, ConstraintMatchTotal<HardMediumSoftScore>> e
                : director.getConstraintMatchTotalMap().entrySet()) {
            totals.put(e.getKey().id(), e.getValue().getScore());
        }
        return totals;
    }

    private static Rules withWeights(Rules rules, Map<String, Long> weights) throws Exception {
        ObjectNode node = JSON.valueToTree(rules);
        node.set("weights", JSON.valueToTree(weights));
        return JSON.treeToValue(node, Rules.class);
    }
}
