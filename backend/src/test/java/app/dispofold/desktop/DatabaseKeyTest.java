package app.dispofold.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseKeyTest {

    static final String PASSWORD = "richtiges-Passwort";
    static final String NEW_PASSWORD = "neues-Passwort-2027";

    @TempDir
    Path tmp;

    Path db() {
        return tmp.resolve("daten/db");
    }

    static int code(Throwable t) {
        return ((DatabaseKey.KeyException) t).code();
    }

    Connection connect(String dbPassword) throws SQLException {
        Properties p = new Properties();
        p.setProperty("user", "sa");
        p.setProperty("password", dbPassword);
        return new org.h2.Driver().connect(DatabaseKey.url(db()), p);
    }

    void createWithRow() throws Exception {
        String key = DatabaseKey.open(db(), PASSWORD);
        try (Connection c = connect(key)) {
            c.createStatement().execute("CREATE TABLE t(x INT); INSERT INTO t VALUES (42)");
        }
    }

    int row(String key) throws SQLException {
        try (Connection c = connect(key); ResultSet r = c.createStatement().executeQuery("SELECT x FROM t")) {
            r.next();
            return r.getInt(1);
        }
    }

    @Test
    void keyIsStretchedWithAStoredRandomSalt() throws Exception {
        assertThat(DatabaseKey.ITERATIONS).isGreaterThanOrEqualTo(600_000);
        String a = DatabaseKey.derive(PASSWORD, new byte[16]);
        assertThat(a).matches("[0-9a-f]{64} [0-9a-f]{64}");
        assertThat(DatabaseKey.derive(PASSWORD, new byte[16])).isEqualTo(a);
        byte[] other = new byte[16];
        other[0] = 1;
        assertThat(DatabaseKey.derive(PASSWORD, other)).isNotEqualTo(a);
        createWithRow();
        assertThat(Files.readString(db().resolve(DatabaseKey.SALT_FILE)).trim()).matches("[0-9a-f]{32}");
        assertThat(row(DatabaseKey.open(db(), PASSWORD))).isEqualTo(42);
    }

    @Test
    void openLeavesTheDatabaseClosed() throws Exception {
        createWithRow();
        DatabaseKey.open(db(), PASSWORD);
        // Still open in this JVM, a new database in the same place would meet the old one (wrong password).
        try (var files = Files.walk(db())) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        DatabaseKey.open(db(), "anderes-Passwort-2027");
    }

    @Test
    void newDatabaseNeedsTwelveCharacters() {
        assertThatThrownBy(() -> DatabaseKey.open(db(), "elf-Zeichen"))
                .satisfies(t -> assertThat(code(t)).isEqualTo(2)).hasMessageContaining("12 Zeichen");
        assertThat(db().resolve(DatabaseKey.DB_FILE)).doesNotExist();
    }

    @Test
    void wrongPasswordIsCode4() throws Exception {
        createWithRow();
        // Also a short one: the minimum length applies to new passwords only.
        for (String wrong : new String[] { "falsch", "falsches-Passwort" }) {
            assertThatThrownBy(() -> DatabaseKey.open(db(), wrong)).satisfies(t -> assertThat(code(t)).isEqualTo(4));
        }
    }

    @Test
    void missingSaltIsReported() throws Exception {
        createWithRow();
        Files.delete(db().resolve(DatabaseKey.SALT_FILE));
        assertThatThrownBy(() -> DatabaseKey.open(db(), PASSWORD)).satisfies(t -> assertThat(code(t)).isEqualTo(1))
                .hasMessageContaining(DatabaseKey.SALT_FILE).hasMessageNotContaining(tmp.toString());
    }

    @Test
    void passwordChangeReencrypts() throws Exception {
        createWithRow();
        String oldSalt = Files.readString(db().resolve(DatabaseKey.SALT_FILE));
        DatabaseKey.change(db(), PASSWORD, NEW_PASSWORD);
        assertThat(row(DatabaseKey.open(db(), NEW_PASSWORD))).isEqualTo(42);
        assertThat(Files.readString(db().resolve(DatabaseKey.SALT_FILE))).isNotEqualTo(oldSalt);
        assertThatThrownBy(() -> DatabaseKey.open(db(), PASSWORD)).satisfies(t -> assertThat(code(t)).isEqualTo(4));
        try (var left = Files.list(tmp.resolve("daten"))) {
            assertThat(left.map(p -> p.getFileName().toString())).containsExactly("db");
        }
    }

    @Test
    void passwordChangeWithWrongOrShortPasswordChangesNothing() throws Exception {
        createWithRow();
        assertThatThrownBy(() -> DatabaseKey.change(db(), "falsches-Passwort", NEW_PASSWORD))
                .satisfies(t -> assertThat(code(t)).isEqualTo(4));
        assertThatThrownBy(() -> DatabaseKey.change(db(), PASSWORD, "kurz"))
                .satisfies(t -> assertThat(code(t)).isEqualTo(2));
        assertThat(row(DatabaseKey.open(db(), PASSWORD))).isEqualTo(42);
    }

    @Test
    void interruptedChangeIsRecovered() throws Exception {
        createWithRow();
        // Crash between the two renames: daten/db is gone, daten/db.alt is the complete old folder.
        Files.move(db(), tmp.resolve("daten/" + DatabaseKey.OLD_DIR));
        assertThat(row(DatabaseKey.open(db(), PASSWORD))).isEqualTo(42);
        assertThat(tmp.resolve("daten/" + DatabaseKey.OLD_DIR)).doesNotExist();
    }
}
