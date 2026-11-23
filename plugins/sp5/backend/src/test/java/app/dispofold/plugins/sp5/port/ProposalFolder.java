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
 * A small synthetic folder for the mapping proposals: two teams with their shifts, a team of stand-ins, an umbrella
 * group with demand of its own, a group over everyone, a group without members and one without duties, a subgroup
 * whose duties go to its parent, a parent over two groups of different areas, a shift split between two teams, a
 * hidden, a rare and an unused shift, and the compensatory rest type. Invented names only.
 */
public final class ProposalFolder {

    /** Short names of the shifts, by id 1 to 11. */
    public static final String[] SHIFTS = { "A1", "A2", "R1", "M1", "Z1", "H1", "T1", "T2", "N1", "S2", "Z0" };

    private ProposalFolder() {
    }

    /** Writes the folder; the duties of Z1 lie in {@code z1Month} (the others in January 2027). */
    public static void write(Path dir, String z1Month) throws Exception {
        List<TestTables.Field> shift = new ArrayList<>(List.of(n("ID", 11), c("NAME", 40), c("SHORTNAME", 24),
                n("HIDE", 1), n("COLORBK", 11)));
        for (int i = 0; i < 8; i++) {
            shift.add(c("STARTEND" + i, 36));
        }
        Object[][] shifts = new Object[SHIFTS.length][];
        for (int s = 0; s < SHIFTS.length; s++) {
            List<Object> r = new ArrayList<>(List.of((long) s + 1, "Dienst " + SHIFTS[s], SHIFTS[s],
                    SHIFTS[s].equals("H1") ? 1L : 0L, 0L));
            for (int i = 0; i < 8; i++) {
                r.add(ascii("07:00-19:00"));
            }
            shifts[s] = r.toArray();
        }
        TestTables.write(dir, "5SHIFT", shift, shifts);
        TestTables.write(dir, "5GROUP", List.of(n("ID", 11), c("NAME", 40), c("SHORTNAME", 24), n("SUPERID", 11)),
                row(1L, "Team Annahme", "G1", 0L), row(2L, "Team Disposition", "G2", 0L),
                row(3L, "Springer Annahme", "G3", 0L), row(4L, "Schicht A", "G4", 0L), row(5L, "Gesamt", "G5", 0L),
                row(6L, "Leer", "G6", 0L), row(7L, "Ruhend", "G7", 0L), row(8L, "Technik", "G8", 0L),
                row(9L, "Technik Nacht", "G9", 8L), row(10L, "Leitstelle", "G10", 0L), row(11L, "Nord", "G11", 10L),
                row(12L, "Süd", "G12", 10L));
        long[][] members = { { 1, 1, 2, 3, 4, 5, 6 }, { 2, 7, 8, 9, 10, 11, 12 }, { 3, 13, 14 }, { 4, 1, 2, 7, 8, 13 },
                { 5, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 }, { 7, 15 }, { 8, 16 }, { 9, 17 }, { 11, 18, 19 },
                { 12, 20, 21 } };
        List<Object[]> grasg = new ArrayList<>();
        for (long[] g : members) {
            for (int i = 1; i < g.length; i++) {
                grasg.add(row((long) grasg.size() + 1, g[i], g[0]));
            }
        }
        TestTables.write(dir, "5GRASG", List.of(n("ID", 11), n("EMPLOYEEID", 11), n("GROUPID", 11)),
                grasg.toArray(new Object[0][]));
        List<Object[]> people = new ArrayList<>();
        for (long e = 1; e <= 21; e++) {
            people.add(row(e, "Person" + e, "Vorname" + e, "P" + e, 0L, ascii("1 1 1 1 1 0 0 0"), 1L, "8.00",
                    "40.00", "0.00", "0.00", "2015-01-01", null));
        }
        TestTables.write(dir, "5EMPL", List.of(n("ID", 11), c("NAME", 40), c("FIRSTNAME", 40), c("SHORTNAME", 24),
                n("HIDE", 1), c("WORKDAYS", 16), n("CALCBASE", 11), f("HRSDAY", 10, 2), f("HRSWEEK", 10, 2),
                f("HRSMONTH", 10, 2), f("HRSTOTAL", 10, 2), d("EMPSTART"), d("EMPEND")), people.toArray(new Object[0][]));
        // {shift id, employees}: A1 70 % Team Annahme and 30 % stand-ins, M1 half and half, Z1 rare, T2 by the subgroup
        long[][] work = { { 1, 1, 2, 3, 4, 5, 6, 1, 13, 14, 13 }, { 2, 1, 2, 3, 4, 5, 6 }, { 3, 7, 8, 9, 10, 11, 12 },
                { 4, 3, 4, 9, 10 }, { 5, 5, 6 }, { 6, 1 }, { 7, 16 }, { 8, 17, 17, 17 }, { 9, 18, 19, 18 },
                { 10, 20, 21, 20 } };
        List<Object[]> duties = new ArrayList<>();
        for (long[] w : work) {
            for (int i = 1; i < w.length; i++) {
                String date = w[0] == 5 ? z1Month + String.format("-%02d", i) : String.format("2027-01-%02d",
                        (duties.size() % 28) + 1);
                duties.add(row((long) duties.size() + 1, w[i], w[0], date, 0L));
            }
        }
        duties.add(row((long) duties.size() + 1, 1L, 2L, "2027-01-28", 1L)); // a target-plan entry: no duty
        TestTables.write(dir, "5MASHI", List.of(n("ID", 11), n("EMPLOYEEID", 11), n("SHIFTID", 11), d("DATE"),
                n("TYPE", 11)), duties.toArray(new Object[0][]));
        // demand: A1 in Team Annahme and in Schicht A, the others in one group each
        long[][] demand = { { 1, 1 }, { 4, 1 }, { 1, 2 }, { 2, 3 }, { 8, 7 }, { 11, 9 }, { 12, 10 } };
        List<Object[]> rows = new ArrayList<>();
        for (long[] x : demand) {
            rows.add(row((long) rows.size() + 1, x[0], 0L, x[1], 1L, 0L));
        }
        TestTables.write(dir, "5SHDEM", List.of(n("ID", 11), n("GROUPID", 11), n("WEEKDAY", 5), n("SHIFTID", 11),
                n("MIN", 5), n("MAX", 5)), rows.toArray(new Object[0][]));
        TestTables.write(dir, "5LEAVT", List.of(n("ID", 11), c("NAME", 40), c("SHORTNAME", 24), n("CHARGETYP", 11),
                n("DEDUCTACT", 1), n("DEDUCTOVT", 1), n("COLORBK", 11)), row(1L, "Urlaub", "U", 1L, 0L, 0L, 0L),
                row(2L, "Ersatzruhe", "ER", 1L, 0L, 0L, 0L), row(3L, "Krankenstand", "K", 1L, 0L, 0L, 0L));
        TestTables.write(dir, "5ABSEN", List.of(n("ID", 11), n("EMPLOYEEID", 11), d("DATE"), n("LEAVETYPID", 11),
                n("INTERVAL", 11)), row(1L, 1L, "2027-01-10", 1L, 0L));
        TestTables.write(dir, "5HOLID", List.of(n("ID", 11), d("DATE"), c("NAME", 40), n("INTERVAL", 11)),
                row(1L, "2027-01-01", "Neujahr", 0L));
    }

    /** A text field written in single bytes, as the import reads times and work days. */
    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
