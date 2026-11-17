package app.dispofold.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import app.dispofold.api.JsonConfig;
import app.dispofold.api.TestData;
import app.dispofold.export.Corrections.Changes;
import app.dispofold.export.Corrections.Conflict;
import app.dispofold.importclient.Issue;
import app.dispofold.model.Dataset;
import app.dispofold.model.DemandRule;
import app.dispofold.model.Person;
import app.dispofold.model.Restriction;

/**
 * The corrections file: diff against the baseline, checked like the export client checks it, applied to the baseline
 * by a separate port of the client ({@link ClientApply}) gives Dispofold's master data in every covered field.
 * Synthetic demo data with invented edits; the ids are given the client's form (E and a number).
 */
class CorrectionsTest {

    static final ObjectMapper MAPPER = mapper();

    static ObjectMapper mapper() {
        ObjectMapper m = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        new JsonConfig().customize(m);
        return m;
    }

    /** The demo dataset with person ids E10001, E10002, ... instead of P0001, P0002, ... */
    static ObjectNode demo() throws Exception {
        String text = Files.readString(Path.of("..", "tests", "fixtures", "synthetic",
                "demo-dataset-2027-01.synthetic.json"));
        return (ObjectNode) MAPPER.readTree(text.replaceAll("\"P(\\d{4})\"", "\"E1$1\""));
    }

    static ObjectNode person(ObjectNode d, int index) {
        return (ObjectNode) d.withArray("people").get(index);
    }

    static String id(ObjectNode d, int index) {
        return person(d, index).get("id").asText();
    }

    static ObjectNode mix(String categories, String areas) throws Exception {
        return (ObjectNode) MAPPER.readTree("{\"categories\": " + categories + ", \"areas\": " + areas + "}");
    }

    /** Baseline: the demo with usual distributions for some people. */
    static ObjectNode baseline() throws Exception {
        ObjectNode base = demo();
        person(base, 2).set("areaIds", MAPPER.readTree("[\"DR\", \"TS\"]"));
        person(base, 2).set("shiftMix", mix("{\"DAY\": 0.7, \"NIGHT\": 0.3}", "{\"DR\": 0.6, \"TS\": 0.4}"));
        person(base, 3).set("areaIds", MAPPER.readTree("[\"AN\"]"));
        person(base, 3).set("shiftMix", mix("{\"DAY\": 1.0}", "{\"AN\": 1.0}"));
        person(base, 5).set("shiftMix", mix("{\"EARLY\": 0.5, \"LATE\": 0.5}", "{}"));
        return base;
    }

