package app.dispofold.plugins.sp5;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.model.Dataset;
import app.dispofold.model.DemandRule;
import app.dispofold.model.FixedAssignment;
import app.dispofold.model.Period;
import app.dispofold.model.Person;
import app.dispofold.model.Restriction;

/**
 * An earlier dataset ("old") against a run ("new"): counts per section, people added, removed and changed (every
 * field, and their restrictions), duties per month and area, and the demand rules that differ. The sections of the
 * periods count the run's months only.
 */
final class Sp5Comparison {

    private Sp5Comparison() {
    }

    static Map<String, Object> compare(ObjectMapper mapper, String against, boolean corrections, Dataset old,
            Dataset neu, List<YearMonth> months) {
        Set<YearMonth> wanted = new HashSet<>(months);
        List<Period> oldPeriods = list(old.periods()).stream().filter(p -> wanted.contains(p.month())).toList();
        List<Period> newPeriods = list(neu.periods()).stream().filter(p -> wanted.contains(p.month())).toList();
        List<Object> counts = new ArrayList<>();
        count(counts, "people", old.people(), neu.people());
        count(counts, "areas", old.areas(), neu.areas());
        count(counts, "shifts", old.shifts(), neu.shifts());
        count(counts, "demand", old.demand(), neu.demand());
        count(counts, "holidays", old.holidays(), neu.holidays());
        count(counts, "absenceTypes", old.absenceTypes(), neu.absenceTypes());
        count(counts, "restrictions", old.restrictions(), neu.restrictions());
        count(counts, "history", old.history(), neu.history());
        count(counts, "duties", oldPeriods, newPeriods, Period::fixed);
        count(counts, "previousDuties", oldPeriods, newPeriods, Period::previousDuties);
        count(counts, "absences", oldPeriods, newPeriods, Period::absences);
        count(counts, "wishes", oldPeriods, newPeriods, Period::wishes);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("against", against);
        result.put("corrections", corrections);
        result.put("counts", counts);
        result.put("people", people(mapper, old, neu));
        result.put("duties", duties(old, neu, oldPeriods, newPeriods));
        result.put("demand", demand(mapper, old, neu));
        return result;
    }

    private static <T> List<T> list(List<T> items) {
        return items == null ? List.of() : items;
    }

    private static void count(List<Object> counts, String section, List<?> old, List<?> neu) {
        counts.add(row("section", section, "old", list(old).size(), "new", list(neu).size()));
    }

    private static void count(List<Object> counts, String section, List<Period> old, List<Period> neu,
            Function<Period, List<?>> items) {
        counts.add(row("section", section, "old", old.stream().mapToInt(p -> list(items.apply(p)).size()).sum(),
                "new", neu.stream().mapToInt(p -> list(items.apply(p)).size()).sum()));
    }

