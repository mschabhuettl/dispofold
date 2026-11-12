package app.dispofold.model;

public enum RestrictionKind {
    /** Never plan this shift (hard). */
    NEVER,
    /** Only after asking the person (soft penalty). */
    ON_REQUEST
}
