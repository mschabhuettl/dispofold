package app.dispofold.model;

import java.time.LocalDate;
import java.util.Map;

/**
 * One constraint match that concerns a person, used to explain why a duty was chosen.
 *
 * @param date the day concerned, or null for a whole-month match (e.g. target hours)
 * @param score weighted impact (negative is a penalty, positive a reward)
 * @param code text key of the user interface, e.g. {@code hit.dailyRest}
 * @param params values for the text: ids, numbers, dates; never names
 */
public record ConstraintHit(String constraintId, String level, LocalDate date, long score, String code,
        Map<String, Object> params) {
}
