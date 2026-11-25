package app.dispofold.model;

/**
 * How long a planning searches for a better plan: a number of evaluated moves per duty cell of the month, so that
 * the same seed, data and thoroughness give the same plan on any machine (see SolveBudget).
 */
public enum Thoroughness {
    /** The budget before version 0.28: a fixed number of moves, done in about a minute. */
    FAST,
    /** The default. */
    NORMAL,
    /** About three times as long as NORMAL. */
    THOROUGH
}
