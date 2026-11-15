package app.dispofold.api;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.importclient.DatasetValidator;
import app.dispofold.importclient.Issue;
import app.dispofold.model.Absence;
import app.dispofold.model.AbsenceType;
import app.dispofold.model.Area;
import app.dispofold.model.Dataset;
import app.dispofold.model.DemandRule;
import app.dispofold.model.FixedAssignment;
import app.dispofold.model.HistoryEntry;
import app.dispofold.model.Holiday;
import app.dispofold.model.Period;
import app.dispofold.model.Person;
import app.dispofold.model.Plan;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.PreviousDuty;
import app.dispofold.model.Restriction;
import app.dispofold.model.ShiftType;
import app.dispofold.model.Wish;
import app.dispofold.store.Store;
import app.dispofold.store.Store.DatasetState;
import app.dispofold.store.Store.Ids;

/**
 * Changes of the master data made in Dispofold: create, update and remove areas, shifts, demand rules, holidays,
 * absence types and people, and replace a person's restrictions. Every change is checked like an import (the whole
 * resulting dataset with all stored planning months) and is based on the revision it was read with. Removing an
 * object that a closed month uses is refused; references in open months and other master data are removed with
 * it after confirmation.
 */
@ApplicationScoped
public class MasterDataService {

    /** Collections of the master data; {@code param} names the kind in error params. */
    public enum Kind {
        PEOPLE("people", "person"), SHIFTS("shifts", "shift"), AREAS("areas", "area"),
        ABSENCE_TYPES("absenceTypes", "absenceType"), HOLIDAYS("holidays", "holiday"), DEMAND("demand", "demand");

        final String path;
        final String param;

        Kind(String path, String param) {
            this.path = path;
            this.param = param;
        }

        static Kind of(String path) {
            return Stream.of(values()).filter(k -> k.path.equals(path)).findFirst()
                    .orElseThrow(() -> ApiErrors.error(Status.NOT_FOUND, "masterdata.kind",
                            PlanService.params("kind", path), "Unbekannte Art von Stammdaten"));
        }
    }

    /** Answer of a create or update: the new revision, the key of the object and the object as stored. */
    public record MasterChange(long revision, String key, Object item) {
    }

    /** Answer of a removal: the new revision and the number of references removed with the object. */
    public record Removal(long revision, int removed) {
    }

    /** Body of a rejected change: the error scheme of {@link ApiErrors} plus the validator's findings. */
    public record ChangeRejected(String code, Map<String, Object> params, String error, List<Issue> errors,
            List<Issue> warnings) {
    }

    /** Prefixes of generated ids ("neu"); imports use other ids (e.g. P0001, E1001, S12). */
    static final Map<Kind, String> PREFIX = Map.of(Kind.PEOPLE, "PN", Kind.SHIFTS, "SN");

    @Inject
    Store store;

    @Inject
    PlanService plans;

    @Inject
    ObjectMapper mapper;

    /** The mutable lists of one change, copied from the stored master data. */
    private static final class Master {
        final Dataset base;
        final List<Area> areas;
        final List<ShiftType> shifts;
        final List<DemandRule> demand;
        final List<Holiday> holidays;
        final List<AbsenceType> absenceTypes;
        final List<Person> people;
        final List<Restriction> restrictions;

        Master(Dataset d) {
            base = d;
            areas = new ArrayList<>(list(d.areas()));
            shifts = new ArrayList<>(list(d.shifts()));
            demand = new ArrayList<>(list(d.demand()));
            holidays = new ArrayList<>(list(d.holidays()));
            absenceTypes = new ArrayList<>(list(d.absenceTypes()));
            people = new ArrayList<>(list(d.people()));
            restrictions = new ArrayList<>(list(d.restrictions()));
        }

