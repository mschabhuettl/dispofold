package app.dispofold.plugins.sp5.port;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The one entry of the port of sp5-export (sp5export/sp5.py import_sp5 and the files of sp5_export.py): the CLI
 * {@link Sp5Main} and the Dispofold plugin both call {@link #run} and write {@link #datasetBytes} and
 * {@link #reportBytes}. The mapping goes through {@link #loadMapping} or {@link #validateMapping}, the corrections
 * file through {@link Corrections#load}.
 */
public final class Sp5Export {

    public static final List<String> DEMAND_SOURCES = List.of("history", "sp5");
    public static final int HISTORY_MONTHS = 12;
    public static final int MAX_HISTORY_MONTHS = 24;
    /** The maximum of the demand from the history: the staffing of 9 of 10 days (as sp5_export.py) or the minimum. */
    public static final String DEMAND_MAX_P90 = "p90";
    public static final String DEMAND_MAX_MIN = "min";
    public static final int MAX_EXTRA_MONTHS = 12;
    public static final int REPORT_COLUMNS = 29;
    public static final int HIDDEN_SHIFTS_MONTHS = 3;
    public static final int MIX_MIN_DUTIES = 10;
    public static final int RECENT_MONTHS = 3;
    static final List<String> PATTERN_LISTS = List.of("ignoreShifts", "ignoreGroups", "excludeGroups",
            "compensatoryRestAbsences", "ignoreAbsences");
    public static final List<String> REPORT_HEADER = List.of("pruefung", "person", "bereich", "schicht", "dienste_historie",
            "bereich_aus_gruppen", "einschraenkung", "tagestyp", "spitze_historie", "spitze_sp5",
            "dienste_nicht_uebernommen", "stunden_historie", "spitze_historie_max", "wochenstunden", "bereiche",
            "mix_kategorien", "mix_bereiche", "auf_anfrage", "grund", "sp5_schicht", "calcbase", "hrsweek",
            "wochenstunden_calcbase", "feld", "status", "wert_export", "wert_korrektur",
            "dienste_nicht_uebernommen_gezaehlt", "letzter_dienst");

    /** The folder cannot be imported (missing tables, unreadable mapping, bad options). German message. */
    public static final class Sp5Error extends Exception {
        public Sp5Error(String message) {
            super(message);
        }
    }

    /**
     * What to import: `month` (YYYY-MM) to `to` (null: one month), the options of sp5_export.py, the mapping (as
     * {@link #loadMapping} returns it) and the corrections (as {@link Corrections#load} returns them, or null).
     * `mixMinDuties` null writes no shift mix.
     */
    /**
     * @param nightsFair a mapping rule without {@code unpopular} marks night shifts (as sp5_export.py does); off, such
     *        a shift is not marked
     * @param areaSource where a person's areas come from (Dispofold only): "groups" as sp5_export.py, "history" or
     *        "reserve" with historyAreaDuties (see {@link Sp5Import#areaSource})
     * @param demandMax the maximum of the demand from the history (Dispofold only): "p90" the staffing reached on 9 of
     *        10 such days, as sp5_export.py; "min" the minimum (see {@link #withMaxAtMinimum})
     */
    public record Request(Path folder, String month, String to, int historyMonths, Integer areasFromHistory,
            Integer shiftsFromHistory, String demand, Integer mixMinDuties, int hiddenShiftsMonths, int recentMonths,
            Map<String, Object> mapping, Map<String, Object> corrections, boolean nightsFair, String areaSource,
            int historyAreaDuties, String demandMax) {

        /** The maximum of the demand from the history as sp5_export.py. */
        public Request(Path folder, String month, String to, int historyMonths, Integer areasFromHistory,
                Integer shiftsFromHistory, String demand, Integer mixMinDuties, int hiddenShiftsMonths,
                int recentMonths, Map<String, Object> mapping, Map<String, Object> corrections, boolean nightsFair,
                String areaSource, int historyAreaDuties) {
            this(folder, month, to, historyMonths, areasFromHistory, shiftsFromHistory, demand, mixMinDuties,
                    hiddenShiftsMonths, recentMonths, mapping, corrections, nightsFair, areaSource, historyAreaDuties,
                    DEMAND_MAX_P90);
        }

        /** The areas from the groups, as sp5_export.py. */
        public Request(Path folder, String month, String to, int historyMonths, Integer areasFromHistory,
                Integer shiftsFromHistory, String demand, Integer mixMinDuties, int hiddenShiftsMonths,
                int recentMonths, Map<String, Object> mapping, Map<String, Object> corrections, boolean nightsFair) {
            this(folder, month, to, historyMonths, areasFromHistory, shiftsFromHistory, demand, mixMinDuties,
                    hiddenShiftsMonths, recentMonths, mapping, corrections, nightsFair, "groups", 3);
        }
    }

    /**
     * The dataset (with appliedCorrections), the report rows (29 columns each, sorted) and the warnings; besides, for
     * Dispofold only and not in the files: where each person's areas come from (person id -> area -> text) and the
     * counts of the areas by their source.
     */
    public record Result(Map<String, Object> dataset, List<List<Object>> reportRows, List<String> warnings,
            Map<String, Map<String, String>> areaSources, Map<String, Integer> areaCounts) {

        public Result(Map<String, Object> dataset, List<List<Object>> reportRows, List<String> warnings) {
            this(dataset, reportRows, warnings, Map.of(), Map.of());
        }
    }

    private Sp5Export() {
    }

    /** The mapping file's bytes: JSON as Python reads it, then validated. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadMapping(byte[] data) throws PyJson.DecodeError, Sp5Error {
        Object mapping = PyJson.load(data);
        validateMapping(mapping);
        return (Map<String, Object>) mapping;
    }

    public static void validateMapping(Map<String, Object> mapping) throws Sp5Error {
        validateMapping((Object) mapping);
    }

    /** A pattern the mapping may hold: text, and with "re:" a regular expression that compiles. */
    public static boolean validPattern(Object pattern) {
        return patternError(pattern) == null;
    }

    /** The import's match of a valid pattern against a value (fnmatch, or "re:" from the start of the value). */
    public static boolean matches(String pattern, String value) {
        return Sp5Import.matches(pattern, value);
    }

    /** A date of a table as the import reads it (an ISO date text), else null. */
    public static LocalDate date(Object value) {
        return TimeUtil.parseDate(value);
    }

    /** The duties of the cycle tables (5CYASS, 5CYCLE, 5CYENT, 5CYEXC) within [from, to], as the import expands them. */
    public static List<Map<String, Object>> cycleDuties(List<Map<String, Object>> assignments,
            List<Map<String, Object>> cycles, List<Map<String, Object>> entries, List<Map<String, Object>> exceptions,
            LocalDate from, LocalDate to) {
        return Sp5Calc.expandCycleAssignments(assignments, cycles, entries, exceptions, from, to);
    }

    private static String patternError(Object pattern) {
        if (!(pattern instanceof String s) || s.isEmpty()) {
            return "ist kein Text";
        }
        if (s.startsWith("re:")) {
            try {
                PyRe.compile(s.substring(3));
            } catch (PyRe.Error e) {
                return "ist kein gültiger regulärer Ausdruck (" + e.msg() + ", Position " + Py.repr(e.pos()) + ")";
            }
        }
        return null;
    }

    /** validate_mapping: the whole mapping checked before anything is read; all problems in one error. */
    public static void validateMapping(Object value) throws Sp5Error {
        List<String> errors = mappingErrors(value);
        if (!errors.isEmpty()) {
            throw new Sp5Error(String.join("; ", errors));
        }
    }

    /** The problems {@link #validateMapping} joins into its message, in order; empty for a valid mapping. */
    @SuppressWarnings("unchecked")
    public static List<String> mappingErrors(Object value) {
        if (!(value instanceof Map)) {
            return List.of("die Zuordnung muss ein JSON-Objekt sein");
        }
        Map<String, Object> mapping = (Map<String, Object>) value;
        List<String> errors = new ArrayList<>();
        Set<String> knownKeys = new HashSet<>(List.of("areas", "shifts", "groups", "ignoreShiftsAsAbsence"));
        knownKeys.addAll(PATTERN_LISTS);
        List<String> unknown = new ArrayList<>();
        for (String key : mapping.keySet()) {
            if (!knownKeys.contains(key)) {
                unknown.add(key);
            }
        }
        unknown.sort(Py::compareStr);
        for (String key : unknown) {
            errors.add("unbekannter Schlüssel '" + key + "'");
        }
        for (String key : List.of("areas", "shifts", "groups")) {
            if (!mapping.containsKey(key)) {
                errors.add("Pflichtschlüssel '" + key + "' fehlt");
            }
        }
        List<String> listKeys = new ArrayList<>(List.of("areas", "shifts", "groups"));
        listKeys.addAll(PATTERN_LISTS);
        for (String key : listKeys) {
            if (mapping.containsKey(key) && !(mapping.get(key) instanceof List)) {
                errors.add("'" + key + "' muss eine Liste sein");
            }
        }
        if (mapping.containsKey("ignoreShiftsAsAbsence") && !(mapping.get("ignoreShiftsAsAbsence") instanceof Boolean)) {
            errors.add("'ignoreShiftsAsAbsence' muss true oder false sein");
        }
        List<Object> areas = mapping.get("areas") instanceof List<?> l ? (List<Object>) l : List.of();
        List<Object> shifts = mapping.get("shifts") instanceof List<?> l ? (List<Object>) l : List.of();
        List<Object> groups = mapping.get("groups") instanceof List<?> l ? (List<Object>) l : List.of();
        if (mapping.containsKey("areas") && areas.isEmpty()) {
            errors.add("'areas' ist leer");
        }
        Set<String> areaIds = new HashSet<>();
        for (int i = 0; i < areas.size(); i++) {
            String where = "areas, Eintrag " + (i + 1);
            if (!(areas.get(i) instanceof Map<?, ?> a)) {
                errors.add(where + ": muss ein Objekt sein");
                continue;
            }
            if (!(a.get("id") instanceof String id) || Py.strip(id).isEmpty()) {
                errors.add(where + ": 'id' fehlt oder ist kein Text");
            } else if (areaIds.contains(id)) {
                errors.add(where + ": Bereich '" + id + "' doppelt");
            } else {
                areaIds.add(id);
            }
            for (String key : List.of("name", "color")) {
                if (!(a.get(key) instanceof String)) {
                    errors.add(where + ": '" + key + "' fehlt oder ist kein Text");
                }
            }
        }
        for (int i = 0; i < shifts.size(); i++) {
            String where = "shifts, Eintrag " + (i + 1);
            if (!rule(errors, where, shifts.get(i))) {
                continue;
            }
            Map<?, ?> r = (Map<?, ?>) shifts.get(i);
            if (!(r.get("areaId") instanceof String id) || !areaIds.contains(id)) {
                errors.add(where + ": 'areaId' " + Py.repr(r.get("areaId")) + " ist kein Bereich aus 'areas'");
            }
            if (r.containsKey("category") && !(r.get("category") instanceof String c
                    && Sp5Import.CATEGORIES.contains(c))) {
                errors.add(where + ": 'category' muss " + String.join(", ", Sp5Import.CATEGORIES) + " sein");
            }
            if (r.containsKey("unpopular") && !(r.get("unpopular") instanceof Boolean)) {
                errors.add(where + ": 'unpopular' muss true oder false sein");
            }
        }
        for (int i = 0; i < groups.size(); i++) {
            String where = "groups, Eintrag " + (i + 1);
            if (!rule(errors, where, groups.get(i))) {
                continue;
            }
            Object ids = ((Map<?, ?>) groups.get(i)).get("areaIds");
            if (!(ids instanceof List<?> l) || l.isEmpty()) {
                errors.add(where + ": 'areaIds' muss eine nicht leere Liste sein");
            } else {
                for (Object a : l) {
                    if (!(a instanceof String s) || !areaIds.contains(s)) {
                        errors.add(where + ": 'areaIds' enthält " + Py.repr(a) + ", keinen Bereich aus 'areas'");
                    }
                }
            }
        }
        for (String key : PATTERN_LISTS) {
            if (mapping.get(key) instanceof List<?> l) {
                for (int i = 0; i < l.size(); i++) {
                    String problem = patternError(l.get(i));
                    if (problem != null) {
                        errors.add(key + ", Eintrag " + (i + 1) + ": Muster " + Py.repr(l.get(i)) + " " + problem);
                    }
                }
            }
        }
        return errors;
    }

    private static boolean rule(List<String> errors, String where, Object r) {
        if (!(r instanceof Map<?, ?> m)) {
            errors.add(where + ": muss ein Objekt sein");
            return false;
        }
        if (!m.containsKey("match")) {
            errors.add(where + ": 'match' fehlt");
        } else {
            String problem = patternError(m.get("match"));
            if (problem != null) {
                errors.add(where + ": Muster " + Py.repr(m.get("match")) + " " + problem);
            }
        }
        return true;
    }

    /** The command line's check of --month and --to (before anything is read), with its message. */
    public static void checkMonth(String month) throws Sp5Error {
        if (month != null && !TimeUtil.validMonth(month)) {
            throw new Sp5Error("ungültiger Monat " + Py.strRepr(month) + ", erwartet YYYY-MM");
        }
    }

    /** import_sp5: the dataset of the months, the report rows and the warnings. The folder is only read. */
    public static Result run(Request q) throws Sp5Error {
        validateMapping(q.mapping());
        String month = q.month();
        String to = q.to();
        List<String> months = new ArrayList<>(List.of(month));
        while (to != null && !to.isEmpty() && Py.compareStr(months.get(months.size() - 1), to) < 0) {
            String lastMonth = months.get(months.size() - 1);
            months.add(Py.at("timeutil.py", 31, () -> TimeUtil.addMonths(lastMonth, 1)));
        }
        if ((to != null && !to.isEmpty() && Py.compareStr(to, month) < 0) || months.size() > MAX_EXTRA_MONTHS + 1) {
            throw new Sp5Error("Zeitraum " + month + " bis " + to + ": Ende muss im selben Monat oder bis zu "
                    + MAX_EXTRA_MONTHS + " Monate danach liegen");
        }
        int history = q.historyMonths();
        if (history < 0 || history > MAX_HISTORY_MONTHS) {
            throw new Sp5Error("Historie " + history + " Monate: erlaubt sind 0 bis " + MAX_HISTORY_MONTHS);
        }
        for (Integer option : new Integer[] { q.areasFromHistory(), q.shiftsFromHistory() }) {
            if (option != null && (option < 1 || history == 0)) {
                throw new Sp5Error("Bereiche und Schichten aus Diensten brauchen eine Mindestzahl ab 1 und eine "
                        + "Historie");
            }
        }
        if (q.hiddenShiftsMonths() < 0) {
            throw new Sp5Error("Ausgeblendete Schichten: Monate müssen 0 oder mehr sein");
        }
        if (q.recentMonths() < 0) {
            throw new Sp5Error("Bereiche nicht mehr gearbeitet: Monate müssen 0 oder mehr sein");
        }
        if (q.mixMinDuties() != null && q.mixMinDuties() < 1) {
            throw new Sp5Error("Schichtmix: Mindestzahl der Dienste muss mindestens 1 sein");
        }
        if (!DEMAND_SOURCES.contains(q.demand())) {
            throw new Sp5Error("Bedarf " + Py.repr(q.demand()) + ": erlaubt sind " + String.join(" oder ",
                    DEMAND_SOURCES));
        }
        if (q.demand().equals("history") && history == 0) {
            throw new Sp5Error("Bedarf aus der Historie braucht eine Historie (--history-months ab 1); ohne Historie "
                    + "--demand sp5 angeben");
        }
        if (!Files.isDirectory(q.folder())) {
            throw new Sp5Error("SP5-Ordner nicht gefunden: " + q.folder());
        }
        Sp5Import job = new Sp5Import(q.folder(), months, q.mapping(), history, q.areasFromHistory(),
                q.shiftsFromHistory(), q.demand(), q.mixMinDuties(), q.hiddenShiftsMonths(), q.recentMonths(),
                q.nightsFair());
        job.areaSource = q.areaSource();
        job.historyAreaDuties = q.historyAreaDuties();
        Map<String, Object> dataset = job.run();
        List<List<Object>> rows = new ArrayList<>();
        if (q.corrections() != null) {
            Corrections.Applied applied = Corrections.apply(dataset, q.corrections());
            rows = applied.rows();
            for (List<Object> r : rows) {
                List<Object> row = new ArrayList<>();
                row.add("korrektur");
                row.add(r.get(0));
                for (int i = 0; i < 16; i++) {
                    row.add("");
                }
                row.add(r.get(3));
                for (int i = 0; i < 4; i++) {
                    row.add("");
                }
                row.add(r.get(1));
                row.add(r.get(2));
                row.add(r.get(4));
                row.add(r.get(5));
                job.reportRows.add(row);
            }
            job.warnings.addAll(applied.warnings());
            job.warn("Korrekturen: " + applied.corrected() + " Mitarbeiter korrigiert, " + applied.applied()
                    + " Felder angewendet, " + applied.skipped() + " Felder übersprungen");
            if (applied.summary() != null) {
                job.warn(applied.summary());
            }
        }
        dataset.put("appliedCorrections", Corrections.section(q.corrections(), rows));
        // after the corrections, which compare with the demand as sp5_export.py computes it: corrected demand too
        if (q.demand().equals("history") && DEMAND_MAX_MIN.equals(q.demandMax())) {
            withMaxAtMinimum(dataset);
        }
        List<List<Object>> report = new ArrayList<>();
        for (List<Object> r : job.reportRows) {
            List<Object> padded = new ArrayList<>(r);
            while (padded.size() < REPORT_COLUMNS) {
                padded.add("");
            }
            report.add(padded);
        }
        java.util.IdentityHashMap<List<Object>, java.math.BigInteger> person = new java.util.IdentityHashMap<>();
        for (List<Object> r : report) {
            String pid = (String) r.get(1);
            person.put(r, pid.isEmpty() ? java.math.BigInteger.ZERO
                    : java.math.BigInteger.valueOf(Py.at("sp5.py", 1296, () -> Py.pyInt(pid.substring(
                            pid.offsetByCodePoints(0, 1))))));
        }
        report.sort(Comparator.<List<Object>, String>comparing(r -> (String) r.get(0), Py::compareStr)
                .thenComparing(person::get)
                .thenComparing(r -> (String) r.get(2), Py::compareStr)
                .thenComparing(r -> (String) r.get(3), Py::compareStr));
        return new Result(dataset, report, job.warnings, job.areaSources, job.areaCounts);
    }

    /**
     * Every demand rule with its maximum at its minimum: staffing above the minimum is then overstaffing in the plan
     * (a window with minimum 0 is a time without staffing).
     */
    @SuppressWarnings("unchecked")
    static void withMaxAtMinimum(Map<String, Object> dataset) {
        for (Map<String, Object> rule : (List<Map<String, Object>>) dataset.get("demand")) {
            rule.put("max", rule.get("min"));
        }
    }

    /** The dataset file: json.dump(dataset, ensure_ascii=False, indent=1) and a newline, UTF-8. */
    public static byte[] datasetBytes(Result result) {
        return (PyJson.dump(result.dataset()) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /** The report file: the header row and the rows, csv.writer(delimiter=";"), UTF-8. */
    public static byte[] reportBytes(Result result) {
        StringBuilder out = new StringBuilder();
        PyCsv.row(out, REPORT_HEADER);
        for (List<Object> r : result.reportRows()) {
            PyCsv.row(out, r);
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }
}
