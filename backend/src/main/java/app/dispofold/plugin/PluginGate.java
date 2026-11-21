package app.dispofold.plugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.ext.Provider;

import app.dispofold.api.ApiErrors;

/**
 * Every route of a plugin ({@code /api/plugins/<id>/...}) and its import ({@code /api/import/plugin/<id>/...})
 * answers 404 while the plugin is off or hidden. Before matching, so that no plugin code runs; without a login the
 * request goes on and the route's own role check answers 401.
 */
@Provider
@PreMatching
public class PluginGate implements ContainerRequestFilter {

    private static final Pattern PATH = Pattern.compile("/?api/(?:plugins|import/plugin)/([^/]+)(?:/.*)?");

    @Inject
    Plugins plugins;

    @Override
    public void filter(ContainerRequestContext ctx) {
        Matcher m = PATH.matcher(ctx.getUriInfo().getPath());
        if (!m.matches() || ctx.getSecurityContext().getUserPrincipal() == null) {
            return;
        }
        if (plugins.active(m.group(1)).isEmpty()) {
            ctx.abortWith(ApiErrors.error(Status.NOT_FOUND, "plugin.unknown", "Diese Erweiterung ist nicht "
                    + "eingeschaltet").getResponse());
        }
    }
}