        Dataset dataset() {
            return new Dataset(base.format(), base.version(), base.source(), List.copyOf(areas),
                    List.copyOf(shifts), List.copyOf(demand), List.copyOf(holidays), List.copyOf(absenceTypes),
                    List.copyOf(people), List.copyOf(restrictions), base.history(), List.of());
        }

        @SuppressWarnings("unchecked")
        <T> List<T> items(Kind kind) {
            return (List<T>) switch (kind) {
                case PEOPLE -> people;
                case SHIFTS -> shifts;
                case AREAS -> areas;
                case ABSENCE_TYPES -> absenceTypes;
                case HOLIDAYS -> holidays;
                case DEMAND -> demand;
            };
        }
    }

    /** The open and closed planning months with their periods and plans, read once per change. */
    private final class Months {
        final Map<YearMonth, Period> periods = new LinkedHashMap<>();
        final Map<YearMonth, Plan> plans = new LinkedHashMap<>();
        final Set<YearMonth> closed = new TreeSet<>();
        final Map<YearMonth, Period> changedPeriods = new LinkedHashMap<>();
        final Map<YearMonth, List<PlanAssignment>> changedCells = new LinkedHashMap<>();

        Months(List<YearMonth> months) {
            for (YearMonth m : months) {
                store.period(m).ifPresent(p -> periods.put(m, p));
                store.plan(m).ifPresent(p -> plans.put(m, p.plan()));
                if (MasterDataService.this.plans.isClosed(m)) {
                    closed.add(m);
                }
            }
        }

        List<Period> all() {
            List<Period> all = new ArrayList<>();
            periods.forEach((m, p) -> all.add(changedPeriods.getOrDefault(m, p)));
            return all;
        }
    }

    // Reading

    public DatasetState state() {
        return store.datasetState();
    }

