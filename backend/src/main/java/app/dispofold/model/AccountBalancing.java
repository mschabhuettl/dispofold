package app.dispofold.model;

/** How the monthly goal evens out the hours account. */
public enum AccountBalancing {
    /**
     * By the end of the averaging period: the goal is the target minus the account divided by the months left in the
     * calendar half-year (this one included); the account starts at 0 in January and July. Needs the calendar
     * half-year; with the rolling period the fixed share applies.
     */
    PERIOD_END,
    /** A fixed share of the account per month ({@link Rules#accountCarryShare()}); the account carries on. */
    MONTHLY_SHARE
}
