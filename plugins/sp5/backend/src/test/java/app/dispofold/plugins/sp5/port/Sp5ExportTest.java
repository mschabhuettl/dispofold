package app.dispofold.plugins.sp5.port;

import static app.dispofold.plugins.sp5.port.TestTables.c;
import static app.dispofold.plugins.sp5.port.TestTables.d;
import static app.dispofold.plugins.sp5.port.TestTables.f;
import static app.dispofold.plugins.sp5.port.TestTables.n;
import static app.dispofold.plugins.sp5.port.TestTables.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The import on a tiny synthetic folder: one area, two shifts, three people, a month of history. */
public class Sp5ExportTest {

    /** Replaces the groups of a folder made by {@link #folder}: rows of ID, NAME, SHORTNAME, SUPERID. */
    public static void groups(Path dir, Object[]... rows) throws IOException {
        TestTables.write(dir, "5GROUP", List.of(n("ID", 11), c("NAME", 40), c("SHORTNAME", 24), n("SUPERID", 11)),
                rows);
    }

    public static final String MAPPING = """
            {"areas": [{"id": "AN", "name": "Annahme", "color": "#B45309"}],
             "shifts": [{"match": "A*", "areaId": "AN"}, {"match": "re:^N\\\\d$", "areaId": "AN"}],
             "ignoreShifts": ["X2"], "groups": [{"match": "Team Annahme", "areaIds": ["AN"]}],
             "excludeGroups": ["Verwaltung"]}""";

