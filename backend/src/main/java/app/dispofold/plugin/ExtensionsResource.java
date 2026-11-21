package app.dispofold.plugin;

import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.SecurityContext;

import app.dispofold.api.ApiErrors;

/** Einstellungen → Erweiterungen: the plugins, switched on and off by a planner. */
@Path("/api/extensions")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({ "planner", "viewer" })
public class ExtensionsResource {

    public record Switch(boolean enabled) {
    }

    @Inject
    Plugins plugins;

    /** Planners: all plugins; readers: only the switched on ones (for their pages), nothing to switch. */
    @GET
    public List<Plugins.Info> list(@Context SecurityContext security) {
        List<Plugins.Info> all = plugins.list();
        return security.isUserInRole("planner") ? all : all.stream().filter(Plugins.Info::enabled).toList();
    }

    @PUT
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @RolesAllowed("planner")
    public Plugins.Info set(@PathParam("id") String id, Switch body) {
        if (!plugins.exists(id) || body == null) {
            throw ApiErrors.error(Status.NOT_FOUND, "plugin.unknown", "Diese Erweiterung gibt es nicht");
        }
        plugins.setEnabled(id, body.enabled());
        return plugins.list().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }
}
