package app.dispofold.model;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @param replacedHours the hours of the duty the plan had on this day when the absence was entered (0: the month was
 *        planned and had no duty that day); null when unknown (no plan yet, or from an import)
 */
public record Absence(String personId, LocalDate date, String typeCode,
        @JsonInclude(JsonInclude.Include.NON_NULL) Double replacedHours) {

    /** An absence without the replaced duty. */
    public Absence(String personId, LocalDate date, String typeCode) {
        this(personId, date, typeCode, null);
    }
}
