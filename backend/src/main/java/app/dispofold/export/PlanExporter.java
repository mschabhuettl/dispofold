package app.dispofold.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import app.dispofold.model.Absence;
import app.dispofold.model.Dataset;
import app.dispofold.model.Holiday;
import app.dispofold.model.Period;
import app.dispofold.model.Person;
import app.dispofold.model.PersonStats;
import app.dispofold.model.Plan;
import app.dispofold.model.PlanAssignment;
import app.dispofold.model.ShiftType;

/** Plan downloads: Excel workbook, CSV for Excel and the plan JSON document. */
@ApplicationScoped
public class PlanExporter {

    public static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String CSV_HEADER =
            "personId;Name;date;shiftCode;shiftId;start;end;hours;compRest;pinned;absenceCode";

    /** Column titles of the sheet "Kennzahlen", in the order of the {@link PersonStats} components. */
    static final List<String> STATS_HEADER = List.of("Id", "Soll (h)", "Gutschrift (h)", "Geplant (h)",
            "Konto vorher (h)", "Konto nachher (h)", "Dienste", "Nächte", "Wochenenden gearbeitet",
            "Wochenenden frei", "Feiertage", "Unbeliebte Dienste", "Wünsche", "Wünsche erfüllt", "ER geplant",
            "ER erworben", "ER offen danach", "Einzeltage", "Historie Nächte", "Historie Wochenenden",
            "Historie Feiertage", "Historie unbeliebt", "Historie Wünsche", "Historie Wünsche erfüllt",
            "Verfügbare Tage", "Konto belastet (h)", "Wochenschnitt (h)", "Wochenschnitt geprüft");

    private static final List<String> WEEKDAYS = List.of("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So");
    private static final Locale DE = Locale.GERMAN;

    @Inject
    ObjectMapper mapper;

