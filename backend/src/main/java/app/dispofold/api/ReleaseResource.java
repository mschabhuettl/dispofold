package app.dispofold.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import app.dispofold.api.ReleaseCheck.Release;
import io.quarkus.arc.profile.UnlessBuildProfile;

/** Server build, planners only: the running version and a newer release, if the release check found one. */
@UnlessBuildProfile("desktop")
@Path("/api/release")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("planner")
public class ReleaseResource {

    public record ReleaseInfo(String current, Release available) {
    }

    @Inject
    ReleaseCheck check;

    @GET
    public ReleaseInfo get() {
        return new ReleaseInfo(check.current(), check.latest());
    }
}