    static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** The folder: A1 07:00-19:00 and N1 19:00-07:00 (mapped), X2 ignored, Q9 without area. */
    public static void folder(Path dir) throws Exception {
        List<TestTables.Field> shift = new ArrayList<>(List.of(n("ID", 11), c("NAME", 40), c("SHORTNAME", 24),
                n("HIDE", 1), n("COLORBK", 11)));
        for (int i = 0; i < 8; i++) {
            shift.add(c("STARTEND" + i, 36));
        }
        Object[][] times = { { "07:00-19:00" }, { "19:00-07:00" }, { "06:00-14:00" }, { "08:00-16:00" } };
        String[] codes = { "A1", "N1", "X2", "Q9" };
        Object[][] shifts = new Object[4][];
        for (int s = 0; s < 4; s++) {
            List<Object> r = new ArrayList<>(List.of((long) s + 1, "Dienst " + codes[s], codes[s], 0L, s == 0 ? 255L
                    : 0L));
            for (int i = 0; i < 8; i++) {
                r.add(ascii((String) times[s][0]));
            }
            shifts[s] = r.toArray();
        }
        TestTables.write(dir, "5SHIFT", shift, shifts);
        TestTables.write(dir, "5GROUP", List.of(n("ID", 11), c("NAME", 40), c("SHORTNAME", 24), n("SUPERID", 11)),
                row(1L, "Team Annahme", "TA", 0L), row(2L, "Verwaltung", "VW", 0L));
        TestTables.write(dir, "5GRASG", List.of(n("ID", 11), n("EMPLOYEEID", 11), n("GROUPID", 11)),
                row(1L, 1L, 1L), row(2L, 2L, 1L), row(3L, 3L, 2L));
        TestTables.write(dir, "5EMPL", List.of(n("ID", 11), c("NAME", 40), c("FIRSTNAME", 40), c("SHORTNAME", 24),
                n("HIDE", 1), c("WORKDAYS", 16), n("CALCBASE", 11), f("HRSDAY", 10, 2), f("HRSWEEK", 10, 2),
                f("HRSMONTH", 10, 2), f("HRSTOTAL", 10, 2), d("EMPSTART"), d("EMPEND")),
                row(1L, "Muster", "Anna", "AM", 0L, ascii("1 1 1 1 1 0 0 0"), 1L, "8.00", "40.00", "0.00", "0.00",
                        "2015-01-01", null),
                row(2L, "Probe", "Ben", "BP", 0L, ascii("1 1 1 1 1 0 0 0"), 0L, "7.70", "40.00", "0.00", "0.00",
                        "2015-01-01", null),
                row(3L, "Amt", "Cora", "CA", 0L, ascii("1 1 1 1 1 0 0 0"), 1L, "8.00", "40.00", "0.00", "0.00",
                        "2015-01-01", null));
        List<Object[]> duties = new ArrayList<>();
        long id = 1;
        for (int day = 1; day <= 31; day++) {
            String date = String.format("2026-12-%02d", day);
            duties.add(row(id++, 1L, day % 2 == 0 ? 1L : 2L, date, 0L));
            duties.add(row(id++, 2L, day % 3 == 0 ? 3L : 1L, date, 0L));
        }
        duties.add(row(id++, 1L, 1L, "2027-01-05", 0L));
        TestTables.write(dir, "5MASHI", List.of(n("ID", 11), n("EMPLOYEEID", 11), n("SHIFTID", 11), d("DATE"),
                n("TYPE", 11)), duties.toArray(new Object[0][]));
        TestTables.write(dir, "5LEAVT", List.of(n("ID", 11), c("NAME", 40), c("SHORTNAME", 24), n("CHARGETYP", 11),
                n("DEDUCTACT", 1), n("DEDUCTOVT", 1), n("COLORBK", 11)), row(1L, "Urlaub", "U", 1L, 0L, 0L, 0L));
        TestTables.write(dir, "5ABSEN", List.of(n("ID", 11), n("EMPLOYEEID", 11), d("DATE"), n("LEAVETYPID", 11),
                n("INTERVAL", 11)), row(1L, 1L, "2027-01-10", 1L, 0L));
        TestTables.write(dir, "5HOLID", List.of(n("ID", 11), d("DATE"), c("NAME", 40), n("INTERVAL", 11)),
                row(1L, "2026-12-25", "Christtag", 0L), row(2L, "2027-01-01", "Neujahr", 0L),
                row(3L, "2027-01-06", "Heilige Drei Könige", 0L));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapping() throws Exception {
        return (Map<String, Object>) PyJson.load(MAPPING);
    }

    static Sp5Export.Request request(Path dir, Map<String, Object> corrections) throws Exception {
        return new Sp5Export.Request(dir, "2027-01", null, 1, null, null, "history", 1, 3, 3, mapping(), corrections,
                true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void importsShiftsPeopleAbsencesHistoryAndDemand(@TempDir Path dir) throws Exception {
        folder(dir);
        Sp5Export.Result r = Sp5Export.run(request(dir, null));
        Map<String, Object> ds = r.dataset();
        List<Map<String, Object>> shifts = (List<Map<String, Object>>) ds.get("shifts");
        assertThat(shifts).extracting(s -> s.get("id")).containsExactly("S1", "S2");
        assertThat(shifts.get(0)).containsEntry("category", "DAY").containsEntry("color", "#FF0000")
                .containsEntry("unpopular", false);
        assertThat(shifts.get(1)).containsEntry("category", "NIGHT").containsEntry("unpopular", true);
        List<Map<String, Object>> people = (List<Map<String, Object>>) ds.get("people");
        assertThat(people).extracting(p -> p.get("id")).containsExactly("E1", "E2");
        assertThat(people.get(1)).containsEntry("weeklyHours", 38.5); // CALCBASE 0: 7.70 h on 5 days
        assertThat(people.get(0).get("shiftMix")).isNotNull();
        List<Map<String, Object>> periods = (List<Map<String, Object>>) ds.get("periods");
        assertThat((List<Map<String, Object>>) periods.get(0).get("absences")).singleElement()
                .isEqualTo(Map.of("personId", "E1", "date", "2027-01-10", "typeCode", "U"));
        assertThat((List<Object>) periods.get(0).get("previousDuties")).hasSize(12);
        assertThat((List<Map<String, Object>>) ds.get("history")).extracting(h -> h.get("month"))
                .containsExactly("2026-12", "2026-12");
        assertThat((List<Map<String, Object>>) ds.get("demand")).isNotEmpty()
                .allSatisfy(rule -> assertThat(rule).containsEntry("areaId", "AN"));
        assertThat(r.warnings()).contains("Schicht ohne Bereich ignoriert: SP5-Id 4, Kürzel 'Q9'",
                "Tabelle 5RESTR fehlt, wird als leer behandelt", "1 Mitarbeiter nicht übernommen (ausgeschlossene Gruppe)");
        assertThat(ds.get("appliedCorrections")).isEqualTo(Map.of("format", "dispofold/corrections", "version", 2L,
                "people", Map.of(), "demand", Map.of()));
        assertThat(r.reportRows()).allSatisfy(row -> assertThat(row).hasSize(29));
        assertThat(new String(Sp5Export.datasetBytes(r), StandardCharsets.UTF_8)).startsWith("{\n \"format\": "
                + "\"dispofold/dataset\",\n \"version\": 2,\n \"source\": \"sp5\",").endsWith("\n}\n");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theMaximumAtTheMinimumChangesOnlyTheMaximaOfTheDemand(@TempDir Path dir) throws Exception {
        folder(dir);
        Sp5Export.Request p90 = request(dir, null);
        Sp5Export.Result usual = Sp5Export.run(p90);
        Sp5Export.Result min = Sp5Export.run(new Sp5Export.Request(dir, "2027-01", null, 1, null, null, "history", 1,
                3, 3, mapping(), null, true, "groups", 3, Sp5Export.DEMAND_MAX_MIN));
        List<Map<String, Object>> before = (List<Map<String, Object>>) usual.dataset().get("demand");
        List<Map<String, Object>> after = (List<Map<String, Object>>) min.dataset().get("demand");
        assertThat(before).anySatisfy(rule -> assertThat(rule.get("max")).isNotEqualTo(rule.get("min")));
        assertThat(after).hasSameSizeAs(before).allSatisfy(rule -> assertThat(rule.get("max")).isEqualTo(rule.get("min")));
        for (int i = 0; i < before.size(); i++) {
            Map<String, Object> expected = new java.util.LinkedHashMap<>(before.get(i));
            expected.put("max", expected.get("min"));
            assertThat(after.get(i)).isEqualTo(expected);
        }
        // the rest of the dataset is the same
        Map<String, Object> rest = new java.util.LinkedHashMap<>(min.dataset());
        rest.put("demand", before);
        assertThat(rest).isEqualTo(usual.dataset());
    }

    /** The shifts' "unpopular" with nightsFair on or off and this mapping. */
    @SuppressWarnings("unchecked")
    static List<Object> unpopular(Path dir, Map<String, Object> mapping, boolean nightsFair) throws Exception {
        Sp5Export.Result r = Sp5Export.run(new Sp5Export.Request(dir, "2027-01", null, 1, null, null, "history", 1, 3,
                3, mapping, null, nightsFair));
        return ((List<Map<String, Object>>) r.dataset().get("shifts")).stream().map(s -> s.get("unpopular")).toList();
    }

    @Test
    @SuppressWarnings("unchecked")
    void nightShiftsAreMarkedOnlyWhenChosenOrSetInTheRule(@TempDir Path dir) throws Exception {
        folder(dir);
        // A1 (day) and N1 (night), rules without "unpopular": marked only with the option, as sp5_export.py does
        assertThat(unpopular(dir, mapping(), false)).containsExactly(false, false);
        assertThat(unpopular(dir, mapping(), true)).containsExactly(false, true);
        // a value in the rule wins either way
        Map<String, Object> set = mapping();
        List<Map<String, Object>> rules = (List<Map<String, Object>>) set.get("shifts");
        rules.get(0).put("unpopular", true);
        rules.get(1).put("unpopular", false);
        assertThat(unpopular(dir, set, false)).containsExactly(true, false);
        assertThat(unpopular(dir, set, true)).containsExactly(true, false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void correctionsAreAppliedLastAndListed(@TempDir Path dir) throws Exception {
        folder(dir);
        Map<String, Object> corrections = Corrections.load(("{\"format\": \"dispofold/corrections\", \"version\": 2, "
                + "\"people\": {\"E1\": {\"weeklyHours\": 30, \"firstName\": \"X\"}, \"E2\": {\"exclude\": true}, "
                + "\"E9\": {\"weeklyHours\": 20}}}").getBytes(StandardCharsets.UTF_8), "k.json");
        Sp5Export.Result r = Sp5Export.run(request(dir, corrections));
        List<Map<String, Object>> people = (List<Map<String, Object>>) r.dataset().get("people");
        assertThat(people).singleElement().satisfies(p -> assertThat(p).containsEntry("weeklyHours", 30.0));
        Map<String, Object> applied = (Map<String, Object>) r.dataset().get("appliedCorrections");
        assertThat(applied.get("people")).isEqualTo(Map.of("E1", Map.of("weeklyHours", 30.0), "E2",
                Map.of("exclude", true)));
        assertThat(r.warnings()).contains("Korrektur E1 firstName übersprungen: nicht korrigierbar",
                "Korrektur für E9 übersprungen: Mitarbeiter nicht exportiert",
                "Korrekturen: 2 Mitarbeiter korrigiert, 2 Felder angewendet, 2 Felder übersprungen");
        assertThat(r.reportRows()).filteredOn(row -> row.get(0).equals("korrektur")).hasSize(4);
    }

    @Test
    void errorsAreGerman(@TempDir Path dir) throws Exception {
        folder(dir);
        Files.delete(dir.resolve("5EMPL.DBF"));
        assertThatThrownBy(() -> Sp5Export.run(request(dir, null))).isInstanceOf(Sp5Export.Sp5Error.class)
                .hasMessage("Tabelle 5EMPL fehlt im SP5-Ordner");
        Map<String, Object> bad = mapping();
        bad.put("ignoreGroups", List.of("re:*x"));
        assertThatThrownBy(() -> Sp5Export.validateMapping(bad)).hasMessage("ignoreGroups, Eintrag 1: Muster 're:*x' "
                + "ist kein gültiger regulärer Ausdruck (nothing to repeat, Position 0)");
        assertThatThrownBy(() -> Corrections.load("{\"format\": \"x\"}".getBytes(StandardCharsets.UTF_8), "k.json"))
                .hasMessage("Korrekturdatei k.json: unbekanntes Format, erwartet \"dispofold/corrections\"");
        assertThatThrownBy(() -> Sp5Export.run(new Sp5Export.Request(dir, "2027-01", "2028-02", 1, null, null,
                "history", 10, 3, 3, mapping(), null, true))).hasMessage("Zeitraum 2027-01 bis 2028-02: Ende muss im selben "
                        + "Monat oder bis zu 12 Monate danach liegen");
    }

    @Test
    void commandLineWritesBothFilesOrNone(@TempDir Path dir) throws Exception {
        Path data = dir.resolve("sp5");
        Files.createDirectory(data);
        folder(data);
        Path mappingFile = dir.resolve("mapping.json");
        Files.writeString(mappingFile, MAPPING);
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        String[] args = { "--data", data.toString(), "--month", "2027-01", "--history-months", "1", "--mapping",
            mappingFile.toString(), "--out", dir.resolve("d.json").toString(), "--report", dir.resolve("r.csv").toString() };
        assertThat(Sp5Main.run(args, new PrintStream(err, true, StandardCharsets.UTF_8))).isZero();
        assertThat(dir.resolve("d.json")).exists();
        assertThat(dir.resolve("r.csv")).exists();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Warnung: Tabelle 5RESTR fehlt")
                .endsWith("1 Planungsmonate, 2 Mitarbeiter, 2 Schichten, " + demandCount(dir.resolve("d.json"))
                        + " Bedarfsregeln, " + err.toString(StandardCharsets.UTF_8).lines()
                                .filter(l -> l.startsWith("Warnung: ")).count() + " Warnungen; geschrieben: "
                        + dir.resolve("d.json") + "\n");
        ByteArrayOutputStream err2 = new ByteArrayOutputStream();
        String[] inside = args.clone();
        inside[9] = data.resolve("x.json").toString();
        assertThat(Sp5Main.run(inside, new PrintStream(err2, true, StandardCharsets.UTF_8))).isEqualTo(1);
        assertThat(err2.toString(StandardCharsets.UTF_8))
                .isEqualTo("Fehler: Ausgabe- und Berichtsdatei dürfen nicht im SP5-Ordner liegen\n");
        String[] badMonth = args.clone();
        badMonth[3] = "2027-13";
        ByteArrayOutputStream err3 = new ByteArrayOutputStream();
        assertThat(Sp5Main.run(badMonth, new PrintStream(err3, true, StandardCharsets.UTF_8))).isEqualTo(1);
        assertThat(err3.toString(StandardCharsets.UTF_8)).isEqualTo("Fehler: ungültiger Monat '2027-13', erwartet "
                + "YYYY-MM\n");
        String[] huge = args.clone();
        huge[5] = "4294967297"; // would be 1 when narrowed to int
        ByteArrayOutputStream err4 = new ByteArrayOutputStream();
        assertThat(Sp5Main.run(huge, new PrintStream(err4, true, StandardCharsets.UTF_8))).isEqualTo(2);
    }

    /** The port's command line, for tests outside this package. */
    public static int commandLine(String[] args, PrintStream err) {
        return Sp5Main.run(args, err);
    }

    @SuppressWarnings("unchecked")
    static int demandCount(Path dataset) throws Exception {
        return ((List<Object>) ((Map<String, Object>) PyJson.load(Files.readAllBytes(dataset))).get("demand")).size();
    }
}
