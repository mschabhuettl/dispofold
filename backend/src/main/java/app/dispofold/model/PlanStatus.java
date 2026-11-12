package app.dispofold.model;

/** CLOSED: the month is closed (balances written to the history) and read-only until reopened. */
public enum PlanStatus {
    EMPTY, SOLVING, SOLVED, STOPPED, FAILED, CLOSED
}
