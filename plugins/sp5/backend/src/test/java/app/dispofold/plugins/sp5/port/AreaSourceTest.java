package app.dispofold.plugins.sp5.port;

import static app.dispofold.plugins.sp5.port.TestTables.c;
import static app.dispofold.plugins.sp5.port.TestTables.d;
import static app.dispofold.plugins.sp5.port.TestTables.f;
import static app.dispofold.plugins.sp5.port.TestTables.n;
import static app.dispofold.plugins.sp5.port.TestTables.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where a person's areas come from ("Bereiche einer Person aus"), on a synthetic folder: one broad group gives the
 * areas X and Y; Z belongs to no group. December 2026 is the history of January 2027.
 * <ul>
 * <li>E1 is in the broad group and in the group Ypsilon (area Y) and works X (10 duties) and Y twice, below the
 * threshold of 3.</li>
 * <li>E2 is in no group and works Z regularly (8 duties).</li>
 * <li>E3 is in the broad group with 2 duties in X, below the threshold of 3.</li>
 * <li>E4 is in the broad group and new: no history.</li>
 * </ul>
 */
@SuppressWarnings("unchecked")
class AreaSourceTest {

    static final String MAPPING = """
            {"areas": [{"id": "X", "name": "Bereich X", "color": "#B45309"},
                       {"id": "Y", "name": "Bereich Y", "color": "#15803D"},
                       {"id": "Z", "name": "Bereich Z", "color": "#2563EB"}],
             "shifts": [{"match": "X1", "areaId": "X"}, {"match": "Y1", "areaId": "Y"}, {"match": "Z1", "areaId": "Z"}],
             "groups": [{"match": "Breit", "areaIds": ["X", "Y"]}, {"match": "Ypsilon", "areaIds": ["Y"]}]}""";

    static void folder(Path dir) throws Exception {
        List<TestTables.Field> shift = new ArrayList<>(List.of(n("ID", 11), c("NAME", 40), c("SHORTNAME", 24),
                n("HIDE", 1), n("COLORBK", 11)));
        for (int i = 0; i < 8; i++) {
            shift.add(c("STARTEND" + i, 36));
        }
        String[] codes = { "X1", "Y1", "Z1" };
        Object[][] shifts = new Object[3][];
        for (int s = 0; s < 3; s++) {
            List<Object> r = new ArrayList<>(List.of((long) s + 1, "Dienst " + codes[s], codes[s], 0L, 0L));
            for (int i = 0; i < 8; i++) {
                r.add("07:00-19:00".getBytes(StandardCharsets.US_ASCII));
            }
            shifts[s] = r.toArray();
        }
        TestTables.write(dir, "5SHIFT", shift, shifts);
        TestTables.write(dir, "5GROUP", List.of(n("ID", 11), c("NAME", 40), c("SHORTNAME", 24), n("SUPERID", 11)),
                row(1L, "Breit", "BR", 0L), row(2L, "Ypsilon", "YP", 0L));
        TestTables.write(dir, "5GRASG", List.of(n("ID", 11), n("EMPLOYEEID", 11), n("GROUPID", 11)),
                row(1L, 1L, 1L), row(2L, 3L, 1L), row(3L, 4L, 1L), row(4L, 1L, 2L));
        List<Object[]> people = new ArrayList<>();
        for (long e = 1; e <= 4; e++) {
            people.add(row(e, "Person" + e, "Vorname" + e, "P" + e, 0L,
                    "1 1 1 1 1 0 0 0".getBytes(StandardCharsets.US_ASCII), 1L, "8.00", "40.00", "0.00", "0.00",
                    e == 4 ? "2027-01-01" : "2015-01-01", null));
        }
        TestTables.write(dir, "5EMPL", List.of(n("ID", 11), c("NAME", 40), c("FIRSTNAME", 40), c("SHORTNAME", 24),
                n("HIDE", 1), c("WORKDAYS", 16), n("CALCBASE", 11), f("HRSDAY", 10, 2), f("HRSWEEK", 10, 2),
                f("HRSMONTH", 10, 2), f("HRSTOTAL", 10, 2), d("EMPSTART"), d("EMPEND")), people.toArray(new Object[0][]));
        List<Object[]> duties = new ArrayList<>();
        long[][] work = { { 1, 1, 10 }, { 1, 2, 2 }, { 2, 3, 8 }, { 3, 1, 2 } }; // {person, shift, December duties}
        for (long[] w : work) {
            for (int k = 0; k < w[2]; k++) {
                duties.add(row((long) duties.size() + 1, w[0], w[1], String.format("2026-12-%02d", k * 3 + (int) w[1]), 0L));
            }
        }
        TestTables.write(dir, "5MASHI", List.of(n("ID", 11), n("EMPLOYEEID", 11), n("SHIFTID", 11), d("DATE"),
                n("TYPE", 11)), duties.toArray(new Object[0][]));
        TestTables.write(dir, "5LEAVT", List.of(n("ID", 11), c("NAME", 40), c("SHORTNAME", 24), n("CHARGETYP", 11),
                n("DEDUCTACT", 1), n("DEDUCTOVT", 1), n("COLORBK", 11)), row(1L, "Urlaub", "U", 1L, 0L, 0L, 0L));
    }

