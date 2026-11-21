package app.dispofold.plugin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.databind.JsonNode;

import app.dispofold.api.ApiErrors;
import app.dispofold.api.ImportResource;
import app.dispofold.api.ImportResource.ImportPreview;
import app.dispofold.api.ImportResource.ImportSummary;
import app.dispofold.plugin.api.ImportSource;

/**
 * The import from a plugin's import source: the same as an uploaded dataset ({@link ImportResource}), with the
 * plugin's request (its own JSON) as body. 404 while the plugin is off ({@link PluginGate}) or has no import source.
 */
@Path("/api/import/plugin/{id}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("planner")
public class PluginImportResource {

    @Inject
    Plugins plugins;

    @Inject
    ImportResource imports;

    @POST
    public ImportSummary run(@PathParam("id") String id, @QueryParam("replace") boolean replace,
            @QueryParam("keep") boolean keep, JsonNode request) {
        ImportSource.Data data = load(id, request);
        return imports.importDataset(data.dataset(), data.appliedCorrections(), replace, keep);
    }

    @POST
    @Path("preview")
    public ImportPreview preview(@PathParam("id") String id, JsonNode request) {
        ImportSource.Data data = load(id, request);
        return imports.preview(data.dataset(), data.appliedCorrections());
    }

    private ImportSource.Data load(String id, JsonNode request) {
        ImportSource source = plugins.active(id).flatMap(p -> p.importSource()).orElseThrow(
                () -> ApiErrors.error(Status.NOT_FOUND, "plugin.unknown", "Diese Erweiterung hat keinen Import"));
        ImportSource.Data data = source.load(request);
        if (data == null || data.dataset() == null) {
            throw ApiErrors.error(Status.BAD_REQUEST, "import.parse", "Die Erweiterung lieferte keinen Datensatz");
        }
        return data;
    }
}
