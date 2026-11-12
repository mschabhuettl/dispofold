package app.dispofold.api;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;

import app.dispofold.model.Rules;
import app.dispofold.store.Store;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "planner", "viewer" })
public class RulesResource {

    /** A catalogue entry with the weight currently in effect. */
    public record ConstraintWeight(String id, String level, long defaultWeight, long weight) {
    }

    @Inject
    Store store;

    @Inject
    PlanService plans;

    @GET
    @Path("rules")
    public Rules get() {
        return store.rules();
    }

    @PUT
    @Path("rules")
    @RolesAllowed("planner")
    public Rules put(Rules rules) {
        String problem = problem(rules);
        if (problem != null) {
            throw ApiErrors.error(Status.BAD_REQUEST, problem,
                    "rules.minDailyRest".equals(problem) ? Map.of("minHours", Rules.MIN_DAILY_REST_HOURS) : Map.of(),
                    "Ungültige Regeln");
        }
        store.saveRules(rules);
        return rules;
    }

    /** The error code of the first reason why the rules cannot be used, or null. */
    static String problem(Rules r) {
        if (r == null) {
            return "rules.missing";
        }
        if (r.nightStart() == null || r.nightEnd() == null) {
            return "rules.nightWindow";
        }
        if (r.minDailyRestHours() < Rules.MIN_DAILY_REST_HOURS) {
            return "rules.minDailyRest";
        }
        if (r.minWeeklyRestHours() < 0 || r.maxDutyHours() <= 0 || r.maxWeekHours() <= 0
                || r.maxAverageWeekHours() <= 0 || r.nightMinHours() < 0 || r.historyMonths() < 0
                || r.maxWishesPerPerson() < 0 || r.averagingWeeks() < 1) {
            return "rules.values";
        }
        if (r.minBlock() < 1 || r.maxBlock() < r.minBlock()) {
            return "rules.block";
        }
        if (r.accountCarryShare() < 0 || r.accountCarryShare() > 1) {
            return "rules.accountShare";
        }
        if (r.weights() != null && r.weights().values().stream().anyMatch(w -> w == null || w < 0)) {
            return "rules.weights";
        }
        if (r.timeZone() == null || !ZoneId.getAvailableZoneIds().contains(r.timeZone())) {
            return "rules.timeZone";
        }
        return null;
    }

    @GET
    @Path("constraints")
    public List<ConstraintWeight> constraints() {
        Map<String, Long> weights = store.rules().weights() == null ? Map.of() : store.rules().weights();
        return plans.facade().constraintCatalog().stream()
                .map(c -> new ConstraintWeight(c.id(), c.level(), c.defaultWeight(),
                        weights.getOrDefault(c.id(), c.defaultWeight())))
                .toList();
    }
}