    /** Invented edits made "in Dispofold". */
    static ObjectNode edited(ObjectNode base) throws Exception {
        ObjectNode d = base.deepCopy();
        person(d, 0).put("weeklyHours", 30.0).put("hoursAccount", 12.5).put("compRestBalance", 2);
        ArrayNode areas = person(d, 1).withArray("areaIds");
        areas.removeAll();
        areas.add("AN").add("LF");
        // an area dropped that has a share: the distribution is rescaled as Dispofold does it
        person(d, 2).set("areaIds", MAPPER.readTree("[\"DR\"]"));
        person(d, 2).set("shiftMix", mix("{\"DAY\": 0.7, \"NIGHT\": 0.3}", "{\"DR\": 1.0}"));
        // an area added, the distribution stays
        person(d, 3).set("areaIds", MAPPER.readTree("[\"AN\", \"TV\"]"));
        person(d, 4).put("weeklyHours", 20.0);
        person(d, 5).putNull("shiftMix");
        String withRules = id(d, 6);
        ArrayNode restrictions = d.withArray("restrictions");
        for (int i = restrictions.size() - 1; i >= 0; i--) {
            if (restrictions.get(i).get("personId").asText().equals(withRules)) {
                restrictions.remove(i);
            }
        }
        restrictions.add(MAPPER.readTree("{\"personId\": \"" + withRules + "\", \"shiftId\": null, \"category\": "
                + "\"NIGHT\", \"weekday\": 5, \"kind\": \"NEVER\", \"validFrom\": \"2027-01-01\", \"validTo\": "
                + "\"2027-06-30\"}"));
        restrictions.add(MAPPER.readTree("{\"personId\": \"" + withRules + "\", \"shiftId\": \"AN-T\", \"category\": "
                + "null, \"weekday\": 0, \"kind\": \"ON_REQUEST\", \"validFrom\": null, \"validTo\": null}"));
        person(d, 7).set("familyRules", MAPPER.readTree("[{\"weekdays\": [2, 4], \"latestEnd\": \"18:00\", "
                + "\"earliestStart\": null, \"noNights\": false, \"note\": \"Kinderbetreuung\"}]"));
        person(d, 8).put("entryDate", "2026-03-01").put("exitDate", "2027-06-30");
        // removed in Dispofold (with its restrictions, as the removal does)
        String removed = id(d, 9);
        d.withArray("people").remove(9);
        for (int i = restrictions.size() - 1; i >= 0; i--) {
            if (restrictions.get(i).get("personId").asText().equals(removed)) {
                restrictions.remove(i);
            }
        }
        // changes the file cannot express
        person(d, 10).put("shortName", "XYZ");
        d.withArray("people").add(person(d, 11).deepCopy().put("id", "PN1"));
        ((ObjectNode) d.withArray("shifts").get(0)).put("name", "Anders benannt");
        // demand: one area changed, one area's rules removed
        ArrayNode demand = d.withArray("demand");
        for (JsonNode r : demand) {
            if (r.get("areaId").asText().equals("LF")) {
                ((ObjectNode) r).put("min", 2).put("max", 2);
                break;
            }
        }
        for (int i = demand.size() - 1; i >= 0; i--) {
            if (demand.get(i).get("areaId").asText().equals("TS")) {
                demand.remove(i);
            }
        }
        return d;
    }

    static Dataset dataset(JsonNode node) throws Exception {
        return MAPPER.treeToValue(node, Dataset.class);
    }

    @Test
    void roundTripThroughTheClient() throws Exception {
        ObjectNode baseJson = baseline();
        ObjectNode currentJson = edited(baseJson);
        Dataset base = dataset(baseJson);
        Dataset current = dataset(currentJson);
        String removed = id(baseJson, 9);

        Corrections.Export export = Corrections.export(base, current, MAPPER);
        JsonNode file = export.file();
        // shape the client expects
        assertThat(file.get("format").asText()).isEqualTo("dispofold/corrections");
        assertThat(file.get("version").asInt()).isEqualTo(2);
        JsonNode people = file.get("people");
        assertThat(people.get(id(baseJson, 2)).has("shiftMix")).as("dropped area carries the distribution").isTrue();
        assertThat(people.get(id(baseJson, 3)).has("shiftMix")).as("added area alone").isFalse();
        assertThat(people.get(id(baseJson, 5)).get("shiftMix").isNull()).isTrue();
        assertThat(people.get(removed).toString()).isEqualTo("{\"exclude\":true}");
        assertThat(people.has("PN1")).isFalse();
        people.forEach(e -> e.path("restrictions").forEach(r -> assertThat(r.has("personId")).isFalse()));
        file.get("demand").forEach(rules -> rules.forEach(r -> assertThat(r.has("areaId")).isFalse()));
        assertThat(file.get("demand").get("TS")).isEmpty();
        assertThat(file.toString()).doesNotContain("XYZ").doesNotContain("firstName").doesNotContain("lastName");
        assertThat(export.notes()).contains(new Issue("corrections.personAdded", Map.of("personId", "PN1")),
                new Issue("corrections.identity", Map.of("personId", id(currentJson, 10), "field", "shortName")),
                new Issue("corrections.master", Map.of("kind", "shift", "key", "AN-F", "change", "changed")));
        assertThat(export.notes()).noneMatch(n -> n.code().equals("corrections.idFormat"));

        ObjectNode applied = baseJson.deepCopy();
        ClientApply.Outcome outcome = ClientApply.apply(applied, file);
        assertThat(outcome.skipped()).isEmpty();
        assertThat(outcome.applied()).hasSize(export.count());

        Dataset result = dataset(applied);
        Map<String, Person> got = byId(result.people());
        for (Person p : current.people()) {
            if (p.id().equals("PN1")) {
                assertThat(got).doesNotContainKey("PN1");
                continue;
            }
            for (String field : Corrections.FIELDS) {
                assertThat(Corrections.value(got.get(p.id()), field)).as(p.id() + " " + field)
                        .isEqualTo(Corrections.value(p, field));
            }
            assertThat(counts(own(result.restrictions(), p.id()))).as(p.id() + " restrictions")
                    .isEqualTo(counts(own(current.restrictions(), p.id())));
        }
        assertThat(got).doesNotContainKey(removed).hasSize(current.people().size() - 1);
        assertThat(applied.toString()).doesNotContain("\"" + removed + "\"");
        assertThat(byArea(result.demand())).isEqualTo(byArea(current.demand()));

        String out = System.getProperty("corrections.out");
        if (out != null) {
            // for a manual run of the real client: baseline, file and Dispofold's master data
            Files.createDirectories(Path.of(out));
            MAPPER.writeValue(Path.of(out, "baseline.json").toFile(), baseJson);
            MAPPER.writeValue(Path.of(out, "korrekturen.json").toFile(), file);
            MAPPER.writeValue(Path.of(out, "dispofold.json").toFile(), currentJson);
        }
    }

