package app.dispofold.security;

import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;

import app.dispofold.desktop.DesktopAuth;

@Path("/api")
@Authenticated
public class AuthResource {

    /** {@code desktop}: the desktop build (only it has the {@link DesktopAuth} bean). */
    public record Me(String username, String role, boolean desktop) {
    }

    @Inject
    SecurityIdentity identity;

    @Inject
    Instance<DesktopAuth> desktopAuth;

    @ConfigProperty(name = "quarkus.http.auth.form.cookie-name", defaultValue = "quarkus-credential")
    String cookieName;

    @GET
    @Path("me")
    public Me me() {
        String role = identity.hasRole("planner") ? "planner" : "viewer";
        return new Me(identity.getPrincipal().getName(), role, desktopAuth.isResolvable());
    }

    @POST
    @Path("logout")
    public Response logout() {
        // Expire the form login cookie; the session lives only in this cookie.
        return Response.noContent()
                .cookie(new NewCookie.Builder(cookieName).value("").path("/").maxAge(0).httpOnly(true).build())
                .build();
    }
}
