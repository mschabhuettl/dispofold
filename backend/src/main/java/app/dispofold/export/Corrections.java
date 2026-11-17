package app.dispofold.export;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.importclient.Issue;
import app.dispofold.model.AbsenceType;
import app.dispofold.model.Area;
import app.dispofold.model.Dataset;
import app.dispofold.model.DemandRule;
import app.dispofold.model.HistoryEntry;
import app.dispofold.model.Period;
import app.dispofold.model.Person;
import app.dispofold.model.Restriction;
import app.dispofold.model.ShiftMix;
import app.dispofold.model.ShiftType;

/**
 * Master data changed in Dispofold against the baseline (the master data as last imported), in the terms of the
 * corrections file (format {@code dispofold/corrections}, version 2) that the export client applies to a fresh
 * dataset as its last step: per person the changed fields as whole values and the whole list of restrictions,
 * {@code exclude} for people removed here, and all demand rules of an area whose rules changed. A field in the file
 * replaces the value completely; a field the client finds invalid is skipped there without stopping, so everything
 * a target dataset cannot take is left out here and listed as a note instead.
 * <p>
 * The same changes carry the edits over to a new import ({@link #apply}); a field also changed between the old
 * baseline and the new import is a conflict, and the value from Dispofold wins.
 * <p>
 * Notes are {@code {code, params}} with ids, field names and counts only, never names.
 */
public final class Corrections {

    public static final String FORMAT = "dispofold/corrections";
    public static final int VERSION = 2;
    /** The correctable person fields, in the order the client applies them; restrictions come last. */
    public static final List<String> FIELDS = List.of("weeklyHours", "areaIds", "hoursAccount", "compRestBalance",
            "familyRules", "entryDate", "exitDate", "shiftMix");
    public static final String RESTRICTIONS = "restrictions";
    public static final String EXCLUDE = "exclude";
    public static final String DEMAND = "demand";
    /** Person fields the file cannot change. */
    static final List<String> IDENTITY = List.of("externalId", "firstName", "lastName", "shortName");
    /** Person keys the client accepts (the dataset ids of its source); others are skipped there. */
    private static final Pattern CLIENT_ID = Pattern.compile("E\\d+");

    /**
     * The changes of one person.
     *
     * @param person the person as in Dispofold, the source of the values
     * @param fields changed fields from {@link #FIELDS} in that order, then {@link #RESTRICTIONS}
     * @param restrictions the person's restrictions in Dispofold, used when {@code fields} has restrictions
     */
    public record PersonChange(Person person, List<String> fields, List<Restriction> restrictions) {
    }

    /**
     * @param people changes per person id, in the client's order
     * @param excluded ids of people removed in Dispofold, in the client's order
     * @param demand all demand rules per area whose rules changed (an empty list removes the area's demand)
     * @param notes what cannot be carried over
     */
    public record Changes(Map<String, PersonChange> people, List<String> excluded,
            Map<String, List<DemandRule>> demand, List<Issue> notes) {

        public boolean isEmpty() {
            return people.isEmpty() && excluded.isEmpty() && demand.isEmpty();
        }

        /** Fields, exclusions and areas that are carried over. */
        public int count() {
            return people.values().stream().mapToInt(c -> c.fields().size()).sum() + excluded.size() + demand.size();
        }
    }

    /** A field changed in Dispofold and also between the old baseline and a new import. */
    public record Conflict(String personId, String areaId, String field) {
    }

    /** The corrections file and the notes about what it leaves out. */
    public record Export(ObjectNode file, List<Issue> notes, int count) {
    }

    private Corrections() {
    }

    /** The changes of {@code current} against {@code base}, checked against the base as target, as a file. */
    public static Export export(Dataset base, Dataset current, ObjectMapper mapper) {
        Changes changes = check(diff(base, current), base);
        List<Issue> notes = new ArrayList<>(changes.notes());
        long foreign = Stream.concat(changes.people().keySet().stream(), changes.excluded().stream())
                .filter(id -> !CLIENT_ID.matcher(id).matches()).count();
        if (foreign > 0) {
            notes.add(note("corrections.idFormat", "count", foreign));
        }
        return new Export(file(changes, mapper), List.copyOf(notes), changes.count());
    }

