package app.dispofold.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class StartupChecksTest {

    static final String GOOD_KEY = "k8Vq2mZ0rT5wYb3nH7cJ1xF4pL9sD6gA";
    static final String HASH = "$2b$04$WX7g7BYFZQ4/hQ3hPH40QO2EsAIh0b8Px8ZTrJGXAgNAEXtne5Fe.";

    @Test
    void goodValuesPass() {
        assertThat(StartupChecks.problems(GOOD_KEY, "x7Rk2pQ9vL4mN8sT")).isEmpty();
    }

    @Test
    void sessionKeyIsChecked() {
        assertThat(StartupChecks.problems("", "x7Rk2pQ9vL4mN8sT")).containsExactly("sessionKeyMissing");
        assertThat(StartupChecks.problems(GOOD_KEY.substring(1), "x7Rk2pQ9vL4mN8sT")).containsExactly("sessionKeyShort");
        for (String placeholder : new String[] { "bitte-aendern-bitte-aendern-bitte-aendern", "CHANGE-ME-0123456789012345678901234",
                "changeme-changeme-changeme-changeme" }) {
            assertThat(StartupChecks.problems(placeholder, "x7Rk2pQ9vL4mN8sT")).as(placeholder)
                    .containsExactly("sessionKeyPlaceholder");
        }
    }

    @Test
    void dbPasswordIsChecked() {
        assertThat(StartupChecks.problems(GOOD_KEY, "")).containsExactly("dbPasswordMissing");
        for (String placeholder : new String[] { "dienstplanung", "bitte-aendern", "ChangeMe123", "postgres" }) {
            assertThat(StartupChecks.problems(GOOD_KEY, placeholder)).as(placeholder)
                    .containsExactly("dbPasswordPlaceholder");
        }
    }

    StartupChecks checks(String key, String db, String users) {
        StartupChecks c = new StartupChecks();
        c.sessionKey = Optional.ofNullable(key);
        c.dbPassword = Optional.ofNullable(db);
        c.users = new AppUsers(users);
        return c;
    }

    @Test
    void startIsRefusedWithCodesAndWithoutTheValues() {
        String weak = "change-me-change-me-change-me-01";
        assertThatThrownBy(() -> checks(weak, null, "a:" + HASH + ":planner").onStart(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup.sessionKeyPlaceholder").hasMessageContaining("startup.dbPasswordMissing")
                .hasMessageNotContaining(weak);
        checks(GOOD_KEY, "x7Rk2pQ9vL4mN8sT", "a:" + HASH + ":planner").onStart(null);
    }
}
