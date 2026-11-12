package app.dispofold.importclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;

import app.dispofold.model.Absence;
import app.dispofold.model.Dataset;
import app.dispofold.model.FixedAssignment;
import app.dispofold.model.HistoryEntry;
import app.dispofold.model.Period;
import app.dispofold.model.Restriction;
import app.dispofold.model.RestrictionKind;
import app.dispofold.model.Rules;
import app.dispofold.model.Wish;
import app.dispofold.model.WishKind;

/** Shared accept/reject corpus in tests/fixtures/contract and the checks of a single planning month. */
class DatasetValidatorTest {

    private static final Path CONTRACT = Path.of("..", "tests", "fixtures", "contract");
    private static final Path SYNTHETIC = Path.of("..", "tests", "fixtures", "synthetic");
    private static final Path DOC = Path.of("..", "docs", "datenformat.md");
    private static final Path SOURCE = Path.of("src", "main", "java", "app", "dispofold", "importclient",
            "DatasetValidator.java");
    private static final Pattern CODE = Pattern.compile("(import\\.[A-Za-z]+)");

    /** Configured like the application: Java time, HH:mm times, unknown keys ignored. */
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
            .registerModule(new SimpleModule("hh-mm")
                    .addSerializer(LocalTime.class, new LocalTimeSerializer(DateTimeFormatter.ofPattern("HH:mm"))))
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static final YearMonth MONTH = YearMonth.of(2027, 1);

