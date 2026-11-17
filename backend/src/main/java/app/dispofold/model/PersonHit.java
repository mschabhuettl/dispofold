package app.dispofold.model;

import java.time.LocalDate;
import java.util.Map;

/** A {@link ConstraintHit} with the person it concerns, for the hits of a whole plan. */
public record PersonHit(String personId, String constraintId, String level, LocalDate date, long score, String code,
        Map<String, Object> params) {

    public PersonHit(String personId, ConstraintHit hit) {
        this(personId, hit.constraintId(), hit.level(), hit.date(), hit.score(), hit.code(), hit.params());
    }
}
