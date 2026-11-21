package app.dispofold.plugin;

import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;

import io.quarkus.runtime.configuration.ConfigUtils;

import app.dispofold.api.ApiErrors;
import app.dispofold.plugin.api.PluginFiles;

/**
 * The folders plugins may read, for picking a folder in a plugin's page. In the desktop program the window adds the
 * folder the user picked in its folder dialog; the page itself cannot (the window removes {@link #PICKED} from the
 * page's requests).
 */
@Path("/api/files")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("planner")
public class FilesResource {

    /** Set by the desktop window only, on the request that adds a picked folder. */
    public static final String PICKED = "X-Dispofold-Picked";

    public record PickedFolder(String path) {
    }

    @Inject
    FileRoots roots;

    @GET
    @Path("roots")
    public List<PluginFiles.Root> roots() {
        return roots.roots();
    }

    @GET
    @Path("list")
    public List<FileRoots.Entry> list(@QueryParam("root") String root, @QueryParam("path") String path) {
        return roots.list(root, path);
    }

    @POST
    @Path("roots")
    @Consumes(MediaType.APPLICATION_JSON)
    public PluginFiles.Root pick(@HeaderParam(PICKED) String picked, PickedFolder body) {
        if (!ConfigUtils.isProfileActive("desktop") || !"1".equals(picked)) {
            throw ApiErrors.error(Status.FORBIDDEN, "files.pickInWindow",
                    "Ordner lassen sich nur im Ordnerdialog des Programms freigeben");
        }
        roots.addPicked(body == null ? null : body.path());
        return new PluginFiles.Root(FileRoots.normal(body.path()));
    }
}
