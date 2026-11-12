package app.dispofold.export;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.List;

import app.dispofold.model.ShiftType;

import org.junit.jupiter.api.Test;

/** Shift codes in exports are unique. */
class PlanExporterCodesTest {

    static ShiftType shift(String id, String area, String code) {
        return new ShiftType(id, area, code, id, LocalTime.of(7, 0), LocalTime.of(19, 0), null, null, false);
    }

    @Test
    void ambiguousCodesGetTheAreaOrTheId() {
        var codes = PlanExporter.uniqueCodes(List.of(shift("A-T", "A", "T"), shift("B-T", "B", "T"),
                shift("B-T2", "B", "T"), shift("A-N", "A", "N")));
        assertThat(codes).containsEntry("A-T", "T/A").containsEntry("B-T", "B-T").containsEntry("B-T2", "B-T2")
                .containsEntry("A-N", "N");
        assertThat(codes.values()).doesNotHaveDuplicates();
    }
}
