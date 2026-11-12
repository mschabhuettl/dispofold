package app.dispofold.desktop.launcher;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import app.dispofold.desktop.update.BundleVerifier;
import app.dispofold.desktop.update.Json;

/**
 * Main class of dispofold-launcher.jar. See docs/desktop.md.
 *
 * <pre>
 * java -jar dispofold-launcher.jar --shell --program-dir &lt;folder&gt; [--data &lt;folder&gt;] [--app &lt;folder&gt;]
 *           [--ready-timeout &lt;seconds&gt;] [--keep-backups &lt;n&gt;]
 * java -jar dispofold-launcher.jar --change-password --program-dir ... [--data ...] [--app ...]
 * </pre>
 *
 * {@code --shell}: started by the program window, talks only the line protocol (ShellUi) on stdin and stdout.
 * {@code --change-password}: re-encrypts the database with a new password and ends (dialog or console; headless
 * with {@code DISPOFOLD_PASSWORD} and {@code DISPOFOLD_NEW_PASSWORD}). Any other start is refused: Dispofold opens
 * only in its program window. {@code --program-dir} (required): the program folder (PORTABLE marker, translocation
 * check); {@code --data}: base folder with daten/ and versionen/
 * (instead of the PORTABLE or user data folder); {@code --app}: folder of the bundled app ({@code bundle.json} and
 * {@code app/quarkus-run.jar}), default the folder of the launcher jar; {@code --keep-backups}: database backups
 * kept in daten/sicherungen/ per kind (default 5).
 */
public final class Launcher {

    private Launcher() {
    }

    record Options(boolean fromShell, boolean changePassword, Path programDir, Path base, Path app, Duration timeout,
            int keepBackups) {
    }

    public static void main(String[] args) {
        // stdout belongs to the line protocol; everything else that prints goes to stderr.
        PrintStream protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        int code = run(args, Arrays.asList(args).contains("--shell") ? null : new SystemUi(), System.in, protocol,
                ProcessRunner::new, Folders.SYSTEM);
        System.exit(code);
    }

    /**
     * {@code console} is used without {@code --shell}; with it, the line protocol on {@code stdin} and
     * {@code protocol}, ending with exactly one {@code exit} line.
     */
    static int run(String[] args, Ui console, InputStream stdin, PrintStream protocol,
            Function<Folders, AppRunner> runners, Folders.StoreKind stores) {
        boolean fromShell = Arrays.asList(args).contains("--shell");
        Options o;
        try {
            o = options(args);
        } catch (IllegalArgumentException e) {
            if (fromShell) {
                failAndExit(protocol, "desktop.launcher.usage", 2);
            } else {
                System.err.println(e.getMessage());
                System.err.println("Aufruf: Dispofold --shell | --change-password --program-dir <Ordner>"
                        + " [--data <Ordner>] [--app <Ordner>] [--ready-timeout <Sekunden>] [--keep-backups <Anzahl>]");
            }
            return 2;
        }
        if (!o.fromShell()) {
            if (!o.changePassword()) {
                console.fail("desktop.launcher.windowOnly", Map.of(), "Dispofold wird über das Programm geöffnet"
                        + " (Startmenü, Programmordner oder Dock).");
                return 2;
            }
            try {
                return changePassword(o, console, runners);
            } catch (IOException | RuntimeException e) {
                console.fail("desktop.launcher.error", Map.of("type", e.getClass().getSimpleName()),
                        "Dispofold konnte nicht gestartet werden (" + e.getClass().getSimpleName() + ").");
                return 1;
            }
        }
        ShellUi shell;
        try {
            shell = new ShellUi(stdin, protocol);
        } catch (IOException | IllegalArgumentException e) {
            failAndExit(protocol, "desktop.protocol.invalid", 2);
            return 2;
        }
        int code = 1;
        try {
            code = start(o, shell, runners, stores);
        } catch (IOException | RuntimeException e) {
            // No exception text: it may name folders below the user's home.
            shell.fail("desktop.launcher.error", Map.of("type", e.getClass().getSimpleName()),
                    "Dispofold konnte nicht gestartet werden (" + e.getClass().getSimpleName() + ").");
            code = 1;
        } finally {
            shell.exit(code);
        }
        return code;
    }

    /** For a start that cannot even read its hello line: {@code failed}, then {@code exit}. */
    private static void failAndExit(PrintStream protocol, String failCode, int exitCode) {
        Map<String, Object> failed = new LinkedHashMap<>();
        failed.put("type", "failed");
        failed.put("code", failCode);
        failed.put("params", Map.of());
        Map<String, Object> exit = new LinkedHashMap<>();
        exit.put("type", "exit");
        exit.put("code", (long) exitCode);
        protocol.println(Json.line(failed));
        protocol.println(Json.line(exit));
        protocol.flush();
    }

