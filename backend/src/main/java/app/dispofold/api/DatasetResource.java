package app.dispofold.api;

import java.time.LocalDate;
import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.api.MasterDataService.MasterChange;
import app.dispofold.api.MasterDataService.Removal;
import app.dispofold.model.Dataset;
import app.dispofold.model.Restriction;
import app.dispofold.store.Store;
import app.dispofold.store.Store.DatasetState;

/** The master data: read, edit per object (planners), and export with all planning months (planners). */
@Path("/api/dataset")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({ "planner", "viewer" })
public class DatasetResource {

    @Inject
    Store store;

    @Inject
    MasterDataService master;

    @Inject
    ObjectMapper mapper;

    /** The master data with their revision, or an empty dataset (revision 0) before the first import. */
    @GET
    public ObjectNode get() {
        Dataset d = store.dataset().orElseGet(() -> new Dataset(Dataset.FORMAT, 1, null, List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));
        DatasetState state = master.state();
        ObjectNode node = mapper.valueToTree(d);
        node.put("revision", state.revision());
        node.put("changedSinceImport", state.changedSinceImport());
        return node;
    }

    /** Master data and all planning months as a dataset file to import again. */
    @GET
    @Path("export")
    @RolesAllowed("planner")
    @Produces(MediaType.APPLICATION_JSON)
    public Response export() {
        return Response.ok(master.export(), MediaType.APPLICATION_JSON)
                .header("Content-Disposition",
                        "attachment; filename=\"dispofold-datensatz-" + LocalDate.now() + ".json\"")
                .build();
    }

    @POST
    @Path("{kind}")
    @RolesAllowed("planner")
    public MasterChange create(@PathParam("kind") String kind, @HeaderParam("If-Match") String ifMatch,
            JsonNode body) {
        return master.create(kind, body, revision(ifMatch));
    }

    @PUT
    @Path("{kind}/{key}")
    @RolesAllowed("planner")
    public MasterChange update(@PathParam("kind") String kind, @PathParam("key") String key,
            @HeaderParam("If-Match") String ifMatch, JsonNode body) {
        return master.update(kind, key, body, revision(ifMatch));
    }

    @DELETE
    @Path("{kind}/{key}")
    @RolesAllowed("planner")
    public Removal remove(@PathParam("kind") String kind, @PathParam("key") String key,
            @QueryParam("confirm") boolean confirm, @HeaderParam("If-Match") String ifMatch) {
        return master.remove(kind, key, confirm, revision(ifMatch));
    }

    @PUT
    @Path("people/{personId}/restrictions")
    @RolesAllowed("planner")
    public MasterChange restrictions(@PathParam("personId") String personId,
            @HeaderParam("If-Match") String ifMatch, List<Restriction> restrictions) {
        return master.restrictions(personId, restrictions, revision(ifMatch));
    }

    /** The revision from {@code If-Match}; null when missing or not a number (the service answers 428). */
    private static Long revision(String ifMatch) {
        try {
            return ifMatch == null ? null : Long.valueOf(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
