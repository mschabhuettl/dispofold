package app.dispofold.desktop;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.Properties;
import java.util.stream.Stream;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.h2.tools.ChangeFileEncryption;

/**
 * The key of the encrypted H2 database in {@code daten/db/}. The password the user enters is stretched with
 * PBKDF2-HMAC-SHA256 (600 000 iterations) and a random 16 byte salt stored next to the database
 * ({@code daten/db/dispofold.salt}, so that backups of the folder carry it). The result gives the H2 password
 * {@code "<file password> <user password>"}, both hex. This is a stored format: changing it makes existing
 * databases unreadable.
 */
public final class DatabaseKey {

    public static final int MIN_PASSWORD_LENGTH = 12;
    static final int ITERATIONS = 600_000;
    static final String SALT_FILE = "dispofold.salt";
    static final String DB_FILE = "dispofold.mv.db";
    static final String OLD_DIR = "db.alt";

    /** Why a database could not be opened or changed; {@code code} is the process exit code. */
    public static final class KeyException extends Exception {
        private final int code;

        KeyException(int code, String message) {
            super(message);
            this.code = code;
        }

        public int code() {
            return code;
        }
    }

    private DatabaseKey() {
    }

    static String url(Path dbDir) {
        return "jdbc:h2:file:" + dbDir.toAbsolutePath() + "/dispofold;CIPHER=AES";
    }

    /** H2 password for this password and salt. */
    static String derive(String password, byte[] salt) {
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(password.toCharArray(), salt, ITERATIONS, 512)).getEncoded();
            HexFormat hex = HexFormat.of();
            return hex.formatHex(key, 0, 32) + " " + hex.formatHex(key, 32, 64);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Opens (or, on the first start, creates) the database once to check the password and returns the H2 password.
     * A new database needs a password of at least {@link #MIN_PASSWORD_LENGTH} characters.
     */
    public static String open(Path dbDir, String password) throws KeyException {
        try {
            Path old = dbDir.toAbsolutePath().getParent().resolve(OLD_DIR);
            if (!Files.exists(dbDir) && Files.exists(old)) {
                // A password change was interrupted between its two renames: the old folder is still complete.
                Files.move(old, dbDir, StandardCopyOption.ATOMIC_MOVE);
            }
            boolean exists = Files.exists(dbDir.resolve(DB_FILE));
            Path saltFile = dbDir.resolve(SALT_FILE);
            if (!exists) {
                requireLength(password);
                if (!Files.exists(saltFile)) {
                    Files.createDirectories(dbDir);
                    writeSalt(saltFile);
                }
            } else if (!Files.exists(saltFile)) {
                throw new KeyException(1, "Die Schlüsseldatei daten/db/" + SALT_FILE + " fehlt. Ohne sie lässt sich"
                        + " die Datenbank nicht öffnen; bitte aus einer Sicherung zurückholen.");
            }
            String dbPassword = derive(password, readSalt(saltFile));
            // Closed again at once: an embedded database stays open (and locked) while a connection lives.
            connect(dbDir, dbPassword).close();
            return dbPassword;
        } catch (SQLException e) {
            throw new KeyException(1, "Die Datenbank konnte nicht geschlossen werden (Fehler " + e.getErrorCode()
                    + ").");
        } catch (IOException e) {
            throw new KeyException(1, "Die Schlüsseldatei der Datenbank ist nicht lesbar.");
        }
    }

    /**
     * Changes the password: re-encrypts a copy of {@code dbDir} with a new salt, checks that the copy opens with the
     * new password and only then puts it in place of the old folder. The database must be closed.
     */
    public static void change(Path dbDir, String oldPassword, String newPassword) throws KeyException {
        String oldKey = open(dbDir, oldPassword);
        requireLength(newPassword);
        Path parent = dbDir.toAbsolutePath().getParent();
        Path copy = parent.resolve("db.passwortwechsel");
        Path old = parent.resolve(OLD_DIR);
        boolean swapped = false;
        try {
            deleteTree(copy);
            deleteTree(old);
            copyTree(dbDir, copy);
            Files.deleteIfExists(copy.resolve("dispofold.trace.db"));
            writeSalt(copy.resolve(SALT_FILE));
            String newKey = derive(newPassword, readSalt(copy.resolve(SALT_FILE)));
            String[] o = oldKey.split(" ");
            String[] n = newKey.split(" ");
            try (Connection c = connect(copy, oldKey); Statement s = c.createStatement()) {
                // Hex only, safe inside the literal.
                s.execute("ALTER USER SA SET PASSWORD '" + n[1] + "'");
            }
            ChangeFileEncryption.execute(copy.toString(), "dispofold", "AES", o[0].toCharArray(),
                    n[0].toCharArray(), true);
            connect(copy, newKey).close();
            Files.move(dbDir, old, StandardCopyOption.ATOMIC_MOVE);
            try {
                Files.move(copy, dbDir, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(old, dbDir, StandardCopyOption.ATOMIC_MOVE);
                throw e;
            }
            swapped = true;
        } catch (IOException | SQLException e) {
            throw new KeyException(1, "Das Passwort konnte nicht geändert werden (Fehler "
                    + (e instanceof SQLException se ? se.getErrorCode() : 0) + "). Die Daten sind unverändert.");
        } finally {
            try {
                deleteTree(copy);
                if (swapped) {
                    deleteTree(old);
                }
            } catch (IOException e) {
                System.err.println("A temporary folder of the password change could not be removed.");
            }
        }
    }

    private static void requireLength(String password) throws KeyException {
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new KeyException(2, "Das Passwort muss mindestens " + MIN_PASSWORD_LENGTH + " Zeichen lang sein.");
        }
    }

    private static Connection connect(Path dbDir, String dbPassword) throws KeyException {
        Properties props = new Properties();
        props.setProperty("user", "sa");
        props.setProperty("password", dbPassword);
        try {
            return new org.h2.Driver().connect(url(dbDir), props);
        } catch (SQLException e) {
            // 28000: wrong user password, 90049: wrong file password
            if (e.getErrorCode() == 28000 || e.getErrorCode() == 90049) {
                throw new KeyException(DesktopMain.EXIT_WRONG_PASSWORD, "Das Passwort ist falsch.");
            }
            throw new KeyException(1, "Die Datenbank konnte nicht geöffnet werden (Fehler " + e.getErrorCode() + ").");
        }
    }

    private static void writeSalt(Path file) throws IOException {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        Files.writeString(file, HexFormat.of().formatHex(salt) + "\n", StandardCharsets.US_ASCII);
    }

    private static byte[] readSalt(Path file) throws IOException {
        try {
            byte[] salt = HexFormat.of().parseHex(Files.readString(file, StandardCharsets.US_ASCII).trim());
            if (salt.length < 16) {
                throw new IOException("salt too short");
            }
            return salt;
        } catch (IllegalArgumentException e) {
            throw new IOException("salt unreadable");
        }
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(p);
            }
        }
    }
}
