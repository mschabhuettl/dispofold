package app.dispofold.desktop.launcher;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import app.dispofold.desktop.update.BundleMaker;
import app.dispofold.desktop.update.BundleVerifierTest;
import app.dispofold.desktop.update.Json;
import app.dispofold.desktop.update.SessionKeys;
import app.dispofold.desktop.update.Version;

/** The launcher's line protocol with the program window, end to end through Launcher.run with a fake app. */
@Timeout(30)
class ShellProtocolTest {

    static final String SECRET = SupervisorTest.SECRET;
    static final String HELLO = "{\"type\":\"hello\",\"secret\":\"" + SECRET + "\",\"protocol\":1}";

    @TempDir
    Path tmp;

    Path program;
    Path base;
    FakeApp app = new FakeApp();

    /** Each start becomes ready at once and runs until stopped (or exits with the next scripted code). */
    static final class FakeApp implements AppRunner {
        final BlockingQueue<Integer> exitCodes = new LinkedBlockingQueue<>();
        final List<String> passwords = new ArrayList<>();
        volatile int notReadyCode = -1;

        @Override
        public Child start(Path quarkusApp, String version, String password, String secret) {
            passwords.add(password);
            int port = 41000 + passwords.size();
            String nonce = SessionKeys.nonce();
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("port", (long) port);
            line.put("pid", 777L);
            line.put("nonce", nonce);
            line.put("proof", SessionKeys.proof(SessionKeys.secret(secret), nonce, port, 777));
            CountDownLatch stopped = new CountDownLatch(1);
            int failCode = notReadyCode;
            notReadyCode = -1;
            return new Child() {
                @Override
                public boolean awaitReady(Duration timeout) {
                    return failCode < 0;
                }

                @Override
                public Map<String, Object> readyLine() {
                    return line;
                }

                @Override
                public int waitFor() {
                    try {
                        while (true) {
                            Integer code = exitCodes.poll(50, TimeUnit.MILLISECONDS);
                            if (code != null) {
                                return code;
                            }
                            if (stopped.await(0, TimeUnit.MILLISECONDS)) {
                                return 0;
                            }
                        }
                    } catch (InterruptedException e) {
                        return 1;
                    }
                }

                @Override
                public int stop() {
                    stopped.countDown();
                    return failCode < 0 ? 0 : failCode;
                }
            };
        }

        @Override
        public int changePassword(Path quarkusApp, String version, String oldPassword, String newPassword) {
            return 0;
        }
    }

    /** A running launcher: write window lines, read launcher lines. */
    final class Session implements AutoCloseable {
        final PipedOutputStream toLauncher = new PipedOutputStream();
        final BlockingQueue<Map<String, Object>> lines = new LinkedBlockingQueue<>();
        final List<String> raw = new ArrayList<>();
        final CompletableFuture<Integer> result;

        Session(Folders.StoreKind stores, String... extraArgs) throws IOException {
            PipedInputStream stdin = new PipedInputStream(toLauncher, 1 << 16);
            PrintStream protocol = new PrintStream(new OutputStream() {
                final StringBuilder b = new StringBuilder();

                @Override
                public synchronized void write(int c) {
                    if (c == '\n') {
                        String text = b.toString();
                        b.setLength(0);
                        synchronized (raw) {
                            raw.add(text);
                        }
                        lines.add(Json.parseObject(text));
                    } else {
                        b.append((char) c);
                    }
                }
            }, true, StandardCharsets.UTF_8);
            List<String> args = new ArrayList<>(List.of("--shell", "--program-dir", program.toString(), "--data",
                    base.toString(), "--app", program.toString()));
            args.addAll(List.of(extraArgs));
            result = CompletableFuture.supplyAsync(() -> Launcher.run(args.toArray(String[]::new), null, stdin,
                    protocol, f -> app, stores));
        }

        void send(String line) throws IOException {
            toLauncher.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            toLauncher.flush();
        }

        Map<String, Object> next() throws InterruptedException {
            Map<String, Object> line = lines.poll(10, TimeUnit.SECONDS);
            assertThat(line).as("a line from the launcher").isNotNull();
            return line;
        }

