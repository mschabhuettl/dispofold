package app.dispofold.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;

/** Checks the form login against {@link AppUsers}. */
@ApplicationScoped
public class AppUserIdentityProvider implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    @Inject
    AppUsers users;

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(UsernamePasswordAuthenticationRequest request,
            AuthenticationRequestContext context) {
        return context.runBlocking(() -> {
            String username = request.getUsername();
            String role = users.check(username, request.getPassword().getPassword())
                    .orElseThrow(AuthenticationFailedException::new);
            return identity(username, role);
        });
    }

    static SecurityIdentity identity(String username, String role) {
        return QuarkusSecurityIdentity.builder().setPrincipal(new QuarkusPrincipal(username)).addRole(role).build();
    }
}