    // Diff

    /**
     * Everything changed in {@code current} against {@code base}, not yet checked against a target; notes for what
     * the file cannot express at all (people added here, changed names and ids, changed areas, shifts, absence types
     * and holidays).
     */
    public static Changes diff(Dataset base, Dataset current) {
        List<Issue> notes = new ArrayList<>();
        Map<String, Person> now = byId(list(current.people()), Person::id);
        Map<String, Person> before = byId(list(base.people()), Person::id);
        Map<String, PersonChange> people = new TreeMap<>(CLIENT_ORDER);
        List<String> excluded = new ArrayList<>();
        for (Person old : list(base.people())) {
            Person p = now.get(old.id());
            if (p == null) {
                excluded.add(old.id());
                continue;
            }
            for (String field : IDENTITY) {
                if (!Objects.equals(identity(old, field), identity(p, field))) {
                    notes.add(note("corrections.identity", "personId", old.id(), "field", field));
                }
            }
            List<String> fields = new ArrayList<>();
            for (String field : FIELDS) {
                if (!Objects.equals(value(old, field), value(p, field))) {
                    fields.add(field);
                }
            }
            List<Restriction> own = own(current.restrictions(), p.id());
            if (!counts(own(base.restrictions(), old.id())).equals(counts(own))) {
                fields.add(RESTRICTIONS);
            }
            if (!fields.isEmpty()) {
                people.put(p.id(), new PersonChange(p, fields, own));
            }
        }
        excluded.sort(CLIENT_ORDER);
        for (Person p : list(current.people())) {
            if (!before.containsKey(p.id())) {
                notes.add(note("corrections.personAdded", "personId", p.id()));
            }
        }
        master(notes, "area", base.areas(), current.areas(), Area::id);
        master(notes, "shift", base.shifts(), current.shifts(), ShiftType::id);
        master(notes, "absenceType", base.absenceTypes(), current.absenceTypes(), AbsenceType::code);
        master(notes, "holiday", base.holidays(), current.holidays(), h -> String.valueOf(h.date()));

        Map<String, List<DemandRule>> demand = new LinkedHashMap<>();
        Map<String, List<DemandRule>> was = byArea(base.demand());
        Map<String, List<DemandRule>> is = byArea(current.demand());
        for (String area : union(was.keySet(), is.keySet())) {
            List<DemandRule> rules = is.getOrDefault(area, List.of());
            if (!counts(was.getOrDefault(area, List.of())).equals(counts(rules))) {
                demand.put(area, rules);
            }
        }
        return new Changes(people, excluded, demand, notes);
    }

    private static <T> void master(List<Issue> notes, String kind, List<T> base, List<T> current,
            Function<T, String> key) {
        Map<String, T> was = byId(list(base), key);
        Map<String, T> is = byId(list(current), key);
        for (String k : union(was.keySet(), is.keySet())) {
            String change = !was.containsKey(k) ? "added" : !is.containsKey(k) ? "removed"
                    : was.get(k).equals(is.get(k)) ? null : "changed";
            if (change != null) {
                notes.add(note("corrections.master", "kind", kind, "key", k, "change", change));
            }
        }
    }

    // Check against a target

