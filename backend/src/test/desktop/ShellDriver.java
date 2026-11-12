import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Stand-in for the program window in e2e.sh until the shell's self-test driver exists: starts the launcher with
 * --shell, speaks the line protocol, checks every ready proof and the challenge answer, and keeps a state file the
 * script reads (port and session header for curl).
 *
 * Usage: java ShellDriver.java <state.json> <password> <command...>
 * The password answers needPassword; after a "desktop.password.wrong" answer the driver quits. Creating the file
 * <state.json>.quit makes the driver send {"type":"quit"}. Exit code: the launcher's.
 */
public class ShellDriver {

    static Path state;
    static final List<String> received = new ArrayList<>();
    static int readyCount;
    static int port;
    static String version = "";
    static String session = "";
    static String lastError = "";
    static boolean identityOk = true;

    public static void main(String[] args) throws Exception {
        state = Path.of(args[0]);
        String password = args[1];
        List<String> cmd = List.of(args).subList(2, args.length);
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        OutputStream in = p.getOutputStream();
        send(in, "{\"type\":\"hello\",\"secret\":\"" + Base64.getEncoder().encodeToString(key) + "\",\"protocol\":1}");
        Path quitFile = Path.of(args[0] + ".quit");
        Thread quitter = new Thread(() -> {
            while (!Files.exists(quitFile)) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    return;
                }
            }
            try {
                send(in, "{\"type\":\"quit\"}");
            } catch (IOException e) {
                // The launcher ended already.
            }
        });
        quitter.setDaemon(true);
        quitter.start();
        int exit = -1;
        try (BufferedReader out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = out.readLine()) != null) {
                received.add(line);
                String type = field(line, "type");
                switch (type) {
                    case "needPassword" -> {
                        lastError = field(line, "error");
                        send(in, lastError.equals("desktop.password.wrong") ? "{\"type\":\"quit\"}"
                                : "{\"type\":\"unlock\",\"password\":\"" + password + "\"}");
                    }
                    case "starting" -> version = field(line, "version");
                    case "ready" -> {
                        int newPort = Integer.parseInt(field(line, "port"));
                        String nonce = field(line, "nonce");
                        String expected = hmac(key, "ready|" + nonce + "|" + newPort + "|" + field(line, "pid"));
                        boolean ok = same(expected, field(line, "proof")) && challenge(key, newPort);
                        identityOk &= ok;
                        if (ok) {
                            port = newPort;
                            session = hmac(key, "session|" + nonce);
                            readyCount++;
                        }
                    }
                    case "exit" -> exit = Integer.parseInt(field(line, "code"));
                    default -> {
                        // warning, failed, locked: kept in "lines"
                    }
                }
                write(-1);
            }
        }
        int code = p.waitFor();
        write(code);
        System.exit(exit >= 0 ? exit : code);
    }

    static void send(OutputStream in, String line) throws IOException {
        synchronized (in) {
            in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
        }
    }

    /** POST /api/desktop/challenge and compare the answer. */
    static boolean challenge(byte[] key, int port) {
        byte[] c = new byte[16];
        new SecureRandom().nextBytes(c);
        String challenge = Base64.getEncoder().encodeToString(c);
        try {
            HttpResponse<String> r = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/desktop/challenge"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"challenge\":\"" + challenge + "\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200
                    && same(hmac(key, "challenge|" + challenge + "|" + port), field(r.body(), "response"));
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** A string or number field of a flat JSON line (enough for the protocol's lines). */
    static String field(String json, String name) {
        Matcher m = Pattern.compile("\"" + name + "\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|(-?\\d+)|null)").matcher(json);
        if (!m.find()) {
            return "";
        }
        return m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : "";
    }

    static String hmac(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean same(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII));
    }

    static synchronized void write(int exitCode) throws IOException {
        StringBuilder lines = new StringBuilder();
        for (String l : received) {
            lines.append(lines.length() == 0 ? "" : ",").append(l);
        }
        String json = "{\"readyCount\":" + readyCount + ",\"port\":" + port + ",\"version\":\"" + version
                + "\",\"session\":\"" + session + "\",\"identityOk\":" + identityOk + ",\"lastError\":\"" + lastError
                + "\",\"exitCode\":" + exitCode + ",\"lines\":[" + lines + "]}";
        Path tmp = state.resolveSibling(state.getFileName() + ".tmp");
        Files.writeString(tmp, json);
        Files.move(tmp, state, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
