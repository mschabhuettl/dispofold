package app.dispofold.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;

/**
 * Server build only: refuses to start with a missing, short or placeholder session key, a missing or placeholder
 * database password, or an invalid APP_USERS value. The log names a code ({@code startup.<code>}), never a value.
 */
@IfBuildProfile("prod")
@ApplicationScoped
public class StartupChecks {

    static final int MIN_SESSION_KEY_LENGTH = 32;
    /** Parts of example values; a secret that contains one of them is a placeholder. */
    static final List<String> PLACEHOLDERS = List.of("bitte-aendern", "bitte_aendern", "bitteaendern", "change-me",
            "change_me", "changeme", "placeholder", "platzhalter", "example", "beispiel");
    /** Values that are placeholders as a whole (old defaults). */
    static final List<String> WEAK = List.of("dienstplanung", "dispofold", "postgres", "password", "passwort",
            "secret", "geheim");

    private static final Logger LOG = Logger.getLogger(StartupChecks.class);

    @ConfigProperty(name = "quarkus.http.auth.session.encryption-key")
    Optional<String> sessionKey;

    @ConfigProperty(name = "quarkus.datasource.password")
    Optional<String> dbPassword;

    @Inject
    AppUsers users;

    /** Before every other start observer (they may already use the database). */
    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent event) {
        // Creating AppUsers here makes a plain text password in APP_USERS stop the start, not the first login.
        if (users.size() == 0) {
            LOG.warn("startup.noUsers: APP_USERS is empty, nobody can log in");
        }
        List<String> codes = problems(sessionKey.orElse(""), dbPassword.orElse(""));
        if (!codes.isEmpty()) {
            for (String code : codes) {
                LOG.errorf("startup.%s: %s", code, text(code));
            }
            throw new IllegalStateException("Start refused: " + String.join(", ", codes.stream()
                    .map(c -> "startup." + c).toList()));
        }
    }

    /** Codes of all problems; empty when the start may go on. */
    static List<String> problems(String sessionKey, String dbPassword) {
        List<String> codes = new ArrayList<>();
        if (sessionKey.isBlank()) {
            codes.add("sessionKeyMissing");
        } else if (placeholder(sessionKey)) {
            codes.add("sessionKeyPlaceholder");
        } else if (sessionKey.length() < MIN_SESSION_KEY_LENGTH) {
            codes.add("sessionKeyShort");
        }
        if (dbPassword.isBlank()) {
            codes.add("dbPasswordMissing");
        } else if (placeholder(dbPassword)) {
            codes.add("dbPasswordPlaceholder");
        }
        return codes;
    }

    static boolean placeholder(String secret) {
        String s = secret.trim().toLowerCase(Locale.ROOT);
        return PLACEHOLDERS.stream().anyMatch(s::contains) || WEAK.contains(s);
    }

    private static String text(String code) {
        return switch (code) {
            case "sessionKeyMissing" -> "SESSION_ENCRYPTION_KEY is not set";
            case "sessionKeyPlaceholder" -> "SESSION_ENCRYPTION_KEY is a placeholder";
            case "sessionKeyShort" -> "SESSION_ENCRYPTION_KEY has fewer than " + MIN_SESSION_KEY_LENGTH + " characters";
            case "dbPasswordMissing" -> "DB_PASSWORD is not set";
            case "dbPasswordPlaceholder" -> "DB_PASSWORD is a placeholder";
            default -> code;
        };
    }
}
