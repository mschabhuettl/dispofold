package app.dispofold.security;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.wildfly.security.password.Password;
import org.wildfly.security.password.PasswordFactory;
import org.wildfly.security.password.WildFlyElytronPasswordProvider;
import org.wildfly.security.password.interfaces.BCryptPassword;
import org.wildfly.security.password.util.ModularCrypt;

/**
 * Local user list from the {@code APP_USERS} variable: {@code user:bcrypt-hash:role,...}. Only bcrypt hashes
 * ({@code $2a$}, {@code $2b$}, {@code $2y$}) are accepted; a plain text password stops the start. Roles are
 * {@code planner} and {@code viewer}. An unknown user name is checked against a hash of the highest configured
 * cost, so that it takes as long as a wrong password.
 * <p>
 * A later directory login (LDAP or OIDC) replaces this class and {@link AppUserIdentityProvider}: either a
 * different {@code IdentityProvider} for the same form login, or {@code quarkus-oidc} instead of form auth. The
 * REST layer only relies on the roles {@code planner} and {@code viewer}.
 */
@ApplicationScoped
public class AppUsers {

    public static final Set<String> ROLES = Set.of("planner", "viewer");

    private static final Logger LOG = Logger.getLogger(AppUsers.class);
    private static final WildFlyElytronPasswordProvider PROVIDER = WildFlyElytronPasswordProvider.getInstance();
    private static final Pattern BCRYPT = Pattern.compile("\\$2[aby]\\$(0[4-9]|[12][0-9]|3[01])\\$[./A-Za-z0-9]{53}");

    record User(String password, String role) {
    }

    private final Map<String, User> users = new HashMap<>();
    /** The configured hash with the highest cost, verified for unknown users; null without users. */
    private String dummyHash;

    // Optional: an empty value counts as missing in MicroProfile Config (desktop build, no APP_USERS).
    @Inject
    public AppUsers(@ConfigProperty(name = "app.users") Optional<String> spec) {
        this(spec.orElse(""));
    }

    public AppUsers(String spec) {
        for (String entry : spec.split(",")) {
            entry = entry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int first = entry.indexOf(':');
            int last = entry.lastIndexOf(':');
            String role = last > first ? entry.substring(last + 1).trim() : "";
            if (first <= 0 || !ROLES.contains(role)) {
                throw new IllegalArgumentException("APP_USERS: entry " + (users.size() + 1) + " is not user:password:role");
            }
            String hash = entry.substring(first + 1, last);
            if (!BCRYPT.matcher(hash).matches()) {
                throw new IllegalArgumentException("APP_USERS: entry " + (users.size() + 1)
                        + " has no bcrypt hash (plain text passwords are not accepted)");
            }
            users.put(entry.substring(0, first), new User(hash, role));
            if (dummyHash == null || cost(hash) > cost(dummyHash)) {
                dummyHash = hash;
            }
        }
        if (users.isEmpty()) {
            LOG.warn("APP_USERS is empty, nobody can log in");
        }
    }

    /** Returns the role if user and password match. */
    public Optional<String> check(String username, char[] password) {
        User user = users.get(username);
        if (user == null) {
            if (dummyHash != null) {
                matches(dummyHash, password);
            }
            return Optional.empty();
        }
        if (!matches(user.password(), password)) {
            return Optional.empty();
        }
        return Optional.of(user.role());
    }

    public Optional<String> role(String username) {
        return Optional.ofNullable(users.get(username)).map(User::role);
    }

    private static int cost(String hash) {
        return Integer.parseInt(hash.substring(4, 6));
    }

    /** Number of configured users. */
    public int size() {
        return users.size();
    }

    static boolean matches(String stored, char[] guess) {
        try {
            // The variants only differ in how old implementations treated non-ASCII input; verify as $2a$.
            Password hash = ModularCrypt.decode("$2a$" + stored.substring(4));
            PasswordFactory factory = PasswordFactory.getInstance(BCryptPassword.ALGORITHM_BCRYPT, PROVIDER);
            return factory.verify(factory.translate(hash), guess);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
