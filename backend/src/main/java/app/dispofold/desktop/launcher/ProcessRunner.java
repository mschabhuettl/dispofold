package app.dispofold.desktop.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import app.dispofold.desktop.update.Json;
import app.dispofold.desktop.update.SessionKeys;

/**
 * Runs {@code <java> -Ddispofold.desktop=true ... -jar <app>/quarkus-run.jar} with the Java runtime the launcher
 * runs on. Its stdin gets one JSON line with password and secret and then stays open: when it closes, the app ends.
 * Its stdout carries only the ready line; its stderr (the log) goes to {@code daten/logs/app-<version>.log}.
 */
final class ProcessRunner implements AppRunner {

    private final Folders folders;

    ProcessRunner(Folders folders) {
        this.folders = folders;
    }

    static Path javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        if (windows) {
            Path javaw = bin.resolve("javaw.exe");
            return Files.exists(javaw) ? javaw : bin.resolve("java.exe");
        }
        return bin.resolve("java");
    }

    private ProcessBuilder builder(Path quarkusApp, String version, String... properties) throws IOException {
        Path logs = folders.data().resolve("logs");
        Files.createDirectories(logs);
        List<String> cmd = new java.util.ArrayList<>(List.of(javaExecutable().toString(),
                "-Ddispofold.desktop=true",
                "-Ddispofold.data-dir=" + folders.data().toAbsolutePath(),
                "-Ddispofold.versions-dir=" + folders.versions().toAbsolutePath()));
        cmd.addAll(List.of(properties));
        cmd.addAll(List.of("-jar", quarkusApp.resolve("quarkus-run.jar").toAbsolutePath().toString()));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(quarkusApp.toFile());
        pb.environment().remove("DISPOFOLD_PASSWORD"); // the app gets passwords on stdin only
        pb.environment().remove("DISPOFOLD_NEW_PASSWORD");
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logs.resolve("app-" + version + ".log").toFile()));
        return pb;
    }

    @Override
    public Child start(Path quarkusApp, String version, String password, String secret) throws IOException {
        Process p = builder(quarkusApp, version, "-Dquarkus.http.port=0").start();
        OutputStream stdin = p.getOutputStream();
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("password", password);
        first.put("secret", secret);
        try {
            stdin.write((Json.line(first) + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            // The child exited before reading; awaitReady reports it.
        }
        return new ProcessChild(p, stdin, SessionKeys.secret(secret));
    }

    @Override
    public int changePassword(Path quarkusApp, String version, String oldPassword, String newPassword)
            throws IOException {
        Process p = builder(quarkusApp, version, "-Ddispofold.change-password=true")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("oldPassword", oldPassword);
        request.put("newPassword", newPassword);
        try (OutputStream in = p.getOutputStream()) {
            in.write((Json.line(request) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Exited before reading; the exit code tells why.
        }
        while (true) {
            try {
                return p.waitFor();
            } catch (InterruptedException e) {
                // Keep waiting: the change must not be cut off.
            }
        }
    }

    /** A ready line with a valid proof for the secret, else null. */
    static Map<String, Object> checkReady(String text, byte[] key) {
        Map<String, Object> line;
        try {
            line = Json.parseObject(text);
        } catch (IllegalArgumentException | ClassCastException e) {
            return null;
        }
        if (!"ready".equals(line.get("type")) || !(line.get("port") instanceof Number port)
                || !(line.get("pid") instanceof Number pid) || !(line.get("nonce") instanceof String nonce)
                || !(line.get("proof") instanceof String proof) || port.intValue() <= 0 || port.intValue() > 65535
                || !SessionKeys.isNonce(nonce)
                || !SessionKeys.same(SessionKeys.proof(key, nonce, port.intValue(), pid.longValue()), proof)) {
            return null;
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("port", (long) port.intValue());
        clean.put("pid", pid.longValue());
        clean.put("nonce", nonce);
        clean.put("proof", proof);
        return clean;
    }

    private static final class ProcessChild implements Child {
        private final Process process;
        private final OutputStream stdin;
        private final CountDownLatch readyLatch = new CountDownLatch(1);
        private volatile Map<String, Object> ready;

        ProcessChild(Process process, OutputStream stdin, byte[] key) {
            this.process = process;
            this.stdin = stdin;
            Thread reader = new Thread(() -> {
                try (BufferedReader out = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = out.readLine()) != null) {
                        Map<String, Object> r = ready == null ? checkReady(line, key) : null;
                        if (r != null) {
                            ready = r;
                            readyLatch.countDown();
                        } else {
                            System.err.println("app stdout: line ignored");
                        }
                    }
                } catch (IOException e) {
                    // The app ended.
                }
                readyLatch.countDown();
            }, "app-stdout");
            reader.setDaemon(true);
            reader.start();
        }

        @Override
        public boolean awaitReady(Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            try {
                while (ready == null && process.isAlive() && System.nanoTime() < deadline) {
                    readyLatch.await(300, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (ready == null) {
                return false;
            }
            URI health = URI.create("http://127.0.0.1:" + ready.get("port") + "/q/health/ready");
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    return false;
                }
                try {
                    HttpURLConnection c = (HttpURLConnection) health.toURL().openConnection();
                    c.setConnectTimeout(1000);
                    c.setReadTimeout(2000);
                    int status = c.getResponseCode();
                    c.disconnect();
                    if (status == 200) {
                        return true;
                    }
                } catch (IOException e) {
                    // Not answering yet.
                }
                try {
                    if (process.waitFor(300, TimeUnit.MILLISECONDS)) {
                        return false;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return false;
        }

        @Override
        public Map<String, Object> readyLine() {
            return ready;
        }

        @Override
        public int waitFor() {
            while (true) {
                try {
                    return process.waitFor();
                } catch (InterruptedException e) {
                    // Keep waiting: the launcher must outlive the app to handle restarts.
                }
            }
        }

        @Override
        public int stop() {
            if (process.isAlive()) {
                try {
                    stdin.close(); // the app ends by itself, gracefully
                } catch (IOException e) {
                    // Already closed.
                }
                try {
                    if (!process.waitFor(20, TimeUnit.SECONDS)) {
                        process.destroy();
                        if (!process.waitFor(10, TimeUnit.SECONDS)) {
                            process.destroyForcibly();
                        }
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                }
            }
            return waitFor();
        }
    }
}
