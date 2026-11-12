package app.dispofold.desktop;

import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;

import app.dispofold.desktop.update.SessionKeys;

/**
 * Desktop login: only the program window talks to the app. Every request needs the header
 * {@code X-Dispofold-Session: base64(HMAC(secret, "session|" + nonce))} with the nonce of this start's ready line, so
 * a value from an earlier start is refused; the window knows the secret, the app got it from the launcher on stdin. There is no cookie and no token in a URL. {@code Host} must be {@code 127.0.0.1:<own port>}
 * (no DNS rebinding), an {@code Origin} must be the window's ({@code dispofold://localhost} or
 * {@code http://dispofold.localhost}) and {@code Sec-Fetch-Site} must be {@code same-origin} or {@code none}. Valid
 * requests act as {@code lokal} with role {@code planner}. Open are {@code /q/health/*} and, without the session
 * header but with the other checks, {@code POST /api/desktop/challenge}.
 * <p>
 * The bean exists only in the desktop build, so the server build cannot turn it on through configuration.
 */
@IfBuildProfile("desktop")
@ApplicationScoped
public class DesktopAuth implements HttpAuthenticationMechanism {

    public static final String HEADER = "X-Dispofold-Session";
    static final String USER = "lokal";
    static final Set<String> ORIGINS = Set.of("dispofold://localhost", "http://dispofold.localhost");
    private static final String REJECT = "dispofold.reject";

    enum Decision {
        /** Health endpoints and the challenge: anonymous. */
        OPEN,
        /** Valid session header. */
        ALLOW,
        /** No or wrong session header. */
        UNAUTHORIZED,
        /** Foreign Host, Origin or Sec-Fetch-Site. */
        FORBIDDEN
    }

    private final String session;

    @Inject
    public DesktopAuth(@ConfigProperty(name = "dispofold.desktop.secret") String secret, ReadyLine ready) {
        this(secret, ready.nonce());
    }

    /** Accepts only the session header of the start with this ready nonce. */
    DesktopAuth(String secret, String nonce) {
        this.session = SessionKeys.session(SessionKeys.secret(secret), nonce);
    }

    /** The checks, without Vert.x types. */
    Decision decide(String path, String method, String host, int localPort, String origin, String fetchSite,
            String header) {
        if (path.equals("/q/health") || path.startsWith("/q/health/")) {
            return Decision.OPEN;
        }
        if (host == null || !host.equals("127.0.0.1:" + localPort)) {
            return Decision.FORBIDDEN;
        }
        if (origin != null && !ORIGINS.contains(origin)) {
            return Decision.FORBIDDEN;
        }
        if (fetchSite != null && !fetchSite.equals("same-origin") && !fetchSite.equals("none")) {
            return Decision.FORBIDDEN;
        }
        if (path.equals("/api/desktop/challenge") && method.equals("POST")) {
            return Decision.OPEN;
        }
        return SessionKeys.same(session, header) ? Decision.ALLOW : Decision.UNAUTHORIZED;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        HttpServerRequest r = context.request();
        Decision d = decide(context.normalizedPath(), r.method().name(), r.getHeader("Host"), r.localAddress().port(),
                r.getHeader("Origin"), r.getHeader("Sec-Fetch-Site"), r.getHeader(HEADER));
        return switch (d) {
            case OPEN -> Uni.createFrom().optional(Optional.empty());
            case ALLOW -> Uni.createFrom().item(identity());
            case UNAUTHORIZED, FORBIDDEN -> {
                context.put(REJECT, d);
                yield Uni.createFrom().failure(new AuthenticationFailedException());
            }
        };
    }

    static SecurityIdentity identity() {
        return QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(USER)).addRole("planner").build();
    }

    @Override
    public Uni<Boolean> sendChallenge(RoutingContext context) {
        Decision d = context.get(REJECT);
        int status = d == Decision.FORBIDDEN ? 403 : 401;
        var response = context.response().setStatusCode(status).putHeader("Cache-Control", "no-store");
        if (context.normalizedPath().startsWith("/api/")) {
            response.putHeader("Content-Type", "application/json").end(status == 403
                    ? "{\"code\":\"security.desktopForeign\",\"params\":{},"
                            + "\"error\":\"Zugriff nur über das Programm Dispofold\"}"
                    : "{\"code\":\"security.desktopLogin\",\"params\":{},"
                            + "\"error\":\"Bitte Dispofold über das Programm öffnen\"}");
        } else {
            response.putHeader("Content-Type", "text/html; charset=utf-8").end(PAGE);
        }
        return Uni.createFrom().item(true);
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().item(new ChallengeData(401, null, null));
    }

    @Override
    public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
        return Set.of();
    }

    private static final String PAGE = """
            <!doctype html>
            <html lang="de"><head><meta charset="utf-8"><title>Dispofold</title>
            <meta name="viewport" content="width=device-width, initial-scale=1"></head>
            <body style="font-family: system-ui, sans-serif; margin: 3rem; max-width: 36rem">
            <h1>Dispofold</h1>
            <p>Bitte Dispofold über das Programm öffnen (Startmenü, Programmordner oder Dock).
            Diese Adresse funktioniert nur im Programmfenster von Dispofold.</p>
            </body></html>
            """;
}
