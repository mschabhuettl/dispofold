package app.dispofold.api;

import java.time.YearMonth;
import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.export.Anonymizer;
import app.dispofold.export.LoadedCorrections;
import app.dispofold.importclient.Issue;
import app.dispofold.model.Dataset;
import app.dispofold.model.Plan;
import app.dispofold.store.Store;

/**
 * Files for outside Dispofold (planners only): the corrections in effect (those applied to the last import and the
 * master data changed since) as a corrections file, and a pseudonymised dataset and plan with a separate key.
 */
@Path("/api/data")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("planner")
public class DataResource {

    /**
     * @param file the corrections file
     * @param notes changes the file cannot express
     * @param count fields, exclusions and demand areas in the file
     * @param earlier of these, the ones from the corrections applied to the last import, unchanged since
     * @param withdrawn exclusions taken back since the last import, no longer in the file
     * @param complete the last import listed its applied corrections; false: only the changes since that import
     */
    public record CorrectionsExport(ObjectNode file, List<Issue> notes, int count, int earlier, int withdrawn,
            boolean complete) {
    }

    /**
     * @param dataset the pseudonymised dataset with all planning months, to share
     * @param plan the pseudonymised plan of the month, to share
     * @param key the key file (CSV): pseudonym, id, external id and names; stays with the planner
     */
    public record Anonymized(Dataset dataset, Plan plan, String key, int people) {
    }

    @Inject
    MasterDataService master;

    @Inject
    CorrectionsService corrections;

    @Inject
    PlanService plans;

    @Inject
    ObjectMapper mapper;

    /**
     * The corrections applied to the last import and the changes of the master data since, as a corrections file
     * (version 2); while a loaded corrections file waits for an import, that file with the changes over it.
     */
    @GET
    @Path("corrections")
    public CorrectionsExport corrections() {
        CorrectionsService.Effective e = corrections.effective().orElseThrow(ImportResource::noBaseline);
        return new CorrectionsExport(e.file(), e.notes(), e.count(), e.earlier(), e.withdrawn(), e.complete());
    }

    /** The loaded corrections file waiting for an import (with its summary), and whether there are any corrections. */
    @GET
    @Path("corrections/state")
    public CorrectionsService.State correctionsState() {
        return corrections.state();
    }

    /** What a corrections file would bring; nothing is saved. Refused as {@code corrections.load.*}. */
    @POST
    @Path("corrections/check")
    @Consumes(MediaType.APPLICATION_JSON)
    public LoadedCorrections.Summary checkCorrections(String body) {
        return corrections.check(body);
    }

    /**
     * Loads a corrections file as the base of the corrections until the next import through an import source,
     * replacing the one loaded before.
     */
    @PUT
    @Path("corrections/loaded")
    @Consumes(MediaType.APPLICATION_JSON)
    public LoadedCorrections.Summary loadCorrections(String body) {
        return corrections.load(body);
    }

    /** Removes the loaded corrections file. */
    @DELETE
    @Path("corrections/loaded")
    public void removeCorrections() {
        corrections.remove();
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
