package app.dispofold.plugins.sp5;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.model.Dataset;
import app.dispofold.model.Period;
import app.dispofold.plugin.api.PluginErrors;
import app.dispofold.plugins.sp5.port.Py;
import app.dispofold.plugins.sp5.port.Sp5Export;

/**
 * The last run of the export without an import (one per server, replaced by the next, gone after a restart): its
 * summary, its report and, for the comparison, its dataset with and without the corrections file.
 */
@ApplicationScoped
public class Sp5Runs {

    @Inject
    Sp5Plugin plugin;

    @Inject
    ObjectMapper mapper;

    private Run last;

    /**
     * What a run was made from: its request, the mapping, the options and the folder's files (name, size, time of
     * change) in {@code base}, the corrections file in {@code corrections}.
     */
    record Fingerprint(byte[] base, byte[] corrections) {
    }

    /** One run: what it was asked, made with and gave. */
    final class Run {
        final String id = UUID.randomUUID().toString();
        final ObjectNode request;
        final Sp5Plugin.Inputs inputs;
        final Fingerprint fingerprint;
        final Sp5Export.Result result;
        final Dataset dataset;
        final Instant ranAt;
        private Dataset withoutCorrections;

        Run(ObjectNode request, Sp5Plugin.Inputs inputs, Fingerprint fingerprint, Sp5Export.Result result,
                Dataset dataset, Instant ranAt) {
            this.request = request;
            this.inputs = inputs;
            this.fingerprint = fingerprint;
            this.result = result;
            this.dataset = dataset;
            this.ranAt = ranAt;
        }

        List<YearMonth> months() {
            return dataset.periods().stream().map(Period::month).toList();
        }

        /** The same request run without the corrections file, made once when first asked for. */
        synchronized Dataset withoutCorrections() {
            if (withoutCorrections == null) {
                // made later from the folder and the mapping as they are then: only if those are still the run's
                if (!Arrays.equals(fingerprint(plugin.inputs(request)).base(), fingerprint.base())) {
                    throw stale();
                }
                Sp5Export.Request r = inputs.request();
                withoutCorrections = r.corrections() == null ? dataset
                        : plugin.data(plugin.export(new Sp5Export.Request(r.folder(), r.month(), r.to(),
                                r.historyMonths(), r.areasFromHistory(), r.shiftsFromHistory(), r.demand(),
                                r.mixMinDuties(), r.hiddenShiftsMonths(), r.recentMonths(), r.mapping(), null)))
                                .dataset();
            }
            return withoutCorrections;
        }

        Map<String, Object> summary() {
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("people", size(dataset.people()));
            counts.put("areas", size(dataset.areas()));
            counts.put("shifts", size(dataset.shifts()));
            counts.put("demandRules", size(dataset.demand()));
            counts.put("duties", dataset.periods().stream().mapToInt(p -> size(p.fixed())).sum());
            counts.put("previousDuties", dataset.periods().stream().mapToInt(p -> size(p.previousDuties())).sum());
            counts.put("absences", dataset.periods().stream().mapToInt(p -> size(p.absences())).sum());
            counts.put("warnings", result.warnings().size());
            counts.put("reportRows", result.reportRows().size());
            counts.put("corrections", result.reportRows().stream().filter(r -> "korrektur".equals(r.get(0)))
                    .count());
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id", id);
            s.put("request", request);
            s.put("months", months().stream().map(YearMonth::toString).toList());
            s.put("ranAt", ranAt.toString());
            s.put("counts", counts);
            s.put("corrections", inputs.correctionsFile() != null);
            return s;
        }

        /** The report's rows as objects of their non-empty columns, as the CSV writes them, and the warnings. */
        Map<String, Object> report() {
            List<Map<String, String>> rows = result.reportRows().stream().map(r -> {
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < r.size(); i++) {
                    String text = r.get(i) == null ? "" : Py.str(r.get(i));
                    if (!text.isEmpty()) {
                        row.put(Sp5Export.REPORT_HEADER.get(i), text);
                    }
                }
                return row;
            }).toList();
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("columns", Sp5Export.REPORT_HEADER);
            report.put("rows", rows);
            report.put("warnings", result.warnings());
            return report;
        }
    }

    private static int size(List<?> items) {
        return items == null ? 0 : items.size();
    }

    /** Runs the export of the request (with the corrections, as the import would load it) and keeps it. */
    Run run(JsonNode body) {
        Sp5Plugin.Inputs inputs = plugin.inputs(body);
        Sp5Export.Result result = plugin.export(inputs.request());
        ObjectNode request = mapper.createObjectNode();
        request.put("month", inputs.request().month());
        request.put("to", inputs.request().to());
        ObjectNode options = request.putObject("options");
        Sp5Settings.defaults(mapper).get("options").fieldNames()
                .forEachRemaining(k -> options.set(k, inputs.options().get(k)));
        Run run = new Run(request, inputs, fingerprint(inputs), result, plugin.data(result).dataset(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS));
        synchronized (this) {
            last = run;
        }
        return run;
    }

    /**
     * An import of the run {@code id} (the Übersicht's): only while it is the last run and the import reads what it
     * was made from, so that it loads the very dataset the run showed; else 409 {@code plugin.sp5.staleRun}.
     */
    void check(String id, Sp5Plugin.Inputs inputs) {
        Run run;
        synchronized (this) {
            run = last;
        }
        Fingerprint now = fingerprint(inputs);
        if (run == null || !run.id.equals(id) || !Arrays.equals(run.fingerprint.base(), now.base())
                || !Arrays.equals(run.fingerprint.corrections(), now.corrections())) {
            throw stale();
        }
    }

    private static jakarta.ws.rs.WebApplicationException stale() {
        return PluginErrors.error(Status.CONFLICT, "plugin.sp5.staleRun", Map.of(),
                "Seit dem Lauf hat sich etwas geändert (ein neuer Lauf, die Zuordnung, die Korrekturen, die Optionen"
                        + " oder der Ordner): bitte noch einmal ausführen");
    }

    /** The fingerprint of an export's inputs (SHA-256). */
    Fingerprint fingerprint(Sp5Plugin.Inputs inputs) {
        Sp5Export.Request r = inputs.request();
        MessageDigest base = sha256();
        update(base, r.month() + "\n" + r.to() + "\n" + inputs.options() + "\n");
        base.update(inputs.mappingFile());
        try (Stream<Path> files = Files.list(r.folder())) {
            for (Path f : files.sorted().toList()) {
                update(base, "\n" + f.getFileName() + "\t" + Files.size(f) + "\t" + Files.getLastModifiedTime(f));
            }
        } catch (IOException e) {
            throw PluginErrors.error(Status.BAD_REQUEST, "plugin.sp5.noFolder", Map.of(), "Der Ordner ist nicht lesbar");
        }
        MessageDigest corrections = sha256();
        if (inputs.correctionsFile() != null) {
            corrections.update(inputs.correctionsFile());
        }
        return new Fingerprint(base.digest(), corrections.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(MessageDigest digest, String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The last run, or 404 {@code plugin.sp5.noRun}. */
    synchronized Run last() {
        if (last == null) {
            throw PluginErrors.error(Status.NOT_FOUND, "plugin.sp5.noRun", Map.of(),
                    "Noch kein Lauf seit dem Start des Servers");
        }
        return last;
    }

    /** Forgets the last run (as a restart does). */
    synchronized void forget() {
        last = null;
    }
}
