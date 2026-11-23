package app.dispofold.plugins.sp5;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.model.Dataset;
import app.dispofold.plugin.api.PluginErrors;
import app.dispofold.plugin.api.PluginHost;
import app.dispofold.plugins.sp5.port.PyJson;
import app.dispofold.plugins.sp5.port.Sp5Export;

/**
 * The plugin's routes: its settings, a run of the export without an import (its summary, report and the comparison
 * with the last import or a file), the check of a mapping against the folder, and the result of an export as files
 * (for the comparison with Python).
 */
@Path("/api/plugins/sp5")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("planner")
public class Sp5Resource {

    @Inject
    PluginHost host;

    @Inject
    Sp5Plugin plugin;

    @Inject
    Sp5Runs runs;

    @Inject
    ObjectMapper mapper;

    @GET
    @Path("einstellungen")
    public JsonNode settings() {
        return plugin.settings();
    }

    @PUT
    @Path("einstellungen")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode save(JsonNode body) {
        synchronized (SETTINGS_LOCK) {
            return saveSettings(body);
        }
    }

    /** Settings are read, changed and written whole: one change at a time, so that none is lost. */
    private static final Object SETTINGS_LOCK = new Object();

    private JsonNode saveSettings(JsonNode body) {
        ObjectNode s = Sp5Settings.checked(mapper, body);
        // the discarded proposals change only through their routes: a page with an older copy keeps them
        ((ObjectNode) s.get("proposals")).set("discarded", plugin.settings().get("proposals").get("discarded"));
        if (!s.get("root").asText().isEmpty()) {
            plugin.folder(s); // an allowed folder, or 404 files.notFound
        }
        if (s.get("mapping").isObject()) {
            plugin.mapping(s.get("mapping"));
        }
        host.saveSettings(Sp5Plugin.ID, s);
        return plugin.settings();
    }

