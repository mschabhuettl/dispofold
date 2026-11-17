package app.dispofold.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import app.dispofold.model.Dataset;
import app.dispofold.model.Person;
import app.dispofold.model.Plan;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.PlanStatus;

/** Pseudonymised dataset and plan: every person reference replaced, all free text neutral, the leak check. */
class AnonymizerTest {

    static final ObjectMapper MAPPER = CorrectionsTest.mapper();

    /**
     * Every text field of the Dataset and Plan models and how the anonymisation treats it. A new text field fails
     * {@link #everyTextFieldIsClassified()} until it is handled and listed here.
     */
    static final Map<String, String> TEXT_FIELDS = new TreeMap<>(Map.ofEntries(
            Map.entry("Dataset.format", "fixed value"),
            Map.entry("Dataset.source", "replaced by a fixed text"),
            Map.entry("Area.id", "id, kept"), Map.entry("Area.name", "replaced by the id"),
            Map.entry("Area.color", "colour, kept"),
            Map.entry("ShiftType.id", "id, kept"), Map.entry("ShiftType.areaId", "id, kept"),
            Map.entry("ShiftType.code", "code, kept (leak check)"), Map.entry("ShiftType.name", "replaced by the code"),
            Map.entry("ShiftType.color", "colour, kept"),
            Map.entry("DemandRule.areaId", "id, kept"), Map.entry("DemandRule.days", "day codes, kept"),
            Map.entry("Holiday.name", "replaced by a fixed text"),
            Map.entry("AbsenceType.code", "code, kept (leak check)"),
            Map.entry("AbsenceType.name", "replaced by the code"), Map.entry("AbsenceType.color", "colour, kept"),
            Map.entry("Person.id", "pseudonym"), Map.entry("Person.firstName", "pseudonym"),
            Map.entry("Person.lastName", "pseudonym"), Map.entry("Person.shortName", "pseudonym"),
            Map.entry("Person.externalId", "pseudonym"), Map.entry("Person.areaIds", "ids, kept"),
            Map.entry("FamilyRule.note", "cleared"),
            Map.entry("ShiftMix.areas", "area ids as keys, kept"),
            Map.entry("Restriction.personId", "pseudonym"), Map.entry("Restriction.shiftId", "id, kept"),
            Map.entry("HistoryEntry.personId", "pseudonym"), Map.entry("HistoryEntry.month", "month, kept"),
            Map.entry("HistoryEntry.source", "import or plan, kept"),
            Map.entry("Absence.personId", "pseudonym"), Map.entry("Absence.typeCode", "code, kept"),
            Map.entry("Wish.personId", "pseudonym"), Map.entry("Wish.shiftId", "id, kept"),
            Map.entry("FixedAssignment.personId", "pseudonym"), Map.entry("FixedAssignment.shiftId", "id, kept"),
            Map.entry("PreviousDuty.personId", "pseudonym"), Map.entry("PreviousDuty.shiftId", "id, kept"),
            Map.entry("Plan.format", "fixed value"), Map.entry("Plan.score", "score, kept"),
            Map.entry("PlanAssignment.personId", "pseudonym"), Map.entry("PlanAssignment.shiftId", "id, kept")));

    @Test
    void everyTextFieldIsClassified() {
        Set<String> found = new java.util.TreeSet<>();
        texts(Dataset.class, found, new HashSet<>());
        texts(Plan.class, found, new HashSet<>());
        assertThat(found).isEqualTo(TEXT_FIELDS.keySet());
    }

    /** String components, lists of strings and maps with string keys of every record reachable from {@code type}. */
    private static void texts(Class<?> type, Set<String> found, Set<Class<?>> seen) {
        if (!type.isRecord() || !seen.add(type)) {
            return;
        }
        for (RecordComponent c : type.getRecordComponents()) {
            String name = type.getSimpleName() + "." + c.getName();
            Type generic = c.getGenericType();
            if (c.getType() == String.class) {
                found.add(name);
            } else if (generic instanceof ParameterizedType p) {
                for (Type arg : p.getActualTypeArguments()) {
                    if (arg == String.class) {
                        found.add(name);
                    } else if (arg instanceof Class<?> k) {
                        texts(k, found, seen);
                    }
                }
            } else {
                texts(c.getType(), found, seen);
            }
        }
    }

