package app.dispofold.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Usual shares of a person's duties per shift category and per area, for example from the history. Each map sums
 * to 1; planners may change or clear it.
 *
 * @param categories share per shift category (EARLY, DAY, LATE, NIGHT)
 * @param areas share per area id; only areas of the person
 */
public record ShiftMix(Map<ShiftCategory, Double> categories, Map<String, Double> areas) {

    /** Allowed difference of a map's sum from 1. */
    public static final double SUM_TOLERANCE = 0.01;

    public Map<ShiftCategory, Double> categoriesOrEmpty() {
        return categories == null ? Map.of() : categories;
    }

    public Map<String, Double> areasOrEmpty() {
        return areas == null ? Map.of() : areas;
    }

    /** The mix without an area that the person no longer has; the other area shares are scaled up to sum to 1. */
    public ShiftMix withoutArea(String areaId) {
        Map<String, Double> rest = new LinkedHashMap<>(areasOrEmpty());
        Double removed = rest.remove(areaId);
        if (removed == null) {
            return this;
        }
        double sum = rest.values().stream().mapToDouble(Double::doubleValue).sum();
        if (sum <= 0) {
            // Only zero shares left: no area mix any more.
            return new ShiftMix(categories, Map.of());
        }
        rest.replaceAll((k, v) -> v / sum);
        return new ShiftMix(categories, rest);
    }
}
