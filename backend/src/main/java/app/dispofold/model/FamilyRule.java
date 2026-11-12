package app.dispofold.model;

import java.time.LocalTime;
import java.util.List;

/**
 * Per-person family preference, e.g. no duty ending after 18:00 on Tuesdays. Soft, with its own weight.
 *
 * @param weekdays ISO day numbers 1 (Monday) to 7 (Sunday) the rule applies to
 * @param latestEnd duties on these days should end by this time (null: no limit)
 * @param earliestStart duties on these days should not start before this time (null: no limit)
 * @param noNights no night duties on these days
 * @param note free text for planners, never logged
 */
public record FamilyRule(List<Integer> weekdays, LocalTime latestEnd, LocalTime earliestStart, boolean noNights,
        String note) {
}