    @TestFactory
    Stream<DynamicTest> acceptFilesHaveNoFindings() throws IOException {
        List<Path> files = corpus("accept");
        assertThat(files).isNotEmpty();
        return files.stream().map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> {
            DatasetValidator.Result r = DatasetValidator.validate(read(f), Rules.defaults());
            assertThat(r.errors()).isEmpty();
            assertThat(r.warnings()).isEmpty();
            assertThat(r.ok()).isTrue();
        }));
    }

    @TestFactory
    Stream<DynamicTest> rejectFilesGiveExactlyTheExpectedCodes() throws IOException {
        List<Path> files = corpus("reject");
        assertThat(files).isNotEmpty();
        return files.stream().map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> {
            Path expected = f.resolveSibling(f.getFileName().toString().replace(".json", ".expected.json"));
            assertThat(expected).exists();
            DatasetValidator.Result r = DatasetValidator.validate(read(f), Rules.defaults());
            assertThat(codes(r.errors())).isEqualTo(expectedCodes(expected));
            assertThat(r.ok()).isFalse();
        }));
    }

    /** Every error code of the validator is documented and has a reject file; every warning is documented. */
    @Test
    void codesAreDocumentedAndCovered() throws IOException {
        String doc = Files.readString(DOC);
        int start = doc.indexOf("## Fehlercodes beim Import");
        int warningsStart = doc.indexOf("### Warnungen", start);
        assertThat(start).isPositive();
        assertThat(warningsStart).isPositive();
        Set<String> documentedErrors = matches(doc.substring(start, warningsStart));
        Set<String> documentedWarnings = matches(doc.substring(warningsStart));

        Set<String> covered = new TreeSet<>();
        for (Path f : corpus("reject")) {
            covered.addAll(expectedCodes(f.resolveSibling(f.getFileName().toString().replace(".json",
                    ".expected.json"))));
        }
        assertThat(covered).isEqualTo(documentedErrors);

        Set<String> documented = new TreeSet<>(documentedErrors);
        documented.addAll(documentedWarnings);
        assertThat(matches(Files.readString(SOURCE))).isEqualTo(documented);
    }

    @Test
    void documentedSampleIsValid() throws IOException {
        Matcher m = Pattern.compile("```json\\n(.*?)```", Pattern.DOTALL).matcher(Files.readString(DOC));
        assertThat(m.find()).isTrue();
        DatasetValidator.Result r = DatasetValidator.validate(MAPPER.readValue(m.group(1), Dataset.class), null);
        assertThat(r.errors()).isEmpty();
    }

    @TestFactory
    Stream<DynamicTest> syntheticVersion2DatasetsAreValid() throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(SYNTHETIC)) {
            files = s.filter(f -> f.toString().endsWith(".json")).sorted().toList();
        }
        return files.stream().map(f -> DynamicTest.dynamicTest(f.getFileName().toString(), () -> {
            JsonNode tree = MAPPER.readTree(f.toFile());
            assumeTrue(tree.path("version").asInt() == Dataset.VERSION, "not yet version 2");
            DatasetValidator.Result r = DatasetValidator.validate(MAPPER.treeToValue(tree, Dataset.class),
                    Rules.defaults());
            assertThat(r.errors()).isEmpty();
        }));
    }

    @Test
    void warningsDoNotFailTheImport() throws IOException {
        Dataset d = read(CONTRACT.resolve("accept").resolve("minimal.synthetic.json"));
        Restriction never = new Restriction("P0001", "AN-F", null, 0, RestrictionKind.NEVER, null, null);
        Restriction onRequest = new Restriction("P0001", "AN-F", null, 0, RestrictionKind.ON_REQUEST, null, null);
        HistoryEntry future = new HistoryEntry("P0001", "2099-01", HistoryEntry.IMPORT, 0, 0, 0, 0, 0, 0, 0, null,
                null, 0, 0);
        Absence vacation = new Absence("P0002", LocalDate.of(2027, 1, 5), "U");
        Dataset changed = new Dataset(d.format(), d.version(), d.source(), d.areas(), d.shifts(), d.demand(),
                d.holidays(), d.absenceTypes(), d.people(), List.of(never, onRequest), List.of(future),
                List.of(new Period(MONTH, List.of(vacation, vacation), List.of(), List.of(), List.of())));

        DatasetValidator.Result r = DatasetValidator.validate(changed, Rules.defaults());

        assertThat(r.ok()).isTrue();
        assertThat(r.dataset()).isSameAs(changed);
        assertThat(codes(r.warnings())).containsExactlyInAnyOrder("import.duplicateRestriction",
                "import.historyInFuture", "import.duplicateAbsence");
        assertThat(r.warnings()).allSatisfy(w -> assertThat(w.params()).containsEntry("personId",
                w.code().equals("import.duplicateAbsence") ? "P0002" : "P0001"));
    }

    @Test
    void maxDutyHoursComeFromTheRules() throws IOException {
        Dataset d = read(CONTRACT.resolve("accept").resolve("minimal.synthetic.json"));
        Rules r = Rules.defaults();
        Rules tenHours = new Rules(r.minDailyRestHours(), r.minWeeklyRestHours(), 10, r.maxWeekHours(),
                r.maxAverageWeekHours(), r.minBlock(), r.maxBlock(), r.maxWishesPerPerson(),
                r.holidayWorkEarnsCompRest(), r.nightStart(), r.nightEnd(), r.nightMinHours(),
                r.accountCarryShare(), r.historyMonths(), r.weights(), r.timeZone(), r.averagingWeeks());

        List<Issue> errors = DatasetValidator.validate(d, tenHours).errors();

        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo("import.shiftTooLong");
            assertThat(e.params()).containsEntry("shiftId", "AN-N").containsEntry("minutes", 720L)
                    .containsEntry("maxMinutes", 600L);
        });
    }

    @Test
    void periodDateOutsideMonth() throws IOException {
        Period p = new Period(MONTH, List.of(), List.of(), List.of(new FixedAssignment("P0001",
                LocalDate.of(2027, 2, 1), "AN-F")), List.of());

        DatasetValidator.Result r = DatasetValidator.validatePeriod(master(), p, Rules.defaults());

        assertThat(r.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo("import.dateOutsideMonth");
            assertThat(e.params()).containsEntry("kind", "fixed").containsEntry("personId", "P0001")
                    .containsEntry("date", "2027-02-01").containsEntry("month", "2027-01");
        });
    }

    @Test
    void periodUnknownPerson() throws IOException {
        Period p = new Period(MONTH, List.of(), List.of(new Wish("P9999", LocalDate.of(2027, 1, 5), WishKind.OFF,
                null)), List.of(), List.of());

        DatasetValidator.Result r = DatasetValidator.validatePeriod(master(), p, Rules.defaults());

        assertThat(codes(r.errors())).containsExactly("import.unknownPerson");
        assertThat(r.errors().get(0).params()).containsEntry("personId", "P9999").containsEntry("kind", "wish");
    }

    @Test
    void periodUnknownAbsenceType() throws IOException {
        Period p = new Period(MONTH, List.of(new Absence("P0001", LocalDate.of(2027, 1, 5), "Q")), List.of(),
                List.of(), List.of());

        DatasetValidator.Result r = DatasetValidator.validatePeriod(master(), p, Rules.defaults());

        assertThat(r.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo("import.unknownAbsenceType");
            assertThat(e.params()).containsEntry("typeCode", "Q").containsEntry("date", "2027-01-05");
        });
    }

    @Test
    void periodShiftWishWithoutShift() throws IOException {
        Period p = new Period(MONTH, List.of(), List.of(new Wish("P0001", LocalDate.of(2027, 1, 5),
                WishKind.SHIFT, null)), List.of(), List.of());

        DatasetValidator.Result r = DatasetValidator.validatePeriod(master(), p, Rules.defaults());

        assertThat(codes(r.errors())).containsExactly("import.wishWithoutShift");
    }

    @Test
    void periodWithoutMonth() throws IOException {
        Period p = new Period(null, List.of(), List.of(), List.of(), List.of());

        DatasetValidator.Result r = DatasetValidator.validatePeriod(master(), p, Rules.defaults());

        assertThat(r.errors()).singleElement().satisfies(e -> {
            assertThat(e.code()).isEqualTo("import.missingField");
            assertThat(e.params()).containsEntry("kind", "period").containsEntry("field", "month");
        });
    }

    @Test
    void nullEntryIsReportedOnce() throws IOException {
        Dataset d = read(CONTRACT.resolve("reject").resolve("null-shift-entry.synthetic.json"));

        assertThat(DatasetValidator.validate(d, Rules.defaults()).errors()).singleElement()
                .satisfies(e -> assertThat(e.params()).containsEntry("kind", "shift").containsEntry("index", 2)
                        .doesNotContainKey("field"));
    }

    @Test
    void validPeriodReplacesThePeriodsOfTheMaster() throws IOException {
        Dataset master = master();
        Period p = new Period(MONTH, List.of(new Absence("P0001", LocalDate.of(2027, 1, 5), "U")), List.of(),
                List.of(), List.of());

        DatasetValidator.Result r = DatasetValidator.validatePeriod(master, p, Rules.defaults());

        assertThat(r.ok()).isTrue();
        assertThat(r.dataset().periods()).containsExactly(p);
        assertThat(r.dataset().people()).isSameAs(master.people());
    }

    private static Dataset master() throws IOException {
        return read(CONTRACT.resolve("accept").resolve("all-fields.synthetic.json"));
    }

    private static List<Path> corpus(String dir) throws IOException {
        try (Stream<Path> s = Files.list(CONTRACT.resolve(dir))) {
            return s.filter(f -> f.toString().endsWith(".synthetic.json")).sorted().toList();
        }
    }

    private static Dataset read(Path f) throws IOException {
        return MAPPER.readValue(f.toFile(), Dataset.class);
    }

    private static Set<String> expectedCodes(Path f) throws IOException {
        Set<String> codes = new TreeSet<>();
        MAPPER.readTree(f.toFile()).path("codes").forEach(c -> codes.add(c.asText()));
        assertThat(codes).as(f.toString()).isNotEmpty();
        return codes;
    }

    private static Set<String> codes(List<Issue> issues) {
        return issues.stream().map(Issue::code).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> matches(String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = CODE.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }
}
