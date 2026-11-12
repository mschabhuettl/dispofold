package app.dispofold.desktop.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import app.dispofold.desktop.update.AtomicFiles;
import app.dispofold.desktop.update.BundleException;
import app.dispofold.desktop.update.BundleVerifier;
import app.dispofold.desktop.update.Json;
import app.dispofold.desktop.update.Version;

/**
 * The launcher's version handling and start loop: stages bundles from {@code versionen/eingang/}, picks the
 * version to start, runs a staged version as a trial (database backup first, rollback when it does not become
 * ready), restarts after exit code 3 and asks the password again after exit code 4.
 */
final class Supervisor {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    /** Database backups kept per kind ({@code vor-*}, {@code fehlgeschlagen-*}) unless the launcher says otherwise. */
    static final int DEFAULT_KEEP_BACKUPS = 5;

    /** Deletes a folder tree; replaceable in tests to simulate a file that cannot be deleted. */
    interface TreeDeleter {
        void delete(Path dir) throws IOException;
    }

    private final Folders folders;
    private final BundleVerifier verifier;
    private final AppRunner runner;
    private final Ui ui;
    private final Duration readyTimeout;
    /** The window's secret (Base64), passed on to every start of the app. */
    private final String secret;
    private final int keepBackups;
    TreeDeleter deleter = BundleVerifier::deleteTree;
    private volatile boolean quitting;
    private volatile AppRunner.Child current;
    /** Versions backed up before their first start during this run of the launcher. */
    private final java.util.Set<String> backedUp = new java.util.HashSet<>();

    /** What to start. {@code dir} is the Quarkus app folder. */
    record Choice(Version version, Path dir, boolean trial) {
    }

    Supervisor(Folders folders, BundleVerifier verifier, AppRunner runner, Ui ui, Duration readyTimeout,
            String secret) {
        this(folders, verifier, runner, ui, readyTimeout, secret, DEFAULT_KEEP_BACKUPS);
    }

    Supervisor(Folders folders, BundleVerifier verifier, AppRunner runner, Ui ui, Duration readyTimeout,
            String secret, int keepBackups) {
        if (keepBackups < 1) {
            throw new IllegalArgumentException("keepBackups must be at least 1");
        }
        this.keepBackups = keepBackups;
        this.folders = folders;
        this.verifier = verifier;
        this.runner = runner;
        this.ui = ui;
        this.readyTimeout = readyTimeout;
        this.secret = secret;
    }

    /** The window was closed: stop the app (it ends through its stdin) and do not start it again. */
    void quit() {
        quitting = true;
        AppRunner.Child c = current;
        if (c != null) {
            c.stop();
        }
    }

    boolean firstStart() throws IOException {
        recoverPasswordChange();
        VersionState state = state();
        boolean interruptedRollback = state.trial != null && state.backup != null;
        return !interruptedRollback && !Files.exists(folders.db().resolve("dispofold.mv.db"));
    }