    @Test
    void whatTheTargetCannotTakeBecomesANote() throws Exception {
        ObjectNode baseJson = baseline();
        ObjectNode d = baseJson.deepCopy();
        // an area and a shift that exist only in Dispofold
        d.withArray("areas").add(MAPPER.readTree("{\"id\": \"NB\", \"name\": \"Neu\", \"color\": \"#000000\"}"));
        d.withArray("shifts").add(MAPPER.readTree("{\"id\": \"SN1\", \"areaId\": \"NB\", \"code\": \"N1\", "
                + "\"name\": \"n\", \"start\": \"07:00\", \"end\": \"15:00\", \"category\": null, \"color\": null, "
                + "\"unpopular\": false}"));
        person(d, 0).set("areaIds", MAPPER.readTree("[\"NB\"]"));
        d.withArray("restrictions").add(MAPPER.readTree("{\"personId\": \"" + id(d, 1) + "\", \"shiftId\": \"SN1\", "
                + "\"category\": null, \"weekday\": 0, \"kind\": \"NEVER\", \"validFrom\": null, \"validTo\": null}"));
        d.withArray("demand").add(MAPPER.readTree("{\"areaId\": \"NB\", \"days\": [\"MON\"], \"date\": null, "
                + "\"from\": \"07:00\", \"to\": \"15:00\", \"min\": 1, \"max\": null}"));
        person(d, 3).set("areaIds", MAPPER.readTree("[]"));
        person(d, 3).putNull("shiftMix");

        Corrections.Export export = Corrections.export(dataset(baseJson), dataset(d), MAPPER);
        assertThat(export.notes()).contains(new Issue("corrections.unknownArea", Map.of("personId", id(d, 0))),
                new Issue("corrections.unknownShift", Map.of("personId", id(d, 1))),
                new Issue("corrections.demandArea", Map.of("areaId", "NB")),
                new Issue("corrections.noArea", Map.of("personId", id(d, 3))),
                new Issue("corrections.master", Map.of("kind", "area", "key", "NB", "change", "added")));
        JsonNode people = export.file().get("people");
        assertThat(people.has(id(d, 0))).isFalse();
        assertThat(people.has(id(d, 1))).isFalse();
        assertThat(people.get(id(d, 3)).toString()).isEqualTo("{\"shiftMix\":null}");
        assertThat(export.file().get("demand").has("NB")).isFalse();
        ObjectNode applied = baseJson.deepCopy();
        assertThat(ClientApply.apply(applied, export.file()).skipped()).isEmpty();
    }

