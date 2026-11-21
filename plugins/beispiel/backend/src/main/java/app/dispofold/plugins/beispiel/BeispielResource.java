package app.dispofold.plugins.beispiel;

import java.util.Map;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.plugin.api.PluginErrors;
import app.dispofold.plugin.api.PluginHost;

/** The example plugin's routes: a greeting readers may see too, and its settings. */
@Path("/api/plugins/beispiel")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("planner")
public class BeispielResource {

    @Inject
    PluginHost host;

    @GET
    @Path("gruss")
    @RolesAllowed({ "planner", "viewer" })
    public Map<String, String> greeting() {
        return Map.of("gruss", host.settings(BeispielPlugin.ID).path("gruss").asText());
    }

    @GET
    @Path("einstellungen")
    public JsonNode settings() {
        return host.settings(BeispielPlugin.ID);
    }

    @PUT
    @Path("einstellungen")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode save(JsonNode settings) {
        String greeting = settings == null ? "" : settings.path("gruss").asText("").strip();
        if (greeting.isEmpty() || greeting.length() > 40) {
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.beispiel.greeting", Map.of("max", 40),
                    "Der Gruß braucht 1 bis 40 Zeichen");
        }
        ObjectNode updated = host.settings(BeispielPlugin.ID).deepCopy();
        updated.put("gruss", greeting);
        host.saveSettings(BeispielPlugin.ID, updated);
        return host.settings(BeispielPlugin.ID);
    }
}