    static Sp5Export.Result run(Path dir, String source, Integer areasFromHistory) throws Exception {
        return Sp5Export.run(new Sp5Export.Request(dir, "2027-01", null, 1, areasFromHistory, null, "history", 1, 3, 3,
                (Map<String, Object>) PyJson.load(MAPPING), null, true, source, 3));
    }

    /** person -> areas. */
    static Map<String, List<String>> areas(Sp5Export.Result r) {
        Map<String, List<String>> out = new TreeMap<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) r.dataset().get("people")) {
            out.put((String) p.get("id"), (List<String>) p.get("areaIds"));
        }
        return out;
    }

    /** person -> "shift kind" of its restrictions. */
    static Map<String, TreeSet<String>> restrictions(Sp5Export.Result r) {
        Map<String, TreeSet<String>> out = new TreeMap<>();
        for (Map<String, Object> x : (List<Map<String, Object>>) r.dataset().get("restrictions")) {
            out.computeIfAbsent((String) x.get("personId"), k -> new TreeSet<>()).add(x.get("shiftId") + " "
                    + x.get("kind") + " " + x.get("weekday"));
        }
        return out;
    }

    @Test
    void groupsAsBeforeHistoryOnlyWhatPeopleWorkReserveKeepsTheGroupAreasOnRequest(@TempDir Path dir)
            throws Exception {
        folder(dir);
        // groups (as sp5_export.py): the broad group's areas for everyone in it, nothing for E2
        Sp5Export.Result groups = run(dir, "groups", null);
        assertThat(areas(groups)).containsExactly(Map.entry("E1", List.of("X", "Y")), Map.entry("E3", List.of("X", "Y")),
                Map.entry("E4", List.of("X", "Y")));
        assertThat(restrictions(groups)).isEmpty();
        // history: E1 only X, E2 gets Z; E3 (2 duties) and E4 (none) keep the group areas
        Sp5Export.Result history = run(dir, "history", null);
        assertThat(areas(history)).containsExactly(Map.entry("E1", List.of("X")), Map.entry("E2", List.of("Z")),
                Map.entry("E3", List.of("X", "Y")), Map.entry("E4", List.of("X", "Y")));
        assertThat(restrictions(history)).isEmpty();
        // reserve: E1 keeps Y, every shift of it on request; the others as with history
        Sp5Export.Result reserve = run(dir, "reserve", null);
        assertThat(areas(reserve)).containsExactly(Map.entry("E1", List.of("X", "Y")), Map.entry("E2", List.of("Z")),
                Map.entry("E3", List.of("X", "Y")), Map.entry("E4", List.of("X", "Y")));
        assertThat(restrictions(reserve)).containsExactly(Map.entry("E1", new TreeSet<>(List.of("S2 ON_REQUEST 0"))));
        assertThat(reserve.warnings()).anySatisfy(w -> assertThat(w).startsWith(
                "Bereiche einer Person aus der Historie (ab 3 Diensten, Gruppen als Reserve): 2 Bereiche aus der Historie, "
                        + "1 aus Gruppen als Reserve nur auf Anfrage, 0 aus Gruppen weggelassen; 2 Mitarbeiter"));
        assertThat(reserve.areaCounts()).containsEntry("fromHistory", 2).containsEntry("reserve", 1)
                .containsEntry("peopleWithoutHistory", 2);
        // where each area comes from: per area only the groups that give it
        assertThat(reserve.areaSources().get("E1")).containsExactly(Map.entry("X", "Gruppe Breit; Historie: 10 Dienste"),
                Map.entry("Y", "Gruppen Breit, Ypsilon; Historie: 2 Dienste"));
        // the reserve area, all on request, gives no usual share (as an area no longer worked)
        Map<String, Object> mix = (Map<String, Object>) ((List<Map<String, Object>>) reserve.dataset().get("people"))
                .stream().filter(p -> "E1".equals(p.get("id"))).findFirst().orElseThrow().get("shiftMix");
        assertThat((Map<String, Object>) mix.get("areas")).containsOnlyKeys("X");
        assertThat(reserve.areaSources().get("E2")).containsExactly(Map.entry("Z", "Historie: 8 Dienste"));
        assertThat(history.areaSources().get("E3")).containsExactly(Map.entry("X", "Gruppe Breit; Historie: 2 Dienste"),
                Map.entry("Y", "Gruppe Breit"));
        // the datasets are valid for Dispofold (shift mix only of the person's areas)
        for (Sp5Export.Result r : List.of(groups, history, reserve)) {
            assertThat(r.dataset().get("people")).isNotNull();
        }
    }

    @Test
    void theGroupsWithAreasFromHistoryStayAsTheyWere(@TempDir Path dir) throws Exception {
        folder(dir);
        // areasFromHistory works under "groups" only: E2 gets Z through it, E1 keeps Y fully allowed
        assertThat(areas(run(dir, "groups", 3))).containsEntry("E1", List.of("X", "Y")).containsEntry("E2",
                List.of("Z"));
        // with history it has no effect: the threshold of the area source decides
        assertThat(areas(run(dir, "history", 1))).isEqualTo(areas(run(dir, "history", null)));
        // the old constructor is "groups"
        Sp5Export.Result old = Sp5Export.run(new Sp5Export.Request(dir, "2027-01", null, 1, 3, null, "history", 1, 3,
                3, (Map<String, Object>) PyJson.load(MAPPING), null, true));
        assertThat(Sp5Export.datasetBytes(old)).isEqualTo(Sp5Export.datasetBytes(run(dir, "groups", 3)));
    }
}
