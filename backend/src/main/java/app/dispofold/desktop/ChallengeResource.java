package app.dispofold.desktop;

import java.util.Map;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.profile.IfBuildProfile;
import io.vertx.core.http.HttpServerRequest;

import app.dispofold.api.ApiErrors;
import app.dispofold.desktop.update.SessionKeys;

/**
 * The window's identity check before it routes pages here: it sends 16 random bytes, the app answers
 * {@code HMAC(secret, "challenge|" + challenge + "|" + port)}. No session header is needed (DesktopAuth still checks
 * Host, Origin and Sec-Fetch-Site).
 */
@IfBuildProfile("desktop")
@Path("/api/desktop/challenge")
@PermitAll
public class ChallengeResource {

    public record Challenge(String challenge) {
    }

    public record Answer(String response) {
    }

    @ConfigProperty(name = "dispofold.desktop.secret")
    String secret;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Answer answer(Challenge body, @Context HttpServerRequest request) {
        if (body == null || !SessionKeys.isNonce(body.challenge())) {
            throw ApiErrors.error(Status.BAD_REQUEST, "desktop.challenge.invalid", Map.of(),
                    "Die Prüfanfrage ist ungültig.");
        }
        return new Answer(SessionKeys.response(SessionKeys.secret(secret), body.challenge(),
                request.localAddress().port()));
    }
}
