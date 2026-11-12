package app.dispofold.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Minimum (and optional maximum) number of people working in an area during a time window.
 * Applies on the listed day types ({@code MON..SUN}, {@code HOL} for public holidays) or, when {@code date}
 * is set, only on that date. If {@code to} is not after {@code from} the window ends on the next day.
 * On a public holiday the {@code HOL} rules of an area replace its weekday rules (if the area has any);
 * date rules replace both for the area on that date.
 */
public record DemandRule(String areaId, List<String> days, LocalDate date, LocalTime from, LocalTime to, int min,
        Integer max) {
}