    /** Runs until the app ends for good; returns the launcher's exit code. */
    int run(String password) throws IOException {
        while (!quitting) {
            stageIncoming();
            Choice choice = choose();
            String version = choice.version().toString();
            if (!choice.trial()) {
                VersionState state = state();
                if (state.lastStarted != null && Version.parse(state.lastStarted).isNewerThan(choice.version())) {
                    ui.fail("desktop.version.dataNewer", Map.of("version", version, "lastStarted", state.lastStarted),
                            "Dispofold kann Version " + version + " nicht starten: Die Daten wurden zuletzt"
                            + " mit der neueren Version " + state.lastStarted + " verwendet. Bitte Dispofold"
                            + " (mindestens Version " + state.lastStarted + ") neu installieren. Die Daten bleiben"
                            + " unverändert.");
                    return 1;
                }
                if (!version.equals(state.lastStarted) && backedUp.add(version)) {
                    // First start of a version on these data (for example from a new installer): back up first,
                    // once (not again after a wrong password).
                    backupDatabase(choice.version());
                    pruneBackups();
                }
            }
            ui.starting(version);
            AppRunner.Child child = runner.start(choice.dir(), version, password, secret);
            current = child;
            Thread hook = new Thread(child::stop);
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                if (child.awaitReady(readyTimeout) && !quitting) {
                    if (choice.trial()) {
                        commitTrial();
                    }
                    VersionState started = state();
                    started.lastStarted = version;
                    started.write(folders.stateFile());
                    announce(choice.version());
                    ui.ready(child.readyLine());
                    int code = child.waitFor();
                    Files.deleteIfExists(folders.instanceFile());
                    if (quitting) {
                        return 0;
                    }
                    if (code == AppRunner.EXIT_RESTART) {
                        continue;
                    }
                    if (code != 0) {
                        ui.fail("desktop.app.crashed", Map.of("version", version, "code", (long) code),
                                "Dispofold wurde unerwartet beendet (Code " + code + "). Protokoll: daten/logs/app-"
                                + version + ".log");
                    }
                    return code;
                }
                int code = child.stop();
                if (quitting) {
                    return 0;
                }
                if (code == AppRunner.EXIT_WRONG_PASSWORD) {
                    password = ui.password(false, true);
                    if (password == null) {
                        return quitting ? 0 : AppRunner.EXIT_WRONG_PASSWORD;
                    }
                    continue;
                }
                if (choice.trial()) {
                    rollback(choice.version());
                    ui.warn("desktop.update.rolledBack", Map.of("version", version), "Die neue Version " + version
                            + " ließ sich nicht starten. Dispofold startet wieder die vorherige Version; die Daten"
                            + " sind auf dem Stand vor dem Update. Protokoll: daten/logs/app-" + version + ".log");
                    continue;
                }
                ui.fail("desktop.app.startFailed", Map.of("version", version), "Dispofold konnte nicht gestartet"
                        + " werden (Version " + version + "). Protokoll: daten/logs/app-" + version + ".log");
                return 1;
            } finally {
                // Never leave the app running without its launcher (also when something above failed).
                current = null;
                child.stop();
                try {
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (IllegalStateException e) {
                    // Already shutting down.
                }
            }
        }
        return 0;
    }

    /**
     * Re-encrypts the database with a new password (the app must not run; the caller holds the lock). Old backups
     * keep the old password.
     */
    int changePassword(String oldPassword, String newPassword) throws IOException {
        Choice choice = choose();
        if (choice.trial()) {
            // A failed trial would put back the backup from before it, with the old password.
            ui.fail("desktop.password.trialPending", Map.of(), "Ein Update wartet noch auf seinen ersten Start."
                    + " Bitte Dispofold erst einmal starten und beenden, dann das Passwort ändern. Es wurde nichts"
                    + " geändert.");
            return 1;
        }
        int code = runner.changePassword(choice.dir(), choice.version().toString(), oldPassword, newPassword);
        switch (code) {
            case 0 -> ui.info("desktop.password.changed", "Das Passwort wurde geändert. Sicherungen in"
                    + " daten/sicherungen/ bleiben mit dem bisherigen Passwort verschlüsselt.");
            case AppRunner.EXIT_WRONG_PASSWORD -> ui.fail("desktop.password.wrong", Map.of(), "Das bisherige"
                    + " Passwort ist falsch. Es wurde nichts geändert.");
            case 2 -> ui.fail("desktop.password.short", Map.of(), "Das neue Passwort muss mindestens 12 Zeichen"
                    + " lang sein. Es wurde nichts geändert.");
            default -> ui.fail("desktop.password.changeFailed", Map.of("code", (long) code), "Das Passwort konnte"
                    + " nicht geändert werden (Code " + code + "). Die Daten sind unverändert. Protokoll:"
                    + " daten/logs/app-" + choice.version() + ".log");
        }
        return code;
    }

