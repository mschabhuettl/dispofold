package app.dispofold.solver;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.ShiftCategory;
import app.dispofold.model.ShiftMix;
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
 * {@code -Dsolver.longTest.moves} a move limit (reproducible run), {@code -Dsolver.longTest.demandFactor} scales the
 * demand (e.g. 0.6: clearly more people than demand), {@code -Dsolver.longTest.mix=apply} gives two thirds of the
 * people clear usual mixes ({@code measure}: the same mixes only measured, for a comparison).
 */
@EnabledIfSystemProperty(named = "solver.longTest", matches = "true")
class DemoSolveTest {

    private static final Logger LOG = Logger.getLogger(DemoSolveTest.class);

    @Test
    void demoMonthIsFeasibleAndCovered() throws Exception {
        PlanningInput input = scaledDemand(Fixtures.input(System.getProperty("solver.longTest.fixture", Fixtures.DEMO)),
                Double.parseDouble(System.getProperty("solver.longTest.demandFactor", "1")));
        // "apply": clear usual mixes for two thirds of the people; "measure": the same mixes only measured.
        String mixMode = System.getProperty("solver.longTest.mix", "");
        Map<String, ShiftMix> mixes = mixMode.isEmpty() ? Map.of() : clearMixes(input.dataset());
        if (mixMode.equals("apply")) {
            input = withMixes(input, mixes);
        }
        int seconds = Integer.getInteger("solver.longTest.seconds", 300);
        int unimproved = Integer.getInteger("solver.longTest.unimproved", 0);
        long moves = Long.getLong("solver.longTest.moves", 0L);
        long[] feasibleAfter = {-1};
        CompletableFuture<SolveProgress> done = new CompletableFuture<>();
        TimefoldPlanningFacade facade = new TimefoldPlanningFacade();
        facade.solve(input, new SolveOptions(1, seconds, unimproved, moves), p -> {
            // Feasible: no hard violation. Coverage is checked on the final plan (medium also counts overstaffing).
            if (feasibleAfter[0] < 0 && p.score().startsWith("0hard/")) {
                feasibleAfter[0] = p.elapsedMillis();
            }
            if (p.finished()) {
                done.complete(p);
            }
        }, done::completeExceptionally);
        SolveProgress result = done.get(seconds + 120, TimeUnit.SECONDS);

        PlanAnalysis analysis = facade.analyze(TimefoldPlanningFacadeTest.withAssignments(input, result.assignments()));
        LOG.infof("Demo month: %d people, feasible after %d ms, final score %s after %d ms, uncovered %.1f h,"
                + " overstaffed %.1f h", input.dataset().people().size(), feasibleAfter[0], result.score(),
                result.elapsedMillis(), analysis.uncoveredHours(), analysis.overstaffedHours());
        analysis.constraints().forEach(c -> LOG.infof("  %s: %d (%d matches)", c.id(), c.score(), c.matches()));
        analysis.constraints().stream().filter(c -> c.id().equals(ConstraintCatalog.ISOLATED_DAY_OFF)
                || c.id().equals(ConstraintCatalog.BLOCK_LENGTH)).forEach(c -> LOG.infof("Blocks: %s %d (score %d)",
                        c.id(), c.matches(), c.score()));
        if (!mixes.isEmpty()) {
            double deviation = analysis.people().stream().filter(p -> mixes.containsKey(p.personId()))
                    .mapToDouble(p -> categoryDeviation(mixes.get(p.personId()), p.plannedMix())).average()
                    .orElse(0);
            LOG.infof("Mix (%s): %d people with a mix, mean category share deviation %.3f", mixMode, mixes.size(),
                    deviation);
        }
        analysis.people().stream().max(java.util.Comparator.comparingDouble(DemoSolveTest::overTarget))
                .ifPresent(p -> LOG.infof("Largest hours over target: %.1f h (%.1f counted, %.1f target)",
                        overTarget(p), p.plannedHours() + p.creditedHours(), p.targetHours()));
        assertThat(analysis.hardViolations()).isZero();
        assertThat(analysis.gaps()).isEmpty();
        assertThat(analysis.uncoveredHours()).isZero();
        assertThat(feasibleAfter[0]).isBetween(0L, seconds * 1000L);
    }

