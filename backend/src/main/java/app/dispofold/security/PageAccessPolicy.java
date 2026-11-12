package app.dispofold.security;

import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.security.HttpSecurityPolicy;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;

/**
 * Policy {@code pages} for everything that is not public by path: the login page's scripts carry a content hash
 * in their names ({@code /theme-<hash>.js}, {@code /js/login-<hash>.js}), which the path patterns of
 * {@code quarkus.http.auth.permission} cannot express. Those two are open; everything else needs a login.
 */
@ApplicationScoped
public class PageAccessPolicy implements HttpSecurityPolicy {

    static final Pattern LOGIN_SCRIPTS = Pattern.compile("/(theme|js/login)-[A-Z2-7]{8}\\.js");

    @Override
    public Uni<CheckResult> checkPermission(RoutingContext request, Uni<SecurityIdentity> identity,
            AuthorizationRequestContext requestContext) {
        if (LOGIN_SCRIPTS.matcher(request.normalizedPath()).matches()) {
            return Uni.createFrom().item(CheckResult.PERMIT);
        }
        return identity.map(id -> id.isAnonymous() ? CheckResult.DENY : CheckResult.PERMIT);
    }

    @Override
    public String name() {
        return "pages";
    }
}
