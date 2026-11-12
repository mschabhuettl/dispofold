package app.dispofold.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import io.quarkus.arc.profile.IfBuildProfile;
import io.smallrye.common.annotation.Blocking;

import app.dispofold.api.ApiErrors;
import app.dispofold.desktop.UpdateService.Available;
import app.dispofold.desktop.UpdateService.Settings;
import app.dispofold.desktop.UpdateService.UpdateException;

/** Desktop only: quit and updates (see docs/desktop.md). */
@IfBuildProfile("desktop")
@Path("/api/desktop")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("planner")
@Blocking
public class DesktopResource {

    /** {@code error} (German) with {@code errorCode} and {@code errorParams} as in the error bodies, or all null. */
    public record UpdateInfo(String current, String source, boolean checkOnStart, Available available, String error,
            String errorCode, Map<String, Object> errorParams) {
    }

    public record Staged(String version) {
    }

    @Inject
    UpdateService updates;

    @Inject
    DesktopExit exit;

    @POST
    @Path("quit")
    public Response quit() {
        exit.exitSoon(0);
        return Response.noContent().build();
    }

    @GET
    @Path("update")
    public UpdateInfo update(@QueryParam("check") boolean check) {
        Settings s = updates.settings();
        Available available = null;
        UpdateException error = null;
        if (check) {
            try {
                available = updates.find(s.source()).map(f -> new Available(f.version().toString(), f.notes()))
                        .orElse(null);
            } catch (UpdateException e) {
                error = e;
            }
        }
        return new UpdateInfo(updates.current().toString(), s.source(), s.checkOnStart(), available,
                error == null ? null : error.getMessage(), error == null ? null : "desktop." + error.code(),
                error == null ? null : error.params());
    }

    @PUT
    @Path("update/settings")
    @Consumes(MediaType.APPLICATION_JSON)
    public Settings settings(Settings settings) throws IOException {
        if (settings == null) {
            throw ApiErrors.error(Status.BAD_REQUEST, "desktop.update.settingsMissing", "Einstellungen fehlen");
        }
        UpdateService.invalidSource(settings.source()).ifPresent(e -> {
            throw error(e);
        });
        Settings clean = new Settings(settings.source().trim(), settings.checkOnStart());
        updates.save(clean);
        return clean;
    }

    @POST
    @Path("update/install")
    public Response install() {
        try {
            return staged(updates.install());
        } catch (UpdateException e) {
            throw error(e);
        }
    }

    @POST
    @Path("update/file")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response file(InputStream body) {
        try {
            return staged(updates.installFile(body));
        } catch (UpdateException e) {
            throw error(e);
        }
    }

    private static WebApplicationException error(UpdateException e) {
        return ApiErrors.error(Status.BAD_REQUEST, "desktop." + e.code(), e.params(), e.getMessage());
    }

    private Response staged(app.dispofold.desktop.update.Version v) {
        exit.exitSoon(DesktopExit.RESTART);
        return Response.accepted(new Staged(v.toString())).build();
    }
}
