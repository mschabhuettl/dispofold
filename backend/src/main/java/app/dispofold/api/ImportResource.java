package app.dispofold.api;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.export.Corrections;
import app.dispofold.export.Corrections.Changes;
import app.dispofold.export.Corrections.Conflict;
import app.dispofold.importclient.DatasetValidator;
import app.dispofold.importclient.Issue;
import app.dispofold.model.AbsenceCredit;
import app.dispofold.model.AbsenceType;
import app.dispofold.model.Area;
import app.dispofold.model.Dataset;
import app.dispofold.model.HistoryEntry;
import app.dispofold.model.Period;
import app.dispofold.model.Person;
import app.dispofold.model.Plan;
import app.dispofold.model.PlanStatus;
import app.dispofold.model.Restriction;
import app.dispofold.model.ShiftMix;
import app.dispofold.model.ShiftType;
import app.dispofold.store.Store;
import app.dispofold.store.Store.StoredPlan;

/**
 * Imports a dataset in the documented format (docs/datenformat.md) or the bundled synthetic demo data. Every import
 * stores the raw imported master data as the baseline, together with the dataset's section {@code appliedCorrections}
 * (the corrections its source applied) if it has one; with {@code keep} the master data changed in Dispofold since
 * the last import are carried over to the new dataset (see {@link Corrections}). People excluded from the source
 * whom an uploaded dataset leaves out stay, see {@link #withExcluded}.
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

    /** The section of an uploaded dataset that is not part of {@link Dataset}. */
    private record Applied(JsonNode appliedCorrections) {
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
            return save(mapper.readValue(in, Dataset.class), null, replace, false, false);
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
        Dataset dataset = parse(body);
        return save(dataset, appliedCorrections(body), replace, keep, true);
    }

    /** Dry run of an import with {@code keep}: what would be carried over and where the new import differs. */
    @POST
    @Path("preview")
    @Consumes(MediaType.APPLICATION_JSON)
    public ImportPreview preview(String body) {
        return preview(parse(body), appliedCorrections(body));
    }

    /**
     * The import of a dataset a plugin made (an import source, see docs/plugins.md), the same as an uploaded one.
     *
     * @param applied the corrections the source applied, in the form of a corrections file; ignored otherwise
     */
    public ImportSummary importDataset(Dataset dataset, JsonNode applied, boolean replace, boolean keep) {
        return importDataset(dataset, applied, replace, keep, null);
    }

    /**
     * The same, with the loaded corrections file as it was before the source made the dataset ({@code loaded}, null
     * if none): when the source lists the corrections it applied, that file is now part of the import and no longer
     * waits; one loaded or removed in the meantime stays as it is.
     */
    public ImportSummary importDataset(Dataset dataset, JsonNode applied, boolean replace, boolean keep,
            String loaded) {
        JsonNode checked = checkedApplied(applied);
        synchronized (Store.DATASET_LOCK) {
            ImportSummary summary = save(dataset, checked, replace, keep, true);
            if (checked != null && loaded != null
                    && store.loadedCorrections().map(Store.LoadedCorrections::doc).filter(loaded::equals).isPresent()) {
                store.clearLoadedCorrections();
            }
            return summary;
        }
    }

    /** The dry run of {@link #importDataset} with {@code keep}. */
    public ImportPreview preview(Dataset dataset, JsonNode applied) {
        JsonNode corrections = checkedApplied(applied);
        synchronized (Store.DATASET_LOCK) {
            Dataset checked = validated(dataset).dataset();
            Dataset raw = withCredits(withExcluded(checked, corrections));
            if (raw != checked) {
                validated(raw);
            }
            Kept kept = kept(raw);
            if (kept != null && !kept.changes().isEmpty()) {
                // rejected like the import would be
                validated(Corrections.apply(kept.changes(), raw));
            }
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

    /** The section {@code appliedCorrections} of a parsed upload if it has the form of a corrections file, else null. */
    private JsonNode appliedCorrections(String body) {
        JsonNode section;
        try {
            section = mapper.readValue(body, Applied.class).appliedCorrections();
        } catch (JsonProcessingException e) {
            return null;
        }
        return checkedApplied(section);
    }

    private static JsonNode checkedApplied(JsonNode section) {
        return section != null && Corrections.FORMAT.equals(section.path("format").asText())
                && section.path("people").isObject() ? section : null;
    }

    /**
     * Replaces the master data and the months contained in the dataset; plans stay. Master data changed in Dispofold
     * since the last import are only replaced with {@code replace}, or carried over with {@code keep}.
     */
    private ImportSummary save(Dataset dataset, JsonNode applied, boolean replace, boolean keep, boolean upload) {
        synchronized (Store.DATASET_LOCK) {
            return replace(dataset, applied, replace, keep, upload);
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

    private ImportSummary replace(Dataset dataset, JsonNode applied, boolean replace, boolean keep, boolean upload) {
        DatasetValidator.Result checked = validated(dataset);
        Dataset retained = withCredits(upload ? withExcluded(checked.dataset(), applied) : checked.dataset());
        DatasetValidator.Result result = retained == checked.dataset() ? checked : validated(retained);
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
                () -> saveChecked(raw, merged, kept, applied));
    }

    /** Called with the locks of all imported months held, so no solve or closing can start in between. */
    private ImportSummary saveChecked(Dataset raw, DatasetValidator.Result result, Kept kept, JsonNode applied) {
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
        store.saveImport(master, MasterDataService.track(store.datasetState().ids(), master), baseline, applied,
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

    /**
     * The import with the people it leaves out who are excluded in Dispofold or were at the last import, or whom its
     * section {@code appliedCorrections} lists as excluded (the source left them out for that): they stay excluded,
     * with their history, their restrictions of known shifts, and their known areas, so that the closed months keep
     * them.
     * The exclusion keeps its day from the last import, else from Dispofold, else the first day after the last
     * closed month. Part of the import (and its baseline), so they are no change since it.
     */
    private Dataset withExcluded(Dataset raw, JsonNode applied) {
        Dataset current = store.dataset().orElse(null);
        if (current == null) {
            return raw;
        }
        Set<String> listed = new HashSet<>();
        if (applied != null) {
            applied.path("people").fields().forEachRemaining(e -> {
                if (e.getValue().path(Corrections.EXCLUDE).booleanValue()) {
                    listed.add(e.getKey());
                }
            });
        }
        Map<String, LocalDate> before = new HashMap<>();
        store.baseline().ifPresent(b -> list(b.people()).stream().filter(p -> p.excludedFrom() != null)
                .forEach(p -> before.put(p.id(), p.excludedFrom())));
        // also excluded at the last import and taken back since: keeping the changes takes it back again
        Set<String> present = list(raw.people()).stream().map(Person::id).collect(Collectors.toSet());
        List<Person> left = list(current.people()).stream().filter(p -> !present.contains(p.id())
                && (p.excludedFrom() != null || listed.contains(p.id()) || before.containsKey(p.id()))).toList();
        if (left.isEmpty()) {
            return raw;
        }
        List<YearMonth> closed = store.plans().stream().map(StoredPlan::plan)
                .filter(p -> p.status() == PlanStatus.CLOSED).map(Plan::month).toList();
        List<YearMonth> months = Stream.of(store.periodMonths().stream(),
                store.plans().stream().map(p -> p.plan().month()), list(raw.periods()).stream().map(Period::month))
                .flatMap(m -> m).toList();
        LocalDate start = MasterDataService.exclusionStart(closed, months);
        Set<String> areas = list(raw.areas()).stream().map(Area::id).collect(Collectors.toSet());
        Set<String> shifts = list(raw.shifts()).stream().map(ShiftType::id).collect(Collectors.toSet());
        YearMonth firstPlanned = list(raw.periods()).stream().map(Period::month).min(YearMonth::compareTo)
                .orElse(null);
        List<Person> people = new ArrayList<>(list(raw.people()));
        List<Restriction> restrictions = new ArrayList<>(list(raw.restrictions()));
        List<HistoryEntry> history = new ArrayList<>(list(raw.history()));
        Set<String> ids = left.stream().map(Person::id).collect(Collectors.toSet());
        for (Person p : left) {
            ShiftMix mix = p.shiftMix();
            for (String a : list(p.areaIds())) {
                mix = mix == null || areas.contains(a) ? mix : mix.withoutArea(a);
            }
            people.add(new Person(p.id(), p.firstName(), p.lastName(), p.shortName(), p.weeklyHours(),
                    list(p.areaIds()).stream().filter(areas::contains).toList(), p.hoursAccount(),
                    p.compRestBalance(), p.familyRules(), p.externalId(), p.entryDate(), p.exitDate(), mix,
                    before.getOrDefault(p.id(), p.excludedFrom() != null ? p.excludedFrom() : start)));
        }
        list(current.restrictions()).stream().filter(r -> ids.contains(r.personId())
                && (r.shiftId() == null || shifts.contains(r.shiftId()))).forEach(restrictions::add);
        list(current.history()).stream().filter(h -> ids.contains(h.personId()) && (firstPlanned == null
                || h.month() != null && h.month().compareTo(firstPlanned.toString()) < 0)).forEach(history::add);
        return new Dataset(raw.format(), raw.version(), raw.source(), raw.areas(), raw.shifts(), raw.demand(),
                raw.holidays(), raw.absenceTypes(), people, restrictions, history, raw.periods());
    }

    /** The credit of an absence type is set in Dispofold: a type of the import without one keeps it. */
    private Dataset withCredits(Dataset raw) {
        Dataset current = store.dataset().orElse(null);
        if (current == null || raw.absenceTypes() == null) {
            return raw;
        }
        Map<String, AbsenceCredit> credits = new HashMap<>();
        list(current.absenceTypes()).stream().filter(t -> t.credit() != null)
                .forEach(t -> credits.put(t.code(), t.credit()));
        List<AbsenceType> types = raw.absenceTypes().stream().map(t -> t.credit() != null
                || !credits.containsKey(t.code()) ? t : new AbsenceType(t.code(), t.name(), t.reducesTarget(),
                        t.chargesAccount(), t.compensatoryRest(), t.color(), credits.get(t.code()))).toList();
        return new Dataset(raw.format(), raw.version(), raw.source(), raw.areas(), raw.shifts(), raw.demand(),
                raw.holidays(), types, raw.people(), raw.restrictions(), raw.history(), raw.periods());
    }

    /** The master data of a dataset: without periods. */
    private static Dataset master(Dataset d) {
        return new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(), d.holidays(),
                d.absenceTypes(), d.people(), d.restrictions(), d.history(), List.of());
    }

    private static <T> List<T> list(List<T> items) {
        return items == null ? List.of() : items;
    }
}
