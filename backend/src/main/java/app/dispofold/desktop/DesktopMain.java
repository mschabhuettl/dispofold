package app.dispofold.desktop;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

import app.dispofold.desktop.update.Json;
import app.dispofold.desktop.update.SessionKeys;

/**
 * Entry point of both builds. With {@code -Ddispofold.desktop=true} (set by the launcher) it first reads one JSON
 * line from stdin, {@code {"password": "...", "secret": "<base64 32 bytes>"}}, derives the database password,
 * checks it against the database file and only then starts Quarkus. Without the property it starts Quarkus
 * directly. The desktop beans themselves exist only in the desktop build ({@code @IfBuildProfile("desktop")}).
 * <p>
 * In the desktop build stdout belongs to the launcher protocol: it is kept for {@link ReadyLine}, and
 * {@code System.out} goes to stderr like the log. When stdin closes (the launcher ended or stops the app), the app
 * ends.
 * <p>
 * With {@code -Ddispofold.change-password=true} as well it reads {@code {"oldPassword": "...", "newPassword":
 * "..."}} instead, re-encrypts the closed database ({@link DatabaseKey#change}) and ends: 0 changed, 4 old password
 * wrong, 2 new password too short, 1 other error. The launcher runs this for {@code --change-password}.
 */
@QuarkusMain
public class DesktopMain {

    /** Exit code for a wrong password, so that the launcher asks again instead of treating it as a broken version. */
    public static final int EXIT_WRONG_PASSWORD = 4;

    /** The process's real stdout, for the ready line; null outside the desktop start. */
    static volatile PrintStream protocol;

    public static void main(String... args) {
        if (Boolean.getBoolean("dispofold.desktop")) {
            protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
            System.setOut(System.err);
        }
        if (Boolean.getBoolean("dispofold.desktop") && Boolean.getBoolean("dispofold.change-password")) {
            System.exit(changePassword());
        }
        if (Boolean.getBoolean("dispofold.desktop")) {
            int code = prepare();
            if (code != 0) {
                System.exit(code);
            }
        }
        Quarkus.run(args);
    }

    /** The first stdin line as a JSON object; null if missing or not an object. */
    private static Map<String, Object> firstLine(BufferedReader in) {
        try {
            String line = in.readLine();
            return line == null ? null : Json.parseObject(line);
        } catch (IOException | IllegalArgumentException | ClassCastException e) {
            return null;
        }
    }

    private static int prepare() {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Map<String, Object> start = firstLine(in);
        String password = start != null && start.get("password") instanceof String p ? p : null;
        String secret = start != null && start.get("secret") instanceof String s ? s : null;
        String dataDir = System.getProperty("dispofold.data-dir");
        try {
            SessionKeys.secret(secret);
        } catch (IllegalArgumentException e) {
            secret = null;
        }
        if (password == null || password.isEmpty() || secret == null || dataDir == null) {
            System.err.println("Dispofold muss über das Programm gestartet werden (Passwort und Schlüssel fehlen).");
            return 2;
        }
        if (dataDir.contains(";") || dataDir.contains("${")) {
            System.err.println("Der Datenordner darf weder \";\" noch \"${\" enthalten.");
            return 2;
        }
        String dbPassword;
        try {
            dbPassword = DatabaseKey.open(Path.of(dataDir, "db"), password);
        } catch (DatabaseKey.KeyException e) {
            System.err.println(e.getMessage());
            return e.code();
        }
        // The launcher keeps stdin open while it wants the app to run; end with it.
        Thread watch = new Thread(() -> {
            try {
                while (in.read() >= 0) {
                    // Nothing else is sent.
                }
            } catch (IOException e) {
                // Treated like the end of stdin.
            }
            System.exit(0);
        }, "launcher-stdin");
        watch.setDaemon(true);
        watch.start();
        System.setProperty("dispofold.desktop.db-password", dbPassword);
        System.setProperty("dispofold.desktop.secret", secret);
        return 0;
    }

    private static int changePassword() {
        Map<String, Object> request = firstLine(
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
        String oldPassword = request != null && request.get("oldPassword") instanceof String p ? p : null;
        String newPassword = request != null && request.get("newPassword") instanceof String p ? p : null;
        String dataDir = System.getProperty("dispofold.data-dir");
        if (oldPassword == null || newPassword == null || dataDir == null || dataDir.contains(";")
                || dataDir.contains("${")) {
            System.err.println("Dispofold muss über das Programm gestartet werden (Passwort fehlt).");
            return 2;
        }
        try {
            DatabaseKey.change(Path.of(dataDir, "db"), oldPassword, newPassword);
            return 0;
        } catch (DatabaseKey.KeyException e) {
            System.err.println(e.getMessage());
            return e.code();
        }
    }
}
