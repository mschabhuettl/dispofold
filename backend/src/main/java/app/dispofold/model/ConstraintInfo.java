package app.dispofold.model;

/**
 * Catalogue entry of a constraint. Label and description are texts of the user interface
 * ({@code constraint.<id>.label}, {@code constraint.<id>.description}).
 *
 * @param id stable id, also the key in {@link Rules#weights()}
 * @param level HARD, MEDIUM (demand coverage) or SOFT
 */
public record ConstraintInfo(String id, String level, long defaultWeight) {
}
