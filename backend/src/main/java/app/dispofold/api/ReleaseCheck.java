package app.dispofold.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.desktop.update.Version;
import io.quarkus.arc.profile.UnlessBuildProfile;
import io.quarkus.runtime.StartupEvent;

/**
 * Server build only: asks the public releases API for the latest release on start and once a day (one anonymous
 * GET, nothing is sent). Off with {@code DISPOFOLD_UPDATE_CHECK=false}. The desktop program asks the user first and
 * checks through its own update source (UpdateService).
 */
@UnlessBuildProfile("desktop")
@ApplicationScoped
public class ReleaseCheck {

    /** A release newer than the running version; {@code url} is its https page or null. */
    public record Release(String version, String title, String notes, String url) {
    }

    private static final Logger LOG = Logger.getLogger(ReleaseCheck.class);

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "dispofold.update-check.enabled")
    boolean enabled;

    @ConfigProperty(name = "dispofold.update-check.url")
    String url;

    @ConfigProperty(name = "quarkus.application.version")
    String applicationVersion;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private ScheduledExecutorService timer;
    private volatile Release latest;

    void onStart(@Observes StartupEvent e) {
        if (!enabled) {
            LOG.info("Release check is switched off");
            return;
        }
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "release-check");
            t.setDaemon(true);
            return t;
        });
        timer.scheduleAtFixedRate(this::check, 0, 24, TimeUnit.HOURS);
    }

    @PreDestroy
    void stop() {
        if (timer != null) {
            timer.shutdownNow();
        }
    }

    /** The newer release found by the last check, or null. */
    public Release latest() {
        return latest;
    }

    public String current() {
        return applicationVersion;
    }

    /** One check; a failure keeps the last result and only writes a log line. */
    void check() {
        if (!Version.isValid(applicationVersion)) {
            return;
        }
        CompletableFuture<HttpResponse<byte[]>> answer = null;
        try {
            answer = http.sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            // the request timeout ends with the headers; this one covers the body too
            HttpResponse<byte[]> r = answer.get(15, TimeUnit.SECONDS);
            if (r.statusCode() != 200) {
                LOG.infof("Release check: HTTP %d", r.statusCode());
                return;
            }
            latest = newer(mapper.readTree(r.body()), Version.parse(applicationVersion));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.infof("Release check failed (%s)", e.getClass().getSimpleName());
        } finally {
            if (answer != null) {
                answer.cancel(true);
            }
        }
    }

    /** The release if it is a final x.y.z release newer than {@code current}, else null. */
    static Release newer(JsonNode release, Version current) {
        String tag = release.path("tag_name").asText("").replaceFirst("^v", "");
        if (release.path("draft").asBoolean() || release.path("prerelease").asBoolean() || !Version.isValid(tag)
                || !Version.parse(tag).isNewerThan(current)) {
            return null;
        }
        String page = release.path("html_url").asText("");
        String title = release.path("name").asText("");
        String notes = release.path("body").asText("");
        return new Release(tag, title.isBlank() ? null : title, notes.isBlank() ? null : notes,
                page.startsWith("https://") ? page : null);
    }
}
