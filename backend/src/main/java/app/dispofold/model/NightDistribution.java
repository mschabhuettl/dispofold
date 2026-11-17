package app.dispofold.model;

/** How night duties are distributed between people with and without a usual night share (see docs/regeln.md). */
public enum NightDistribution {
    /** A person with a usual category mix keeps it, nights included, and is not in the night comparison. */
    HABIT,
    /** The night share of a usual mix is ignored: everybody who can do nights is in the night comparison. */
    EVEN
}
