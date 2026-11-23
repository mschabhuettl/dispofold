package app.dispofold.plugins.sp5;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.model.Dataset;
import app.dispofold.plugin.api.ImportSource;
import app.dispofold.plugin.api.Plugin;
import app.dispofold.plugin.api.PluginErrors;
import app.dispofold.plugin.api.PluginHost;
import app.dispofold.plugins.sp5.port.Corrections;
import app.dispofold.plugins.sp5.port.Py;
import app.dispofold.plugins.sp5.port.PyJson;
import app.dispofold.plugins.sp5.port.Sp5Export;

/**
 * The plugin: imports a Schichtplaner 5 folder (read only) with the port of sp5-export ({@link Sp5Export}). Its
 * settings hold the folder (an allowed root and a path in it), the mapping and the options of sp5_export.py with
 * their defaults; the corrections are the cumulative corrections file of Dispofold.
 */
@ApplicationScoped
public class Sp5Plugin implements Plugin, ImportSource {

    static final String ID = "sp5";

    /** The corrections file's name in the result ZIP and in the port's messages. */
    static final String CORRECTIONS_FILE = "korrekturen.json";

    @Inject
    PluginHost host;

    @Inject
    ObjectMapper mapper;

    @Inject
    Sp5Runs runs;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public JsonNode migrateSettings(int fromVersion, JsonNode settings) {
        return Sp5Settings.withDefaults(mapper, settings);
    }

    @Override
    public Optional<ImportSource> importSource() {
        return Optional.of(this);
    }

    /**
     * Request {@code {"month": "YYYY-MM", "to": "YYYY-MM" | null, "options": {…} (optional), "run": id (optional)}}.
     * With {@code run} (the Übersicht imports its run) only that run, unchanged (see {@link Sp5Runs#check}).
     */
    @Override
    public Data load(JsonNode request) {
        Inputs inputs = inputs(request);
        if (request != null && request.hasNonNull("run")) {
            runs.check(request.path("run").asText(), inputs);
        }
        return data(export(inputs.request()));
    }

    /** An export's dataset as the import loads it. */
    Data data(Sp5Export.Result result) {
        Map<String, Object> dataset = new LinkedHashMap<>(result.dataset());
        Object applied = dataset.remove("appliedCorrections");
        return new Data(mapper.convertValue(dataset, Dataset.class),
                applied == null ? null : mapper.valueToTree(applied));
    }

    /** The export as sp5_export.py makes it, with the settings, the request and the corrections in effect. */
    Sp5Export.Result export(JsonNode request) {
        return export(request(request));
    }