    /**
     * The export of the months in the body ({@code {"month", "to"}}) as a ZIP file: dataset.json, report.csv and
     * warnings.txt as sp5_export.py writes them, and what it was made with (anfrage.json with the options of
     * sp5_export.py and nightsFair, mapping.json, korrekturen.json if Dispofold has corrections), and
     * vorschlag-vergleich.json: the proposals for an empty mapping against this mapping, counts only.
     * plugins/sp5/tools/sp5-vergleich.py runs the Python program with the same inputs and compares.
     */
    @POST
    @Path("ergebnis")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces("application/zip")
    public Response result(JsonNode body) throws IOException {
        Sp5Plugin.Inputs inputs = plugin.inputs(body);
        Sp5Export.Request request = inputs.request();
        Sp5Export.Result result = plugin.export(request);
        ObjectNode question = mapper.createObjectNode();
        question.put("format", "dispofold/sp5-ergebnis");
        question.put("version", 1);
        question.set("args", mapper.valueToTree(args(request)));
        // sp5_export.py has no such option: it always marks night shifts whose rule has no "unpopular"
        question.put("nightsFair", request.nightsFair());
        question.put("mapping", "mapping.json");
        if (request.corrections() != null) {
            question.put("corrections", Sp5Plugin.CORRECTIONS_FILE);
        } else {
            question.putNull("corrections");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "dataset.json", Sp5Export.datasetBytes(result));
            entry(zip, "report.csv", Sp5Export.reportBytes(result));
            StringBuilder warnings = new StringBuilder();
            result.warnings().forEach(w -> warnings.append(w).append('\n'));
            entry(zip, "warnings.txt", warnings.toString().getBytes(StandardCharsets.UTF_8));
            entry(zip, "anfrage.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(question));
            entry(zip, "mapping.json", inputs.mappingFile());
            if (inputs.correctionsFile() != null) {
                entry(zip, Sp5Plugin.CORRECTIONS_FILE, inputs.correctionsFile());
            }
            // how far the proposals for an empty mapping come to this mapping: counts only (sp5-vergleich.py
            // --vorschlag); left out if a folder gives the proposals trouble, the result files come first
            byte[] counts = null;
            try {
                counts = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Sp5ProposalCheck.counts(
                        request.folder(), request.mapping(), Sp5Settings.proposals(plugin.settings())));
            } catch (RuntimeException e) {
                // no counts
            }
            if (counts != null) {
                entry(zip, "vorschlag-vergleich.json", counts);
            }
        }
        String month = body == null ? "" : body.path("month").asText("").replaceAll("[^0-9-]", "");
        return Response.ok(bytes.toByteArray(), "application/zip")
                .header("Content-Disposition", "attachment; filename=\"dispofold-sp5-ergebnis-" + month + ".zip\"")
                .build();
    }

    /** Runs the export of {@code {"month", "to", "options"}} as the import would load it, without importing. */
    @POST
    @Path("lauf")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> run(JsonNode body) {
        return runs.run(body).summary();
    }

    @GET
    @Path("lauf")
    public Map<String, Object> lastRun() {
        return runs.last().summary();
    }

    @GET
    @Path("lauf/bericht")
    public Map<String, Object> report() {
        return runs.last().report();
    }

    @GET
    @Path("lauf/bericht.csv")
    @Produces("text/csv; charset=utf-8")
    public Response reportFile() {
        Sp5Runs.Run run = runs.last();
        return Response.ok(Sp5Export.reportBytes(run.result), "text/csv; charset=utf-8")
                .header("Content-Disposition", "attachment; filename=\"dispofold-sp5-bericht-"
                        + run.inputs.request().month().replaceAll("[^0-9-]", "") + ".csv\"")
                .build();
    }

    /** The problems of a mapping the import would refuse, in plain German; empty if it is valid. */
    @POST
    @Path("zuordnung/pruefen")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> check(JsonNode mapping) {
        return Map.of("errors", Sp5MappingText.plain(Sp5Export.mappingErrors(python(mapping))));
    }

    /** Which rule of the mapping each shift, group and absence type of the folder in the settings meets. */
    @POST
    @Path("zuordnung/codes")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> codes(JsonNode mapping) {
        Object m = python(mapping);
        List<String> errors = Sp5MappingText.plain(Sp5Export.mappingErrors(m));
        return Sp5Codes.codes(plugin.folder(plugin.settings()), m, errors);
    }

    /**
     * Proposals of mapping entries from the folder in the settings for what the mapping in the body does not meet
     * yet, without the discarded ones ({@link Sp5Proposals}).
     */
    @POST
    @Path("zuordnung/vorschlaege")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> proposals(JsonNode mapping) {
        JsonNode s = plugin.settings();
        return Sp5Proposals.propose(plugin.folder(s), python(mapping), Sp5Settings.proposals(s));
    }

    /** Remembers a proposal ({@code {"key"}}) as discarded: it is not proposed again until the reset. */
    @POST
    @Path("zuordnung/vorschlaege/verwerfen")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode discard(JsonNode body) {
        String key = body == null ? "" : body.path("key").asText("");
        if (key.isBlank()) {
            throw Sp5Settings.invalid("key");
        }
        synchronized (SETTINGS_LOCK) {
            return discard(key);
        }
    }

    private JsonNode discard(String key) {
        ObjectNode s = (ObjectNode) plugin.settings().deepCopy();
        ArrayNode list = (ArrayNode) s.get("proposals").get("discarded");
        boolean known = false;
        for (JsonNode k : list) {
            known |= k.asText().equals(key);
        }
        if (!known) {
            list.add(key);
        }
        host.saveSettings(Sp5Plugin.ID, s);
        return plugin.settings();
    }

    /** Forgets every discarded proposal. */
    @POST
    @Path("zuordnung/vorschlaege/zuruecksetzen")
    public JsonNode resetDiscarded() {
        synchronized (SETTINGS_LOCK) {
            ObjectNode s = (ObjectNode) plugin.settings().deepCopy();
            ((ObjectNode) s.get("proposals")).putArray("discarded");
            host.saveSettings(Sp5Plugin.ID, s);
            return plugin.settings();
        }
    }

    /** The last run against the last import (409 {@code plugin.sp5.noBaseline} before the first). */
    @GET
    @Path("vergleich")
    public Map<String, Object> compareWithImport(@QueryParam("korrekturen") @DefaultValue("true") boolean corrections) {
        Sp5Runs.Run run = runs.last();
        Optional<Dataset> old = host.lastImport(run.months());
        if (old.isEmpty()) {
            throw PluginErrors.error(Status.CONFLICT, "plugin.sp5.noBaseline", Map.of(),
                    "Noch nichts importiert");
        }
        return Sp5Comparison.compare(mapper, "import", corrections, old.get(),
                corrections ? run.dataset : run.withoutCorrections(), run.months());
    }

    /** The last run against an earlier dataset in the body (400 {@code plugin.sp5.dataset} if it is none). */
    @POST
    @Path("vergleich")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> compareWithFile(@QueryParam("korrekturen") @DefaultValue("true") boolean corrections,
            String body) {
        Sp5Runs.Run run = runs.last();
        Dataset old;
        try {
            old = body == null ? null : mapper.readValue(body, Dataset.class);
        } catch (JsonProcessingException e) {
            old = null;
        }
        if (old == null || !Dataset.FORMAT.equals(old.format()) || old.version() != Dataset.VERSION
                || !Sp5Comparison.whole(old)) {
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.dataset", Map.of(),
                    "Die Datei ist kein Datensatz von Dispofold");
        }
        return Sp5Comparison.compare(mapper, "datei", corrections, old,
                corrections ? run.dataset : run.withoutCorrections(), run.months());
    }

    /** A mapping in the body as sp5_export.py reads it from its file. */
    private Object python(JsonNode mapping) {
        try {
            return PyJson.load(mapper.writeValueAsBytes(mapping));
        } catch (JsonProcessingException | PyJson.DecodeError e) {
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.mapping", Map.of(), "die Zuordnung ist kein JSON");
        }
    }

    private static void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    /** The options of sp5_export.py for this request, without --data, --mapping, --corrections, --out, --report. */
    static List<String> args(Sp5Export.Request r) {
        List<String> a = new ArrayList<>(List.of("--month", r.month()));
        if (r.to() != null) {
            a.addAll(List.of("--to", r.to()));
        }
        a.addAll(List.of("--history-months", String.valueOf(r.historyMonths())));
        if (r.areasFromHistory() != null) {
            a.addAll(List.of("--areas-from-history", String.valueOf(r.areasFromHistory())));
        }
        if (r.shiftsFromHistory() != null) {
            a.addAll(List.of("--shifts-from-history", String.valueOf(r.shiftsFromHistory())));
        }
        a.addAll(List.of("--demand", r.demand()));
        if (r.mixMinDuties() == null) {
            a.add("--no-shift-mix");
        } else {
            a.addAll(List.of("--mix-min-duties", String.valueOf(r.mixMinDuties())));
        }
        a.addAll(List.of("--hidden-shifts-months", String.valueOf(r.hiddenShiftsMonths()), "--recent-months",
                String.valueOf(r.recentMonths())));
        return a;
    }
}
