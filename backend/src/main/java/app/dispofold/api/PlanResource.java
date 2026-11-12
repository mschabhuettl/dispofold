package app.dispofold.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.api.PlanService.PlanState;
import app.dispofold.api.PlanService.SolveRequest;
import app.dispofold.export.PlanExporter;
import app.dispofold.model.ConstraintHit;
import app.dispofold.model.CoverageSegment;
import app.dispofold.model.HistoryEntry;
import app.dispofold.model.PersonExplanation;
import app.dispofold.model.PersonStats;
import app.dispofold.model.Plan;
import app.dispofold.model.PlanAnalysis;
import app.dispofold.model.PlanAssignment;
import app.dispofold.planning.PlanningInput;
import io.quarkus.security.identity.SecurityIdentity;

@Path("/api/plans/{month}")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({ "planner", "viewer" })
public class PlanResource {

    @Inject
    PlanService plans;

    @Inject
    PlanExporter exporter;

    @Inject
    ObjectMapper mapper;

    @Inject
    SecurityIdentity identity;

    @GET
    public PlanState get(@PathParam("month") String month) {
        return plans.state(ApiErrors.month(month));
    }

    @POST
    @Path("solve")
    @RolesAllowed("planner")
    public Response solve(@PathParam("month") String month, String body) {
        // The body is optional, so the request may come without a content type.
        SolveRequest request = null;
        if (body != null && !body.isBlank()) {
            try {
                request = mapper.readValue(body, SolveRequest.class);
            } catch (JsonProcessingException e) {
                throw ApiErrors.error(Status.BAD_REQUEST, "request.solveOptions", "Ungültige Planungsoptionen");
            }
        }
        return Response.accepted(plans.solve(ApiErrors.month(month), request)).build();
    }

    @POST
    @Path("stop")
    @RolesAllowed("planner")
    public PlanState stop(@PathParam("month") String month) {
        return plans.stop(ApiErrors.month(month));
    }

    @PUT
    @Path("cells")
    @RolesAllowed("planner")
    public Plan setCell(@PathParam("month") String month, PlanAssignment cell) {
        if (cell == null) {
            throw ApiErrors.error(Status.BAD_REQUEST, "request.noCell", "Zelle fehlt");
        }
        return plans.setCell(ApiErrors.month(month), cell);
    }

    @POST
    @Path("unpin")
    @RolesAllowed("planner")
    public Plan unpin(@PathParam("month") String month) {
        return plans.unpin(ApiErrors.month(month));
    }

    @GET
    @Path("analysis")
    public PlanAnalysis analysis(@PathParam("month") String month) {
        PlanAnalysis analysis = plans.facade().analyze(plans.input(ApiErrors.month(month)));
        if (identity.hasRole("planner")) {
            return analysis;
        }
        return new PlanAnalysis(analysis.score(), analysis.hardViolations(), analysis.constraints(), analysis.gaps(),
                analysis.people().stream().map(PlanResource::forViewer).toList());
    }

    /** Key figures without wishes, hours account and time off in lieu (which would reveal the absence type). */
    static PersonStats forViewer(PersonStats s) {
        return new PersonStats(s.personId(), s.targetHours(), s.creditedHours(), s.plannedHours(), 0, 0,
                s.duties(), s.nights(), s.weekendsWorked(), s.weekendsOff(), s.holidays(), s.unpopular(), 0, 0,
                s.compRestPlanned(), s.compRestEarned(), s.compRestBalanceAfter(), s.singleDays(), s.historyNights(),
                s.historyWeekends(), s.historyHolidays(), s.historyUnpopular(), 0, 0, s.availableDays(), 0,
                s.averageWeekHours(), s.averageComplete());
    }

    /** Hits that would show wishes, family rules, restrictions or the kind of absence are planners only. */
    static final Set<String> PRIVATE_HITS = Set.of("hit.wishes", "hit.family", "hit.onRequest",
            "hit.neverRestriction");

    @GET
    @Path("people/{personId}")
    public PersonExplanation explain(@PathParam("month") String month, @PathParam("personId") String personId) {
        PlanningInput input = plans.input(ApiErrors.month(month));
        if (input.dataset().people().stream().noneMatch(p -> p.id().equals(personId))) {
            throw ApiErrors.error(Status.NOT_FOUND, "person.unknown", Map.of("personId", personId),
                    "Unbekannte Person");
        }
        PersonExplanation explanation = plans.facade().explain(input, personId);
        if (identity.hasRole("planner")) {
            return explanation;
        }
        List<ConstraintHit> hits = explanation.hits().stream().filter(h -> !PRIVATE_HITS.contains(h.code()))
                .map(h -> "hit.absence".equals(h.code())
                        ? new ConstraintHit(h.constraintId(), h.level(), h.date(), h.score(), h.code(),
                                Map.of("typeCode", "ABW"))
                        : h)
                .toList();
        return new PersonExplanation(forViewer(explanation.stats()), hits);
    }

    @GET
    @Path("coverage/{areaId}")
    public List<CoverageSegment> coverage(@PathParam("month") String month, @PathParam("areaId") String areaId) {
        PlanningInput input = plans.input(ApiErrors.month(month));
        if (input.dataset().areas().stream().noneMatch(a -> a.id().equals(areaId))) {
            throw ApiErrors.error(Status.NOT_FOUND, "area.unknown", Map.of("areaId", areaId),
                    "Unbekannter Bereich");
        }
        return plans.facade().coverage(input, areaId);
    }

    /** Closes the month (balances into the history, read-only); see {@link PlanService#closeMonth}. */
    @POST
    @Path("finalize")
    @RolesAllowed("planner")
    public List<HistoryEntry> finalizeMonth(@PathParam("month") String month) {
        return plans.closeMonth(ApiErrors.month(month));
    }

    @POST
    @Path("reopen")
    @RolesAllowed("planner")
    public PlanState reopen(@PathParam("month") String month) {
        return plans.reopen(ApiErrors.month(month));
    }

    /**
     * Planners only (the export contains absences and names). Not while the month is being planned; a plan with
     * hard violations is marked in the file and its name.
     */
    @GET
    @Path("export")
    @RolesAllowed("planner")
    @Produces({ MediaType.APPLICATION_JSON, "text/csv", PlanExporter.XLSX })
    public Response export(@PathParam("month") String month, @QueryParam("format") String format) {
        var ym = ApiErrors.month(month);
        plans.requireNotSolving(ym);
        Plan plan = plans.plan(ym);
        int violations = plans.state(ym).hardViolations();
        String base = "dienstplan-" + ym + (violations > 0 ? "-mit-verstoessen" : "");
        return switch (format == null ? "json" : format) {
            case "json" -> download(exporter.json(plan), MediaType.APPLICATION_JSON, base + ".json");
            case "csv" -> download(exporter.csv(plans.dataset(), plans.period(ym), plan, violations),
                    "text/csv; charset=UTF-8", base + ".csv");
            case "xlsx" -> {
                PlanningInput input = plans.input(ym);
                yield download(exporter.xlsx(input.dataset(), input.period(), plan,
                        plans.facade().analyze(input).people(), violations), PlanExporter.XLSX, base + ".xlsx");
            }
            default -> throw ApiErrors.error(Status.BAD_REQUEST, "export.format", "Format xlsx, csv oder json erwartet");
        };
    }

    @POST
    @Path("import")
    @RolesAllowed("planner")
    public Plan importPlan(@PathParam("month") String month, Plan plan) {
        return plans.importPlan(ApiErrors.month(month), plan);
    }

    private static Response download(byte[] body, String type, String fileName) {
        return Response.ok(body, type).header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .build();
    }
}
