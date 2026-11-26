package app.dispofold.export;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.model.Absence;
import app.dispofold.model.AbsenceType;
import app.dispofold.model.Area;
import app.dispofold.model.Dataset;
import app.dispofold.model.FamilyRule;
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

/**
 * A pseudonymised copy of the dataset and a plan for sharing, with a separate key file. People sorted by id get
 * P001, P002, ...; their id, external id, first, last and short name become the pseudonym and every person reference
 * is replaced (references to people no longer in the master data get further pseudonyms). Every other free text is
 * neutralised: the source, area, shift and absence type names (replaced by their id or code), holiday names, family
 * rule notes (cleared). Ids and codes of areas, shifts and absence types, numbers, dates and colours stay.
 * <p>
 * Leak check: no name or short name of the key longer than two characters may remain as a word in the shared
 * files; {@link Result#leaks()} names the places (JSON paths, never the names).
 */
public final class Anonymizer {

    public static final String KEY_HEADER = "pseudonym;id;externe_id;nachname;vorname;kuerzel";
    static final String SOURCE = "anonymisiert";
    static final String HOLIDAY = "Feiertag";

    /**
     * @param key the key file (CSV with {@code ;}, UTF-8 with BOM), never part of the shared files
     * @param leaks JSON paths of the shared files where a name of the key still occurs as a word
     */
    public record Result(Dataset dataset, Plan plan, String key, int people, List<String> leaks) {
    }

    private Anonymizer() {
    }

    public static Result anonymize(Dataset d, Plan plan, ObjectMapper mapper) {
        Map<String, Person> people = new LinkedHashMap<>();
        list(d.people()).stream().sorted((a, b) -> a.id().compareTo(b.id())).forEach(p -> people.putIfAbsent(p.id(), p));
        // Person references without a person (e.g. in old months) are replaced as well, after the people.
        TreeSet<String> strays = new TreeSet<>();
        references(d, plan).filter(id -> id != null && !people.containsKey(id)).forEach(strays::add);
        Map<String, String> pseudonym = new LinkedHashMap<>();
        for (String id : Stream.concat(people.keySet().stream(), strays.stream()).toList()) {
            pseudonym.put(id, String.format("P%03d", pseudonym.size() + 1));
        }
        Function<String, String> map = id -> id == null ? null : pseudonym.get(id);

        StringBuilder key = new StringBuilder("﻿").append(KEY_HEADER).append("\r\n");
        List<String> names = new ArrayList<>();
        pseudonym.forEach((id, p) -> {
            Person person = people.get(id);
            String[] row = person == null ? new String[] { p, id, "", "", "", "" }
                    : new String[] { p, id, person.externalId(), person.lastName(), person.firstName(),
                            person.shortName() };
            key.append(String.join(";", Stream.of(row).map(Anonymizer::csv).toList())).append("\r\n");
            if (person != null) {
                Stream.of(person.lastName(), person.firstName(), person.shortName())
                        .filter(n -> n != null && n.codePointCount(0, n.length()) > 2).forEach(names::add);
            }
        });

        List<Person> persons = people.values().stream().map(p -> {
            String id = map.apply(p.id());
            return new Person(id, id, id, id, p.weeklyHours(), p.areaIds(), p.hoursAccount(), p.compRestBalance(),
                    list(p.familyRules()).stream().map(f -> new FamilyRule(f.weekdays(), f.latestEnd(),
                            f.earliestStart(), f.noNights(), null)).toList(),
                    id, p.entryDate(), p.exitDate(), p.shiftMix(), p.excludedFrom(), null, null);
        }).toList();
        Dataset dataset = new Dataset(d.format(), d.version(), SOURCE,
                list(d.areas()).stream().map(a -> new Area(a.id(), a.id(), a.color())).toList(),
                list(d.shifts()).stream().map(s -> new ShiftType(s.id(), s.areaId(), s.code(), s.code(), s.start(),
                        s.end(), s.category(), s.color(), s.unpopular(), s.usage())).toList(),
                d.demand(),
                list(d.holidays()).stream().map(h -> new Holiday(h.date(), HOLIDAY)).toList(),
                list(d.absenceTypes()).stream().map(t -> new AbsenceType(t.code(), t.code(), t.reducesTarget(),
                        t.chargesAccount(), t.compensatoryRest(), t.color(), t.credit())).toList(),
                persons,
                list(d.restrictions()).stream().map(r -> new Restriction(map.apply(r.personId()), r.shiftId(),
                        r.category(), r.weekday(), r.kind(), r.validFrom(), r.validTo())).toList(),
                list(d.history()).stream().map(h -> new HistoryEntry(map.apply(h.personId()), h.month(), h.source(),
                        h.nights(), h.weekendsWorked(), h.holidays(), h.unpopular(), h.wishes(), h.wishesMet(),
                        h.hours(), h.hoursAccountAfter(), h.compRestBalanceAfter(), h.compRestUsed(),
                        h.compRestEarned(), h.weeklyHours())).toList(),
                list(d.periods()).stream().map(p -> new Period(p.month(),
                        list(p.absences()).stream().map(a -> new Absence(map.apply(a.personId()), a.date(),
                                a.typeCode(), a.replacedHours())).toList(),
                        list(p.wishes()).stream().map(w -> new Wish(map.apply(w.personId()), w.date(), w.kind(),
                                w.shiftId())).toList(),
                        list(p.fixed()).stream().map(f -> new FixedAssignment(map.apply(f.personId()), f.date(),
                                f.shiftId())).toList(),
                        list(p.previousDuties()).stream().map(v -> new PreviousDuty(map.apply(v.personId()),
                                v.date(), v.shiftId())).toList())).toList());
        Plan shared = plan == null ? null : new Plan(plan.format(), plan.version(), plan.month(), plan.status(),
                plan.score(), plan.seed(), plan.updated(), list(plan.assignments()).stream()
                        .map(a -> new PlanAssignment(map.apply(a.personId()), a.date(), a.shiftId(), a.compRest(),
                                a.pinned()))
                        .toList());

        List<String> leaks = new ArrayList<>();
        leaks(mapper.valueToTree(dataset), "dataset", names, leaks);
        if (shared != null) {
            leaks(mapper.valueToTree(shared), "plan", names, leaks);
        }
        return new Result(dataset, shared, key.toString(), people.size(), leaks);
    }

