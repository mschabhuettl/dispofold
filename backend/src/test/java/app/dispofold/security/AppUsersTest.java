package app.dispofold.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AppUsersTest {

    // bcrypt of "geheim" (cost 4) with all three prefixes
    static final String HASH = "$04$WX7g7BYFZQ4/hQ3hPH40QO2EsAIh0b8Px8ZTrJGXAgNAEXtne5Fe.";

    @Test
    void bcryptVariants() {
        for (String prefix : new String[] { "$2a", "$2b", "$2y" }) {
            assertThat(AppUsers.matches(prefix + HASH, "geheim".toCharArray())).as(prefix).isTrue();
            assertThat(AppUsers.matches(prefix + HASH, "Geheim".toCharArray())).as(prefix).isFalse();
        }
    }

    @Test
    void parsesUsers() {
        AppUsers users = new AppUsers(" a:$2y" + HASH + ":planner , b:$2b" + HASH + ":viewer");
        assertThat(users.size()).isEqualTo(2);
        assertThat(users.check("a", "geheim".toCharArray())).contains("planner");
        assertThat(users.check("a", "px".toCharArray())).isEmpty();
        assertThat(users.check("b", "geheim".toCharArray())).contains("viewer");
        assertThat(users.check("c", "geheim".toCharArray())).isEmpty();
        assertThat(users.role("b")).contains("viewer");
    }

    @Test
    void rejectsPlainTextPasswords() {
        for (String bad : new String[] { "a:pw:planner", "a:$2b$04$tooshort:planner", "a:$2x" + HASH + ":planner",
                "a:$2b$99" + HASH.substring(3) + ":planner", "a:" + "$2b" + HASH + "x:planner" }) {
            assertThatThrownBy(() -> new AppUsers(bad)).as(bad).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("pw").hasMessageNotContaining(HASH);
        }
    }

    // bcrypt of "geheim" with cost 10 (about 50 ms per check)
    static final String HASH10 = "$2b$10$vYuC3Ft2JUFIBr.P0JGtfePNA9rGU4bnMf2JJLdlznpV8pKP8THR2";

    static long medianNanos(Runnable r) {
        long[] t = new long[7];
        for (int i = 0; i < t.length; i++) {
            long start = System.nanoTime();
            r.run();
            t[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(t);
        return t[t.length / 2];
    }

    @Test
    void unknownUserTakesAsLongAsAWrongPassword() {
        AppUsers users = new AppUsers("a:$2b" + HASH + ":viewer,b:" + HASH10 + ":planner");
        users.check("b", "x".toCharArray());
        long wrongPassword = medianNanos(() -> users.check("b", "falsch".toCharArray()));
        long unknownUser = medianNanos(() -> users.check("nobody", "falsch".toCharArray()));
        // Without the dummy check the unknown user answers in microseconds; with it, like a cost 10 check.
        assertThat(unknownUser).isGreaterThan(wrongPassword / 2);
        assertThat(users.check("nobody", "geheim".toCharArray())).isEmpty();
    }

    @Test
    void rejectsBadEntries() {
        assertThatThrownBy(() -> new AppUsers("a:pw:admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AppUsers("a:planner")).isInstanceOf(IllegalArgumentException.class);
    }
}
