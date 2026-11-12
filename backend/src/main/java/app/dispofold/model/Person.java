package app.dispofold.model;

import java.time.LocalDate;
import java.util.List;

/**
 * A full-time staff member who is planned.
 *
 * @param id internal id, the only identifier that may appear in logs
 * @param weeklyHours contract weekly hours, e.g. 40.0
 * @param areaIds areas this person is qualified for
 * @param hoursAccount hours balance carried over from earlier months (positive: worked more than the target)
 * @param compRestBalance open compensatory rest days ("Ersatzruhe")
 * @param familyRules optional family preferences
 * @param externalId id in the source system (e.g. the personnel number)
 * @param entryDate first day of employment, null when employed before the planning month
 * @param exitDate last day of employment, null when open; days outside are not planned and the target is pro rata
 */
public record Person(String id, String firstName, String lastName, String shortName, double weeklyHours,
        List<String> areaIds, double hoursAccount, int compRestBalance, List<FamilyRule> familyRules,
        String externalId, LocalDate entryDate, LocalDate exitDate) {

    /** Employed on this day. */
    public boolean employedOn(LocalDate date) {
        return (entryDate == null || !date.isBefore(entryDate)) && (exitDate == null || !date.isAfter(exitDate));
    }


    public String displayName() {
        return lastName + " " + firstName;
    }

    @Override
    public String toString() {
        return "Person[" + id + "]";
    }
}
