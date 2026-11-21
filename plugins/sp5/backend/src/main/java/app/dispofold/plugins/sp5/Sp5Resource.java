package app.dispofold.plugins.sp5;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.plugin.api.PluginErrors;
import app.dispofold.plugin.api.PluginHost;
import app.dispofold.plugins.sp5.port.Sp5Export;

/** The plugin's routes: its settings, and the result of an export as files (for the comparison with Python). */
@Path("/api/plugins/sp5")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("planner")
public class Sp5Resource {

    @Inject
    PluginHost host;

    @Inject
    Sp5Plugin plugin;

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
        ObjectNode s = Sp5Settings.checked(mapper, body);
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
     * sp5_export.py, mapping.json, korrekturen.json if Dispofold has corrections). plugins/sp5/tools/sp5-vergleich.py
     * runs the Python program with the same inputs and compares.
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
        }
        String month = body == null ? "" : body.path("month").asText("").replaceAll("[^0-9-]", "");
        return Response.ok(bytes.toByteArray(), "application/zip")
                .header("Content-Disposition", "attachment; filename=\"dispofold-sp5-ergebnis-" + month + ".zip\"")
                .build();
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
