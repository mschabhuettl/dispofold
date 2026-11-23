package app.dispofold.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * @param reducesTarget "senkt das Soll": on workdays (Monday to Friday, no public holiday) the day reduces the
 *        target by weekly hours / 5
 * @param chargesAccount "belastet das Stundenkonto": the hours the day reduces the target by are booked against
 *        the hours account (time off in lieu)
 * @param compensatoryRest this type is compensatory rest ("Ersatzruhe") and reduces the balance
 * @param credit what a day of a type that lowers the target is credited with; null means
 *        {@link AbsenceCredit#WEEKLY_HOURS}
 */
public record AbsenceType(String code, String name, boolean reducesTarget, boolean chargesAccount,
        boolean compensatoryRest, String color, @JsonInclude(JsonInclude.Include.NON_NULL) AbsenceCredit credit) {

    /** A type with the default credit. */
    public AbsenceType(String code, String name, boolean reducesTarget, boolean chargesAccount,
            boolean compensatoryRest, String color) {
        this(code, name, reducesTarget, chargesAccount, compensatoryRest, color, null);
    }

    /** {@link #credit()} with the default. */
    public AbsenceCredit creditKind() {
        return credit != null ? credit : AbsenceCredit.WEEKLY_HOURS;
    }
}
