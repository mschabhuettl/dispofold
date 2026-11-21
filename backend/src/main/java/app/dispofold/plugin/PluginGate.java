package app.dispofold.plugin;

import java.util.List;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.ext.Provider;

import app.dispofold.api.ApiErrors;

/**
 * Every route of a plugin ({@code /api/plugins/<id>/...}) and its import ({@code /api/import/plugin/<id>/...})
 * answers 404 while the plugin is off or hidden, to everyone, logged in or not. Before matching, so that no plugin
 * code runs. The path is taken by its segments (without matrix parameters and empty segments), as JAX-RS matches it.
 */
@Provider
@PreMatching
public class PluginGate implements ContainerRequestFilter {

    @Inject
    Plugins plugins;

    @Override
    public void filter(ContainerRequestContext ctx) {
        List<String> path = ctx.getUriInfo().getPathSegments().stream().map(s -> s.getPath())
                .filter(s -> !s.isEmpty()).toList();
        String id;
        if (path.size() >= 3 && path.get(0).equals("api") && path.get(1).equals("plugins")) {
            id = path.get(2);
        } else if (path.size() >= 4 && path.get(0).equals("api") && path.get(1).equals("import")
                && path.get(2).equals("plugin")) {
            id = path.get(3);
        } else {
            return;
        }
        if (plugins.active(id).isEmpty()) {
            ctx.abortWith(ApiErrors.error(Status.NOT_FOUND, "plugin.unknown", "Diese Erweiterung ist nicht "
                    + "eingeschaltet").getResponse());
        }
    }
}
