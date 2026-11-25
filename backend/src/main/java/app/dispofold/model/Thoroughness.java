package app.dispofold.model;

/**
 * How long a planning searches for a better plan: a fixed number of evaluated moves, so that the same seed, data and
 * thoroughness give the same plan on any machine (see SolveBudget).
 */
public enum Thoroughness {
    /** The budget before version 0.28: 250 000 moves, about 2 minutes with 150 people. */
    FAST,
    /** The default. */
    NORMAL,
    /** About three times as long as NORMAL. */
    THOROUGH
}
