package app.dispofold.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A person's duties in the history months before the planning month, as an import counts them: per shift and per
 * duty length. A shift that is no longer in the master data still counts by its length. Both maps count the same
 * duties.
 *
 * @param shifts duties per shift id
 * @param hours duties per duty length in hours (for example "10" or "8.5")
 */
public record DutyHistory(@JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Integer> shifts,
        @JsonInclude(JsonInclude.Include.NON_NULL) Map<String, Integer> hours) {

    /** Fewer duties than this in the history give no usual shifts and lengths: everything would be unusual. */
    public static final int MIN_DUTIES = 10;

    public Map<String, Integer> shiftsOrEmpty() {
        return shifts == null ? Map.of() : shifts;
    }

    public Map<String, Integer> hoursOrEmpty() {
        return hours == null ? Map.of() : hours;
    }

    /** The duties counted per length. */
    public int duties() {
        return hoursOrEmpty().values().stream().mapToInt(Integer::intValue).sum();
    }

    /** The length class of a duty: 0 under 8 hours, 1 from 8 to under 10, 2 from 10 to under 12, 3 from 12. */
    public static int lengthClass(int minutes) {
        return minutes < 480 ? 0 : minutes < 600 ? 1 : minutes < 720 ? 2 : 3;
    }

    /** The length classes with at least one duty. */
    public Set<Integer> lengthClasses() {
        Set<Integer> classes = new HashSet<>();
        hoursOrEmpty().forEach((hours, n) -> {
            if (n != null && n > 0) {
                classes.add(lengthClass((int) Math.round(Double.parseDouble(hours) * 60)));
            }
        });
        return classes;
    }

    /** Enough duties to say what is usual for the person. */
    public boolean telling() {
        return duties() >= MIN_DUTIES;
    }
}
