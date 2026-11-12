package app.dispofold.desktop.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import app.dispofold.desktop.update.Json;
import app.dispofold.desktop.update.SessionKeys;

/**
 * The line protocol with the program window (docs/desktop.md, "Fenster"): one JSON object per line. The window
 * sends {@code hello} (once, first), {@code unlock} and {@code quit}; closing stdin is the same as {@code quit}.
 * The launcher sends {@code needPassword}, {@code locked}, {@code starting}, {@code ready}, {@code warning},
 * {@code failed} and, as the very last line, {@code exit}. Nothing else is written to this stdout.
 */
final class ShellUi implements Ui {

    static final int MIN_PASSWORD_LENGTH = SystemUi.MIN_LENGTH;
    private static final String QUIT = "\0quit";

    private final PrintStream out;
    private final BufferedReader in;
    private final String secret;
    private final BlockingQueue<String> passwords = new LinkedBlockingQueue<>();
    private volatile boolean quit;
    private volatile Runnable onQuit = () -> { };
    private boolean exited;

    /** Reads and checks the hello line; IllegalArgumentException if it is missing or wrong. */
    ShellUi(InputStream stdin, PrintStream stdout) throws IOException {
        this.out = stdout;
        this.in = new BufferedReader(new InputStreamReader(stdin, StandardCharsets.UTF_8));
        Map<String, Object> hello = parse(in.readLine());
        if (hello == null || !"hello".equals(hello.get("type")) || !(hello.get("protocol") instanceof Number p)
                || p.intValue() != 1 || !(hello.get("secret") instanceof String s)) {
            throw new IllegalArgumentException("hello line missing or wrong");
        }
        SessionKeys.secret(s);
        this.secret = s;
    }

    /** The secret from the hello line (Base64). */
    String secret() {
        return secret;
    }

    /** Starts reading the window's lines; {@code onQuit} runs once on quit or end of stdin. */
    void listen(Runnable onQuit) {
        this.onQuit = onQuit;
        Thread t = new Thread(this::read, "shell-stdin");
        t.setDaemon(true);
        t.start();
    }

    boolean quitRequested() {
        return quit;
    }

    private void read() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                Map<String, Object> msg = parse(line);
                if (msg == null) {
                    System.err.println("shell: unreadable line ignored");
                    continue;
                }
                switch (String.valueOf(msg.get("type"))) {
                    case "unlock" -> {
                        if (msg.get("password") instanceof String pw) {
                            passwords.add(pw);
                        }
                    }
                    case "quit" -> {
                        quit();
                        return;
                    }
                    default -> System.err.println("shell: unknown line type ignored");
                }
            }
        } catch (IOException e) {
            // Same as the end of stdin.
        }
        quit();
    }

    private void quit() {
        if (!quit) {
            quit = true;
            passwords.add(QUIT);
            onQuit.run();
        }
    }

    private static Map<String, Object> parse(String line) {
        if (line == null) {
            return null;
        }
        try {
            return Json.parseObject(line);
        } catch (IllegalArgumentException | ClassCastException e) {
            return null;
        }
    }

    synchronized void send(Map<String, Object> line) {
        if (!exited) {
            out.println(Json.line(line));
            out.flush();
        }
    }

    private static Map<String, Object> line(String type, Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return m;
    }

    @Override
    public void warn(String code, Map<String, Object> params, String text) {
        System.err.println(text);
        send(line("warning", "code", code, "params", params));
    }

    @Override
    public void fail(String code, Map<String, Object> params, String text) {
        System.err.println(text);
        send(line("failed", "code", code, "params", params));
    }

    @Override
    public void info(String code, String text) {
        System.err.println(text);
    }

    @Override
    public String password(boolean firstStart, boolean retry) {
        String error = retry ? "desktop.password.wrong" : null;
        while (!quit) {
            send(line("needPassword", "firstStart", firstStart, "error", error));
            String pw;
            try {
                pw = passwords.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            if (QUIT.equals(pw)) {
                return null;
            }
            if (firstStart && pw.length() < MIN_PASSWORD_LENGTH) {
                error = "desktop.password.short";
                continue;
            }
            if (pw.isEmpty() || pw.contains("\n") || pw.contains("\r")) {
                error = "desktop.password.wrong";
                continue;
            }
            return pw;
        }
        return null;
    }

    void locked() {
        send(line("locked", "error", "desktop.lock.held"));
    }

    @Override
    public void starting(String version) {
        send(line("starting", "version", version));
    }

    @Override
    public void ready(Map<String, Object> ready) {
        send(line("ready", "port", ready.get("port"), "pid", ready.get("pid"), "nonce", ready.get("nonce"),
                "proof", ready.get("proof")));
    }

    /** The last line; later sends are dropped. */
    synchronized void exit(int code) {
        if (!exited) {
            send(line("exit", "code", (long) code));
            exited = true;
        }
    }
}
