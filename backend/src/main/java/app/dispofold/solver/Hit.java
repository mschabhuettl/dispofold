package app.dispofold.solver;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import ai.timefold.solver.core.api.score.stream.ConstraintJustification;

/**
 * Justification of a constraint match: the person concerned (null for demand coverage), the day (null for
 * whole-month matches) and the values the user interface needs for its text {@code hit.<constraintId>}.
 *
 * @param params ids, numbers and ISO dates; never names
 */
public record Hit(String personId, LocalDate date, Map<String, Object> params) implements ConstraintJustification {

    /** @param keysAndValues alternating key and value */
    public static Hit of(String personId, LocalDate date, Object... keysAndValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            params.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return new Hit(personId, date, Collections.unmodifiableMap(params));
    }
}