        int exitCode() throws Exception {
            return result.get(20, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws IOException {
            toLauncher.close();
        }
    }

    static final Folders.StoreKind LOCAL = existing -> "ext4";

    @BeforeEach
    void setUp() throws IOException {
        program = tmp.resolve("program");
        BundleVerifierTest.fakeApp(program.resolve("app"));
        Files.write(program.resolve("bundle.json"), BundleMaker.manifest(program.resolve("app"), Version.parse("1.0.0")));
        base = tmp.resolve("base");
    }

    @Test
    void firstStartUnlockReadyQuit() throws Exception {
        try (Session s = new Session(LOCAL)) {
            s.send(HELLO);
            assertThat(s.next()).containsEntry("type", "needPassword").containsEntry("firstStart", true)
                    .containsEntry("error", null);
            s.send("{\"type\":\"unlock\",\"password\":\"elf-Zeichen\"}");
            assertThat(s.next()).containsEntry("type", "needPassword").containsEntry("error", "desktop.password.short");
            s.send("{\"type\":\"unlock\",\"password\":\"zwoelf-Zeichen\"}");
            assertThat(s.next()).containsEntry("type", "starting").containsEntry("version", "1.0.0");
            Map<String, Object> ready = s.next();
            assertThat(ready).containsOnlyKeys("type", "port", "pid", "nonce", "proof").containsEntry("type", "ready");
            int port = ((Number) ready.get("port")).intValue();
            assertThat(ready.get("proof")).isEqualTo(SessionKeys.proof(SessionKeys.secret(SECRET),
                    (String) ready.get("nonce"), port, 777));
            s.send("{\"type\":\"quit\"}");
            assertThat(s.next()).containsEntry("type", "exit").containsEntry("code", 0L);
            assertThat(s.exitCode()).isZero();
        }
        assertThat(app.passwords).containsExactly("zwoelf-Zeichen");
    }

    @Test
    void closingStdinIsQuit() throws Exception {
        Session s = new Session(LOCAL);
        s.send(HELLO);
        assertThat(s.next()).containsEntry("type", "needPassword");
        s.close();
        assertThat(s.next()).containsEntry("type", "exit").containsEntry("code", 0L);
        assertThat(s.exitCode()).isZero();
        assertThat(app.passwords).isEmpty();
    }

    @Test
    void restartAfterAnUpdateSendsANewHandshake() throws Exception {
        try (Session s = new Session(LOCAL)) {
            s.send(HELLO);
            s.next();
            s.send("{\"type\":\"unlock\",\"password\":\"zwoelf-Zeichen\"}");
            assertThat(s.next()).containsEntry("type", "starting");
            Map<String, Object> first = s.next();
            app.exitCodes.add(AppRunner.EXIT_RESTART);
            assertThat(s.next()).containsEntry("type", "starting");
            Map<String, Object> second = s.next();
            assertThat(second).containsEntry("type", "ready");
            assertThat(second.get("nonce")).isNotEqualTo(first.get("nonce"));
            assertThat(second.get("port")).isNotEqualTo(first.get("port"));
            s.send("{\"type\":\"quit\"}");
            assertThat(s.next()).containsEntry("type", "exit");
        }
        assertThat(app.passwords).hasSize(2);
    }

    @Test
    void wrongPasswordAsksAgain() throws Exception {
        Files.createDirectories(base.resolve("daten/db"));
        Files.writeString(base.resolve("daten/db/dispofold.mv.db"), "x");
        app.notReadyCode = AppRunner.EXIT_WRONG_PASSWORD;
        try (Session s = new Session(LOCAL)) {
            s.send(HELLO);
            assertThat(s.next()).containsEntry("type", "needPassword").containsEntry("firstStart", false);
            s.send("{\"type\":\"unlock\",\"password\":\"falsch\"}");
            assertThat(s.next()).containsEntry("type", "starting");
            assertThat(s.next()).containsEntry("type", "needPassword").containsEntry("error", "desktop.password.wrong");
            s.send("{\"type\":\"unlock\",\"password\":\"richtig\"}");
            assertThat(s.next()).containsEntry("type", "starting");
            assertThat(s.next()).containsEntry("type", "ready");
            s.send("{\"type\":\"quit\"}");
            assertThat(s.next()).containsEntry("type", "exit");
        }
        assertThat(app.passwords).containsExactly("falsch", "richtig");
        // One backup before the first start of this version, not one per attempt.
        try (var backups = Files.list(base.resolve("daten/sicherungen"))) {
            assertThat(backups).hasSize(1);
        }
    }

    @Test
    void networkDataFolderIsWarnedAbout() throws Exception {
        List<Path> asked = new ArrayList<>();
        try (Session s = new Session(existing -> {
            asked.add(existing);
            return "cifs";
        })) {
            s.send(HELLO);
            assertThat(s.next()).containsEntry("type", "warning").containsEntry("code", "desktop.dataFolder.network")
                    .containsEntry("params", Map.of());
            assertThat(s.next()).containsEntry("type", "needPassword");
            s.send("{\"type\":\"quit\"}");
            assertThat(s.next()).containsEntry("type", "exit");
        }
        assertThat(asked).isNotEmpty();
    }

    @Test
    void heldLockIsReported() throws Exception {
        Files.createDirectories(base.resolve("daten"));
        try (InstanceLock other = InstanceLock.tryAcquire(base.resolve("daten/dispofold.lock")).orElseThrow();
                Session s = new Session(LOCAL)) {
            s.send(HELLO);
            assertThat(s.next()).containsEntry("type", "locked").containsEntry("error", "desktop.lock.held");
            assertThat(s.next()).containsEntry("type", "exit").containsEntry("code", 1L);
            assertThat(s.exitCode()).isEqualTo(1);
        }
    }

    @Test
    void wrongHelloFailsWithExactlyOneExit() throws Exception {
        for (String hello : new String[] { "", "kein json", "{\"type\":\"unlock\",\"password\":\"x\"}",
                "{\"type\":\"hello\",\"secret\":\"AAEC\",\"protocol\":1}",
                "{\"type\":\"hello\",\"secret\":\"" + SECRET + "\",\"protocol\":2}" }) {
            try (Session s = new Session(LOCAL)) {
                s.send(hello);
                assertThat(s.next()).as(hello).containsEntry("type", "failed")
                        .containsEntry("code", "desktop.protocol.invalid");
                assertThat(s.next()).containsEntry("type", "exit").containsEntry("code", 2L);
                assertThat(s.exitCode()).isEqualTo(2);
                assertThat(s.lines).isEmpty();
            }
        }
    }

    @Test
    void appThatDoesNotStartFails() throws Exception {
        app.notReadyCode = 1;
        try (Session s = new Session(LOCAL)) {
            s.send(HELLO);
            s.next();
            s.send("{\"type\":\"unlock\",\"password\":\"zwoelf-Zeichen\"}");
            assertThat(s.next()).containsEntry("type", "starting");
            assertThat(s.next()).containsEntry("type", "failed").containsEntry("code", "desktop.app.startFailed")
                    .containsEntry("params", Map.of("version", "1.0.0"));
            assertThat(s.next()).containsEntry("type", "exit").containsEntry("code", 1L);
        }
    }

    @Test
    void translocatedProgramFails() throws Exception {
        Path translocated = Files.createDirectories(tmp.resolve("private/var/folders/x/T/AppTranslocation/1/d"));
        program = translocated;
        BundleVerifierTest.fakeApp(program.resolve("app"));
        Files.write(program.resolve("bundle.json"), BundleMaker.manifest(program.resolve("app"), Version.parse("1.0.0")));
        try (Session s = new Session(LOCAL)) {
            s.send(HELLO);
            assertThat(s.next()).containsEntry("type", "failed").containsEntry("code", "desktop.translocated");
            assertThat(s.next()).containsEntry("type", "exit").containsEntry("code", 2L);
        }
    }

    @Test
    void usageErrorsAndPlainStartsAreRefused() throws Exception {
        try (Session s = new Session(LOCAL, "--unbekannt")) {
            assertThat(s.next()).containsEntry("type", "failed").containsEntry("code", "desktop.launcher.usage");
            assertThat(s.next()).containsEntry("type", "exit").containsEntry("code", 2L);
        }
        SupervisorTest.RecordingUi console = new SupervisorTest.RecordingUi();
        assertThat(Launcher.run(new String[] { "--data", base.toString() }, console,
                new ByteArrayInputStream(new byte[0]), null, f -> app, LOCAL)).isEqualTo(2);
        assertThat(console.codes).containsExactly("desktop.launcher.windowOnly");
        // The program folder is required (no guessing from the jar's place).
        SupervisorTest.RecordingUi noDir = new SupervisorTest.RecordingUi();
        assertThat(Launcher.run(new String[] { "--change-password", "--data", base.toString() }, noDir,
                new ByteArrayInputStream(new byte[0]), null, f -> app, LOCAL)).isEqualTo(2);
        assertThat(noDir.codes).containsExactly("desktop.launcher.programDirMissing");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        assertThat(Launcher.run(new String[] { "--shell", "--data", base.toString() }, null,
                new ByteArrayInputStream((HELLO + "\n").getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out, true, StandardCharsets.UTF_8), f -> app, LOCAL)).isEqualTo(2);
        assertThat(out.toString(StandardCharsets.UTF_8).lines().map(Json::parseObject).toList()).containsExactly(
                Map.of("type", "failed", "code", "desktop.launcher.programDirMissing", "params", Map.of()),
                Map.of("type", "exit", "code", 2L));
    }

    @Test
    void networkPathRules() throws IOException {
        assertThat(Folders.unc("\\\\server\\share\\Dispofold")).isTrue();
        assertThat(Folders.unc("\\\\?\\UNC\\server\\share")).isTrue();
        assertThat(Folders.unc("\\\\?\\C:\\Daten")).isFalse();
        assertThat(Folders.unc("\\\\.\\C:\\Daten")).isFalse();
        assertThat(Folders.unc("C:\\Daten")).isFalse();
        assertThat(Folders.unc("/home/x")).isFalse();
        for (String type : new String[] { "cifs", "smb3", "smbfs", "nfs", "NFS4", "fuse.sshfs", "9p", "afpfs",
                "webdav", "remote" }) {
            assertThat(Folders.onNetwork(tmp, existing -> type)).as(type).isTrue();
        }
        for (String type : new String[] { "ext4", "btrfs", "apfs", "NTFS", "tmpfs", "fuse.overlay" }) {
            assertThat(Folders.onNetwork(tmp, existing -> type)).as(type).isFalse();
        }
        // A folder that does not exist yet: its nearest existing parent decides.
        List<Path> asked = new ArrayList<>();
        assertThat(Folders.onNetwork(tmp.resolve("neu/daten"), existing -> {
            asked.add(existing);
            return "nfs";
        })).isTrue();
        assertThat(asked).containsExactly(tmp.toAbsolutePath().normalize());
        assertThat(Folders.onNetwork(tmp, existing -> {
            throw new IOException("no store");
        })).isFalse();
        // The real lookup on this machine: a local temp folder is not a network share.
        assertThat(Folders.windowsRemote(Files.getFileStore(tmp))).isFalse();
    }

    @Test
    void readyLinesAreChecked() {
        byte[] key = SessionKeys.secret(SECRET);
        String nonce = "ZGVmZ2hpamtsbW5vcHFycw==";
        String proof = SessionKeys.proof(key, nonce, 43210, 4242);
        String good = "{\"type\":\"ready\",\"port\":43210,\"pid\":4242,\"nonce\":\"" + nonce + "\",\"proof\":\""
                + proof + "\"}";
        assertThat(ProcessRunner.checkReady(good, key)).containsEntry("port", 43210L).containsEntry("pid", 4242L)
                .containsEntry("proof", "AYue2OIBHSl9qTJ72vnA6EYg5Bfwo7h33kUQvks72S0=");
        for (String bad : new String[] { good.replace("43210", "43211"), good.replace("4242", "4243"),
                good.replace(proof, SessionKeys.proof(new byte[32], nonce, 43210, 4242)),
                good.replace("\"ready\"", "\"other\""), good.replace("43210", "0"), "2026-11-12 INFO started",
                "{\"type\":\"ready\"}", good.replace(nonce, "AAEC") }) {
            assertThat(ProcessRunner.checkReady(bad, key)).as(bad).isNull();
        }
    }
}
