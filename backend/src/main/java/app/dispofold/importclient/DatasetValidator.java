package app.dispofold.importclient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

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
import app.dispofold.model.PreviousDuty;
import app.dispofold.model.Restriction;
import app.dispofold.model.Rules;
import app.dispofold.model.ShiftType;
import app.dispofold.model.Wish;
import app.dispofold.model.WishKind;
import app.dispofold.solver.ShiftOption;

/**
 * Checks a dataset before it is stored. Nothing is dropped or repaired: every defect is an error and the import
 * fails as a whole; harmless findings are warnings. Each defect yields one issue with a code (see the list in
 * docs/datenformat.md) and params that hold ids, numbers and ISO dates only, never names.
 */
public final class DatasetValidator {

    public record Result(Dataset dataset, List<Issue> errors, List<Issue> warnings) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    private static final Set<String> DAY_CODES = Set.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "HOL");
    private static final Pattern MONTH = Pattern.compile("\\d{4}-\\d{2}");

    private final List<Issue> errors = new ArrayList<>();
    private final List<Issue> warnings = new ArrayList<>();
    private Set<String> personIds = Set.of();
    private Set<String> shiftIds = Set.of();
    private Set<String> typeCodes = Set.of();

    private DatasetValidator() {
    }

    /** Whole dataset (import). Shift lengths are checked against {@code rules.maxDutyHours()}. */
    public static Result validate(Dataset d, Rules rules) {
        DatasetValidator v = new DatasetValidator();
        if (d == null || !Dataset.FORMAT.equals(d.format())) {
            v.error("import.format");
        } else if (d.version() != Dataset.VERSION) {
            v.error("import.version", "version", d.version());
        } else {
            v.master(d, rules == null ? Rules.defaults() : rules);
            v.periods(d.periods());
        }
        return v.result(d);
    }

    /**
     * One planning month entered via the API, checked against the stored master data; the periods of the master
     * and defects of the master itself are ignored. The result's dataset is the master with {@code period} as its
     * only period.
     */
    public static Result validatePeriod(Dataset master, Period period, Rules rules) {
        DatasetValidator v = new DatasetValidator();
        v.personIds = ids(master.people(), Person::id);
        v.shiftIds = ids(master.shifts(), ShiftType::id);
        v.typeCodes = ids(master.absenceTypes(), AbsenceType::code);
        if (period == null) {
            v.error("import.format");
        } else {
            if (period.month() == null) {
                v.missing("period", "month", 0, null);
            }
            v.period(period, 0);
        }
        return v.result(new Dataset(master.format(), master.version(), master.source(), master.areas(),
                master.shifts(), master.demand(), master.holidays(), master.absenceTypes(), master.people(),
                master.restrictions(), master.history(), period == null ? List.of() : List.of(period)));
    }

    private Result result(Dataset d) {
        return new Result(d, List.copyOf(errors), List.copyOf(warnings));
    }

    private void master(Dataset d, Rules rules) {
        required(d.areas(), "areas");
        required(d.shifts(), "shifts");
        required(d.demand(), "demand");
        required(d.holidays(), "holidays");
        required(d.absenceTypes(), "absenceTypes");
        required(d.people(), "people");
        required(d.restrictions(), "restrictions");
        required(d.history(), "history");
        required(d.periods(), "periods");
        if (d.people() != null && d.people().isEmpty()) {
            error("import.noPeople");
        }
        if (d.shifts() != null && d.shifts().isEmpty()) {
            error("import.noShifts");
        }

        Set<String> areaIds = unique(d.areas(), Area::id, "area", "id");
        shiftIds = unique(d.shifts(), ShiftType::id, "shift", "id");
        typeCodes = unique(d.absenceTypes(), AbsenceType::code, "absenceType", "code");
        personIds = unique(d.people(), Person::id, "person", "id");

        long maxMinutes = Math.round(rules.maxDutyHours() * 60);
        nonNull(d.shifts(), (s, i) -> {
            if (present(s.areaId(), "shift", "areaId", i, null) && !areaIds.contains(s.areaId())) {
                error("import.unknownArea", "kind", "shift", "shiftId", s.id(), "areaId", s.areaId());
            }
            if (ShiftOption.COMP_REST_ID.equals(s.id())) {
                error("import.shiftIdReserved", "shiftId", s.id());
            }
            if (s.start() == null || s.end() == null) {
                error("import.missingTime", "shiftId", s.id());
                return;
            }
            long minutes = Duration.between(s.start(), s.end()).toMinutes();
            if (minutes <= 0) {
                minutes += 24 * 60;
            }
            if (minutes > maxMinutes) {
                error("import.shiftTooLong", "shiftId", s.id(), "minutes", minutes, "maxMinutes", maxMinutes);
            }
        });

        each(d.demand(), "demand", null, (r, i) -> {
            if (present(r.areaId(), "demand", "areaId", i, null) && !areaIds.contains(r.areaId())) {
                error("import.unknownArea", "kind", "demand", "index", i, "areaId", r.areaId());
            }
            if (r.from() == null) {
                missing("demand", "from", i, null);
            }
            if (r.to() == null) {
                missing("demand", "to", i, null);
            }
            for (String day : list(r.days())) {
                if (day == null || !DAY_CODES.contains(day)) {
                    error("import.badDemandDay", "areaId", r.areaId(), "index", i, "day", day);
                }
            }
            if (r.date() == null && list(r.days()).isEmpty() || r.min() < 0
                    || r.max() != null && r.max() < r.min()) {
                error("import.badDemand", "areaId", r.areaId(), "index", i);
            }
        });

        each(d.holidays(), "holiday", null, (Holiday h, int i) -> {
            if (h.date() == null) {
                missing("holiday", "date", i, null);
            }
        });

        nonNull(d.people(), (p, i) -> {
            if (!(p.weeklyHours() > 0)) {
                error("import.badWeeklyHours", "personId", p.id(), "weeklyHours", p.weeklyHours());
            }
            if (p.areaIds() == null) {
                missing("person", "areaIds", i, null);
            }
            for (String areaId : list(p.areaIds())) {
                if (!areaIds.contains(areaId)) {
                    error("import.unknownArea", "kind", "person", "personId", p.id(), "areaId", areaId);
                }
            }
            if (p.entryDate() != null && p.exitDate() != null && p.exitDate().isBefore(p.entryDate())) {
                error("import.badEmployment", "personId", p.id());
            }
            for (FamilyRule f : list(p.familyRules())) {
                if (f == null) {
                    error("import.missingField", "kind", "familyRule", "field", "familyRules", "personId", p.id());
                }
                for (Integer weekday : list(f == null ? null : f.weekdays())) {
                    if (weekday == null || weekday < 1 || weekday > 7) {
                        error("import.badWeekday", "kind", "familyRule", "personId", p.id(), "weekday", weekday);
                    }
                }
            }
        });

        Set<String> restrictionKeys = new HashSet<>();
        each(d.restrictions(), "restriction", null, (r, i) -> {
            person(r.personId(), "restriction", i, null, null);
            if ((r.shiftId() == null) == (r.category() == null)) {
                error("import.restrictionTarget", "personId", r.personId());
            } else if (r.shiftId() != null && !shiftIds.contains(r.shiftId())) {
                error("import.unknownShift", "kind", "restriction", "personId", r.personId(), "shiftId",
                        r.shiftId());
            }
            if (r.weekday() < 0 || r.weekday() > 7) {
                error("import.badWeekday", "kind", "restriction", "personId", r.personId(), "weekday", r.weekday());
            }
            if (r.kind() == null) {
                missing("restriction", "kind", i, null);
            }
            if (r.validFrom() != null && r.validTo() != null && r.validTo().isBefore(r.validFrom())) {
                error("import.badValidity", "personId", r.personId());
            }
            String target = r.shiftId() != null ? r.shiftId() : String.valueOf(r.category());
            if (!restrictionKeys.add(r.personId() + "/" + target + "/" + r.weekday())) {
                warning("import.duplicateRestriction", "personId", r.personId(), "shiftId", r.shiftId(),
                        "category", r.category() == null ? null : r.category().name(), "weekday", r.weekday());
            }
        });

        Set<String> historyKeys = new HashSet<>();
        // History belongs before the planned months; an entry for a planned month or later is suspicious.
        YearMonth firstPlanned = d.periods() == null ? null : d.periods().stream()
                .filter(p -> p != null && p.month() != null).map(Period::month).min(YearMonth::compareTo)
                .orElse(null);
        each(d.history(), "history", null, (HistoryEntry h, int i) -> {
            String month = h.month();
            person(h.personId(), "history", i, null, month);
            if (present(month, "history", "month", i, null)) {
                YearMonth ym = yearMonth(month);
                if (ym == null) {
                    error("import.badMonth", "personId", h.personId(), "month", month);
                } else if (firstPlanned != null && !ym.isBefore(firstPlanned)) {
                    warning("import.historyInFuture", "personId", h.personId(), "month", month);
                }
                if (h.personId() != null && !historyKeys.add(h.personId() + "/" + month)) {
                    error("import.duplicateEntry", "kind", "history", "personId", h.personId(), "month", month);
                }
            }
            if (present(h.source(), "history", "source", i, null) && !HistoryEntry.IMPORT.equals(h.source())
                    && !HistoryEntry.PLAN.equals(h.source())) {
                error("import.badHistorySource", "personId", h.personId(), "month", month);
            }
        });
    }

    private void periods(List<Period> periods) {
        Set<YearMonth> months = new HashSet<>();
        each(periods, "period", null, (p, i) -> {
            if (p.month() == null) {
                missing("period", "month", i, null);
            } else if (!months.add(p.month())) {
                error("import.duplicateId", "kind", "period", "id", p.month().toString());
            }
            period(p, i);
        });
    }

    private void period(Period p, int index) {
        YearMonth month = p.month();
        String m = month == null ? null : month.toString();
        required(p.absences(), "absences", index, m);
        required(p.wishes(), "wishes", index, m);
        required(p.fixed(), "fixed", index, m);
        required(p.previousDuties(), "previousDuties", index, m);

        Set<String> absenceDays = new HashSet<>();
        each(p.absences(), "absence", m, (Absence a, int i) -> {
            boolean known = person(a.personId(), "absence", i, a.date(), m);
            boolean dated = date(a.date(), "absence", i, m) && inMonth(a.date(), month, "absence", a.personId());
            if (present(a.typeCode(), "absence", "typeCode", i, m) && !typeCodes.contains(a.typeCode())) {
                error("import.unknownAbsenceType", "personId", a.personId(), "typeCode", a.typeCode(), "date",
                        iso(a.date()));
            }
            if (known && dated && !absenceDays.add(a.personId() + "/" + a.date())) {
                warning("import.duplicateAbsence", "personId", a.personId(), "date", iso(a.date()));
            }
        });

        each(p.wishes(), "wish", m, (Wish w, int i) -> {
            person(w.personId(), "wish", i, w.date(), m);
            if (date(w.date(), "wish", i, m)) {
                inMonth(w.date(), month, "wish", w.personId());
            }
            if (present(w.kind(), "wish", "kind", i, m) && w.shiftId() == null
                    && (w.kind() == WishKind.SHIFT || w.kind() == WishKind.NOT_SHIFT)) {
                error("import.wishWithoutShift", "personId", w.personId(), "date", iso(w.date()));
            }
            shift(w.shiftId(), "wish", w.personId(), w.date());
        });

        Set<String> fixedDays = new HashSet<>();
        each(p.fixed(), "fixed", m, (FixedAssignment f, int i) -> {
            boolean known = person(f.personId(), "fixed", i, f.date(), m);
            boolean dated = date(f.date(), "fixed", i, m) && inMonth(f.date(), month, "fixed", f.personId());
            shift(f.shiftId(), "fixed", f.personId(), f.date());
            if (known && dated && !fixedDays.add(f.personId() + "/" + f.date())) {
                error("import.duplicateEntry", "kind", "fixed", "personId", f.personId(), "date", iso(f.date()));
            }
        });

        each(p.previousDuties(), "previousDuty", m, (PreviousDuty v, int i) -> {
            person(v.personId(), "previousDuty", i, v.date(), m);
            if (date(v.date(), "previousDuty", i, m) && month != null && !v.date().isBefore(month.atDay(1))) {
                error("import.dateOutsideMonth", "kind", "previousDuty", "personId", v.personId(), "date",
                        iso(v.date()), "month", m);
            }
            if (present(v.shiftId(), "previousDuty", "shiftId", i, m)) {
                shift(v.shiftId(), "previousDuty", v.personId(), v.date());
            }
        });
    }

    /** Reports a missing or unknown person id; true if the id is known. */
    private boolean person(String personId, String kind, int index, LocalDate date, String month) {
        if (!present(personId, kind, "personId", index, month)) {
            return false;
        }
        if (personIds.contains(personId)) {
            return true;
        }
        error("import.unknownPerson", "kind", kind, "personId", personId, "date", iso(date), "month", month);
        return false;
    }

    /** Reports an unknown shift id; null means no shift and is fine here. */
    private void shift(String shiftId, String kind, String personId, LocalDate date) {
        if (shiftId != null && !shiftIds.contains(shiftId)) {
            error("import.unknownShift", "kind", kind, "personId", personId, "shiftId", shiftId, "date", iso(date));
        }
    }

    private boolean date(LocalDate date, String kind, int index, String month) {
        return present(date, kind, "date", index, month);
    }

    /** Reports a date outside the planning month; true if inside or the month is unknown. */
    private boolean inMonth(LocalDate date, YearMonth month, String kind, String personId) {
        if (month == null || YearMonth.from(date).equals(month)) {
            return true;
        }
        error("import.dateOutsideMonth", "kind", kind, "personId", personId, "date", iso(date), "month",
                month.toString());
        return false;
    }

    private boolean present(Object value, String kind, String field, int index, String month) {
        if (value == null || value instanceof String s && s.isBlank()) {
            missing(kind, field, index, month);
            return false;
        }
        return true;
    }

    private void missing(String kind, String field, Integer index, String month) {
        error("import.missingField", "kind", kind, "field", field, "index", index, "month", month);
    }

    private void required(List<?> list, String field) {
        if (list == null) {
            missing("dataset", field, null, null);
        }
    }

    private void required(List<?> list, String field, int index, String month) {
        if (list == null) {
            missing("period", field, index, month);
        }
    }

    /** Collects the ids of the items and reports null items, missing and duplicate ids. */
    private <T> Set<String> unique(List<T> items, Function<T, String> id, String kind, String field) {
        Set<String> seen = new HashSet<>();
        each(items, kind, null, (item, i) -> {
            String key = id.apply(item);
            if (present(key, kind, field, i, null) && !seen.add(key)) {
                error("import.duplicateId", "kind", kind, "id", key);
            }
        });
        return seen;
    }

    private interface Check<T> {
        void check(T item, int index);
    }

    /** Runs the check for every item; a null item is reported as missing. */
    private <T> void each(List<T> items, String kind, String month, Check<T> check) {
        List<T> all = list(items);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) == null) {
                missing(kind, null, i, month);
            } else {
                check.check(all.get(i), i);
            }
        }
    }

    /** Runs the check for every item that is not null; for lists whose null items {@link #unique} reported. */
    private static <T> void nonNull(List<T> items, Check<T> check) {
        List<T> all = list(items);
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i) != null) {
                check.check(all.get(i), i);
            }
        }
    }

    private static <T> Set<String> ids(List<T> items, Function<T, String> id) {
        Set<String> ids = new HashSet<>();
        for (T item : list(items)) {
            if (item != null && id.apply(item) != null) {
                ids.add(id.apply(item));
            }
        }
        return ids;
    }

    private static YearMonth yearMonth(String month) {
        if (!MONTH.matcher(month).matches()) {
            return null;
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String iso(LocalDate date) {
        return date == null ? null : date.toString();
    }

    private static <T> List<T> list(List<T> list) {
        return list == null ? List.of() : list;
    }

    private void error(String code, Object... params) {
        errors.add(issue(code, params));
    }

    private void warning(String code, Object... params) {
        warnings.add(issue(code, params));
    }

    /** Params as key/value pairs; null values are left out. */
    private static Issue issue(String code, Object... params) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < params.length; i += 2) {
            if (params[i + 1] != null) {
                map.put((String) params[i], params[i + 1]);
            }
        }
        return new Issue(code, Collections.unmodifiableMap(map));
    }
}