    /**
     * The changes the target dataset can take, as the client checks them: known area and shift ids, a non-empty
     * area list, a usual distribution only over the person's areas (a new area list that leaves a share of the
     * target's distribution outside it carries the distribution along), the person present, not the last person
     * excluded, demand only for known areas. Everything left out becomes a note.
     */
    public static Changes check(Changes changes, Dataset target) {
        List<Issue> notes = new ArrayList<>(changes.notes());
        Set<String> areas = list(target.areas()).stream().map(Area::id).collect(Collectors.toSet());
        Set<String> shifts = list(target.shifts()).stream().map(ShiftType::id).collect(Collectors.toSet());
        Map<String, Person> people = byId(list(target.people()), Person::id);

        List<String> excluded = new ArrayList<>();
        int remaining = people.size();
        for (String id : changes.excluded()) {
            if (!people.containsKey(id)) {
                continue; // already gone there
            }
            if (remaining == 1) {
                notes.add(note("corrections.lastPerson", "personId", id));
                continue;
            }
            excluded.add(id);
            remaining--;
        }

        Map<String, PersonChange> kept = new TreeMap<>(CLIENT_ORDER);
        changes.people().forEach((id, change) -> {
            Person t = people.get(id);
            if (t == null) {
                notes.add(note("corrections.personMissing", "personId", id));
                return;
            }
            Person p = change.person();
            List<String> fields = new ArrayList<>(change.fields());
            if (fields.contains("areaIds") && !fields.contains("shiftMix")
                    && !list(p.areaIds()).containsAll(mixAreas(t.shiftMix()))) {
                fields.add(fields.indexOf("areaIds") + 1, "shiftMix");
            }
            if (fields.contains("areaIds")) {
                List<String> ids = list(p.areaIds());
                Set<String> mix = mixAreas(fields.contains("shiftMix") ? p.shiftMix() : t.shiftMix());
                if (ids.isEmpty() || new HashSet<>(ids).size() != ids.size() || !areas.containsAll(ids)
                        || !ids.containsAll(mix)) {
                    fields.remove("areaIds");
                    notes.add(note(ids.isEmpty() ? "corrections.noArea" : "corrections.unknownArea", "personId", id));
                }
            }
            if (fields.contains("shiftMix")) {
                List<String> after = fields.contains("areaIds") ? list(p.areaIds()) : list(t.areaIds());
                if (!after.containsAll(mixAreas(p.shiftMix()))) {
                    fields.remove("shiftMix");
                    notes.add(note("corrections.mixArea", "personId", id));
                }
            }
            if (fields.contains(RESTRICTIONS) && change.restrictions().stream()
                    .anyMatch(r -> r.shiftId() != null && !shifts.contains(r.shiftId()))) {
                fields.remove(RESTRICTIONS);
                notes.add(note("corrections.unknownShift", "personId", id));
            }
            if (!fields.isEmpty()) {
                kept.put(id, new PersonChange(p, List.copyOf(fields), change.restrictions()));
            }
        });

        Map<String, List<DemandRule>> demand = new LinkedHashMap<>();
        changes.demand().forEach((area, rules) -> {
            if (areas.contains(area)) {
                demand.put(area, rules);
            } else if (!rules.isEmpty()) {
                notes.add(note("corrections.demandArea", "areaId", area));
            }
        });
        return new Changes(kept, excluded, demand, List.copyOf(notes));
    }

    // The file

    /** The corrections file (version 2) of checked changes. */
    public static ObjectNode file(Changes changes, ObjectMapper mapper) {
        ObjectNode file = mapper.createObjectNode().put("format", FORMAT).put("version", VERSION);
        ObjectNode people = file.putObject("people");
        changes.people().forEach((id, change) -> {
            ObjectNode entry = people.putObject(id);
            JsonNode person = mapper.valueToTree(change.person());
            for (String field : change.fields()) {
                if (field.equals(RESTRICTIONS)) {
                    var list = entry.putArray(RESTRICTIONS);
                    for (Restriction r : change.restrictions()) {
                        list.add(((ObjectNode) mapper.valueToTree(r)).without("personId"));
                    }
                } else if (field.equals("shiftMix")) {
                    ShiftMix mix = mix(change.person().shiftMix());
                    if (mix == null) {
                        entry.putNull(field);
                    } else {
                        // both maps as objects: a null map is not valid there
                        ObjectNode m = entry.putObject(field);
                        m.set("categories", mapper.valueToTree(mix.categoriesOrEmpty()));
                        m.set("areas", mapper.valueToTree(mix.areasOrEmpty()));
                    }
                } else if (field.equals("areaIds") || field.equals("familyRules")) {
                    entry.set(field, mapper.valueToTree(field.equals("areaIds") ? list(change.person().areaIds())
                            : list(change.person().familyRules())));
                } else {
                    entry.set(field, person.get(field));
                }
            }
        });
        for (String id : changes.excluded()) {
            people.putObject(id).put(EXCLUDE, true);
        }
        ObjectNode demand = file.putObject(DEMAND);
        changes.demand().forEach((area, rules) -> {
            var list = demand.putArray(area);
            for (DemandRule r : rules) {
                list.add(((ObjectNode) mapper.valueToTree(r)).without("areaId"));
            }
        });
        return file;
    }

