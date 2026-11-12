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
}