    /** Every person id the dataset and the plan refer to. */
    private static Stream<String> references(Dataset d, Plan plan) {
        Stream<String> ids = Stream.of(
                list(d.restrictions()).stream().map(Restriction::personId),
                list(d.history()).stream().map(HistoryEntry::personId),
                list(d.periods()).stream().flatMap(p -> Stream.of(
                        list(p.absences()).stream().map(Absence::personId),
                        list(p.wishes()).stream().map(Wish::personId),
                        list(p.fixed()).stream().map(FixedAssignment::personId),
                        list(p.previousDuties()).stream().map(PreviousDuty::personId)).flatMap(s -> s)))
                .flatMap(s -> s);
        return plan == null ? ids
                : Stream.concat(ids, list(plan.assignments()).stream().map(PlanAssignment::personId));
    }

    /** Collects the paths of text values and field names in which one of the names occurs as a word. */
    private static void leaks(JsonNode node, String path, List<String> names, List<String> leaks) {
        if (node.isTextual()) {
            if (containsWord(node.asText(), names)) {
                leaks.add(path);
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                leaks(node.get(i), path + "[" + i + "]", names, leaks);
            }
        } else if (node.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
                Map.Entry<String, JsonNode> e = it.next();
                if (containsWord(e.getKey(), names)) {
                    // the key itself may be the name: the path ends before it
                    leaks.add(path);
                } else {
                    leaks(e.getValue(), path + "." + e.getKey(), names, leaks);
                }
            }
        }
    }

    /** One of the names as a whole word (not inside a longer word or number), case-sensitive. */
    static boolean containsWord(String text, List<String> names) {
        for (String name : names) {
            if (text.contains(name) && Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(name)
                    + "(?![\\p{L}\\p{N}_])").matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        return value.contains(";") || value.contains("\"") || value.contains("\n") || value.contains("\r")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }

    private static <T> List<T> list(List<T> items) {
        return items == null ? List.of() : items;
    }
}
