package app.dispofold.planning;

import app.dispofold.model.Thoroughness;

/**
 * The search budget of a thoroughness: a number of evaluated moves, so that the same seed, data and thoroughness
 * give the same plan on any machine, and a time limit as a safety net well above the time the moves take: about eight
 * to ten times the time measured with 150 people (about 2, 7 and 21 minutes), so that a much slower machine still
 * ends at the move limit.
 *
 * @param moves evaluated moves
 * @param seconds time limit
 */
public record SolveBudget(long moves, int seconds) {

    public static SolveBudget of(Thoroughness thoroughness) {
        return switch (thoroughness) {
            case FAST -> new SolveBudget(250_000, 1800);
            case NORMAL -> new SolveBudget(1_000_000, 3600);
            case THOROUGH -> new SolveBudget(3_000_000, 10800);
        };
    }
}
