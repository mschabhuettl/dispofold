package app.dispofold.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import app.dispofold.model.HistoryEntry;

import org.junit.jupiter.api.Test;

/** Imported history wins field by field; unknown balances come from the closed plan. */
class HistoryMergeTest {

    static HistoryEntry entry(String source, double hours, Double account, Integer compRest) {
        return new HistoryEntry("P1", "2027-01", source, 1, 1, 0, 0, 0, 0, hours, account, compRest, 0, 0);
    }

    @Test
    void importedValuesWin() {
        List<HistoryEntry> merged = Store.merge(List.of(entry(HistoryEntry.PLAN, 150, 5.0, 2)),
                List.of(entry(HistoryEntry.IMPORT, 160, 7.5, 3)));
        assertThat(merged).containsExactly(entry(HistoryEntry.IMPORT, 160, 7.5, 3));
    }

    @Test
    void unknownImportedBalancesComeFromTheClosedPlan() {
        List<HistoryEntry> merged = Store.merge(List.of(entry(HistoryEntry.PLAN, 150, 5.0, 2)),
                List.of(entry(HistoryEntry.IMPORT, 160, null, null)));
        assertThat(merged).containsExactly(entry(HistoryEntry.IMPORT, 160, 5.0, 2));
        // without a closed plan they stay unknown (the person's imported values apply)
        assertThat(Store.merge(List.of(), List.of(entry(HistoryEntry.IMPORT, 160, null, null))))
                .containsExactly(entry(HistoryEntry.IMPORT, 160, null, null));
    }

    @Test
    void unknownImportedWeeklyHoursComeFromTheClosedPlan() {
        HistoryEntry plan = new HistoryEntry("P1", "2027-01", HistoryEntry.PLAN, 1, 1, 0, 0, 0, 0, 150, 5.0, 2, 0, 0,
                30.0);
        HistoryEntry imported = entry(HistoryEntry.IMPORT, 160, 7.5, 3);
        assertThat(Store.merge(List.of(plan), List.of(imported)).get(0).weeklyHours()).isEqualTo(30.0);
        HistoryEntry own = new HistoryEntry("P1", "2027-01", HistoryEntry.IMPORT, 1, 1, 0, 0, 0, 0, 160, 7.5, 3, 0, 0,
                20.0);
        assertThat(Store.merge(List.of(plan), List.of(own))).containsExactly(own);
    }
}
