package app.dispofold.api;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.export.Corrections;
import app.dispofold.export.Corrections.Changes;
import app.dispofold.export.Corrections.Conflict;
import app.dispofold.importclient.DatasetValidator;
import app.dispofold.importclient.Issue;
import app.dispofold.model.Dataset;
import app.dispofold.model.Period;
import app.dispofold.store.Store;

/**
 * Imports a dataset in the documented format (docs/datenformat.md) or the bundled synthetic demo data. Every import
 * stores the raw imported master data as the baseline; with {@code keep} the master data changed in Dispofold since
 * the last import are carried over to the new dataset (see {@link Corrections}).
 */
@Path("/api/import")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("planner")
public class ImportResource {

    /** Synthetic demo dataset (150 invented people, January 2027), bundled at build time. */
    static final String DEMO_RESOURCE = "/demo/demo-dataset-2027-01.synthetic.json";

    /**
     * @param kept fields, exclusions and demand areas carried over from Dispofold
     * @param notes changes from Dispofold that could not be carried over
     */
    public record ImportSummary(String source, int people, int areas, int shifts, List<String> months,
            List<Issue> warnings, int kept, List<Issue> notes) {
    }

    /** Body of a rejected import: the error scheme of {@link ApiErrors} plus the list of findings. */
    public record ImportRejected(String code, Map<String, Object> params, String error, List<Issue> errors,
            List<Issue> warnings) {
    }

    /**
     * What an import with {@code keep} would carry over.
     *
     * @param changed the master data were changed in Dispofold since the last import
     * @param kept fields, exclusions and demand areas that would be carried over
     * @param conflicts carried-over fields that the new import changes as well; the value from Dispofold wins
     * @param notes changes that could not be carried over
     */
    public record ImportPreview(boolean changed, int kept, List<Conflict> conflicts, List<Issue> notes) {
    }

    /** The changes from Dispofold checked against the new import, and their conflicts. */
    private record Kept(Changes changes, List<Conflict> conflicts) {
    }

    @Inject
    Store store;

    @Inject
    PlanService plans;

    @Inject
    ObjectMapper mapper;

    @POST
    @Path("demo")
    public ImportSummary demo(@QueryParam("replace") boolean replace) throws IOException {
        try (InputStream in = ImportResource.class.getResourceAsStream(DEMO_RESOURCE)) {
            if (in == null) {
                throw ApiErrors.error(Status.INTERNAL_SERVER_ERROR, "import.demoMissing",
                        "Demodaten fehlen im Programm");
            }
            return save(mapper.readValue(in, Dataset.class), replace, false);
        }
    }

    /**
     * @param replace replace master data changed in Dispofold since the last import
     * @param keep carry the changes made in Dispofold since the last import over to the new dataset
     */
    @POST
    @Path("dataset")
    @Consumes(MediaType.APPLICATION_JSON)
    public ImportSummary dataset(@QueryParam("replace") boolean replace, @QueryParam("keep") boolean keep,
            String body) {
        return save(parse(body), replace, keep);
    }

    /** Dry run of an import with {@code keep}: what would be carried over and where the new import differs. */
    @POST
    @Path("preview")
    @Consumes(MediaType.APPLICATION_JSON)
    public ImportPreview preview(String body) {
        Dataset dataset = parse(body);
        synchronized (Store.DATASET_LOCK) {
            Kept kept = kept(validated(dataset).dataset());
            return kept == null ? new ImportPreview(false, 0, List.of(), List.of())
                    : new ImportPreview(true, kept.changes().count(), kept.conflicts(), kept.changes().notes());
        }
    }

    /** No baseline: installs upgraded from a version without one, until the next import. */
    static WebApplicationException noBaseline() {
        return ApiErrors.error(Status.CONFLICT, "corrections.noBaseline",
                "Der Stand des letzten Imports ist nicht gespeichert (Installation aus einer älteren Version). "
                        + "Änderungen lassen sich erst nach dem nächsten Import ermitteln.");
    }