    /**
     * The fixture with the minimum and maximum staffing of every demand rule times {@code factor}, rounded, at least
     * 1: with a factor below 1 there is clearly more capacity than demand.
     */
    static PlanningInput scaledDemand(PlanningInput input, double factor) {
        if (factor == 1) {
            return input;
        }
        app.dispofold.model.Dataset d = input.dataset();
        java.util.List<app.dispofold.model.DemandRule> demand = d.demand().stream()
                .map(r -> new app.dispofold.model.DemandRule(r.areaId(), r.days(), r.date(), r.from(), r.to(),
                        (int) Math.max(1, Math.round(r.min() * factor)),
                        r.max() == null ? null : (int) Math.max(1, Math.round(r.max() * factor))))
                .toList();
        app.dispofold.model.Dataset changed = new app.dispofold.model.Dataset(d.format(), d.version(), d.source(),
                d.areas(), d.shifts(), demand, d.holidays(), d.absenceTypes(), d.people(), d.restrictions(),
                d.history(), d.periods());
        return new PlanningInput(changed, input.period(), input.rules(), input.assignments());
    }

    /**
     * Clear usual mixes: every third person mostly day duties with a few nights, every third mostly nights, the
     * rest none; only for people whose areas have both day and night shifts.
     */
    static Map<String, ShiftMix> clearMixes(app.dispofold.model.Dataset d) {
        Map<String, ShiftMix> mixes = new java.util.LinkedHashMap<>();
        int i = 0;
        for (app.dispofold.model.Person p : d.people()) {
            java.util.Set<ShiftCategory> categories = d.shifts().stream()
                    .filter(s -> p.areaIds().contains(s.areaId())).map(app.dispofold.model.ShiftType::category)
                    .collect(java.util.stream.Collectors.toSet());
            if (categories.contains(ShiftCategory.DAY) && categories.contains(ShiftCategory.NIGHT)) {
                if (i % 3 == 0) {
                    mixes.put(p.id(), new ShiftMix(Map.of(ShiftCategory.DAY, 0.9, ShiftCategory.NIGHT, 0.1), Map.of()));
                } else if (i % 3 == 1) {
                    mixes.put(p.id(), new ShiftMix(Map.of(ShiftCategory.NIGHT, 0.7, ShiftCategory.DAY, 0.3), Map.of()));
                }
                i++;
            }
        }
        return mixes;
    }

    static PlanningInput withMixes(PlanningInput input, Map<String, ShiftMix> mixes) {
        app.dispofold.model.Dataset d = input.dataset();
        java.util.List<app.dispofold.model.Person> people = d.people().stream()
                .map(p -> new app.dispofold.model.Person(p.id(), p.firstName(), p.lastName(), p.shortName(),
                        p.weeklyHours(), p.areaIds(), p.hoursAccount(), p.compRestBalance(), p.familyRules(),
                        p.externalId(), p.entryDate(), p.exitDate(), mixes.get(p.id())))
                .toList();
        app.dispofold.model.Dataset changed = new app.dispofold.model.Dataset(d.format(), d.version(), d.source(),
                d.areas(), d.shifts(), d.demand(), d.holidays(), d.absenceTypes(), people, d.restrictions(),
                d.history(), d.periods());
        return new PlanningInput(changed, input.period(), input.rules(), input.assignments());
    }

    /** Mean absolute difference of the category shares (0: exactly the mix, 1: completely different). */
    static double categoryDeviation(ShiftMix mix, ShiftMix planned) {
        double sum = 0;
        for (ShiftCategory c : ShiftCategory.values()) {
            double p = planned == null ? 0 : planned.categoriesOrEmpty().getOrDefault(c, 0.0);
            sum += Math.abs(mix.categoriesOrEmpty().getOrDefault(c, 0.0) - p);
        }
        return sum / 2;
    }

    /** Counted hours (duties and credited days) above the target of the month. */
    private static double overTarget(app.dispofold.model.PersonStats p) {
        return p.plannedHours() + p.creditedHours() - p.targetHours();
    }
}
