package app.dispofold.security;

import java.util.Map;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;

import app.dispofold.api.ApiErrors;

/**
 * The REST API answers 401 instead of redirecting to the login page like the static pages do, and both 401 and
 * 403 carry the error scheme {@code {code, params, error}}.
 */
public class UnauthorizedMapper {

    @ServerExceptionMapper
    public Response unauthorized(UnauthorizedException e) {
        return Response.status(Response.Status.UNAUTHORIZED).type(MediaType.APPLICATION_JSON)
                .entity(new ApiErrors.Error("security.unauthenticated", Map.of(), "Bitte anmelden.")).build();
    }

    @ServerExceptionMapper
    public Response forbidden(ForbiddenException e) {
        return Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON)
                .entity(new ApiErrors.Error("security.forbidden", Map.of(), "Dafür fehlt die Berechtigung.")).build();
    }
}
