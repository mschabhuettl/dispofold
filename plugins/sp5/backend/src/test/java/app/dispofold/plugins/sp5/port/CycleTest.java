package app.dispofold.plugins.sp5.port;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Cycle assignments (5CYASS) as openschichtplaner5 1.0.1 and SP5 read them: from START to END, both needed. */
class CycleTest {

    static Map<String, Object> rec(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // a day model of two days: shift 7 on the first, free on the second
    static final List<Map<String, Object>> CYCLES = List.of(rec("ID", 1L, "SIZE", 2L, "UNIT", 0L));
    static final List<Map<String, Object>> ENTRIES = List.of(rec("CYCLEEID", 1L, "INDEX", 0L, "SHIFTID", 7L,
            "WORKPLACID", 0L));
    static final LocalDate VON = LocalDate.of(2027, 1, 1);
    static final LocalDate BIS = LocalDate.of(2027, 1, 31);

    static List<Object> dates(List<Map<String, Object>> assignments, List<Map<String, Object>> exceptions) {
        return Sp5Calc.expandCycleAssignments(assignments, CYCLES, ENTRIES, exceptions, VON, BIS).stream()
                .map(r -> r.get("DATE")).toList();
    }

    @Test
    void withStartAndEndTheDutiesRunFromStartToEndInclusive() {
        List<Map<String, Object>> a = List.of(rec("ID", 1L, "EMPLOYEEID", 5L, "CYCLEID", 1L, "START", "2026-12-30",
                "END", "2027-01-05", "ENTRANCE", 0L));
        // position 0 on 2026-12-30, so the duties fall on the even day offsets
        assertThat(dates(a, List.of())).containsExactly("2027-01-01", "2027-01-03", "2027-01-05");
        Map<String, Object> first = Sp5Calc.expandCycleAssignments(a, CYCLES, ENTRIES, List.of(), VON, BIS).get(0);
        assertThat(first).containsEntry("EMPLOYEEID", 5L).containsEntry("SHIFTID", 7L).containsEntry("WORKPLACID", 0L);
    }

    @Test
    void withoutEndThereAreNoDuties() {
        List<Map<String, Object>> a = List.of(rec("ID", 1L, "EMPLOYEEID", 5L, "CYCLEID", 1L, "START", "2026-12-30",
                "END", null, "ENTRANCE", 0L));
        assertThat(dates(a, List.of())).isEmpty();
    }

    @Test
    void withoutStartThereAreNoDuties() {
        List<Map<String, Object>> a = List.of(rec("ID", 1L, "EMPLOYEEID", 5L, "CYCLEID", 1L, "START", null,
                "END", "2027-01-31", "ENTRANCE", 0L));
        assertThat(dates(a, List.of())).isEmpty();
    }

    @Test
    void anExceptionSuppressesTheDutyOfItsDayAndTheEntranceShiftsThePosition() {
        List<Map<String, Object>> a = List.of(rec("ID", 3L, "EMPLOYEEID", 5L, "CYCLEID", 1L, "START", "2027-01-01",
                "END", "2027-01-06", "ENTRANCE", 1L));
        List<Map<String, Object>> exceptions = List.of(rec("EMPLOYEEID", 5L, "CYCLEASSID", 3L, "DATE", "2027-01-04"));
        assertThat(dates(a, exceptions)).containsExactly("2027-01-02", "2027-01-06");
    }

    @Test
    void weekModelsAlignTheWeekday() {
        List<Map<String, Object>> cycles = List.of(rec("ID", 1L, "SIZE", 1L, "UNIT", 1L));
        List<Map<String, Object>> entries = List.of(rec("CYCLEEID", 1L, "INDEX", 0L, "SHIFTID", 7L)); // Mondays
        List<Map<String, Object>> a = List.of(rec("ID", 1L, "EMPLOYEEID", 5L, "CYCLEID", 1L, "START", "2027-01-01",
                "END", "2027-01-31", "ENTRANCE", 0L));
        assertThat(Sp5Calc.expandCycleAssignments(a, cycles, entries, List.of(), VON, BIS).stream()
                .map(r -> r.get("DATE")).toList()).containsExactly("2027-01-04", "2027-01-11", "2027-01-18",
                        "2027-01-25");
    }
}