    private Dataset parse(String body) {
        try {
            return mapper.readValue(body, Dataset.class);
        } catch (JsonProcessingException e) {
            // Name the position only, never the content: it may contain names.
            JsonLocation at = e.getLocation();
            Map<String, Object> params = at == null || at.getLineNr() < 1 ? Map.of()
                    : Map.of("line", at.getLineNr(), "column", at.getColumnNr());
            throw ApiErrors.error(Status.BAD_REQUEST, "import.parse", params,
                    "Die Datei passt nicht zum Datenformat");
        }
    }

    /**
     * Replaces the master data and the months contained in the dataset; plans stay. Master data changed in Dispofold
     * since the last import are only replaced with {@code replace}, or carried over with {@code keep}.
     */
    private ImportSummary save(Dataset dataset, boolean replace, boolean keep) {
        synchronized (Store.DATASET_LOCK) {
            return replace(dataset, replace, keep);
        }
    }

    private DatasetValidator.Result validated(Dataset dataset) {
        DatasetValidator.Result result = DatasetValidator.validate(dataset, store.rules());
        if (!result.ok()) {
            throw new WebApplicationException(Response.status(Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON)
                    .entity(new ImportRejected("import.invalid", Map.of("count", result.errors().size()),
                            "Der Datensatz enthält Fehler und wurde nicht importiert", result.errors(),
                            result.warnings()))
                    .build());
        }
        return result;
    }

    /** The changes made in Dispofold since the last import, checked against the new import; null if unchanged. */
    private Kept kept(Dataset incoming) {
        if (!store.datasetState().changedSinceImport()) {
            return null;
        }
        Dataset base = store.baseline().orElseThrow(ImportResource::noBaseline);
        Dataset current = plans.dataset();
        Changes changes = Corrections.check(Corrections.diff(base, current), incoming);
        return new Kept(changes, Corrections.conflicts(changes, base, incoming));
    }

    private ImportSummary replace(Dataset dataset, boolean replace, boolean keep) {
        DatasetValidator.Result result = validated(dataset);
        Kept kept = keep ? kept(result.dataset()) : null;
        // Asked only for a valid file: an invalid one is rejected anyway.
        if (kept == null && !replace && store.datasetState().changedSinceImport()) {
            throw ApiErrors.error(Status.CONFLICT, "import.masterChanged",
                    "Die Stammdaten wurden seit dem letzten Import in Dispofold geändert");
        }
        Dataset raw = result.dataset();
        DatasetValidator.Result merged = kept == null || kept.changes().isEmpty() ? result
                : validated(Corrections.apply(kept.changes(), raw));
        return plans.withMonths(raw.periods().stream().map(Period::month).toList(),
                () -> saveChecked(raw, merged, kept));
    }

    /** Called with the locks of all imported months held, so no solve or closing can start in between. */
    private ImportSummary saveChecked(Dataset raw, DatasetValidator.Result result, Kept kept) {
        Dataset d = result.dataset();
        for (Period p : d.periods()) {
            if (plans.isSolving(p.month())) {
                throw ApiErrors.error(Status.CONFLICT, "import.solving", Map.of("month", p.month().toString()),
                        "Für einen importierten Monat läuft gerade eine Planung");
            }
            // A closed month may come back unchanged (a dataset exported from here); it then stays as it is.
            if (plans.isClosed(p.month()) && !store.period(p.month()).map(p::equals).orElse(false)) {
                throw ApiErrors.error(Status.CONFLICT, "import.closed", Map.of("month", p.month().toString()),
                        "Ein importierter Monat ist abgeschlossen");
            }
        }
        Dataset master = master(d);
        Dataset baseline = master(raw);
        store.saveImport(master, MasterDataService.track(store.datasetState().ids(), master), baseline,
                master.equals(baseline));
        for (Period p : d.periods()) {
            if (!plans.isClosed(p.month())) {
                plans.savePeriod(p, null);
            }
        }
        return new ImportSummary(d.source(), d.people().size(), d.areas().size(), d.shifts().size(),
                d.periods().stream().map(p -> p.month().toString()).toList(), result.warnings(),
                kept == null ? 0 : kept.changes().count(), kept == null ? List.of() : kept.changes().notes());
    }

    /** The master data of a dataset: without periods. */
    private static Dataset master(Dataset d) {
        return new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(), d.holidays(),
                d.absenceTypes(), d.people(), d.restrictions(), d.history(), List.of());
    }
}
