package app.dispofold.solver;

import app.dispofold.model.ShiftCategory;

/**
 * A value of {@link DutyCell#getShift()}: a shift variant of an area, or the compensatory rest marker.
 * Times are minutes; {@code startMinute} is the minute of the day, the duty may end on the next day.
 */
public final class ShiftOption {

    public static final String COMP_REST_ID = "ER";

    private final String id;
    private final String areaId;
    private final int startMinute;
    private final int durationMinutes;
    private final ShiftCategory category;
    private final boolean night;
    private final boolean unpopular;
    private final boolean compRest;

    public ShiftOption(String id, String areaId, int startMinute, int durationMinutes, ShiftCategory category,
            boolean night, boolean unpopular, boolean compRest) {
        this.id = id;
        this.areaId = areaId;
        this.startMinute = startMinute;
        this.durationMinutes = durationMinutes;
        this.category = category;
        this.night = night;
        this.unpopular = unpopular;
        this.compRest = compRest;
    }

    public static ShiftOption compRest() {
        return new ShiftOption(COMP_REST_ID, null, 0, 0, null, false, false, true);
    }

    public String getId() {
        return id;
    }

    public String getAreaId() {
        return areaId;
    }

    public int getStartMinute() {
        return startMinute;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public ShiftCategory getCategory() {
        return category;
    }

    public boolean isNight() {
        return night;
    }

    public boolean isUnpopular() {
        return unpopular;
    }

    public boolean isCompRest() {
        return compRest;
    }

    @Override
    public String toString() {
        return id;
    }
}