    static Options options(String[] args) {
        boolean fromShell = false;
        boolean changePassword = false;
        Path programDir = null;
        Path base = null;
        Path app = null;
        Duration timeout = Duration.ofMinutes(3);
        int keepBackups = Supervisor.DEFAULT_KEEP_BACKUPS;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--shell" -> fromShell = true;
                case "--change-password" -> changePassword = true;
                case "--program-dir" -> programDir = Path.of(value(args, ++i)).toAbsolutePath();
                case "--data" -> base = Path.of(value(args, ++i)).toAbsolutePath();
                case "--app" -> app = Path.of(value(args, ++i)).toAbsolutePath();
                case "--ready-timeout" -> timeout = Duration.ofSeconds(Long.parseLong(value(args, ++i)));
                case "--keep-backups" -> {
                    keepBackups = Integer.parseInt(value(args, ++i));
                    if (keepBackups < 1) {
                        throw new IllegalArgumentException("--keep-backups muss mindestens 1 sein");
                    }
                }
                default -> throw new IllegalArgumentException("Unbekannte Option: " + args[i]);
            }
        }
        if (fromShell && changePassword) {
            throw new IllegalArgumentException("--shell und --change-password schließen sich aus");
        }
        return new Options(fromShell, changePassword, programDir, base, app, timeout, keepBackups);
    }

    /** The folders, or null after a {@code fail} (no program folder, translocated program, unsafe data path). */
    private static Folders folders(Options o, Ui ui) {
        Path jarDir = jarDir();
        Path program = o.programDir();
        if (program == null) {
            ui.fail("desktop.launcher.programDirMissing", Map.of(), "Der Programmordner fehlt (--program-dir).");
            return null;
        }
        if (Folders.translocated(program)) {
            ui.fail("desktop.translocated", Map.of(), Folders.TRANSLOCATED);
            return null;
        }
        Path base = o.base() != null ? o.base() : Folders.base(program, System.getenv(),
                System.getProperty("os.name", ""), System.getProperty("user.home"));
        Folders folders = Folders.of(base, o.app() != null ? o.app() : jarDir);
        String unsafe = Folders.unsafePath(folders.data());
        if (unsafe != null) {
            ui.fail("desktop.dataFolder.unsafe", Map.of(), unsafe);
            return null;
        }
        return folders;
    }

    private static int start(Options o, ShellUi shell, Function<Folders, AppRunner> runners, Folders.StoreKind stores)
            throws IOException {
        Folders folders = folders(o, shell);
        if (folders == null) {
            return 2;
        }
        Optional<InstanceLock> lock = InstanceLock.tryAcquire(folders.data().resolve("dispofold.lock"));
        if (lock.isEmpty()) {
            shell.locked();
            return 1;
        }
        try (InstanceLock held = lock.get()) {
            Files.deleteIfExists(folders.instanceFile());
            if (Folders.onNetwork(folders.data(), stores)) {
                shell.warn("desktop.dataFolder.network", Map.of(), "Der Datenordner liegt auf einem Netzlaufwerk. Das"
                        + " kann die Datenbank beschädigen. Empfohlen ist ein lokaler Ordner.");
            }
            Supervisor supervisor = new Supervisor(folders, new BundleVerifier(), runners.apply(folders), shell,
                    o.timeout(), shell.secret(), o.keepBackups());
            shell.listen(supervisor::quit);
            String password = shell.password(supervisor.firstStart(), false);
            if (password == null) {
                return 0;
            }
            try {
                return supervisor.run(password);
            } finally {
                Files.deleteIfExists(folders.instanceFile());
            }
        }
    }

    private static int changePassword(Options o, Ui console, Function<Folders, AppRunner> runners)
            throws IOException {
        Folders folders = folders(o, console);
        if (folders == null) {
            return 2;
        }
        Optional<InstanceLock> lock = InstanceLock.tryAcquire(folders.data().resolve("dispofold.lock"));
        if (lock.isEmpty()) {
            console.fail("desktop.lock.held", Map.of(), "Dispofold ist geöffnet. Bitte zuerst beenden.");
            return 1;
        }
        try (InstanceLock held = lock.get()) {
            String env = System.getenv("DISPOFOLD_PASSWORD");
            Ui prompt = env == null || env.isEmpty() ? console : new EnvPassword(console, env);
            Supervisor supervisor = new Supervisor(folders, new BundleVerifier(), runners.apply(folders), prompt,
                    o.timeout(), null, o.keepBackups());
            if (supervisor.firstStart()) {
                console.fail("desktop.password.noDatabase", Map.of(),
                        "Es gibt noch keine Datenbank, deren Passwort sich ändern ließe.");
                return 1;
            }
            String oldPassword = prompt.password(false, false);
            String newPassword = env == null || env.isEmpty() ? console.password(true, false)
                    : System.getenv("DISPOFOLD_NEW_PASSWORD");
            if (oldPassword == null || newPassword == null || newPassword.isEmpty()) {
                console.fail("desktop.password.missing", Map.of(), "Kein neues Passwort: Es wurde nichts geändert.");
                return 1;
            }
            return supervisor.changePassword(oldPassword, newPassword);
        }
    }

    private static String value(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Wert fehlt für " + args[i - 1]);
        }
        return args[i];
    }

    private static Path jarDir() {
        try {
            Path p = Path.of(Launcher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isDirectory(p) ? p : p.getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Password from DISPOFOLD_PASSWORD; a wrong one ends the launcher instead of asking again. */
    private record EnvPassword(Ui ui, String password) implements Ui {

        @Override
        public void warn(String code, Map<String, Object> params, String text) {
            ui.warn(code, params, text);
        }

        @Override
        public void fail(String code, Map<String, Object> params, String text) {
            ui.fail(code, params, text);
        }

        @Override
        public void info(String code, String text) {
            ui.info(code, text);
        }

        @Override
        public String password(boolean firstStart, boolean retry) {
            if (retry) {
                ui.fail("desktop.password.wrong", Map.of(), "Das Passwort aus DISPOFOLD_PASSWORD ist falsch.");
                return null;
            }
            return password;
        }
    }
}
