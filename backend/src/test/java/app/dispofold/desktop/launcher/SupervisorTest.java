package app.dispofold.desktop.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import app.dispofold.desktop.update.BundleMaker;
import app.dispofold.desktop.update.BundleVerifier;
import app.dispofold.desktop.update.BundleVerifierTest;
import app.dispofold.desktop.update.SessionKeys;
import app.dispofold.desktop.update.SignTool;
import app.dispofold.desktop.update.Version;

/** The launcher's state machine with a scripted fake app instead of real processes. */
class SupervisorTest {

    @TempDir
    Path tmp;

    static final String SECRET = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    KeyPair keys;
    Folders folders;
    FakeRunner runner = new FakeRunner();
    RecordingUi ui = new RecordingUi();
    Supervisor supervisor;

    /** One scripted start: becomes ready or not, then exits with {@code code}; {@code action} runs while it runs. */
    record Step(boolean ready, int code, IoAction action, IoAction whileRunning) {
        Step(boolean ready, int code, IoAction action) {
            this(ready, code, action, () -> { });
        }
    }

    interface IoAction {
        void run() throws IOException;
    }

    final class FakeRunner implements AppRunner {
        final Deque<Step> steps = new ArrayDeque<>();
        final List<String> started = new ArrayList<>();
        final List<String> passwords = new ArrayList<>();
        final List<String> secrets = new ArrayList<>();
        final List<Map<String, Object>> readyLines = new ArrayList<>();
        final List<String> passwordChanges = new ArrayList<>();
        int passwordChangeCode;

        @Override
        public int changePassword(Path quarkusApp, String version, String oldPassword, String newPassword) {
            assertThat(quarkusApp.resolve("quarkus-run.jar")).exists();
            passwordChanges.add(version + ":" + oldPassword + ">" + newPassword);
            return passwordChangeCode;
        }

