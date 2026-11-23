package app.dispofold.model;

/** What a day of an absence type that lowers the target is credited with. */
public enum AbsenceCredit {
    /** Weekly hours / 5 on workdays (Monday to Friday, no public holiday); nothing on other days. */
    WEEKLY_HOURS,
    /**
     * The hours of the duty the absence replaced, on any day of the week, nothing without a duty; as long as the data
     * does not say which duty that was (a month planned ahead, an absence from an import), weekly hours / 5.
     */
    PLANNED_DUTY
}
