package app.dispofold.api;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.importclient.DatasetValidator;
import app.dispofold.importclient.Issue;
import app.dispofold.model.Absence;
import app.dispofold.model.Period;
import app.dispofold.model.PlanStatus;
import app.dispofold.store.Store;
import app.dispofold.store.Store.StoredPlan;
import io.quarkus.security.identity.SecurityIdentity;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "planner", "viewer" })
public class MonthResource {

    public record MonthInfo(String month, boolean hasPeriod, PlanStatus status, String score) {
    }

    @Inject
    Store store;

    @Inject
    PlanService plans;

    @Inject
    ObjectMapper mapper;

    @Inject
    SecurityIdentity identity;

    @GET
    @Path("months")
    public List<MonthInfo> months() {
        Map<YearMonth, MonthInfo> months = new TreeMap<>();
        for (YearMonth m : store.periodMonths()) {
            months.put(m, new MonthInfo(m.toString(), true, PlanStatus.EMPTY, null));
        }
        for (StoredPlan p : store.plans()) {
            YearMonth m = p.plan().month();
            months.put(m, new MonthInfo(m.toString(), months.containsKey(m), p.plan().status(), p.plan().score()));
        }
        return List.copyOf(months.values());
    }

    /** Body of a rejected period: the error scheme of {@link ApiErrors} plus the validator's findings. */
    public record PeriodRejected(String code, Map<String, Object> params, String error, List<Issue> errors) {
    }

    /**
     * The period with its {@code revision}. Viewers see absences only as "ABW" (abwesend) and no wishes.
     */
    @GET
    @Path("periods/{month}")
    public ObjectNode get(@PathParam("month") String month) {
        YearMonth ym = ApiErrors.month(month);
        Period p = plans.period(ym);
        if (!identity.hasRole("planner")) {
            p = new Period(p.month(), p.absences().stream().map(a -> new Absence(a.personId(), a.date(), "ABW"))
                    .toList(), List.of(), p.fixed(), p.previousDuties());
        }
        ObjectNode node = mapper.valueToTree(p);
        node.put("revision", store.storedPeriod(ym).map(Store.StoredPeriod::revision).orElse(0L));
        return node;
    }

    /**
     * Stores the period if it is based on the stored revision (header {@code If-Match} or body field
     * {@code revision}); entries with unknown ids or dates outside the month are rejected.
     */
    @PUT
    @Path("periods/{month}")
    @RolesAllowed("planner")
    public ObjectNode put(@PathParam("month") String month, @HeaderParam("If-Match") String ifMatch,
            ObjectNode body) {
        YearMonth ym = ApiErrors.month(month);
        if (body == null) {
            throw ApiErrors.error(Status.BAD_REQUEST, "request.periodMissing", "Planungsmonat fehlt");
        }
        Long revision = revision(ifMatch, body.remove("revision"));
        Period period;
        try {
            period = mapper.treeToValue(body, Period.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw ApiErrors.error(Status.BAD_REQUEST, "request.periodFormat", "Planungsmonat im falschen Format");
        }
        Period withMonth = new Period(ym, list(period.absences()), list(period.wishes()), list(period.fixed()),
                list(period.previousDuties()));
        DatasetValidator.Result result = DatasetValidator.validatePeriod(plans.dataset(), withMonth, store.rules());
        if (!result.ok()) {
            throw new WebApplicationException(Response.status(Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON)
                    .entity(new PeriodRejected("period.invalid", Map.of("count", result.errors().size()),
                            "Der Planungsmonat enthält Fehler und wurde nicht gespeichert", result.errors()))
                    .build());
        }
        long saved = plans.savePeriod(withMonth, revision);
        ObjectNode node = mapper.valueToTree(withMonth);
        node.put("revision", saved);
        return node;
    }

    private static Long revision(String ifMatch, JsonNode inBody) {
        String value = ifMatch != null ? ifMatch.replace("\"", "").trim()
                : inBody != null && !inBody.isNull() ? inBody.asText() : null;
        if (value == null || value.isEmpty()) {
            throw ApiErrors.error(Status.PRECONDITION_REQUIRED, "period.revisionMissing",
                    "Revision des Planungsmonats fehlt");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw ApiErrors.error(Status.BAD_REQUEST, "period.revisionMissing", "Revision des Planungsmonats fehlt");
        }
    }

    private static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }
}