        @Override
        public Child start(Path quarkusApp, String version, String password, String secret) {
            started.add(version);
            passwords.add(password);
            secrets.add(secret);
            int port = 40000 + started.size();
            String nonce = SessionKeys.nonce();
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("port", (long) port);
            line.put("pid", 4242L);
            line.put("nonce", nonce);
            line.put("proof", SessionKeys.proof(SessionKeys.secret(secret), nonce, port, 4242));
            readyLines.add(line);
            assertThat(quarkusApp.resolve("quarkus-run.jar")).exists();
            Step step = steps.removeFirst();
            return new Child() {
                @Override
                public boolean awaitReady(Duration timeout) {
                    try {
                        step.action().run();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    return step.ready();
                }

                @Override
                public Map<String, Object> readyLine() {
                    return line;
                }

                @Override
                public int waitFor() {
                    try {
                        step.whileRunning().run();
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    return step.code();
                }

                @Override
                public int stop() {
                    return step.code();
                }
            };
        }
    }

    static class RecordingUi implements Ui {
        final List<String> messages = new ArrayList<>();
        final List<String> codes = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final Deque<String> passwords = new ArrayDeque<>();

        @Override
        public void warn(String code, Map<String, Object> params, String text) {
            codes.add(code);
            messages.add(text);
        }

        @Override
        public void fail(String code, Map<String, Object> params, String text) {
            codes.add(code);
            messages.add(text);
            events.add("failed " + code);
        }

        @Override
        public void info(String code, String text) {
            codes.add(code);
            messages.add(text);
        }

        @Override
        public String password(boolean firstStart, boolean retry) {
            return passwords.pollFirst();
        }

        @Override
        public void starting(String version) {
            events.add("starting " + version);
        }

        @Override
        public void ready(Map<String, Object> line) {
            events.add("ready " + line.get("port"));
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        keys = SignTool.keyPair();
        Path program = tmp.resolve("program");
        BundleVerifierTest.fakeApp(program.resolve("app"));
        Files.write(program.resolve("bundle.json"), BundleMaker.manifest(program.resolve("app"), Version.parse("1.0.0")));
        folders = Folders.of(tmp.resolve("base"), program);
        supervisor = new Supervisor(folders, new BundleVerifier(keys.getPublic()), runner, ui, Duration.ofSeconds(1),
                SECRET);
        Files.createDirectories(folders.db());
        Files.writeString(folders.db().resolve("dispofold.mv.db"), "data before update");
    }

    void offer(String version) throws IOException {
        Path appDir = BundleVerifierTest.fakeApp(tmp.resolve("build-" + version));
        Files.writeString(appDir.resolve("version.txt"), version);
        BundleMaker.make(appDir, Version.parse(version), keys.getPrivate(), folders.incoming());
    }

    VersionState state() throws IOException {
        return VersionState.read(folders.stateFile());
    }

    @Test
    void bundledVersionStartsWithoutState() throws IOException {
        runner.steps.add(new Step(true, 0, () -> { }));
        assertThat(supervisor.run("pw")).isZero();
        assertThat(runner.started).containsExactly("1.0.0");
        assertThat(folders.instanceFile()).doesNotExist();
    }

    @Test
    void everyStartIsAnnouncedWithItsReadyLine() throws IOException {
        runner.steps.add(new Step(true, 0, () -> { }, () -> {
            String instance = Files.readString(folders.instanceFile());
            assertThat(instance).contains("\"host\"").contains("\"version\"").doesNotContain(SECRET)
                    .doesNotContain("port");
        }));
        assertThat(supervisor.run("pw")).isZero();
        assertThat(ui.events).containsExactly("starting 1.0.0", "ready 40001");
        assertThat(runner.secrets).containsExactly(SECRET);
        assertThat(folders.instanceFile()).doesNotExist();
    }

    @Test
    void trialSuccessBecomesCurrent() throws IOException {
        offer("1.1.0");
        runner.steps.add(new Step(true, 0, () -> {
            assertThat(folders.instanceFile()).doesNotExist();
            assertThat(state().trial).isEqualTo("1.1.0");
        }));
        assertThat(supervisor.run("pw")).isZero();
        assertThat(runner.started).containsExactly("1.1.0");
        VersionState s = state();
        assertThat(s.current).isEqualTo("1.1.0");
        assertThat(s.previous).isEqualTo("1.0.0");
        assertThat(s.trial).isNull();
        assertThat(s.bad).isEmpty();
        assertThat(folders.incoming().resolve("dispofold-1.1.0-update.zip")).doesNotExist();
        try (var backups = Files.list(folders.data().resolve("sicherungen"))) {
            Path backup = backups.findFirst().orElseThrow();
            assertThat(backup.getFileName().toString()).startsWith("vor-1.1.0-");
            assertThat(backup.resolve("dispofold.mv.db")).hasContent("data before update");
        }
    }

    @Test
    void trialFailureRestoresBackupAndMarksBad() throws IOException {
        offer("1.1.0");
        // The trial changes the database (a migration) and then fails before it is ready.
        runner.steps.add(new Step(false, 1,
                () -> Files.writeString(folders.db().resolve("dispofold.mv.db"), "migrated by 1.1.0")));
        runner.steps.add(new Step(true, 0, () -> assertThat(folders.db().resolve("dispofold.mv.db"))
                .hasContent("data before update")));
        assertThat(supervisor.run("pw")).isZero();
        assertThat(runner.started).containsExactly("1.1.0", "1.0.0");
        VersionState s = state();
        assertThat(s.bad).containsExactly("1.1.0");
        assertThat(s.trial).isNull();
        assertThat(s.current).isNull();
        assertThat(folders.versions().resolve("1.1.0")).doesNotExist();
        assertThat(ui.messages).anyMatch(m -> m.contains("1.1.0") && m.contains("nicht starten"));
        try (var backups = Files.list(folders.data().resolve("sicherungen"))) {
            assertThat(backups.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith("fehlgeschlagen-1.1.0"));
        }
    }

    @Test
    void badVersionIsSkipped() throws IOException {
        VersionState s = new VersionState();
        s.bad.add("1.1.0");
        s.trial = "1.1.0";
        s.write(folders.stateFile());
        offer("1.1.0");
        runner.steps.add(new Step(true, 0, () -> { }));
        assertThat(supervisor.run("pw")).isZero();
        assertThat(runner.started).containsExactly("1.0.0");
        assertThat(state().trial).isNull();
        assertThat(folders.versions().resolve("1.1.0")).doesNotExist();
        assertThat(ui.messages).anyMatch(m -> m.contains("nicht installiert"));
    }

    @Test
    void restartCodeStagesTheNewBundle() throws IOException {
        // The app stages 1.2.0 in eingang/ and exits with 3; the launcher starts the trial.
        runner.steps.add(new Step(true, AppRunner.EXIT_RESTART, () -> offer("1.2.0")));
        runner.steps.add(new Step(true, 0, () -> { }));
        assertThat(supervisor.run("pw")).isZero();
        assertThat(runner.started).containsExactly("1.0.0", "1.2.0");
        assertThat(state().current).isEqualTo("1.2.0");
        // A new handshake for the new start: starting and ready again, a new port and nonce, the same secret.
        assertThat(ui.events).containsExactly("starting 1.0.0", "ready 40001", "starting 1.2.0", "ready 40002");
        assertThat(runner.readyLines.get(1).get("nonce")).isNotEqualTo(runner.readyLines.get(0).get("nonce"));
        assertThat(runner.secrets).containsExactly(SECRET, SECRET);
    }

    @Test
    void undeletableOldVersionDoesNotStopTheNewOne() throws IOException {
        // 1.1.0, then 1.2.0, then 1.3.0: committing 1.3.0 removes 1.1.0, which is locked (Windows file lock).
        supervisor.deleter = dir -> {
            if (dir.getFileName().toString().equals("1.1.0")) {
                throw new IOException("locked");
            }
            org.junit.jupiter.api.Assertions.fail("only 1.1.0 is removed");
        };
        offer("1.1.0");
        runner.steps.add(new Step(true, AppRunner.EXIT_RESTART, () -> offer("1.2.0")));
        runner.steps.add(new Step(true, AppRunner.EXIT_RESTART, () -> offer("1.3.0")));
        runner.steps.add(new Step(true, 0, () -> { }, () -> assertThat(state().current).isEqualTo("1.3.0")));
        assertThat(supervisor.run("pw")).isZero();
        assertThat(runner.started).containsExactly("1.1.0", "1.2.0", "1.3.0");
        VersionState s = state();
        assertThat(s.current).isEqualTo("1.3.0");
        assertThat(s.previous).isEqualTo("1.2.0");
        assertThat(s.trial).isNull();
        assertThat(s.bad).isEmpty();
        assertThat(folders.versions().resolve("1.1.0")).exists();
        assertThat(ui.messages).isEmpty();
    }

    @Test
    void undeletableFailedVersionStillRollsBack() throws IOException {
        supervisor.deleter = dir -> {
            throw new IOException("locked");
        };
        offer("1.1.0");
        runner.steps.add(new Step(false, 1, () -> { }));
        runner.steps.add(new Step(true, 0, () -> { }));
        assertThat(supervisor.run("pw")).isZero();
        assertThat(runner.started).containsExactly("1.1.0", "1.0.0");
        assertThat(state().bad).containsExactly("1.1.0");
        assertThat(state().trial).isNull();
    }

    @Test
    void oldBackupsArePruned() throws IOException {
        Path backups = Files.createDirectories(folders.data().resolve("sicherungen"));
        for (int i = 1; i <= 4; i++) {
            Files.createDirectories(backups.resolve("vor-0.9." + i + "-2026010" + i + "-120000"));
            Files.createDirectories(backups.resolve("fehlgeschlagen-0.9." + i + "-2026010" + i + "-120000"));
        }
        Files.createDirectories(backups.resolve("eigene-kopie"));
        Supervisor keepTwo = new Supervisor(folders, new BundleVerifier(keys.getPublic()), runner, ui,
                Duration.ofSeconds(1), SECRET, 2);
        offer("1.1.0");
        runner.steps.add(new Step(true, 0, () -> { }));
        assertThat(keepTwo.run("pw")).isZero();
        try (var left = Files.list(backups)) {
            assertThat(left.map(p -> p.getFileName().toString())).containsExactlyInAnyOrder(
                    "vor-0.9.4-20260104-120000", newestBackup(backups),
                    "fehlgeschlagen-0.9.3-20260103-120000", "fehlgeschlagen-0.9.4-20260104-120000", "eigene-kopie");
        }
        assertThat(state().current).isEqualTo("1.1.0");
    }

    static String newestBackup(Path backups) throws IOException {
        try (var all = Files.list(backups)) {
            return all.map(p -> p.getFileName().toString()).filter(n -> n.startsWith("vor-1.1.0-")).findFirst()
                    .orElseThrow();
        }
    }

    @Test
    void backupOfAPendingTrialAndUnstampedBackupsAreKept() throws IOException {
        Path backups = Files.createDirectories(folders.data().resolve("sicherungen"));
        // A clock that was ahead once: this backup sorts after the new one.
        Files.createDirectories(backups.resolve("vor-0.9.9-20990101-000000"));
        Files.createDirectories(backups.resolve("vor-wichtig"));
        Supervisor keepOne = new Supervisor(folders, new BundleVerifier(keys.getPublic()), runner, ui,
                Duration.ofSeconds(1), SECRET, 1);
        offer("1.1.0");
        keepOne.stageIncoming();
        String needed = state().backup;
        assertThat(needed).startsWith("vor-1.1.0-");
        assertThat(backups.resolve(needed)).isDirectory();
        assertThat(backups.resolve("vor-wichtig")).isDirectory();
    }

    @Test
    void noPasswordChangeWhileATrialIsPending() throws IOException {
        offer("1.1.0");
        supervisor.stageIncoming();
        assertThat(supervisor.changePassword("alt-Passwort-123", "neues-Passwort-1")).isEqualTo(1);
        assertThat(runner.passwordChanges).isEmpty();
        assertThat(ui.messages).anyMatch(m -> m.contains("Update wartet"));
    }

    @Test
    void interruptedPasswordChangeIsRecoveredBeforeAnythingElse() throws IOException {
        Files.move(folders.db(), folders.data().resolve("db.alt"));
        assertThat(supervisor.firstStart()).isFalse();
        assertThat(folders.db().resolve("dispofold.mv.db")).hasContent("data before update");
        assertThat(folders.data().resolve("db.alt")).doesNotExist();
    }

    @Test
    void keepBackupsMustBePositive() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Supervisor(folders,
                new BundleVerifier(keys.getPublic()), runner, ui, Duration.ofSeconds(1), SECRET, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passwordChangeRunsOnTheVersionThatWouldStart() throws IOException {
        offer("1.1.0");
        runner.steps.add(new Step(true, 0, () -> { }));
        supervisor.run("pw");
        assertThat(supervisor.changePassword("alt", "neues-Passwort-1")).isZero();
        assertThat(runner.passwordChanges).containsExactly("1.1.0:alt>neues-Passwort-1");
        assertThat(ui.messages).anyMatch(m -> m.contains("Passwort wurde geändert"));
        runner.passwordChangeCode = AppRunner.EXIT_WRONG_PASSWORD;
        assertThat(supervisor.changePassword("falsch", "neues-Passwort-1")).isEqualTo(4);
        assertThat(ui.messages).anyMatch(m -> m.contains("bisherige Passwort ist falsch"));
    }

    @Test
    void olderBundleThanCurrentIsRefused() throws IOException {
        offer("1.1.0");
        runner.steps.add(new Step(true, AppRunner.EXIT_RESTART, () -> offer("1.0.5")));
        runner.steps.add(new Step(true, 0, () -> { }));
        assertThat(supervisor.run("pw")).isZero();
        assertThat(runner.started).containsExactly("1.1.0", "1.1.0");
        assertThat(ui.messages).anyMatch(m -> m.contains("nicht neuer"));
    }

    @Test
    void wrongPasswordAsksAgainWithoutRollback() throws IOException {
        offer("1.1.0");
        ui.passwords.add("right");
        runner.steps.add(new Step(false, AppRunner.EXIT_WRONG_PASSWORD, () -> { }));
        runner.steps.add(new Step(true, 0, () -> { }));
        assertThat(supervisor.run("wrong")).isZero();
        assertThat(runner.started).containsExactly("1.1.0", "1.1.0");
        assertThat(runner.passwords).containsExactly("wrong", "right");
        assertThat(state().bad).isEmpty();
        assertThat(state().current).isEqualTo("1.1.0");
    }

    @Test
    void damagedInstalledVersionIsNotReplacedByAnOlderOne() throws IOException {
        offer("1.1.0");
        runner.steps.add(new Step(true, 0, () -> { }));
        supervisor.run("pw");
        Files.writeString(folders.versions().resolve("1.1.0/app/quarkus-run.jar"), "changed");
        // The bundled 1.0.0 would run on data last used by 1.1.0: refused, nothing started
        assertThat(supervisor.run("pw")).isEqualTo(1);
        assertThat(runner.started).containsExactly("1.1.0");
        assertThat(ui.messages).anyMatch(m -> m.contains("beschädigt"));
        assertThat(ui.messages).anyMatch(m -> m.contains("neueren Version 1.1.0"));
        assertThat(folders.db().resolve("dispofold.mv.db")).hasContent("data before update");
    }

    @Test
    void firstStartOfAnyVersionOnExistingDataMakesABackup() throws IOException {
        runner.steps.add(new Step(true, 0, () -> { }));
        supervisor.run("pw");
        runner.steps.add(new Step(true, 0, () -> { }));
        supervisor.run("pw");
        assertThat(state().lastStarted).isEqualTo("1.0.0");
        try (var backups = Files.list(folders.data().resolve("sicherungen"))) {
            // one backup before the first start of 1.0.0, none for the second start of the same version
            assertThat(backups.map(p -> p.getFileName().toString())).singleElement()
                    .satisfies(n -> assertThat(n).startsWith("vor-1.0.0-"));
        }
    }

    @Test
    void damagedStateFileIsReplacedWithTheNewestInstalledVersion() throws IOException {
        offer("1.1.0");
        runner.steps.add(new Step(true, 0, () -> { }));
        supervisor.run("pw");
        Files.writeString(folders.stateFile(), "{\"current\": \"1.1");
        runner.steps.add(new Step(true, 0, () -> { }));
        assertThat(supervisor.run("pw")).isZero();
        assertThat(runner.started).containsExactly("1.1.0", "1.1.0");
        assertThat(state().current).isEqualTo("1.1.0");
        assertThat(folders.versions().resolve("state.json.beschaedigt")).exists();
        assertThat(ui.messages).anyMatch(m -> m.contains("state.json war beschädigt"));
    }

    @Test
    void newerBundledVersionWinsOverInstalled() throws IOException {
        offer("1.1.0");
        runner.steps.add(new Step(true, 0, () -> { }));
        supervisor.run("pw");
        // A new installer brings 2.0.0
        Files.write(folders.bundled().resolve("bundle.json"),
                BundleMaker.manifest(folders.bundled().resolve("app"), Version.parse("2.0.0")));
        runner.steps.add(new Step(true, 0, () -> { }));
        supervisor.run("pw");
        assertThat(runner.started).containsExactly("1.1.0", "2.0.0");
        try (var backups = Files.list(folders.data().resolve("sicherungen"))) {
            assertThat(backups.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith("vor-2.0.0-"));
        }
    }

    @Test
    void interruptedRollbackRestoresTheBackupBeforeStarting() throws IOException {
        offer("1.1.0");
        supervisor.stageIncoming();
        // A rollback stopped after moving the database aside: no database, trial and backup still recorded
        app.dispofold.desktop.update.BundleVerifier.deleteTree(folders.db());
        assertThat(supervisor.firstStart()).isFalse();
        runner.steps.add(new Step(true, 0, () -> assertThat(folders.db().resolve("dispofold.mv.db"))
                .hasContent("data before update")));
        assertThat(supervisor.run("pw")).isZero();
    }

    @Test
    void dataFolderWithSemicolonOrExpressionIsRefused() throws IOException {
        for (String bad : new String[] { "a;b", "a${x}b" }) {
            RecordingUi recording = new RecordingUi();
            String[] args = { "--change-password", "--program-dir", tmp.toString(), "--data",
                    tmp.resolve(bad).toString(), "--app", folders.bundled().toString() };
            assertThat(Launcher.run(args, recording, null, null, f -> runner, Folders.SYSTEM)).isEqualTo(2);
            assertThat(recording.messages).singleElement().asString().contains("Datenordner");
            assertThat(tmp.resolve(bad)).doesNotExist();
        }
    }

    @Test
    void lockIsExclusive() throws IOException {
        Path file = tmp.resolve("daten/dispofold.lock");
        try (InstanceLock first = InstanceLock.tryAcquire(file).orElseThrow()) {
            assertThat(InstanceLock.tryAcquire(file)).isEmpty();
        }
        assertThat(InstanceLock.tryAcquire(file)).isPresent();
    }

    @Test
    void portableMarkerAndSystemFolders() throws IOException {
        Path program = Files.createDirectories(tmp.resolve("Dispofold"));
        assertThat(Folders.base(program, java.util.Map.of(), "Linux", "/u")).isEqualTo(Path.of("/u/.local/share/dispofold"));
        assertThat(Folders.base(program, java.util.Map.of("XDG_DATA_HOME", "/x"), "Linux", "/u"))
                .isEqualTo(Path.of("/x/dispofold"));
        assertThat(Folders.base(program, java.util.Map.of(), "Mac OS X", "/u"))
                .isEqualTo(Path.of("/u/Library/Application Support/Dispofold"));
        Files.writeString(program.resolve("PORTABLE"), "");
        assertThat(Folders.base(program, java.util.Map.of(), "Linux", "/u")).isEqualTo(program);
    }

    @Test
    void appTranslocationIsDetected() {
        // --program-dir as the window passes it on macOS: the resource folder inside the .app
        Path program = Path.of("/private/var/folders/xy/abc123/T/AppTranslocation/0F1E2D3C-4B5A/d/Dispofold.app"
                + "/Contents/Resources");
        assertThat(Folders.translocated(program)).isTrue();
        assertThat(Folders.translocated(Path.of("/Applications/Dispofold"))).isFalse();
        assertThat(Folders.translocated(tmp)).isFalse();
        assertThat(Folders.TRANSLOCATED).contains("App Translocation").contains("Dispofold.app im Finder")
                .contains("\"Programme\"").contains("xattr -dr com.apple.quarantine /Applications/Dispofold.app")
                .doesNotContain("ZIP").doesNotContain("den Ordner Dispofold");
    }
}
