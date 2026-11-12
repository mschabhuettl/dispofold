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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.importclient.DatasetValidator;
import app.dispofold.importclient.Issue;
import app.dispofold.model.Dataset;
import app.dispofold.model.Period;
import app.dispofold.store.Store;

/** Imports a dataset in the documented format (docs/datenformat.md) or the bundled synthetic demo data. */
@Path("/api/import")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("planner")
public class ImportResource {

    /** Synthetic demo dataset (150 invented people, January 2027), bundled at build time. */
    static final String DEMO_RESOURCE = "/demo/demo-dataset-2027-01.synthetic.json";

    public record ImportSummary(String source, int people, int areas, int shifts, List<String> months,
            List<Issue> warnings) {
    }

    /** Body of a rejected import: the error scheme of {@link ApiErrors} plus the list of findings. */
    public record ImportRejected(String code, Map<String, Object> params, String error, List<Issue> errors,
            List<Issue> warnings) {
    }

    @Inject
    Store store;

    @Inject
    PlanService plans;

    @Inject
    ObjectMapper mapper;

    @POST
    @Path("demo")
    public ImportSummary demo() throws IOException {
        try (InputStream in = ImportResource.class.getResourceAsStream(DEMO_RESOURCE)) {
            if (in == null) {
                throw ApiErrors.error(Status.INTERNAL_SERVER_ERROR, "import.demoMissing",
                        "Demodaten fehlen im Programm");
            }
            return save(mapper.readValue(in, Dataset.class));
        }
    }

    @POST
    @Path("dataset")
    @Consumes(MediaType.APPLICATION_JSON)
    public ImportSummary dataset(String body) {
        Dataset dataset;
        try {
            dataset = mapper.readValue(body, Dataset.class);
        } catch (JsonProcessingException e) {
            // Name the position only, never the content: it may contain names.
            JsonLocation at = e.getLocation();
            Map<String, Object> params = at == null || at.getLineNr() < 1 ? Map.of()
                    : Map.of("line", at.getLineNr(), "column", at.getColumnNr());
            throw ApiErrors.error(Status.BAD_REQUEST, "import.parse", params,
                    "Die Datei passt nicht zum Datenformat");
        }
        return save(dataset);
    }

    /** Replaces the master data and the months contained in the dataset; plans stay. */
    private ImportSummary save(Dataset dataset) {
        synchronized (Store.DATASET_LOCK) {
            return replace(dataset);
        }
    }

    private ImportSummary replace(Dataset dataset) {
        DatasetValidator.Result result = DatasetValidator.validate(dataset, store.rules());
        if (!result.ok()) {
            throw new WebApplicationException(Response.status(Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON)
                    .entity(new ImportRejected("import.invalid", Map.of("count", result.errors().size()),
                            "Der Datensatz enthält Fehler und wurde nicht importiert", result.errors(),
                            result.warnings()))
                    .build());
        }
        Dataset d = result.dataset();
        return plans.withMonths(d.periods().stream().map(Period::month).toList(), () -> saveChecked(d, result));
    }

    /** Called with the locks of all imported months held, so no solve or closing can start in between. */
    private ImportSummary saveChecked(Dataset d, DatasetValidator.Result result) {
        for (Period p : d.periods()) {
            if (plans.isSolving(p.month())) {
                throw ApiErrors.error(Status.CONFLICT, "import.solving", Map.of("month", p.month().toString()),
                        "Für einen importierten Monat läuft gerade eine Planung");
            }
            if (plans.isClosed(p.month())) {
                throw ApiErrors.error(Status.CONFLICT, "import.closed", Map.of("month", p.month().toString()),
                        "Ein importierter Monat ist abgeschlossen");
            }
        }
        store.saveDataset(new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), d.people(), d.restrictions(), d.history(), List.of()));
        for (Period p : d.periods()) {
            plans.savePeriod(p, null);
        }
        return new ImportSummary(d.source(), d.people().size(), d.areas().size(), d.shifts().size(),
                d.periods().stream().map(p -> p.month().toString()).toList(), result.warnings());
    }
}