    /**
     * Reads state.json. A damaged file is moved aside; the newest installed version that verifies becomes
     * current again, so that the launcher does not fall back to an older version on newer data.
     */
    private VersionState state() throws IOException {
        VersionState state = VersionState.read(folders.stateFile());
        if (!state.damaged) {
            return state;
        }
        Version newest = null;
        if (Files.isDirectory(folders.versions())) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(folders.versions())) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    if (!Version.isValid(name) || (newest != null && !Version.parse(name).isNewerThan(newest))) {
                        continue;
                    }
                    try {
                        if (verifier.verifyDir(p).version().equals(Version.parse(name))) {
                            newest = Version.parse(name);
                        }
                    } catch (BundleException e) {
                        // Not usable.
                    }
                }
            }
        }
        state.current = newest == null ? null : newest.toString();
        state.lastStarted = state.current;
        state.write(folders.stateFile());
        ui.warn("desktop.state.damaged", Map.of(), "versionen/state.json war beschädigt und wurde neu angelegt (alte"
                + " Datei: state.json.beschaedigt).");
        return state;
    }

    /** Verifies, unpacks and stages every bundle in {@code versionen/eingang/}; the files are removed after. */
    void stageIncoming() throws IOException {
        Path in = folders.incoming();
        if (!Files.isDirectory(in)) {
            return;
        }
        List<Path> zips = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(in, "*.zip")) {
            ds.forEach(zips::add);
        }
        zips.sort(null);
        for (Path zip : zips) {
            try {
                Version active = choose().version();
                VersionState state = state();
                Version v = verifier.unpack(zip, folders.versions(), active);
                if (state.bad.contains(v.toString())) {
                    BundleVerifier.deleteTree(folders.versions().resolve(v.toString()));
                    throw new BundleException("knownBad", Map.of("version", v.toString()),
                            "Version " + v + " ließ sich bereits früher nicht starten.");
                }
                state.backup = backupDatabase(v);
                state.trial = v.toString();
                state.write(folders.stateFile());
                pruneBackups();
            } catch (BundleException e) {
                Map<String, Object> params = new LinkedHashMap<>(e.params());
                params.put("reason", "desktop.bundle." + e.code());
                ui.warn("desktop.update.refused", params, "Das Update wurde nicht installiert: " + e.getMessage());
            } finally {
                Files.deleteIfExists(zip);
            }
        }
    }

    /**
     * The version to start: a staged trial; else the installed current version if newer than the bundled one,
     * then the previous one, then the bundled one. Installed versions are verified before every start; a version
     * that fails is skipped.
     */
    Choice choose() throws IOException {
        recoverPasswordChange();
        VersionState state = state();
        Path pending = folders.data().resolve("db.wiederherstellen");
        if (state.trial != null && state.backup != null && !Files.exists(folders.db())) {
            // An interrupted rollback: put the backup back before anything opens (or creates) a database.
            BundleVerifier.deleteTree(pending);
            copyTree(folders.data().resolve("sicherungen").resolve(state.backup), pending);
            Files.move(pending, folders.db(), StandardCopyOption.ATOMIC_MOVE);
        }
        if (state.trial != null) {
            Choice trial = installed(state.trial, state, null);
            if (trial != null) {
                return new Choice(trial.version(), trial.dir(), true);
            }
            state.bad.add(state.trial);
            state.trial = null;
            state.backup = null;
            state.write(folders.stateFile());
        }
        return active(state);
    }

    private Choice active(VersionState state) throws IOException {
        Choice bundled = bundled();
        for (String v : new String[] { state.current, state.previous }) {
            Choice c = installed(v, state, bundled.version());
            if (c != null) {
                return c;
            }
        }
        return bundled;
    }

    private Choice installed(String version, VersionState state, Version mustBeNewerThan) {
        if (version == null || state.bad.contains(version)) {
            return null;
        }
        Version v = Version.parse(version);
        if (mustBeNewerThan != null && !v.isNewerThan(mustBeNewerThan)) {
            return null;
        }
        Path dir = folders.versions().resolve(version);
        try {
            Version found = verifier.verifyDir(dir).version();
            if (!found.equals(v)) {
                throw new BundleException("wrongVersion", "Der Ordner enthält Version " + found + ".");
            }
            return new Choice(v, dir.resolve(BundleVerifier.APP), false);
        } catch (BundleException e) {
            ui.warn("desktop.version.damaged", Map.of("version", version, "reason", "desktop.bundle." + e.code()),
                    "Version " + version + " ist beschädigt und wird übersprungen: " + e.getMessage());
            return null;
        }
    }

    private Choice bundled() throws IOException {
        Path json = folders.bundled().resolve(BundleVerifier.BUNDLE_JSON);
        Path app = folders.bundled().resolve(BundleVerifier.APP);
        if (!Files.isRegularFile(json) || !Files.isRegularFile(app.resolve("quarkus-run.jar"))) {
            throw new IOException("Die Programmdateien fehlen.");
        }
        Map<String, Object> doc = Json.parseObject(Files.readString(json, StandardCharsets.UTF_8));
        return new Choice(Version.parse(String.valueOf(doc.get("version"))), app, false);
    }

    private void commitTrial() throws IOException {
        VersionState state = state();
        state.previous = active(state).version().toString();
        state.current = state.trial;
        state.trial = null;
        state.backup = null;
        state.write(folders.stateFile());
        // Keep the current and the previous version only. state.json is written, so the new version is committed;
        // a folder that cannot be deleted now (a file lock on Windows) is tried again after the next update.
        List<Path> old = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folders.versions())) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (Files.isDirectory(p) && Version.isValid(name) && !name.equals(state.current)
                        && !name.equals(state.previous)) {
                    old.add(p);
                }
            }
        } catch (IOException e) {
            System.err.println("versionen/ could not be listed to remove old versions");
        }
        old.forEach(this::deleteBestEffort);
    }

    private void deleteBestEffort(Path dir) {
        try {
            deleter.delete(dir);
        } catch (IOException e) {
            System.err.println("Could not remove " + dir.getFileName() + " (will be tried again later)");
        }
    }

    /**
     * A password change that stopped between its two renames left the complete old folder in {@code daten/db.alt}
     * (see DatabaseKey): put it back before anything looks at {@code daten/db}.
     */
    private void recoverPasswordChange() throws IOException {
        Path old = folders.data().resolve("db.alt");
        if (!Files.exists(folders.db()) && Files.isDirectory(old)) {
            Files.move(old, folders.db(), StandardCopyOption.ATOMIC_MOVE);
        }
    }

    /**
     * Keeps the newest {@code keepBackups} backups of each kind whose names end with a time stamp; the backup a
     * pending trial needs for its rollback and folders named otherwise are never removed.
     */
    void pruneBackups() {
        Path backups = folders.data().resolve("sicherungen");
        if (!Files.isDirectory(backups)) {
            return;
        }
        for (String kind : new String[] { "vor-", "fehlgeschlagen-" }) {
            List<Path> found = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(backups, kind + "*")) {
                ds.forEach(found::add);
            } catch (IOException e) {
                System.err.println("daten/sicherungen/ could not be listed to remove old backups");
                return;
            }
            found.sort(java.util.Comparator.comparing((Path p) -> stamp(p.getFileName().toString()))
                    .thenComparing(p -> p.getFileName().toString()));
            found.removeIf(p -> stamp(p.getFileName().toString()).isEmpty());
            String needed;
            try {
                needed = VersionState.read(folders.stateFile()).backup;
            } catch (IOException e) {
                return;
            }
            for (int i = 0; i < found.size() - keepBackups; i++) {
                if (!found.get(i).getFileName().toString().equals(needed)) {
                    deleteBestEffort(found.get(i));
                }
            }
        }
    }

    /** The trailing {@code yyyyMMdd-HHmmss} of a backup name, or "" when there is none. */
    private static String stamp(String name) {
        return name.length() >= 15 && name.substring(name.length() - 15).matches("\\d{8}-\\d{6}")
                ? name.substring(name.length() - 15) : "";
    }

    /** Copies {@code daten/db} to {@code daten/sicherungen/vor-<version>-<time>/}; null if there is no database. */
    private String backupDatabase(Version v) throws IOException {
        if (!Files.isDirectory(folders.db())) {
            return null;
        }
        String name = "vor-" + v + "-" + LocalDateTime.now().format(STAMP);
        copyTree(folders.db(), folders.data().resolve("sicherungen").resolve(name));
        return name;
    }

    /** Puts the backup taken before the trial back; the database of the failed start is kept aside. */
    private void rollback(Version v) throws IOException {
        VersionState state = state();
        Path db = folders.db();
        Path backups = folders.data().resolve("sicherungen");
        // Copy first: if that fails the database stays where it is and state.json still names the trial.
        Path tmp = folders.data().resolve("db.wiederherstellen");
        BundleVerifier.deleteTree(tmp);
        if (state.backup != null) {
            copyTree(backups.resolve(state.backup), tmp);
        }
        if (Files.exists(db)) {
            Files.createDirectories(backups);
            Files.move(db, backups.resolve("fehlgeschlagen-" + v + "-" + LocalDateTime.now().format(STAMP)));
        }
        if (state.backup != null) {
            Files.move(tmp, db, StandardCopyOption.ATOMIC_MOVE);
        }
        if (!state.bad.contains(v.toString())) {
            state.bad.add(v.toString());
        }
        state.trial = null;
        state.backup = null;
        state.write(folders.stateFile());
        // A bad version is never started again, so its folder may stay when it cannot be deleted now.
        deleteBestEffort(folders.versions().resolve(v.toString()));
        pruneBackups();
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

    /** Writes {@code daten/instanz.json} (no secrets: host, version, pid, start time). */
    private void announce(Version version) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("host", Folders.hostName());
        doc.put("version", version.toString());
        doc.put("pid", ProcessHandle.current().pid());
        doc.put("started", System.currentTimeMillis());
        AtomicFiles.write(folders.instanceFile(), Json.write(doc).getBytes(StandardCharsets.UTF_8), false);
    }
}
