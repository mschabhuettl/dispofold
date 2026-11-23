package app.dispofold.model;

/** Over which period the average weekly hours are counted (see docs/regeln.md). */
public enum AveragingPeriod {
    /** The calendar half-year of the planned month (January to June, July to December), up to its end. */
    HALF_YEAR,
    /** The {@code averagingWeeks} weeks up to the end of the planned month. */
    ROLLING
}