    public byte[] json(Plan plan) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(plan);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("plan cannot be serialised", e);
        }
    }

    /** Line in front of the data when the plan breaks hard rules. */
    public static String violationMark(int hardViolations) {
        return "Achtung: " + hardViolations + " harte Regelverstöße, Plan nicht verwendbar";
    }

    /**
     * One row per person and day, semicolon separated, UTF-8 with BOM so that Excel detects the encoding. Absence
     * days carry the absence code. With hard violations the file starts with {@link #violationMark}.
     */
    public byte[] csv(Dataset dataset, Period period, Plan plan, int hardViolations) {
        Map<String, ShiftType> shifts = byId(dataset.shifts(), ShiftType::id);
        Map<String, String> codes = uniqueCodes(dataset.shifts());
        Map<String, Person> people = byId(dataset.people(), Person::id);
        Map<String, String> absences = period.absences().stream()
                .collect(Collectors.toMap(a -> a.personId() + "/" + a.date(), Absence::typeCode, (x, y) -> y));
        StringBuilder out = new StringBuilder("﻿");
        if (hardViolations > 0) {
            out.append(violationMark(hardViolations)).append("\r\n");
        }
        out.append(CSV_HEADER).append("\r\n");
        for (PlanAssignment a : plan.assignments()) {
            ShiftType s = a.shiftId() == null ? null : shifts.get(a.shiftId());
            Person p = people.get(a.personId());
            out.append(String.join(";", csvField(a.personId()), csvField(p == null ? "" : p.displayName()),
                    a.date().toString(), csvField(s == null ? "" : codes.get(s.id())), csvField(a.shiftId()),
                    s == null ? "" : s.start().toString(), s == null ? "" : s.end().toString(),
                    s == null ? "" : String.format(DE, "%.2f", hours(s)), String.valueOf(a.compRest()),
                    String.valueOf(a.pinned()), csvField(absences.getOrDefault(a.personId() + "/" + a.date(), ""))))
                    .append("\r\n");
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Sheet "Dienstplan" (people x days) and sheet "Kennzahlen" (per-person figures). */
    public byte[] xlsx(Dataset dataset, Period period, Plan plan, List<PersonStats> stats, int hardViolations) {
        Map<String, ShiftType> shifts = byId(dataset.shifts(), ShiftType::id);
        Map<String, String> codes = uniqueCodes(dataset.shifts());
        Set<LocalDate> holidays = dataset.holidays().stream().map(Holiday::date).collect(Collectors.toSet());
        Map<String, PlanAssignment> cells = plan.assignments().stream()
                .collect(Collectors.toMap(a -> a.personId() + "/" + a.date(), a -> a, (x, y) -> y));
        Map<String, String> absences = period.absences().stream()
                .collect(Collectors.toMap(a -> a.personId() + "/" + a.date(), Absence::typeCode, (x, y) -> y));
        int days = plan.month().lengthOfMonth();

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            Styles styles = new Styles(wb);
            XSSFSheet sheet = wb.createSheet("Dienstplan");
            Row weekdays = sheet.createRow(0);
            Row dates = sheet.createRow(1);
            weekdays.createCell(0).setCellValue("Id");
            weekdays.createCell(1).setCellValue("Name");
            for (int d = 1; d <= days; d++) {
                LocalDate date = plan.month().atDay(d);
                CellStyle style = styles.header(free(date, holidays));
                var w = weekdays.createCell(1 + d);
                w.setCellValue(WEEKDAYS.get(date.getDayOfWeek().getValue() - 1));
                w.setCellStyle(style);
                var t = dates.createCell(1 + d);
                t.setCellValue(String.format("%02d.%02d.", d, date.getMonthValue()));
                t.setCellStyle(style);
                sheet.setColumnWidth(1 + d, 6 * 256);
            }
            weekdays.createCell(days + 2).setCellValue("Stunden");
            if (hardViolations > 0) {
                weekdays.createCell(days + 3).setCellValue(violationMark(hardViolations));
                wb.setSheetName(0, "Dienstplan mit Verstößen");
            }
            sheet.createFreezePane(2, 2);
            sheet.setColumnWidth(1, 24 * 256);

            int r = 2;
            for (Person p : dataset.people()) {
                Row row = sheet.createRow(r++);
                row.createCell(0).setCellValue(p.id());
                row.createCell(1).setCellValue(p.displayName());
                double sum = 0;
                for (int d = 1; d <= days; d++) {
                    LocalDate date = plan.month().atDay(d);
                    String key = p.id() + "/" + date;
                    PlanAssignment a = cells.get(key);
                    ShiftType s = a == null || a.shiftId() == null ? null : shifts.get(a.shiftId());
                    var cell = row.createCell(1 + d);
                    if (s != null) {
                        cell.setCellValue(codes.get(s.id()));
                        cell.setCellStyle(styles.fill(s.color(), free(date, holidays)));
                        sum += hours(s);
                    } else {
                        String code = a != null && a.compRest() ? "ER" : absences.getOrDefault(key, "");
                        cell.setCellValue(code);
                        cell.setCellStyle(styles.fill(null, free(date, holidays)));
                    }
                }
                row.createCell(days + 2).setCellValue(sum);
            }

            XSSFSheet figures = wb.createSheet("Kennzahlen");
            Row header = figures.createRow(0);
            header.createCell(0).setCellValue("Name");
            for (int c = 0; c < STATS_HEADER.size(); c++) {
                header.createCell(c + 1).setCellValue(STATS_HEADER.get(c));
            }
            Map<String, Person> people = byId(dataset.people(), Person::id);
            RecordComponent[] components = PersonStats.class.getRecordComponents();
            r = 1;
            for (PersonStats s : stats) {
                Row row = figures.createRow(r++);
                Person p = people.get(s.personId());
                row.createCell(0).setCellValue(p == null ? "" : p.displayName());
                for (int c = 0; c < components.length; c++) {
                    Object value = components[c].getAccessor().invoke(s);
                    if (value instanceof Number n) {
                        row.createCell(c + 1).setCellValue(n.doubleValue());
                    } else {
                        row.createCell(c + 1).setCellValue(String.valueOf(value));
                    }
                }
            }
            wb.write(bytes);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Shift codes as written to the exports, unique: a code used by several shifts gets the area id appended, and
     * if that is still ambiguous the shift id is used.
     */
    static Map<String, String> uniqueCodes(List<ShiftType> shifts) {
        Map<String, Long> byCode = shifts.stream()
                .collect(Collectors.groupingBy(PlanExporter::code, Collectors.counting()));
        Map<String, String> first = new java.util.LinkedHashMap<>();
        for (ShiftType s : shifts) {
            first.put(s.id(), byCode.get(code(s)) > 1 ? code(s) + "/" + s.areaId() : code(s));
        }
        Map<String, Long> again = first.values().stream()
                .collect(Collectors.groupingBy(c -> c, Collectors.counting()));
        Map<String, String> result = new java.util.HashMap<>();
        first.forEach((id, code) -> result.put(id, again.get(code) > 1 ? id : code));
        return result;
    }

    /** The shift code, or the id when there is none. */
    private static String code(ShiftType s) {
        return s.code() == null || s.code().isBlank() ? s.id() : s.code();
    }

    /** Paid hours of a shift; a shift that does not end after its start ends on the next day. */
    static double hours(ShiftType s) {
        Duration d = Duration.between(s.start(), s.end());
        if (!s.end().isAfter(s.start())) {
            d = d.plusDays(1);
        }
        return d.toMinutes() / 60.0;
    }

    private static boolean free(LocalDate date, Set<LocalDate> holidays) {
        return date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY
                || holidays.contains(date);
    }

    private static String csvField(String value) {
        if (value == null) {
            return "";
        }
        if (!value.isEmpty() && "=+-@\t\r".indexOf(value.charAt(0)) >= 0) {
            // Keep spreadsheet programs from reading the value as a formula.
            value = "'" + value;
        }
        if (value.contains(";") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private static <T> Map<String, T> byId(List<T> items, Function<T, String> id) {
        return items.stream().collect(Collectors.toMap(id, t -> t, (x, y) -> x));
    }

    /** Cell styles, cached per colour (a workbook allows a limited number of styles). */
    private static final class Styles {
        private static final byte[] WEEKEND = { (byte) 0xE4, (byte) 0xE4, (byte) 0xE4 };
        private final XSSFWorkbook wb;
        private final Map<String, XSSFCellStyle> cache = new HashMap<>();

        Styles(XSSFWorkbook wb) {
            this.wb = wb;
        }

        CellStyle header(boolean free) {
            return cache.computeIfAbsent("header/" + free, k -> {
                XSSFCellStyle style = base(free ? WEEKEND : null);
                Font font = wb.createFont();
                font.setBold(true);
                style.setFont(font);
                return style;
            });
        }

        CellStyle fill(String color, boolean free) {
            byte[] rgb = rgb(color);
            return cache.computeIfAbsent((rgb == null ? "none" : color) + "/" + free,
                    k -> base(rgb != null ? rgb : free ? WEEKEND : null));
        }

        private XSSFCellStyle base(byte[] rgb) {
            XSSFCellStyle style = wb.createCellStyle();
            style.setAlignment(HorizontalAlignment.CENTER);
            if (rgb != null) {
                style.setFillForegroundColor(new XSSFColor(rgb, null));
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            return style;
        }

        private static byte[] rgb(String color) {
            if (color == null || !color.matches("#[0-9A-Fa-f]{6}")) {
                return null;
            }
            int v = Integer.parseInt(color.substring(1), 16);
            return new byte[] { (byte) (v >> 16), (byte) (v >> 8), (byte) v };
        }
    }
}