    Sp5Export.Result export(Sp5Export.Request r) {
        try {
            Sp5Export.checkMonth(r.month());
            Sp5Export.checkMonth(r.to());
            return Sp5Export.run(r);
        } catch (Sp5Export.Sp5Error e) {
            // the program's own message: German, SP5 ids and counts only, never names
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.failed", Map.of(), e.getMessage());
        } catch (Py.PyException e) {
            // where the program stops with an unexpected error, with its message
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.failed", Map.of(),
                    "unerwarteter Fehler " + e.type() + " in " + e.file() + ":" + e.line());
        }
    }

    /**
     * An export's request and the files it was made from, as sp5_export.py reads them (corrections: null if none),
     * with the options in effect (those of the settings, the request's over them).
     */
    record Inputs(Sp5Export.Request request, byte[] mappingFile, byte[] correctionsFile, ObjectNode options) {
    }

    /** What an export uses: the settings, the folder, the months and the corrections file (or none). */
    Sp5Export.Request request(JsonNode request) {
        return inputs(request).request();
    }

    /** The request with the mapping and corrections files it was made from, read once. */
    Inputs inputs(JsonNode request) {
        JsonNode s = settings();
        ObjectNode options = options(s, request);
        String month = request == null ? "" : request.path("month").asText("");
        String to = request == null || request.path("to").isNull() ? null : request.path("to").asText(null);
        if (!s.path("mapping").isObject()) {
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.noMapping", Map.of(),
                    "Die Zuordnung fehlt (Einstellungen der Erweiterung)");
        }
        byte[] mappingFile = bytes(s.path("mapping"));
        Map<String, Object> mapping = mapping(mappingFile);
        Optional<ObjectNode> file = host.corrections();
        byte[] correctionsFile = file.isPresent() ? bytes(file.get()) : null;
        Map<String, Object> corrections = correctionsFile == null ? null : corrections(correctionsFile);
        return new Inputs(new Sp5Export.Request(folder(s), month, to == null || to.isEmpty() ? null : to,
                options.path("historyMonths").asInt(), optionalInt(options.path("areasFromHistory")),
                optionalInt(options.path("shiftsFromHistory")), options.path("demand").asText(),
                options.path("shiftMix").asBoolean() ? options.path("mixMinDuties").asInt() : null,
                options.path("hiddenShiftsMonths").asInt(), options.path("recentMonths").asInt(), mapping,
                corrections, options.path("nightsFair").asBoolean()), mappingFile, correctionsFile, options);
    }

    /**
     * The settings' options with the request's {@code "options"} over them, checked as saved settings are; without
     * them (missing or null) the settings' own, unchecked as before.
     */
    private ObjectNode options(JsonNode settings, JsonNode request) {
        JsonNode given = request == null ? null : request.get("options");
        if (given == null || given.isNull()) {
            return (ObjectNode) settings.get("options");
        }
        if (!given.isObject()) {
            throw Sp5Settings.invalid("options");
        }
        ObjectNode merged = settings.deepCopy();
        ((ObjectNode) merged.get("options")).setAll((ObjectNode) given);
        return (ObjectNode) Sp5Settings.checked(mapper, merged).get("options");
    }

    /** The stored settings, every missing key at its default. */
    JsonNode settings() {
        return Sp5Settings.withDefaults(mapper, host.settings(ID));
    }

    private byte[] bytes(JsonNode node) {
        try {
            return mapper.writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The mapping as sp5_export.py reads it from its file, checked as it checks it. */
    Map<String, Object> mapping(JsonNode mapping) {
        return mapping(bytes(mapping));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapping(byte[] file) {
        Object mapping;
        try {
            mapping = PyJson.load(file);
        } catch (PyJson.DecodeError e) {
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.mapping", Map.of(), e.getMessage());
        }
        // the check of Sp5Export.loadMapping, its problems in plain German as on the page Zuordnung
        List<String> errors = Sp5Export.mappingErrors(mapping);
        if (!errors.isEmpty()) {
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.mapping", Map.of(),
                    String.join(" ", Sp5MappingText.plain(errors)));
        }
        return (Map<String, Object>) mapping;
    }

    /** Dispofold's corrections file as sp5_export.py reads it with --corrections. */
    private static Map<String, Object> corrections(byte[] file) {
        try {
            return Corrections.load(file, CORRECTIONS_FILE);
        } catch (Corrections.CorrectionsError e) {
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.failed", Map.of(), e.getMessage());
        }
    }

    private static Integer optionalInt(JsonNode n) {
        return n.isNumber() ? n.asInt() : null;
    }

    /**
     * The SP5 folder from the settings, checked: an allowed root and every file in it inside the root (a symbolic
     * link to elsewhere is refused before anything is read).
     */
    Path folder(JsonNode settings) {
        String root = settings.path("root").asText(null);
        String path = settings.path("path").asText("");
        if (root == null || root.isEmpty()) {
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.noFolder", Map.of(),
                    "Der Ordner fehlt (Einstellungen der Erweiterung)");
        }
        Path folder = host.files().resolve(root, path);
        if (!Files.isDirectory(folder)) {
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.noFolder", Map.of(),
                    "Der Ordner fehlt (Einstellungen der Erweiterung)");
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path f : files.toList()) {
                host.files().resolve(root, path.isEmpty() ? f.getFileName().toString()
                        : path + "/" + f.getFileName());
            }
        } catch (IOException e) {
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.noFolder", Map.of(),
                    "Der Ordner ist nicht lesbar");
        }
        return folder;
    }
}