    static Dataset demo() throws Exception {
        ObjectNode d = (ObjectNode) MAPPER.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("..",
                "tests", "fixtures", "synthetic", "demo-dataset-2027-01.synthetic.json")));
        return MAPPER.treeToValue(d, Dataset.class);
    }

    static Plan plan(Dataset d) {
        List<PlanAssignment> cells = new ArrayList<>();
        for (Person p : d.people().subList(0, 20)) {
            cells.add(new PlanAssignment(p.id(), LocalDate.of(2027, 1, 4), "AN-T", false, false));
        }
        // a person no longer in the master data
        cells.add(new PlanAssignment("X0999", LocalDate.of(2027, 1, 5), "AN-T", false, true));
        return new Plan(Plan.FORMAT, 1, YearMonth.of(2027, 1), PlanStatus.SOLVED, "0hard/0medium/-5soft", 1,
                Instant.parse("2027-01-02T10:00:00Z"), cells);
    }

    @Test
    void everyPersonReferenceAndNameIsReplaced() throws Exception {
        Dataset d = demo();
        Plan plan = plan(d);
        Anonymizer.Result r = Anonymizer.anonymize(d, plan, MAPPER);

        assertThat(r.people()).isEqualTo(150);
        assertThat(r.leaks()).isEmpty();
        assertThat(r.dataset().people()).extracting(Person::id).startsWith("P001", "P002").contains("P150");
        assertThat(r.dataset().people().get(0).firstName()).isEqualTo("P001");
        assertThat(r.dataset().people().get(0).externalId()).isEqualTo("P001");
        assertThat(r.plan().assignments().get(20).personId()).as("stray reference").isEqualTo("P151");

        // no original id, external id or name survives as any text in the shared files
        Set<String> secrets = new HashSet<>();
        for (Person p : d.people()) {
            Stream.of(p.id(), p.externalId(), p.firstName(), p.lastName(), p.shortName()).forEach(secrets::add);
        }
        secrets.add("X0999");
        List<String> texts = new ArrayList<>();
        strings(MAPPER.valueToTree(r.dataset()), texts);
        strings(MAPPER.valueToTree(r.plan()), texts);
        assertThat(texts).isNotEmpty().noneMatch(secrets::contains);
        String shared = MAPPER.writeValueAsString(r.dataset()) + MAPPER.writeValueAsString(r.plan());
        assertThat(d.people()).allSatisfy(p -> assertThat(Anonymizer.containsWord(shared,
                Stream.of(p.lastName(), p.firstName(), p.shortName()).filter(n -> n.length() > 2).toList()))
                .as(p.id()).isFalse());

        // free text neutral
        assertThat(r.dataset().source()).isEqualTo("anonymisiert");
        assertThat(r.dataset().people()).allSatisfy(p -> assertThat(p.familyRules()).allSatisfy(
                f -> assertThat(f.note()).isNull()));
        assertThat(r.dataset().areas()).allSatisfy(a -> assertThat(a.name()).isEqualTo(a.id()));
        assertThat(r.dataset().shifts()).allSatisfy(s -> assertThat(s.name()).isEqualTo(s.code()));
        assertThat(r.dataset().absenceTypes()).allSatisfy(t -> assertThat(t.name()).isEqualTo(t.code()));
        assertThat(r.dataset().holidays()).allSatisfy(h -> assertThat(h.name()).isEqualTo("Feiertag"));
        // the structure stays, and the shared dataset can be imported
        assertThat(app.dispofold.importclient.DatasetValidator.validate(r.dataset(),
                app.dispofold.model.Rules.defaults()).errors()).isEmpty();
        assertThat(r.dataset().restrictions()).hasSameSizeAs(d.restrictions());
        assertThat(r.dataset().history()).hasSameSizeAs(d.history());
        assertThat(r.dataset().periods().get(0).absences()).hasSameSizeAs(d.periods().get(0).absences());

        // the key: separate, with the names
        String[] lines = r.key().split("\r\n");
        assertThat(lines[0]).isEqualTo("﻿pseudonym;id;externe_id;nachname;vorname;kuerzel");
        Person first = d.people().stream().min((a, b) -> a.id().compareTo(b.id())).orElseThrow();
        assertThat(lines[1]).isEqualTo(String.join(";", "P001", first.id(), first.externalId(), first.lastName(),
                first.firstName(), first.shortName()));
        assertThat(lines).hasSize(152).contains("P151;X0999;;;;");
        assertThat(shared).doesNotContain("pseudonym;id");
    }

    @Test
    void aNameLeftInTheFilesIsFound() throws Exception {
        Dataset d = demo();
        Person p = d.people().get(4);
        var shifts = new ArrayList<>(d.shifts());
        var s = shifts.get(1);
        shifts.set(1, new app.dispofold.model.ShiftType(s.id(), s.areaId(), p.lastName(), s.name(), s.start(),
                s.end(), s.category(), s.color(), s.unpopular()));
        Dataset leaking = new Dataset(d.format(), d.version(), d.source(), d.areas(), shifts, d.demand(),
                d.holidays(), d.absenceTypes(), d.people(), d.restrictions(), d.history(), d.periods());
        Anonymizer.Result r = Anonymizer.anonymize(leaking, plan(d), MAPPER);
        assertThat(r.leaks()).containsExactly("dataset.shifts[1].code", "dataset.shifts[1].name");
        assertThat(String.join(" ", r.leaks())).doesNotContain(p.lastName());
        // as a part of a longer word it is no leak
        assertThat(Anonymizer.containsWord("Vor" + p.lastName(), List.of(p.lastName()))).isFalse();
        assertThat(Anonymizer.containsWord("A-" + p.lastName() + " 2", List.of(p.lastName()))).isTrue();
    }

    private static void strings(JsonNode node, List<String> out) {
        if (node.isTextual()) {
            out.add(node.asText());
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> e = it.next();
            out.add(e.getKey());
            strings(e.getValue(), out);
        }
        if (node.isArray()) {
            node.forEach(x -> strings(x, out));
        }
    }
}
