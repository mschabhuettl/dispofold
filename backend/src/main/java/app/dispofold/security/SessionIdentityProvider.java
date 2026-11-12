package app.dispofold.security;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.TrustedAuthenticationRequest;
import io.smallrye.mutiny.Uni;

/** Restores the identity from the form login cookie; users removed from APP_USERS lose their session. */
@ApplicationScoped
public class SessionIdentityProvider implements IdentityProvider<TrustedAuthenticationRequest> {

    @Inject
    AppUsers users;

    @Override
    public Class<TrustedAuthenticationRequest> getRequestType() {
        return TrustedAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(TrustedAuthenticationRequest request,
            AuthenticationRequestContext context) {
        String username = request.getPrincipal();
        return users.role(username)
                .map(role -> Uni.createFrom().item(AppUserIdentityProvider.identity(username, role)))
                .orElseGet(() -> Uni.createFrom().failure(new AuthenticationFailedException()));
    }
}
