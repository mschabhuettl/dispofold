package app.dispofold.api;

import java.time.YearMonth;
import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.export.Anonymizer;
import app.dispofold.export.Corrections;
import app.dispofold.importclient.Issue;
import app.dispofold.model.Dataset;
import app.dispofold.model.Plan;
import app.dispofold.store.Store;

/**
 * Files for outside Dispofold (planners only): the master data changed since the last import as a corrections file,
 * and a pseudonymised dataset and plan with a separate key.
 */
@Path("/api/data")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("planner")
public class DataResource {

    /**
     * @param file the corrections file
     * @param notes changes the file cannot express
     * @param count fields, exclusions and demand areas in the file
     */
    public record CorrectionsExport(ObjectNode file, List<Issue> notes, int count) {
    }

    /**
     * @param dataset the pseudonymised dataset with all planning months, to share
     * @param plan the pseudonymised plan of the month, to share
     * @param key the key file (CSV): pseudonym, id, external id and names; stays with the planner
     */
    public record Anonymized(Dataset dataset, Plan plan, String key, int people) {
    }

    @Inject
    Store store;

    @Inject
    MasterDataService master;

    @Inject
    PlanService plans;

    @Inject
    ObjectMapper mapper;

    /** The changes of the master data since the last import, as a corrections file (version 2). */
    @GET
    @Path("corrections")
    public CorrectionsExport corrections() {
        Dataset base;
        Dataset current;
        synchronized (Store.DATASET_LOCK) {
            current = plans.dataset();
            base = store.baseline().orElseThrow(ImportResource::noBaseline);
        }
        Corrections.Export export = Corrections.export(base, current, mapper);
        return new CorrectionsExport(export.file(), export.notes(), export.count());
    }

    /**
     * The dataset (master data and all planning months) and the plan of the month pseudonymised. Refused when a name
     * of the key still occurs as a word in them ({@code anonymize.leak} with the places, never the names).
     */
    @GET
    @Path("anonymized")
    public Anonymized anonymized(@QueryParam("month") String month) {
        YearMonth ym = ApiErrors.month(month);
        plans.requireNotSolving(ym);
        Dataset d;
        synchronized (Store.DATASET_LOCK) {
            d = master.export();
        }
        Anonymizer.Result r = Anonymizer.anonymize(d, plans.plan(ym), mapper);
        if (!r.leaks().isEmpty()) {
            throw ApiErrors.error(Status.CONFLICT, "anonymize.leak", PlanService.params("count", r.leaks().size(),
                    "places", String.join(", ", r.leaks().subList(0, Math.min(10, r.leaks().size())))),
                    "Ein Name kommt nach dem Pseudonymisieren noch vor; nichts exportiert");
        }
        return new Anonymized(r.dataset(), r.plan(), r.key(), r.people());
    }
}
