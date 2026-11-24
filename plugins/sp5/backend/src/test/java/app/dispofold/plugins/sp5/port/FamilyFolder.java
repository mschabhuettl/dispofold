package app.dispofold.plugins.sp5.port;

import static app.dispofold.plugins.sp5.port.TestTables.c;
import static app.dispofold.plugins.sp5.port.TestTables.d;
import static app.dispofold.plugins.sp5.port.TestTables.f;
import static app.dispofold.plugins.sp5.port.TestTables.n;
import static app.dispofold.plugins.sp5.port.TestTables.row;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A synthetic folder whose groups do not separate the shifts: two umbrella groups hold almost everyone, the areas are
 * families of shift names. NW (day and night, one staff), LK with the variants LK-F and LK-S (one rotating staff),
 * P, PU and P/PU (one staff under three names), M-QZ and W-QZ (the same letters after the dash, different staff), DS
 * and FA; a pool of three stand-ins works NW and LK now and then; a hidden NW code and a rare one. Only the small group
 * Team P matches a family. Invented codes and names only.
 */
public final class FamilyFolder {

    /** {short name, hidden, first person, last person, duties per person}: who works the shift in January 2027. */
    public static final Object[][] SHIFTS = {
        { "NW 7-19", 0L, 1, 8, 6 }, { "NW 19-7", 0L, 1, 8, 5 }, { "NW 0-24", 1L, 1, 2, 1 },
        { "LK-F 6-14", 0L, 9, 18, 4 }, { "LK-S 14-22", 0L, 9, 18, 4 }, { "LK 7-19", 0L, 9, 18, 3 },
        { "P 7-19", 0L, 19, 24, 5 }, { "PU 19-7", 0L, 19, 24, 4 }, { "P/PU 18-6", 0L, 19, 24, 2 },
        { "M-QZ 7-19", 0L, 25, 28, 8 }, { "W-QZ 7-19", 0L, 29, 32, 8 },
        { "DS 7-19", 0L, 36, 40, 6 }, { "DS 19-7", 0L, 36, 40, 5 }, { "FA 7-19", 0L, 41, 45, 9 },
        { "Q 8-12", 0L, 1, 1, 2 },
    };

    /** The stand-ins 33 to 35: two duties each in NW 7-19 and in LK 7-19. */
    static final int[] POOL = { 33, 34, 35 };

    private FamilyFolder() {
    }

    public static void write(Path dir) throws Exception {
        write(dir, SHIFTS);
    }

    /** The folder with other shifts, in the shape of {@link #SHIFTS}. */
    public static void write(Path dir, Object[][] SHIFTS) throws Exception {
        List<TestTables.Field> shift = new ArrayList<>(List.of(n("ID", 11), c("NAME", 40), c("SHORTNAME", 24),
                n("HIDE", 1), n("COLORBK", 11)));
        for (int i = 0; i < 8; i++) {
            shift.add(c("STARTEND" + i, 36));
        }
        Object[][] shifts = new Object[SHIFTS.length][];
        for (int s = 0; s < SHIFTS.length; s++) {
            List<Object> r = new ArrayList<>(List.of((long) s + 1, "Dienst " + SHIFTS[s][0], SHIFTS[s][0], SHIFTS[s][1],
                    0L));
            for (int i = 0; i < 8; i++) {
                r.add("07:00-19:00".getBytes(StandardCharsets.US_ASCII));
            }
            shifts[s] = r.toArray();
        }
        TestTables.write(dir, "5SHIFT", shift, shifts);
        TestTables.write(dir, "5GROUP", List.of(n("ID", 11), c("NAME", 40), c("SHORTNAME", 24), n("SUPERID", 11)),
                row(1L, "Tagdienst", "TD", 0L), row(2L, "Nachtdienst", "ND", 0L), row(3L, "Team P", "TP", 0L));
        List<Object[]> grasg = new ArrayList<>();
        for (int e = 1; e <= 45; e++) {
            if (e <= 24 || e >= 33) {
                grasg.add(row((long) grasg.size() + 1, (long) e, 1L));
            }
            if (e >= 9) {
                grasg.add(row((long) grasg.size() + 1, (long) e, 2L));
            }
            if (e >= 19 && e <= 24) {
                grasg.add(row((long) grasg.size() + 1, (long) e, 3L));
            }
        }
        TestTables.write(dir, "5GRASG", List.of(n("ID", 11), n("EMPLOYEEID", 11), n("GROUPID", 11)),
                grasg.toArray(new Object[0][]));
        List<Object[]> people = new ArrayList<>();
        for (long e = 1; e <= 45; e++) {
            people.add(row(e, "Person" + e, "Vorname" + e, "P" + e, 0L,
                    "1 1 1 1 1 0 0 0".getBytes(StandardCharsets.US_ASCII), 1L, "8.00", "40.00", "0.00", "0.00",
                    "2015-01-01", null));
        }
        TestTables.write(dir, "5EMPL", List.of(n("ID", 11), c("NAME", 40), c("FIRSTNAME", 40), c("SHORTNAME", 24),
                n("HIDE", 1), c("WORKDAYS", 16), n("CALCBASE", 11), f("HRSDAY", 10, 2), f("HRSWEEK", 10, 2),
                f("HRSMONTH", 10, 2), f("HRSTOTAL", 10, 2), d("EMPSTART"), d("EMPEND")), people.toArray(new Object[0][]));
        List<Object[]> duties = new ArrayList<>();
        for (int s = 0; s < SHIFTS.length; s++) {
            for (int e = (int) SHIFTS[s][2]; e <= (int) SHIFTS[s][3]; e++) {
                for (int k = 0; k < (int) SHIFTS[s][4]; k++) {
                    duties.add(row((long) duties.size() + 1, (long) e, (long) s + 1,
                            String.format("2027-01-%02d", (duties.size() % 28) + 1), 0L));
                }
            }
        }
        for (int e : POOL) {
            for (long s : new long[] { 1, 6 }) {
                for (int k = 0; k < 2; k++) {
                    duties.add(row((long) duties.size() + 1, (long) e, s,
                            String.format("2027-01-%02d", (duties.size() % 28) + 1), 0L));
                }
            }
        }
        TestTables.write(dir, "5MASHI", List.of(n("ID", 11), n("EMPLOYEEID", 11), n("SHIFTID", 11), d("DATE"),
                n("TYPE", 11)), duties.toArray(new Object[0][]));
        TestTables.write(dir, "5LEAVT", List.of(n("ID", 11), c("NAME", 40), c("SHORTNAME", 24), n("CHARGETYP", 11),
                n("DEDUCTACT", 1), n("DEDUCTOVT", 1), n("COLORBK", 11)), row(1L, "Urlaub", "U", 1L, 0L, 0L, 0L),
                row(2L, "Ersatzruhe", "ER", 1L, 0L, 0L, 0L));
    }
}
