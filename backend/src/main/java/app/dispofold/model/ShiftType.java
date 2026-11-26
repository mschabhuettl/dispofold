package app.dispofold.model;

import java.time.LocalTime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One shift variant of an area. If {@code end} is not after {@code start} the shift ends on the next day.
 * Paid minutes equal the duration (paid breaks are part of the duty under the collective agreement).
 *
 * @param category optional; derived from the times when null
 * @param unpopular counted separately in the fairness overview
 * @param usage share of the area's duties on this shift in the history months (0 to 1), as an import computes it;
 *        null when unknown (the shift counts as usual); written only when set
 */
public record ShiftType(String id, String areaId, String code, String name, LocalTime start, LocalTime end,
        ShiftCategory category, String color, boolean unpopular,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double usage) {

    /** A shift without a share in the history. */
    public ShiftType(String id, String areaId, String code, String name, LocalTime start, LocalTime end,
            ShiftCategory category, String color, boolean unpopular) {
        this(id, areaId, code, name, start, end, category, color, unpopular, null);
    }

    /** The same shift with another {@link #usage}. */
    public ShiftType withUsage(Double newUsage) {
        return new ShiftType(id, areaId, code, name, start, end, category, color, unpopular, newUsage);
    }
}