    @Test
    void theLastPersonIsNotExcludedAndForeignIdsAreNamed() throws Exception {
        Dataset base = TestData.dataset(YearMonth.of(2033, 3));
        Dataset none = new Dataset(base.format(), base.version(), base.source(), base.areas(), base.shifts(),
                base.demand(), base.holidays(), base.absenceTypes(), List.of(), List.of(), base.history(), List.of());
        Corrections.Export export = Corrections.export(base, none, MAPPER);
        assertThat(export.file().get("people").size()).isEqualTo(5);
        assertThat(export.notes()).contains(new Issue("corrections.lastPerson", Map.of("personId", "P0006")),
                new Issue("corrections.idFormat", Map.of("count", 5L)));
    }

    @Test
    void anImportKeepsTheEditsAndNamesTheConflicts() throws Exception {
        Dataset oldBase = dataset(baseline());
        ObjectNode currentJson = baseline();
        person(currentJson, 0).put("weeklyHours", 30.0);
        person(currentJson, 1).put("hoursAccount", 5.5);
        String excluded = id(currentJson, 9);
        currentJson.withArray("people").remove(9);
        Dataset current = dataset(currentJson);
        // the new import: changed upstream as well
        ObjectNode incomingJson = baseline();
        person(incomingJson, 0).put("weeklyHours", 25.0);
        person(incomingJson, 1).put("weeklyHours", 35.0);
        person(incomingJson, 9).put("compRestBalance", 9);
        Dataset incoming = dataset(incomingJson);

        Changes changes = Corrections.check(Corrections.diff(oldBase, current), incoming);
        List<Conflict> conflicts = Corrections.conflicts(changes, oldBase, incoming);
        assertThat(conflicts).containsExactlyInAnyOrder(new Conflict(id(incomingJson, 0), null, "weeklyHours"),
                new Conflict(excluded, null, "exclude"));

        Dataset merged = Corrections.apply(changes, incoming);
        Map<String, Person> people = byId(merged.people());
        assertThat(people.get(id(incomingJson, 0)).weeklyHours()).as("Dispofold wins").isEqualTo(30.0);
        assertThat(people.get(id(incomingJson, 1)).weeklyHours()).as("new import").isEqualTo(35.0);
        assertThat(people.get(id(incomingJson, 1)).hoursAccount()).as("kept").isEqualTo(5.5);
        assertThat(people).doesNotContainKey(excluded);
        assertThat(merged.restrictions()).noneMatch(r -> r.personId().equals(excluded));
        assertThat(merged.history()).noneMatch(h -> h.personId().equals(excluded));
        assertThat(merged.periods().get(0).absences()).noneMatch(a -> a.personId().equals(excluded));
        assertThat(merged.periods().get(0).previousDuties()).noneMatch(a -> a.personId().equals(excluded));
        assertThat(incoming.history()).anyMatch(h -> h.personId().equals(excluded));
    }

    private static Map<String, Person> byId(List<Person> people) {
        return people.stream().collect(Collectors.toMap(Person::id, Function.identity()));
    }

    private static List<Restriction> own(List<Restriction> all, String id) {
        return all.stream().filter(r -> r.personId().equals(id)).toList();
    }

    private static <T> Map<T, Long> counts(List<T> items) {
        return items.stream().collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    }

    private static Map<String, Map<DemandRule, Long>> byArea(List<DemandRule> demand) {
        Map<String, List<DemandRule>> grouped = demand.stream().collect(Collectors.groupingBy(DemandRule::areaId));
        return grouped.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> counts(new ArrayList<>(e.getValue()))));
    }
}
