package app.dispofold.planning;

/**
 * @param seed random seed; the same seed, input and {@code moveLimit} give the same plan
 * @param timeLimitSeconds wall clock limit
 * @param unimprovedSeconds stop early when the score has not improved for this long (0: off)
 * @param moveLimit stop after this many evaluated moves (0: off); use it for reproducible runs, since a
 *        time limit depends on the speed of the machine
 */
public record SolveOptions(long seed, int timeLimitSeconds, int unimprovedSeconds, long moveLimit) {
}
