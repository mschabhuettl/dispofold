package app.dispofold.model;

/** Total of one constraint in a plan. {@code score} is the weighted impact (negative is a penalty). */
public record ConstraintSummary(String id, String level, long weight, long score, int matches) {
}
