package app.dispofold.model;

import java.time.LocalTime;

/**
 * One shift variant of an area. If {@code end} is not after {@code start} the shift ends on the next day.
 * Paid minutes equal the duration (paid breaks are part of the duty under the collective agreement).
 *
 * @param category optional; derived from the times when null
 * @param unpopular counted separately in the fairness overview
 */
public record ShiftType(String id, String areaId, String code, String name, LocalTime start, LocalTime end,
        ShiftCategory category, String color, boolean unpopular) {
}
