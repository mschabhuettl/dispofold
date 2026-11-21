package app.dispofold.plugins.beispiel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.model.Dataset;
import app.dispofold.plugin.api.ImportSource;
import app.dispofold.plugin.api.Plugin;
import app.dispofold.plugin.api.PluginErrors;
import app.dispofold.plugin.api.PluginHost;

/**
 * The example plugin, only in the test build. Its settings (version 2) hold a greeting {@code gruss}; version 1
 * called it {@code text}. Its import source reads a dataset file from an allowed folder and, if asked, lists the
 * current cumulative corrections as applied.
 */
@ApplicationScoped
public class BeispielPlugin implements Plugin, ImportSource {

    static final String ID = "beispiel";

    @Inject
    PluginHost host;

    @Inject
    ObjectMapper mapper;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int settingsVersion() {
        return 2;
    }

    @Override
    public JsonNode migrateSettings(int fromVersion, JsonNode settings) {
        ObjectNode s = settings.deepCopy();
        if (fromVersion == 1 && s.has("text")) {
            s.set("gruss", s.remove("text"));
        }
        if (!s.has("gruss")) {
            s.put("gruss", "Hallo");
        }
        return s;
    }

    @Override
    public Optional<ImportSource> importSource() {
        return Optional.of(this);
    }

    /** Request {@code {"root": "<root id>", "file": "<path in it>", "corrections": true|false}}. */
    @Override
    public Data load(JsonNode request) {
        Path file = host.files().resolve(request.path("root").asText(null), request.path("file").asText(""));
        Dataset dataset;
        try {
            dataset = mapper.readValue(Files.readAllBytes(file), Dataset.class);
        } catch (IOException e) {
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.beispiel.unreadable", Map.of(),
                    "Die Datei ist kein Datensatz");
        }
        JsonNode applied = request.path("corrections").asBoolean() ? host.corrections().orElse(null) : null;
        return new Data(dataset, applied);
    }
}