    // Apply to a new import

    /**
     * Applies checked changes to a dataset: changed fields and restrictions replaced, excluded people removed with
     * every entry that refers to them (restrictions, history, absences, wishes, fixed duties, previous duties), the
     * demand rules of an area replaced where its first rule stood.
     */
    public static Dataset apply(Changes changes, Dataset target) {
        Set<String> gone = Set.copyOf(changes.excluded());
        List<Person> people = new ArrayList<>();
        for (Person t : list(target.people())) {
            PersonChange c = changes.people().get(t.id());
            if (!gone.contains(t.id())) {
                people.add(c == null ? t : merge(t, c.person(), c.fields()));
            }
        }
        List<Restriction> restrictions = new ArrayList<>();
        Set<String> replaced = new HashSet<>();
        for (Restriction r : list(target.restrictions())) {
            PersonChange c = changes.people().get(r.personId());
            if (gone.contains(r.personId())) {
                continue;
            }
            if (c != null && c.fields().contains(RESTRICTIONS)) {
                if (replaced.add(r.personId())) {
                    restrictions.addAll(c.restrictions());
                }
                continue;
            }
            restrictions.add(r);
        }
        changes.people().forEach((id, c) -> {
            if (c.fields().contains(RESTRICTIONS) && !replaced.contains(id)) {
                restrictions.addAll(c.restrictions());
            }
        });
        List<DemandRule> demand = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (DemandRule r : list(target.demand())) {
            List<DemandRule> rules = changes.demand().get(r.areaId());
            if (rules == null) {
                demand.add(r);
            } else if (placed.add(r.areaId())) {
                demand.addAll(rules);
            }
        }
        changes.demand().forEach((area, rules) -> {
            if (!placed.contains(area)) {
                demand.addAll(rules);
            }
        });
        Predicate<String> keep = id -> !gone.contains(id);
        List<Period> periods = list(target.periods()).stream().map(p -> new Period(p.month(),
                filter(p.absences(), a -> keep.test(a.personId())), filter(p.wishes(), w -> keep.test(w.personId())),
                filter(p.fixed(), f -> keep.test(f.personId())),
                filter(p.previousDuties(), d -> keep.test(d.personId())))).toList();
        List<HistoryEntry> history = filter(target.history(), h -> keep.test(h.personId()));
        return new Dataset(target.format(), target.version(), target.source(), target.areas(), target.shifts(),
                List.copyOf(demand), target.holidays(), target.absenceTypes(), List.copyOf(people),
                List.copyOf(restrictions), history, periods);
    }

    private static Person merge(Person t, Person p, List<String> fields) {
        return new Person(t.id(), t.firstName(), t.lastName(), t.shortName(),
                fields.contains("weeklyHours") ? p.weeklyHours() : t.weeklyHours(),
                fields.contains("areaIds") ? p.areaIds() : t.areaIds(),
                fields.contains("hoursAccount") ? p.hoursAccount() : t.hoursAccount(),
                fields.contains("compRestBalance") ? p.compRestBalance() : t.compRestBalance(),
                fields.contains("familyRules") ? p.familyRules() : t.familyRules(), t.externalId(),
                fields.contains("entryDate") ? p.entryDate() : t.entryDate(),
                fields.contains("exitDate") ? p.exitDate() : t.exitDate(),
                fields.contains("shiftMix") ? p.shiftMix() : t.shiftMix());
    }

