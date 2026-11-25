package app.dispofold.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The state of an area for a person ({@link Person#areaStates}): planbar (no entry), "nur von Hand" (ON_REQUEST) or
 * "nie" (NEVER) for every shift of the area on every day. Stored, planned, compared and written into files as
 * restrictions: one undated row for every day per shift of the area, as before; so the solver, the corrections file,
 * the import's comparisons and older versions read the same meaning. The pages see it folded ({@link #fold}): the
 * state, and restrictions only for the exceptions; what they save is expanded again ({@link #expand}).
 */
public final class AreaStates {

    private AreaStates() {
    }

    private static <T> List<T> list(List<T> l) {
        return l == null ? List.of() : l;
    }

    /** A restriction for every day without dates: the kind a whole-area set is made of. */
    private static boolean whole(Restriction r) {
        return r.shiftId() != null && r.weekday() == 0 && r.validFrom() == null && r.validTo() == null;
    }

    /** The shift ids of each area, in the order of the dataset. */
    private static Map<String, List<String>> shiftsByArea(Dataset d) {
        Map<String, List<String>> byArea = new LinkedHashMap<>();
        for (ShiftType s : list(d.shifts())) {
            byArea.computeIfAbsent(s.areaId(), k -> new ArrayList<>()).add(s.id());
        }
        return byArea;
    }

    /**
     * The canonical form: for each area of a person whose every shift has a whole-day restriction of one kind (NEVER
     * before ON_REQUEST), the state of that kind in place of those rows; rows a stated area repeats are dropped; a state for an area the person
     * no longer has is dropped. Everything else stays a restriction (an exception). Idempotent.
     */
    public static Dataset fold(Dataset d) {
        Map<String, List<String>> byArea = shiftsByArea(d);
        Map<String, List<Restriction>> byPerson = new HashMap<>();
        for (Restriction r : list(d.restrictions())) {
            byPerson.computeIfAbsent(r.personId(), k -> new ArrayList<>()).add(r);
        }
        List<Person> people = new ArrayList<>();
        Set<Restriction> drop = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        boolean changed = false;
        for (Person p : list(d.people())) {
            Map<String, RestrictionKind> states = new LinkedHashMap<>();
            List<Restriction> own = byPerson.getOrDefault(p.id(), List.of());
            for (String a : list(p.areaIds())) {
                List<String> shifts = byArea.getOrDefault(a, List.of());
                RestrictionKind stated = p.areaStates() == null ? null : p.areaStates().get(a);
                RestrictionKind kind = stated;
                if (kind == null && !shifts.isEmpty()) {
                    // a kind whose whole-day rows cover every shift of the area; NEVER first, as it is the stricter
                    // (rows of the other kind stay exceptions: the meaning is the same)
                    for (RestrictionKind k : List.of(RestrictionKind.NEVER, RestrictionKind.ON_REQUEST)) {
                        boolean all = true;
                        for (String s : shifts) {
                            all &= own.stream().anyMatch(r -> s.equals(r.shiftId()) && whole(r) && r.kind() == k);
                        }
                        if (all) {
                            kind = k;
                            break;
                        }
                    }
                }
                if (kind != null) {
                    states.put(a, kind);
                    for (Restriction r : own) {
                        if (shifts.contains(r.shiftId()) && whole(r) && r.kind() == kind) {
                            drop.add(r);
                        }
                    }
                }
            }
            Map<String, RestrictionKind> before = p.areaStates() == null ? Map.of() : p.areaStates();
            if (!states.equals(before)) {
                changed = true;
            }
            people.add(states.equals(before) ? p : p.withAreaStates(states.isEmpty() ? null : states));
        }
        if (!changed && drop.isEmpty()) {
            return d;
        }
        List<Restriction> restrictions = list(d.restrictions()).stream().filter(r -> !drop.contains(r)).toList();
        return new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(), d.holidays(),
                d.absenceTypes(), people, restrictions, d.history(), d.periods());
    }

    /** The restrictions with every area state as its rows (one undated, every-day row per shift of the area). */
    public static List<Restriction> expanded(Dataset d) {
        Map<String, List<String>> byArea = shiftsByArea(d);
        List<Restriction> out = new ArrayList<>(list(d.restrictions()));
        Set<Restriction> present = new HashSet<>(out);
        for (Person p : list(d.people())) {
            if (p.areaStates() == null) {
                continue;
            }
            p.areaStates().forEach((a, kind) -> {
                if (!list(p.areaIds()).contains(a)) {
                    return;
                }
                for (String s : byArea.getOrDefault(a, List.of())) {
                    Restriction r = new Restriction(p.id(), s, null, 0, kind, null, null);
                    if (present.add(r)) {
                        out.add(r);
                    }
                }
            });
        }
        return out;
    }

    /** The dataset as files and the solver see it: the states as rows, no states on the people. */
    public static Dataset expand(Dataset d) {
        boolean any = list(d.people()).stream().anyMatch(p -> p.areaStates() != null && !p.areaStates().isEmpty());
        if (!any) {
            return d;
        }
        List<Restriction> restrictions = expanded(d);
        List<Person> people = list(d.people()).stream().map(p -> p.areaStates() == null ? p : p.withAreaStates(null))
                .toList();
        return new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(), d.holidays(),
                d.absenceTypes(), people, restrictions, d.history(), d.periods());
    }
}
