package app.dispofold.model;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

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
 * @param shiftMix usual shares of duties per category and area (for example from the history), or null
 * @param excludedFrom no longer taken over from the source: the first day the person is not planned (the first day
 *        after the last closed month when it was set), or null; written only when set
 * @param areaSources where each area of the person comes from, as text per area id (an import writes it, for
 *        example "Gruppe Team Annahme; Historie: 14 Dienste"), or null; only shown, written only when set
 * @param areaStates per area of the person that is not freely plannable: ON_REQUEST ("nur von Hand": never planned
 *        automatically, a planner may set a duty) or NEVER ("nie"), for every shift of the area on every day; or null.
 *        Only in what the pages read and send: stored as restrictions ({@link AreaStates})
 */
public record Person(String id, String firstName, String lastName, String shortName, double weeklyHours,
        List<String> areaIds, double hoursAccount, int compRestBalance, List<FamilyRule> familyRules,
        String externalId, LocalDate entryDate, LocalDate exitDate, ShiftMix shiftMix,
        @JsonInclude(JsonInclude.Include.NON_NULL) LocalDate excludedFrom,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, String> areaSources,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) Map<String, RestrictionKind> areaStates) {

    /** A person taken over from the source. */
    public Person(String id, String firstName, String lastName, String shortName, double weeklyHours,
            List<String> areaIds, double hoursAccount, int compRestBalance, List<FamilyRule> familyRules,
            String externalId, LocalDate entryDate, LocalDate exitDate, ShiftMix shiftMix) {
        this(id, firstName, lastName, shortName, weeklyHours, areaIds, hoursAccount, compRestBalance, familyRules,
                externalId, entryDate, exitDate, shiftMix, null, null, null);
    }

    /** Employed on this day, and not yet excluded from it on. */
    public boolean employedOn(LocalDate date) {
        return (entryDate == null || !date.isBefore(entryDate)) && (exitDate == null || !date.isAfter(exitDate))
                && (excludedFrom == null || date.isBefore(excludedFrom));
    }

    /** The same person with another {@link #excludedFrom}. */
    public Person withExcludedFrom(LocalDate from) {
        return new Person(id, firstName, lastName, shortName, weeklyHours, areaIds, hoursAccount, compRestBalance,
                familyRules, externalId, entryDate, exitDate, shiftMix, from, areaSources, areaStates);
    }

    /** The same person with other {@link #areaSources}. */
    public Person withAreaSources(Map<String, String> sources) {
        return new Person(id, firstName, lastName, shortName, weeklyHours, areaIds, hoursAccount, compRestBalance,
                familyRules, externalId, entryDate, exitDate, shiftMix, excludedFrom, sources, areaStates);
    }

    /** The same person with other {@link #areaStates}. */
    public Person withAreaStates(Map<String, RestrictionKind> states) {
        return new Person(id, firstName, lastName, shortName, weeklyHours, areaIds, hoursAccount, compRestBalance,
                familyRules, externalId, entryDate, exitDate, shiftMix, excludedFrom, areaSources, states);
    }


    public String displayName() {
        return lastName + " " + firstName;
    }

    @Override
    public String toString() {
        return "Person[" + id + "]";
    }
}