    /** The stored master data with all planning months, as an import file. */
    public Dataset export() {
        Dataset d = plans.dataset();
        List<Period> periods = store.periodMonths().stream().map(m -> store.period(m).orElseThrow()).toList();
        return new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(), d.holidays(),
                d.absenceTypes(), d.people(), d.restrictions(), d.history(), periods);
    }

    // Changes

    public MasterChange create(String kindPath, JsonNode body, Long revision) {
        Kind kind = Kind.of(kindPath);
        return change(revision, (master, months, ids) -> {
            ObjectNode node = object(kind, body);
            String key;
            Object item;
            switch (kind) {
                case PEOPLE, SHIFTS -> {
                    key = nextId(kind, master, ids);
                    node.put("id", key);
                    item = parse(kind, node);
                }
                case AREAS, ABSENCE_TYPES -> {
                    item = parse(kind, node);
                    key = key(kind, item);
                    if (key == null || key.isBlank()) {
                        throw ApiErrors.error(Status.BAD_REQUEST, "masterdata.idMissing",
                                PlanService.params("kind", kind.param), "Kürzel fehlt");
                    }
                    if (master.items(kind).stream().anyMatch(x -> key.equals(key(kind, x)))
                            || retired(ids, kind).contains(key)) {
                        throw ApiErrors.error(Status.BAD_REQUEST, "masterdata.idTaken",
                                PlanService.params("kind", kind.param, "key", key),
                                "Dieses Kürzel ist schon vergeben oder war vergeben");
                    }
                }
                default -> {
                    item = parse(kind, node);
                    key = String.valueOf(master.items(kind).size());
                }
            }
            master.items(kind).add(item);
            return new Result(key, item, 0);
        });
    }

    public MasterChange update(String kindPath, String key, JsonNode body, Long revision) {
        Kind kind = Kind.of(kindPath);
        return change(revision, (master, months, ids) -> {
            int index = indexOf(kind, master, key);
            Object item = parse(kind, object(kind, body));
            if (kind != Kind.HOLIDAYS && kind != Kind.DEMAND && !key.equals(key(kind, item))) {
                throw ApiErrors.error(Status.BAD_REQUEST, "masterdata.idMismatch",
                        PlanService.params("kind", kind.param, "key", key),
                        "Die Id im Pfad und im Body muss gleich sein");
            }
            master.items(kind).set(index, item);
            return new Result(key, item, 0);
        });
    }

    public MasterChange restrictions(String personId, List<Restriction> restrictions, Long revision) {
        return change(revision, (master, months, ids) -> {
            indexOf(Kind.PEOPLE, master, personId);
            if (restrictions == null || restrictions.contains(null)) {
                throw ApiErrors.error(Status.BAD_REQUEST, "masterdata.format",
                        PlanService.params("kind", "restriction"), "Einschränkungen im falschen Format");
            }
            List<Restriction> own = restrictions.stream().map(r -> new Restriction(personId, r.shiftId(),
                    r.category(), r.weekday(), r.kind(), r.validFrom(), r.validTo())).toList();
            master.restrictions.removeIf(r -> personId.equals(r.personId()));
            master.restrictions.addAll(own);
            return new Result(personId, own, 0);
        });
    }

    public Removal remove(String kindPath, String key, boolean confirm, Long revision) {
        Kind kind = Kind.of(kindPath);
        MasterChange change = change(revision, (master, months, ids) -> {
            int index = indexOf(kind, master, key);
            int removed = switch (kind) {
                case PEOPLE -> removePerson(master, months, key, confirm);
                case SHIFTS -> removeShift(master, months, key, confirm);
                case AREAS -> removeArea(master, key, confirm);
                case ABSENCE_TYPES -> removeAbsenceType(months, key, confirm);
                default -> 0;
            };
            master.items(kind).remove(index);
            if (kind == Kind.AREAS || kind == Kind.ABSENCE_TYPES) {
                ids = retire(ids, kind, key);
            }
            return new Result(key, null, removed, ids);
        });
        return new Removal(change.revision(), (int) change.item());
    }

    // Removal of references

    private int removePerson(Master master, Months months, String id, boolean confirm) {
        Set<YearMonth> closed = new TreeSet<>();
        Stream.concat(list(master.base.history()).stream(), store.history().stream())
                .filter(h -> id.equals(h.personId())).map(HistoryEntry::month).map(MasterDataService::month)
                .filter(Objects::nonNull).forEach(closed::add);
        Predicate<String> person = id::equals;
        References refs = references(months, a -> person.test(a.personId()), w -> person.test(w.personId()),
                f -> person.test(f.personId()), v -> person.test(v.personId()), c -> person.test(c.personId()));
        closed.addAll(refs.closed);
        int own = (int) master.restrictions.stream().filter(r -> id.equals(r.personId())).count();
        require(Kind.PEOPLE, id, closed, refs.count + own, refs.open, confirm);
        master.restrictions.removeIf(r -> id.equals(r.personId()));
        apply(months, refs);
        return refs.count + own;
    }

    private int removeShift(Master master, Months months, String id, boolean confirm) {
        References refs = references(months, a -> false, w -> id.equals(w.shiftId()), f -> id.equals(f.shiftId()),
                v -> id.equals(v.shiftId()), c -> id.equals(c.shiftId()));
        int own = (int) master.restrictions.stream().filter(r -> id.equals(r.shiftId())).count();
        require(Kind.SHIFTS, id, refs.closed, refs.count + own, refs.open, confirm);
        master.restrictions.removeIf(r -> id.equals(r.shiftId()));
        apply(months, refs);
        return refs.count + own;
    }

    private int removeArea(Master master, String id, boolean confirm) {
        long shifts = master.shifts.stream().filter(s -> id.equals(s.areaId())).count();
        if (shifts > 0) {
            throw ApiErrors.error(Status.CONFLICT, "masterdata.areaHasShifts",
                    PlanService.params("key", id, "count", shifts), "Der Bereich hat noch Schichten");
        }
        int demand = (int) master.demand.stream().filter(r -> id.equals(r.areaId())).count();
        int people = (int) master.people.stream().filter(p -> list(p.areaIds()).contains(id)).count();
        require(Kind.AREAS, id, Set.of(), demand + people, Set.of(), confirm);
        master.demand.removeIf(r -> id.equals(r.areaId()));
        master.people.replaceAll(p -> !list(p.areaIds()).contains(id) ? p
                : new Person(p.id(), p.firstName(), p.lastName(), p.shortName(), p.weeklyHours(),
                        p.areaIds().stream().filter(a -> !id.equals(a)).toList(), p.hoursAccount(),
                        p.compRestBalance(), p.familyRules(), p.externalId(), p.entryDate(), p.exitDate()));
        return demand + people;
    }

    private int removeAbsenceType(Months months, String code, boolean confirm) {
        References refs = references(months, a -> code.equals(a.typeCode()), w -> false, f -> false, v -> false,
                c -> false);
        require(Kind.ABSENCE_TYPES, code, refs.closed, refs.count, refs.open, confirm);
        apply(months, refs);
        return refs.count;
    }

    /** Matching entries of the planning months: counted for open months, the months listed for closed ones. */
    private static final class References {
        int count;
        final Set<YearMonth> open = new TreeSet<>();
        final Set<YearMonth> closed = new TreeSet<>();
        final Map<YearMonth, Period> periods = new LinkedHashMap<>();
        final Map<YearMonth, List<PlanAssignment>> cells = new LinkedHashMap<>();
    }

    private References references(Months months, Predicate<Absence> absence, Predicate<Wish> wish,
            Predicate<FixedAssignment> fixed, Predicate<PreviousDuty> previous, Predicate<PlanAssignment> cell) {
        References refs = new References();
        months.periods.forEach((m, p) -> {
            int n = count(p.absences(), absence) + count(p.wishes(), wish) + count(p.fixed(), fixed)
                    + count(p.previousDuties(), previous);
            if (n == 0) {
                return;
            }
            if (months.closed.contains(m)) {
                refs.closed.add(m);
                return;
            }
            refs.count += n;
            refs.open.add(m);
            refs.periods.put(m, new Period(m, without(p.absences(), absence), without(p.wishes(), wish),
                    without(p.fixed(), fixed), without(p.previousDuties(), previous)));
        });
        months.plans.forEach((m, p) -> {
            int n = count(p.assignments(), cell);
            if (n == 0) {
                return;
            }
            if (months.closed.contains(m)) {
                refs.closed.add(m);
                return;
            }
            refs.count += n;
            refs.open.add(m);
            refs.cells.put(m, without(p.assignments(), cell));
        });
        return refs;
    }

    private void require(Kind kind, String key, Set<YearMonth> closed, int count, Set<YearMonth> open,
            boolean confirm) {
        if (!closed.isEmpty()) {
            throw ApiErrors.error(Status.CONFLICT, "masterdata.inUseClosed",
                    PlanService.params("kind", kind.param, "key", key, "months", join(closed)),
                    "Kommt in einem abgeschlossenen Monat vor und kann nicht entfernt werden");
        }
        if (count > 0 && !confirm) {
            throw ApiErrors.error(Status.CONFLICT, "masterdata.inUse",
                    PlanService.params("kind", kind.param, "key", key, "count", count, "months", join(open)),
                    "Wird noch verwendet; zum Entfernen samt Verweisen bestätigen");
        }
        for (YearMonth m : open) {
            plans.requireNotSolving(m);
        }
    }

    private static void apply(Months months, References refs) {
        refs.periods.forEach(months.changedPeriods::put);
        refs.cells.forEach(months.changedCells::put);
    }

    // The common path of every change

    private record Result(String key, Object item, int removed, Ids ids) {
        Result(String key, Object item, int removed) {
            this(key, item, removed, null);
        }
    }

    private interface Change {
        Result apply(Master master, Months months, Ids ids);
    }

    /**
     * Applies the change to a copy of the master data under the dataset lock and the locks of all planning months,
     * checks the revision and the whole resulting dataset, then writes the changed periods and plans and the master
     * data last.
     */
    private MasterChange change(Long revision, Change change) {
        if (revision == null) {
            throw ApiErrors.error(Status.PRECONDITION_REQUIRED, "masterdata.revisionMissing",
                    "Revision der Stammdaten fehlt");
        }
        synchronized (Store.DATASET_LOCK) {
            List<YearMonth> all = Stream.concat(store.periodMonths().stream(),
                    store.plans().stream().map(p -> p.plan().month())).distinct().sorted().toList();
            return plans.withMonths(all, () -> changeLocked(revision, change, all));
        }
    }

    private MasterChange changeLocked(long revision, Change change, List<YearMonth> all) {
        Dataset stored = plans.dataset();
        DatasetState state = store.datasetState();
        if (state.revision() != revision) {
            throw ApiErrors.error(Status.CONFLICT, "masterdata.conflict",
                    PlanService.params("revision", state.revision()),
                    "Die Stammdaten wurden inzwischen geändert, bitte neu laden");
        }
        Master master = new Master(stored);
        Months months = new Months(all);
        List<Period> before = months.all();
        Result result = change.apply(master, months, state.ids());
        Dataset changed = master.dataset();
        // The import's check on the whole dataset with all stored months. Defects that were there before (e.g. a
        // month of an earlier import with people no longer in the master data) do not block the change; every
        // defect it adds does.
        DatasetValidator.Result was = DatasetValidator.validate(withPeriods(stored, before), store.rules());
        DatasetValidator.Result checked = DatasetValidator.validate(withPeriods(changed, months.all()), store.rules());
        List<Issue> errors = added(checked.errors(), was.errors());
        if (!errors.isEmpty()) {
            throw new WebApplicationException(Response.status(Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON)
                    .entity(new ChangeRejected("masterdata.invalid", Map.of("count", errors.size()),
                            "Die Änderung enthält Fehler und wurde nicht gespeichert", errors,
                            added(checked.warnings(), was.warnings())))
                    .build());
        }
        months.changedPeriods.values().forEach(p -> store.savePeriod(p, null));
        months.changedCells.forEach(plans::saveCells);
        Ids ids = track(result.ids() != null ? result.ids() : state.ids(), changed);
        long saved = store.saveDataset(changed, ids, false);
        months.changedCells.keySet().forEach(plans::rescore);
        return new MasterChange(saved, result.key(), result.item() != null ? result.item() : result.removed());
    }

    private static Dataset withPeriods(Dataset d, List<Period> periods) {
        return new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(), d.holidays(),
                d.absenceTypes(), d.people(), d.restrictions(), d.history(), periods);
    }

    /** The issues of {@code after} that {@code before} does not have (as many times as they are added). */
    private static List<Issue> added(List<Issue> after, List<Issue> before) {
        List<Issue> old = new ArrayList<>(before);
        List<Issue> added = new ArrayList<>();
        for (Issue i : after) {
            if (!old.remove(i)) {
                added.add(i);
            }
        }
        return added;
    }

    // Ids

    /** The next generated id: one more than every id of the kind ever counted, and not in use. */
    private static String nextId(Kind kind, Master master, Ids ids) {
        String prefix = PREFIX.get(kind);
        Set<String> used = master.items(kind).stream().map(x -> key(kind, x)).collect(Collectors.toSet());
        long n = Math.max(ids.counters().getOrDefault(kind.param, 0L), highest(prefix, used));
        String id;
        do {
            id = prefix + ++n;
        } while (used.contains(id));
        return id;
    }

    /** Raises the counters to the highest generated-looking id in the data, so that none is ever generated again. */
    static Ids track(Ids ids, Dataset d) {
        Map<String, Long> counters = new HashMap<>(ids.counters());
        counters.merge(Kind.PEOPLE.param, highest(PREFIX.get(Kind.PEOPLE),
                list(d.people()).stream().map(Person::id).toList()), Math::max);
        counters.merge(Kind.SHIFTS.param, highest(PREFIX.get(Kind.SHIFTS),
                list(d.shifts()).stream().map(ShiftType::id).toList()), Math::max);
        return new Ids(Map.copyOf(counters), ids.retired());
    }

    private static long highest(String prefix, java.util.Collection<String> ids) {
        long max = 0;
        for (String id : ids) {
            if (id != null && id.startsWith(prefix) && id.length() > prefix.length() && id.length() < 19
                    && id.substring(prefix.length()).matches("\\d+")) {
                max = Math.max(max, Long.parseLong(id.substring(prefix.length())));
            }
        }
        return max;
    }

    private static Set<String> retired(Ids ids, Kind kind) {
        return Set.copyOf(ids.retired().getOrDefault(kind.param, List.of()));
    }

    private static Ids retire(Ids ids, Kind kind, String key) {
        Map<String, List<String>> retired = new HashMap<>(ids.retired());
        List<String> codes = new ArrayList<>(retired.getOrDefault(kind.param, List.of()));
        if (!codes.contains(key)) {
            codes.add(key);
        }
        retired.put(kind.param, List.copyOf(codes));
        return new Ids(ids.counters(), Map.copyOf(retired));
    }

    // Helpers

    private static String key(Kind kind, Object item) {
        return switch (kind) {
            case PEOPLE -> ((Person) item).id();
            case SHIFTS -> ((ShiftType) item).id();
            case AREAS -> ((Area) item).id();
            case ABSENCE_TYPES -> ((AbsenceType) item).code();
            default -> null;
        };
    }

    private static int indexOf(Kind kind, Master master, String key) {
        List<Object> items = master.items(kind);
        if (kind == Kind.HOLIDAYS || kind == Kind.DEMAND) {
            try {
                int i = Integer.parseInt(key);
                if (i >= 0 && i < items.size()) {
                    return i;
                }
            } catch (NumberFormatException e) {
                // unknown below
            }
        } else {
            for (int i = 0; i < items.size(); i++) {
                if (key.equals(key(kind, items.get(i)))) {
                    return i;
                }
            }
        }
        throw ApiErrors.error(Status.NOT_FOUND, "masterdata.unknown", PlanService.params("kind", kind.param, "key", key),
                "Unbekannter Eintrag");
    }

    private static ObjectNode object(Kind kind, JsonNode body) {
        if (body == null || !body.isObject()) {
            throw format(kind);
        }
        return ((ObjectNode) body).deepCopy();
    }

    private Object parse(Kind kind, ObjectNode node) {
        Class<?> type = switch (kind) {
            case PEOPLE -> Person.class;
            case SHIFTS -> ShiftType.class;
            case AREAS -> Area.class;
            case ABSENCE_TYPES -> AbsenceType.class;
            case HOLIDAYS -> Holiday.class;
            case DEMAND -> DemandRule.class;
        };
        try {
            return mapper.treeToValue(node, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw format(kind);
        }
    }

    private static WebApplicationException format(Kind kind) {
        return ApiErrors.error(Status.BAD_REQUEST, "masterdata.format", PlanService.params("kind", kind.param),
                "Eintrag im falschen Format");
    }

    private static YearMonth month(String value) {
        try {
            return value == null ? null : YearMonth.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String join(Set<YearMonth> months) {
        return months.stream().map(YearMonth::toString).collect(Collectors.joining(","));
    }

    private static <T> int count(List<T> items, Predicate<T> match) {
        return (int) list(items).stream().filter(Objects::nonNull).filter(match).count();
    }

    private static <T> List<T> without(List<T> items, Predicate<T> match) {
        return list(items).stream().filter(x -> x == null || !match.test(x)).toList();
    }

    private static <T> List<T> list(List<T> items) {
        return items == null ? List.of() : items;
    }
}
