package app.dispofold.desktop;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.desktop.update.AtomicFiles;
import app.dispofold.desktop.update.BundleException;
import app.dispofold.desktop.update.BundleVerifier;
import app.dispofold.desktop.update.Version;
import io.quarkus.arc.profile.IfBuildProfile;

/**
 * Update settings ({@code daten/einstellungen.json}), the check against the update source (a releases API URL or
 * a folder) and staging of verified bundles in {@code versionen/eingang/} for the launcher.
 */
@IfBuildProfile("desktop")
@ApplicationScoped
public class UpdateService {

    static final long MAX_BUNDLE_BYTES = 1L << 30;
    private static final Pattern BUNDLE_NAME = Pattern.compile("dispofold-(\\d+\\.\\d+\\.\\d+)-update\\.zip");
    private static final Logger LOG = Logger.getLogger(UpdateService.class);

    public record Settings(String source, boolean checkOnStart) {
    }

    record SettingsFile(Settings update) {
    }

    public record Available(String version, String notes) {
    }

    /** A newer bundle at the source; {@code location} is a URL or a file path. */
    record Found(Version version, String notes, String location) {
    }

    /**
     * {@code code}: stable key, answered as {@code desktop.<code>} (see docs/api.md); {@code params}: numbers and
     * versions only; the message is the German fallback.
     */
    public static class UpdateException extends Exception {
        private final String code;
        private final Map<String, Object> params;

        public UpdateException(String code, Map<String, Object> params, String message) {
            super(message);
            this.code = code;
            this.params = params;
        }

        public UpdateException(String code, String message) {
            this(code, Map.of(), message);
        }

        public String code() {
            return code;
        }

