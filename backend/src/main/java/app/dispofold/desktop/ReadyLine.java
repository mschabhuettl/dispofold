package app.dispofold.desktop;

import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.arc.profile.IfBuildProfile;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.value.registry.ValueRegistry;
import io.quarkus.vertx.http.HttpServer;

import app.dispofold.desktop.update.Json;
import app.dispofold.desktop.update.SessionKeys;

/**
 * Once the HTTP server listens (the launcher passes port 0), tells the launcher on stdout:
 * {@code {"type":"ready","port":p,"pid":n,"nonce":"...","proof":"..."}} with
 * {@code proof = HMAC(secret, "ready|" + nonce + "|" + port + "|" + pid)} and a new nonce for every start. The same
 * nonce makes the session header of this start ({@link SessionKeys#session}).
 */
@IfBuildProfile("desktop")
@ApplicationScoped
public class ReadyLine {

    @ConfigProperty(name = "dispofold.desktop.secret")
    String secret;

    @Inject
    ValueRegistry values;

    /** The last line, for tests (outside the launcher there is no protocol stream). */
    static volatile Map<String, Object> last;

    /** This start's nonce: in the ready line and in the session header the window sends (DesktopAuth). */
    private final String nonce = SessionKeys.nonce();

    String nonce() {
        return nonce;
    }

    /**
     * The HTTP server binds after the start observers ran; the actual port appears in the value registry then. A
     * short-lived thread waits for it and announces the app.
     */
    void announce(@Observes @Priority(Interceptor.Priority.PLATFORM_AFTER + 1000) StartupEvent event) {
        Thread t = new Thread(() -> {
            long deadline = System.nanoTime() + 120_000_000_000L;
            while (System.nanoTime() < deadline) {
                Integer port = port();
                if (port != null && port > 0) {
                    send(port);
                    return;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
            System.err.println("desktop.ready: the HTTP port did not appear");
        }, "ready-line");
        t.setDaemon(true);
        t.start();
    }

    private Integer port() {
        if (values.containsKey(HttpServer.HTTP_TEST_PORT)) {
            return values.get(HttpServer.HTTP_TEST_PORT);
        }
        return values.containsKey(HttpServer.HTTP_PORT) ? values.get(HttpServer.HTTP_PORT) : null;
    }

    private void send(int port) {
        long pid = ProcessHandle.current().pid();
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("type", "ready");
        line.put("port", (long) port);
        line.put("pid", pid);
        line.put("nonce", nonce);
        line.put("proof", SessionKeys.proof(SessionKeys.secret(secret), nonce, port, pid));
        last = line;
        PrintStream out = DesktopMain.protocol;
        if (out != null) {
            out.println(Json.line(line));
            out.flush();
        }
    }
}