    private static Map<String, Object> row(Object... keysAndValues) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            r.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return r;
    }

    private static Map<String, Object> person(Person p) {
        String name = (Objects.toString(p.lastName(), "") + " " + Objects.toString(p.firstName(), "")).strip();
        return row("id", p.id(), "name", name);
    }

    private static Map<String, Object> people(ObjectMapper mapper, Dataset old, Dataset neu) {
        Map<String, Person> before = new LinkedHashMap<>();
        list(old.people()).forEach(p -> before.put(p.id(), p));
        Map<String, Person> after = new LinkedHashMap<>();
        list(neu.people()).forEach(p -> after.put(p.id(), p));
        Map<String, List<JsonNode>> oldRestrictions = restrictions(mapper, old);
        Map<String, List<JsonNode>> newRestrictions = restrictions(mapper, neu);
        List<Object> added = new ArrayList<>();
        List<Object> changed = new ArrayList<>();
        for (Person p : after.values()) {
            Person q = before.get(p.id());
            if (q == null) {
                added.add(person(p));
                continue;
            }
            ObjectNode a = mapper.valueToTree(q);
            ObjectNode b = mapper.valueToTree(p);
            Set<String> keys = new LinkedHashSet<>();
            b.fieldNames().forEachRemaining(keys::add);
            a.fieldNames().forEachRemaining(keys::add);
            List<Object> fields = new ArrayList<>();
            for (String k : keys) {
                JsonNode x = a.has(k) ? a.get(k) : NullNode.getInstance();
                JsonNode y = b.has(k) ? b.get(k) : NullNode.getInstance();
                if (!x.equals(y)) {
                    fields.add(row("field", k, "old", x, "new", y));
                }
            }
            List<JsonNode> removedRestrictions = new ArrayList<>(oldRestrictions.getOrDefault(p.id(), List.of()));
            List<JsonNode> addedRestrictions = new ArrayList<>();
            for (JsonNode r : newRestrictions.getOrDefault(p.id(), List.of())) {
                if (!removedRestrictions.remove(r)) {
                    addedRestrictions.add(r);
                }
            }
            if (!addedRestrictions.isEmpty() || !removedRestrictions.isEmpty()) {
                fields.add(row("field", "restrictions", "added", addedRestrictions, "removed", removedRestrictions));
            }
            if (!fields.isEmpty()) {
                Map<String, Object> c = person(p);
                c.put("fields", fields);
                changed.add(c);
            }
        }
        List<Object> removed = before.values().stream().filter(p -> !after.containsKey(p.id()))
                .map(Sp5Comparison::person).map(Object.class::cast).toList();
        return row("added", added, "removed", removed, "changed", changed);
    }

    private static Map<String, List<JsonNode>> restrictions(ObjectMapper mapper, Dataset d) {
        Map<String, List<JsonNode>> byPerson = new HashMap<>();
        for (Restriction r : list(d.restrictions())) {
            byPerson.computeIfAbsent(r.personId(), k -> new ArrayList<>()).add(mapper.valueToTree(r));
        }
        return byPerson;
    }

    /** Duties per month and area of their shift (null: a shift the dataset does not have); rows that differ. */
    private static List<Object> duties(Dataset old, Dataset neu, List<Period> oldPeriods, List<Period> newPeriods) {
        Map<List<String>, int[]> counts = new HashMap<>();
        tally(counts, old, oldPeriods, 0);
        tally(counts, neu, newPeriods, 1);
        Comparator<String> nullsLast = Comparator.nullsLast(Comparator.naturalOrder());
        return counts.entrySet().stream().filter(e -> e.getValue()[0] != e.getValue()[1])
                .sorted(Comparator.<Map.Entry<List<String>, int[]>, String>comparing(e -> e.getKey().get(0))
                        .thenComparing(e -> e.getKey().get(1), nullsLast))
                .map(e -> (Object) row("month", e.getKey().get(0), "areaId", e.getKey().get(1), "old",
                        e.getValue()[0], "new", e.getValue()[1]))
                .toList();
    }

    private static void tally(Map<List<String>, int[]> counts, Dataset d, List<Period> periods, int side) {
        Map<String, String> areaOf = new HashMap<>();
        list(d.shifts()).forEach(s -> areaOf.put(s.id(), s.areaId()));
        for (Period p : periods) {
            for (FixedAssignment f : list(p.fixed())) {
                List<String> key = Arrays.asList(p.month().toString(), areaOf.get(f.shiftId()));
                counts.computeIfAbsent(key, k -> new int[2])[side]++;
            }
        }
    }

    /** Demand rules grouped by area, date, days and times; the groups whose sorted {min, max} differ. */
    private static List<Object> demand(ObjectMapper mapper, Dataset old, Dataset neu) {
        Map<String, ObjectNode> keys = new HashMap<>();
        Map<String, List<List<Integer>>> before = groups(mapper, old, keys);
        Map<String, List<List<Integer>>> after = groups(mapper, neu, keys);
        Set<String> all = new HashSet<>(before.keySet());
        all.addAll(after.keySet());
        Comparator<JsonNode> text = Comparator.comparing(n -> n.isNull() ? "" : n.toString());
        Comparator<ObjectNode> order = Comparator.<ObjectNode, JsonNode>comparing(k -> k.get("areaId"), text)
                .thenComparing(k -> k.get("date"), text).thenComparing(k -> k.get("from"), text)
                .thenComparing(k -> k.get("to"), text).thenComparing(k -> k.get("days"), text);
        return all.stream().filter(k -> !before.getOrDefault(k, List.of()).equals(after.getOrDefault(k, List.of())))
                .map(keys::get).sorted(order).map(k -> {
                    String key = k.toString();
                    return (Object) row("areaId", k.get("areaId"), "days", k.get("days"), "date", k.get("date"),
                            "from", k.get("from"), "to", k.get("to"), "old", bounds(before.get(key)), "new",
                            bounds(after.get(key)));
                }).toList();
    }

    private static Map<String, List<List<Integer>>> groups(ObjectMapper mapper, Dataset d,
            Map<String, ObjectNode> keys) {
        Comparator<Integer> nullsLast = Comparator.nullsLast(Comparator.naturalOrder());
        Map<String, List<List<Integer>>> groups = new HashMap<>();
        for (DemandRule r : list(d.demand())) {
            ObjectNode node = mapper.valueToTree(r);
            ObjectNode key = mapper.createObjectNode();
            for (String k : List.of("areaId", "date", "days", "from", "to")) {
                key.set(k, node.has(k) ? node.get(k) : NullNode.getInstance());
            }
            keys.put(key.toString(), key);
            groups.computeIfAbsent(key.toString(), k -> new ArrayList<>()).add(Arrays.asList(r.min(),
                    r.max()));
        }
        groups.values().forEach(l -> l.sort(Comparator.<List<Integer>, Integer>comparing(b -> b.get(0))
                .thenComparing(b -> b.get(1), nullsLast)));
        return groups;
    }

    private static List<Object> bounds(List<List<Integer>> list) {
        return list == null ? List.of() : list.stream().map(b -> (Object) row("min", b.get(0), "max", b.get(1)))
                .toList();
    }
}
