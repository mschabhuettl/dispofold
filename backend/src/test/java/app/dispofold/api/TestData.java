package app.dispofold.api;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import app.dispofold.model.Absence;
import app.dispofold.model.AbsenceType;
import app.dispofold.model.Area;
import app.dispofold.model.Dataset;
import app.dispofold.model.DemandRule;
import app.dispofold.model.FixedAssignment;
import app.dispofold.model.HistoryEntry;
import app.dispofold.model.Holiday;
import app.dispofold.model.Period;
import app.dispofold.model.Person;
import app.dispofold.model.ShiftCategory;
import app.dispofold.model.ShiftType;
import app.dispofold.model.Wish;
import app.dispofold.model.WishKind;

/** Small synthetic dataset: two areas, three shifts, six people. */
public final class TestData {

    private TestData() {
    }

    public static Dataset dataset(YearMonth month) {
        List<Area> areas = List.of(new Area("AN", "Notrufannahme", "#0F766E"),
                new Area("DR", "Disposition Rettung", "#1F4E79"));
        List<ShiftType> shifts = List.of(
                new ShiftType("AN-T", "AN", "A2", "Annahme Tag 07:00", LocalTime.of(7, 0), LocalTime.of(19, 0),
                        ShiftCategory.DAY, "#F6E3D3", false),
                new ShiftType("DR-T", "DR", "R1", "Disposition Tag 07:00", LocalTime.of(7, 0), LocalTime.of(19, 0),
                        ShiftCategory.DAY, "#DDEBF7", false),
                new ShiftType("DR-N", "DR", "R4", "Disposition Nacht 19:00", LocalTime.of(19, 0), LocalTime.of(7, 0),
                        ShiftCategory.NIGHT, "#9BC2E6", true));
        List<String> all = List.of("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN", "HOL");
        List<DemandRule> demand = List.of(
                new DemandRule("AN", all, null, LocalTime.of(7, 0), LocalTime.of(19, 0), 1, null),
                new DemandRule("DR", all, null, LocalTime.of(7, 0), LocalTime.of(19, 0), 1, null),
                new DemandRule("DR", all, null, LocalTime.of(19, 0), LocalTime.of(7, 0), 1, null));
        List<Person> people = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            people.add(new Person(String.format("P%04d", i), "Vorname" + i, "Testperson" + i, "T" + i, 40.0,
                    i % 2 == 0 ? List.of("AN", "DR") : List.of("AN"), 0, 0, List.of(), String.valueOf(i), null, null));
        }
        List<AbsenceType> types = List.of(new AbsenceType("U", "Urlaub", true, false, false, "#A3C585"),
                new AbsenceType("ER", "Ersatzruhe", true, false, true, "#8FD3C7"));
        LocalDate first = month.atDay(1);
        Period period = new Period(month, List.of(new Absence("P0001", first.plusDays(2), "U")),
                List.of(new Wish("P0002", first.plusDays(3), WishKind.OFF, null)),
                List.of(new FixedAssignment("P0003", first, "AN-T")), List.of());
        List<HistoryEntry> history = List.of(
                new HistoryEntry("P0001", month.minusMonths(1).toString(), HistoryEntry.IMPORT, 3, 2, 0, 1, 2, 1, 160,
                        null, null, 0, 0));
        return new Dataset(Dataset.FORMAT, Dataset.VERSION, "synthetic", areas, shifts, demand,
                List.of(new Holiday(month.atDay(8), "Testfeiertag")), types, people, List.of(), history,
                List.of(period));
    }
}
