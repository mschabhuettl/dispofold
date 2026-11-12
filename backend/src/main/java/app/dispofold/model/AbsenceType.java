package app.dispofold.model;

/**
 * @param reducesTarget "senkt das Soll": on workdays (Monday to Friday, no public holiday) the day reduces the
 *        target by weekly hours / 5
 * @param chargesAccount "belastet das Stundenkonto": the hours the day reduces the target by are booked against
 *        the hours account (time off in lieu)
 * @param compensatoryRest this type is compensatory rest ("Ersatzruhe") and reduces the balance
 */
public record AbsenceType(String code, String name, boolean reducesTarget, boolean chargesAccount,
        boolean compensatoryRest, String color) {
}
