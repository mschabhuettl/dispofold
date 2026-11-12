package app.dispofold.model;

public enum WishKind {
    /** Day off wanted. */
    OFF,
    /** Any duty wanted on that day. */
    DUTY,
    /** This shift wanted (shiftId set). */
    SHIFT,
    /** This shift not wanted (shiftId set). */
    NOT_SHIFT
}