        public Map<String, Object> params() {
            return params;
        }
    }

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "dispofold.data-dir")
    Path dataDir;

    @ConfigProperty(name = "dispofold.versions-dir")
    Optional<Path> versionsDir;

    @ConfigProperty(name = "quarkus.application.version")
    String applicationVersion;

    BundleVerifier verifier = new BundleVerifier();

    /** Closes a download that is still running after its deadline (HttpClient timeouts end with the headers). */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "update-download-deadline");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public Version current() {
        return Version.parse(applicationVersion);
    }

    Path settingsFile() {
        return dataDir.resolve("einstellungen.json");
    }

    Path incoming() {
        return versions().resolve("eingang");
    }

    private Path versions() {
        return versionsDir.orElse(dataDir.resolveSibling("versionen"));
    }

    /** Versions that failed their trial start ({@code bad} in the launcher's state.json); never offered again. */
    Set<Version> bad() {
        Path file = versions().resolve("state.json");
        Set<Version> bad = new HashSet<>();
        try {
            if (Files.exists(file)) {
                for (JsonNode v : mapper.readTree(file.toFile()).path("bad")) {
                    if (Version.isValid(v.asText())) {
                        bad.add(Version.parse(v.asText()));
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("versionen/state.json is unreadable");
        }
        return bad;
    }

    public synchronized Settings settings() {
        Path file = settingsFile();
        try {
            if (Files.exists(file)) {
                SettingsFile s = mapper.readValue(file.toFile(), SettingsFile.class);
                if (s.update() != null && s.update().source() != null) {
                    return s.update();
                }
            }
        } catch (IOException e) {
            LOG.warn("daten/einstellungen.json is unreadable, using defaults");
        }
        // No built-in source: without one the program never contacts the network.
        return new Settings("", true);
    }

    public synchronized void save(Settings settings) throws IOException {
        AtomicFiles.write(settingsFile(), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                new SettingsFile(settings)), false);
    }

    /** An update source is empty (no updates), an http(s) URL or a folder. */
    public static Optional<UpdateException> invalidSource(String source) {
        if (source == null) {
            return Optional.of(new UpdateException("update.sourceMissing", "Bitte eine Update-Quelle angeben."));
        }
        if (source.isBlank()) {
            return Optional.empty();
        }
        if (isUrl(source)) {
            try {
                URI.create(source.trim()).toURL();
                return Optional.empty();
            } catch (IllegalArgumentException | IOException e) {
                return Optional.of(new UpdateException("update.sourceInvalid",
                        "Die Adresse der Update-Quelle ist ungültig."));
            }
        }
        if (source.contains("://")) {
            return Optional.of(new UpdateException("update.sourceUnsupported",
                    "Als Update-Quelle sind nur http(s)-Adressen oder Ordner möglich."));
        }
        return Optional.empty();
    }

    private static boolean isUrl(String source) {
        String s = source.trim().toLowerCase();
        return s.startsWith("http://") || s.startsWith("https://");
    }

    /** The newest bundle at the source that is newer than the running version, if any. */
    Optional<Found> find(String source) throws UpdateException {
        if (source == null || source.isBlank()) {
            throw new UpdateException("update.noSource", "Es ist keine Update-Quelle eingestellt.");
        }
        return isUrl(source) ? findAtUrl(source.trim()) : findInFolder(Path.of(source.trim()));
    }

    private Optional<Found> findInFolder(Path folder) throws UpdateException {
        if (!Files.isDirectory(folder)) {
            throw new UpdateException("update.folderUnreachable", "Der Ordner der Update-Quelle ist nicht erreichbar.");
        }
        Found best = null;
        Set<Version> bad = bad();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder, "dispofold-*-update.zip")) {
            for (Path p : ds) {
                Matcher m = BUNDLE_NAME.matcher(p.getFileName().toString());
                if (m.matches() && Version.isValid(m.group(1))) {
                    Version v = Version.parse(m.group(1));
                    if (v.isNewerThan(current()) && !bad.contains(v) && (best == null || v.isNewerThan(best.version()))) {
                        best = new Found(v, null, p.toString());
                    }
                }
            }
        } catch (IOException e) {
            throw new UpdateException("update.folderUnreadable", "Der Ordner der Update-Quelle ist nicht lesbar.");
        }
        return Optional.ofNullable(best);
    }

    /** Releases API: a release object ({@code /releases/latest}) or a list of them (newest first). */
    private Optional<Found> findAtUrl(String url) throws UpdateException {
        JsonNode doc;
        try {
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(20)).header("Accept", "application/json").GET().build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body(); Deadline d = deadline(in, Duration.ofSeconds(30))) {
                if (r.statusCode() == 404) {
                    return Optional.empty();
                }
                if (r.statusCode() != 200) {
                    throw new UpdateException("update.sourceStatus", Map.of("status", r.statusCode()),
                            "Die Update-Quelle antwortet nicht wie erwartet (HTTP "
                            + r.statusCode() + ").");
                }
                doc = mapper.readTree(in.readNBytes(4 << 20));
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new UpdateException("update.sourceUnreachable", "Die Update-Quelle ist nicht erreichbar.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("update.checkInterrupted", "Die Update-Prüfung wurde abgebrochen.");
        }
        JsonNode release = doc;
        if (doc.isArray()) {
            release = null;
            for (JsonNode n : doc) {
                if (!n.path("draft").asBoolean() && !n.path("prerelease").asBoolean()) {
                    release = n;
                    break;
                }
            }
            if (release == null) {
                return Optional.empty();
            }
        }
        String tag = release.path("tag_name").asText("").replaceFirst("^v", "");
        if (!Version.isValid(tag)) {
            throw new UpdateException("update.sourceVersionInvalid", "Die Update-Quelle nennt keine gültige Version.");
        }
        Version v = Version.parse(tag);
        if (!v.isNewerThan(current()) || bad().contains(v)) {
            return Optional.empty();
        }
        String wanted = "dispofold-" + v + "-update.zip";
        for (JsonNode asset : release.path("assets")) {
            if (wanted.equals(asset.path("name").asText())) {
                String download = asset.path("browser_download_url").asText("");
                if (!isUrl(download)) {
                    break;
                }
                String notes = release.path("body").asText("");
                return Optional.of(new Found(v, notes.isBlank() ? null : notes, download));
            }
        }
        throw new UpdateException("update.assetMissing", Map.of("version", v.toString()),
                "Version " + v + " enthält keine Update-Datei (" + wanted + ").");
    }

    /** Downloads or copies the newest bundle, verifies it and stages it; returns its version. */
    public synchronized Version install() throws UpdateException {
        Found found = find(settings().source()).orElseThrow(() -> new UpdateException("update.noneAvailable",
                "Kein neueres Update verfügbar."));
        Path part = newPart();
        try {
            if (isUrl(found.location())) {
                HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(found.location()))
                        .timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = r.body(); Deadline d = deadline(in, Duration.ofMinutes(15))) {
                    if (r.statusCode() != 200) {
                        throw new UpdateException("update.downloadStatus", Map.of("status", r.statusCode()),
                                "Das Update konnte nicht geladen werden (HTTP " + r.statusCode()
                                + ").");
                    }
                    copyLimited(in, part);
                }
            } else {
                try (InputStream in = Files.newInputStream(Path.of(found.location()))) {
                    copyLimited(in, part);
                }
            }
            return stage(part);
        } catch (IOException e) {
            throw new UpdateException("update.downloadFailed", "Das Update konnte nicht geladen werden.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpdateException("update.downloadInterrupted", "Das Laden des Updates wurde abgebrochen.");
        } finally {
            deleteQuietly(part);
        }
    }

    /** Verifies an uploaded bundle and stages it; returns its version. */
    public synchronized Version installFile(InputStream body) throws UpdateException {
        Path part = newPart();
        try {
            copyLimited(body, part);
            return stage(part);
        } catch (IOException e) {
            throw new UpdateException("update.storeFailed", "Die Update-Datei konnte nicht gespeichert werden.");
        } finally {
            deleteQuietly(part);
        }
    }

    private Path newPart() throws UpdateException {
        try {
            Files.createDirectories(incoming());
        } catch (IOException e) {
            throw new UpdateException("update.incomingFolder", "Der Ordner für Updates kann nicht angelegt werden.");
        }
        return incoming().resolve(".laden-" + UUID.randomUUID() + ".part");
    }

    private Version stage(Path part) throws UpdateException, IOException {
        Version v;
        try {
            v = verifier.verifyZip(part, incoming(), current());
        } catch (BundleException e) {
            throw new UpdateException("bundle." + e.code(), e.params(), e.getMessage());
        }
        if (bad().contains(v)) {
            throw new UpdateException("bundle.knownBad", Map.of("version", v.toString()),
                    "Version " + v + " ließ sich bereits früher nicht starten.");
        }
        // Only one staged bundle at a time.
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(incoming(), "*.zip")) {
            for (Path old : ds) {
                Files.deleteIfExists(old);
            }
        }
        Path target = incoming().resolve("dispofold-" + v + "-update.zip");
        Files.move(part, target, StandardCopyOption.ATOMIC_MOVE);
        LOG.infof("Update %s staged", v);
        return v;
    }

    private interface Deadline extends AutoCloseable {
        @Override
        void close();
    }

    private static Deadline deadline(InputStream in, Duration after) {
        ScheduledFuture<?> f = WATCHDOG.schedule(() -> {
            try {
                in.close();
            } catch (IOException ignored) {
                // The reader sees the closed stream and fails.
            }
        }, after.toMillis(), TimeUnit.MILLISECONDS);
        return () -> f.cancel(false);
    }

    private static void copyLimited(InputStream in, Path target) throws IOException, UpdateException {
        long total = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_BUNDLE_BYTES) {
                    throw new UpdateException("bundle.tooLarge", "Die Update-Datei ist zu groß.");
                }
                out.write(buf, 0, n);
            }
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // A leftover .part file is ignored by the launcher.
        }
    }
}