    /**
     * The checked changes whose value also changed between the old baseline and the new import: per person field
     * (also {@code restrictions}), {@code exclude} when the import changed an excluded person, {@code demand} per
     * area.
     */
    public static List<Conflict> conflicts(Changes changes, Dataset oldBase, Dataset incoming) {
        Map<String, Person> was = byId(list(oldBase.people()), Person::id);
        Map<String, Person> is = byId(list(incoming.people()), Person::id);
        List<Conflict> conflicts = new ArrayList<>();
        changes.people().forEach((id, c) -> {
            for (String field : c.fields()) {
                boolean same = field.equals(RESTRICTIONS)
                        ? counts(own(oldBase.restrictions(), id)).equals(counts(own(incoming.restrictions(), id)))
                        : Objects.equals(value(was.get(id), field), value(is.get(id), field));
                if (!same) {
                    conflicts.add(new Conflict(id, null, field));
                }
            }
        });
        for (String id : changes.excluded()) {
            boolean same = FIELDS.stream().noneMatch(f -> !Objects.equals(value(was.get(id), f), value(is.get(id), f)))
                    && counts(own(oldBase.restrictions(), id)).equals(counts(own(incoming.restrictions(), id)));
            if (!same) {
                conflicts.add(new Conflict(id, null, EXCLUDE));
            }
        }
        Map<String, List<DemandRule>> before = byArea(oldBase.demand());
        Map<String, List<DemandRule>> after = byArea(incoming.demand());
        for (String area : changes.demand().keySet()) {
            if (!counts(before.getOrDefault(area, List.of())).equals(counts(after.getOrDefault(area, List.of())))) {
                conflicts.add(new Conflict(null, area, DEMAND));
            }
        }
        return conflicts;
    }

    // Values as compared: missing lists are empty, a distribution without shares is none.

    static Object value(Person p, String field) {
        return switch (field) {
            case "weeklyHours" -> p.weeklyHours();
            case "areaIds" -> list(p.areaIds());
            case "hoursAccount" -> p.hoursAccount();
            case "compRestBalance" -> p.compRestBalance();
            case "familyRules" -> list(p.familyRules());
            case "entryDate" -> p.entryDate();
            case "exitDate" -> p.exitDate();
            case "shiftMix" -> mix(p.shiftMix());
            default -> throw new IllegalArgumentException(field);
        };
    }

    private static String identity(Person p, String field) {
        return switch (field) {
            case "externalId" -> p.externalId();
            case "firstName" -> p.firstName();
            case "lastName" -> p.lastName();
            default -> p.shortName();
        };
    }

    static ShiftMix mix(ShiftMix mix) {
        if (mix == null || mix.categoriesOrEmpty().isEmpty() && mix.areasOrEmpty().isEmpty()) {
            return null;
        }
        return new ShiftMix(mix.categoriesOrEmpty(), mix.areasOrEmpty());
    }

    private static Set<String> mixAreas(ShiftMix mix) {
        return mix == null ? Set.of() : mix.areasOrEmpty().keySet();
    }

    // Helpers

    /** Like the client: ids of its own form by number first, then the others by text. */
    static final Comparator<String> CLIENT_ORDER = Comparator
            .comparing((String id) -> CLIENT_ID.matcher(id).matches() ? 0 : 1)
            .thenComparing(id -> CLIENT_ID.matcher(id).matches() ? new java.math.BigInteger(id.substring(1))
                    : java.math.BigInteger.ZERO)
            .thenComparing(Comparator.naturalOrder());

    private static List<Restriction> own(List<Restriction> restrictions, String personId) {
        return list(restrictions).stream().filter(r -> personId.equals(r.personId())).toList();
    }

    private static Map<String, List<DemandRule>> byArea(List<DemandRule> demand) {
        Map<String, List<DemandRule>> map = new LinkedHashMap<>();
        for (DemandRule r : list(demand)) {
            map.computeIfAbsent(r.areaId(), k -> new ArrayList<>()).add(r);
        }
        return map;
    }

    /** Entries with their number of occurrences: lists compared regardless of order. */
    private static <T> Map<T, Long> counts(Collection<T> items) {
        return items.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static <T> Map<String, T> byId(List<T> items, Function<T, String> id) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) {
            map.putIfAbsent(id.apply(item), item);
        }
        return map;
    }

    private static List<String> union(Set<String> a, Set<String> b) {
        List<String> all = new ArrayList<>(a);
        b.stream().filter(k -> !a.contains(k)).forEach(all::add);
        return all;
    }

    private static <T> List<T> filter(List<T> items, Predicate<T> keep) {
        return list(items).stream().filter(keep).toList();
    }

    private static <T> List<T> list(List<T> items) {
        return items == null ? List.of() : items;
    }

    private static Issue note(String code, Object... params) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < params.length; i += 2) {
            map.put((String) params[i], params[i + 1]);
        }
        return new Issue(code, map);
    }
}
