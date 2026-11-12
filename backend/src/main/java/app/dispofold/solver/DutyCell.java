package app.dispofold.solver;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.dispofold.model.Wish;

import ai.timefold.solver.core.api.domain.common.PlanningId;
import ai.timefold.solver.core.api.domain.entity.PlanningEntity;
import ai.timefold.solver.core.api.domain.entity.PlanningPin;
import ai.timefold.solver.core.api.domain.valuerange.ValueRangeProvider;
import ai.timefold.solver.core.api.domain.variable.PlanningVariable;

/**
 * One person on one day. The planning variable is the shift (null: no duty). Cells before the month hold the
 * previous duties and are pinned; they only matter for rest, weekly and block rules.
 */
@PlanningEntity
public class DutyCell {

    public static final int DAY_MINUTES = 24 * 60;

    @PlanningId
    private String id;
    private Staff staff;
    private LocalDate date;
    private boolean inMonth;
    private boolean monthEnd;
    private boolean holiday;
    /** Monday to Friday and not a public holiday: absences credit the target only on these days. */
    private boolean workday;
    /** Epoch day of the Saturday if this is a Saturday or Sunday of a weekend that lies in the month, else -1. */
    private long weekend;
    private String absenceCode;
    private boolean outsideEmployment;
    private String missingShiftId;
    private boolean absenceCredits;
    private Set<String> onRequest;
    private Set<String> never;
    private List<Wish> wishes;
    /** Family rule for this weekday: latest end and earliest start as minutes after this day's 00:00, or -1. */
    private int familyLatestEnd;
    private int familyEarliestStart;
    private boolean familyNoNights;
    private List<ShiftOption> options;

    /** Real minute of this day's local midnight (see {@link TimeLine}). */
    private long midnight;
    /** Real start and end per shift on days with a change of the clock, else null. */
    private Map<ShiftOption, long[]> irregular;

    @PlanningPin
    private boolean pinned;
    /** Fixed by planners (period or roster); absence days are pinned without this flag. */
    private boolean plannerPinned;

    @PlanningVariable(allowsUnassigned = true, valueRangeProviderRefs = "options")
    private ShiftOption shift;

    public DutyCell() {
    }

    public DutyCell(Staff staff, LocalDate date, boolean inMonth, List<ShiftOption> options) {
        this.id = staff.getId() + "/" + date;
        this.staff = staff;
        this.date = date;
        this.inMonth = inMonth;
        this.options = options;
        this.weekend = -1;
        this.onRequest = Set.of();
        this.never = Set.of();
        this.wishes = List.of();
        this.familyLatestEnd = -1;
        this.familyEarliestStart = -1;
        this.midnight = TimeLine.UTC.midnight(date);
    }

    /** Places the cell on the time line; call after the options are final. */
    public void setTimes(TimeLine timeLine) {
        midnight = timeLine.midnight(date);
        irregular = null;
        if (!timeLine.regular(date)) {
            irregular = new java.util.IdentityHashMap<>();
            for (ShiftOption o : options) {
                irregular.put(o, new long[] { timeLine.at(date, o.getStartMinute()),
                        timeLine.at(date, o.getStartMinute() + o.getDurationMinutes()) });
            }
        }
    }

    /** Real start of the given shift on this day. */
    public long startOf(ShiftOption o) {
        long[] times = irregular == null ? null : irregular.get(o);
        return times != null ? times[0] : midnight + o.getStartMinute();
    }

    /** Real end of the given shift on this day. */
    public long endOf(ShiftOption o) {
        long[] times = irregular == null ? null : irregular.get(o);
        return times != null ? times[1] : midnight + o.getStartMinute() + o.getDurationMinutes();
    }

    @ValueRangeProvider(id = "options")
    public List<ShiftOption> getOptions() {
        return options;
    }

    public void setOptions(List<ShiftOption> options) {
        this.options = options;
    }

    public String getId() {
        return id;
    }

    public Staff getStaff() {
        return staff;
    }

    public LocalDate getDate() {
        return date;
    }

    public long getDay() {
        return date.toEpochDay();
    }

    public int getWeekday() {
        return date.getDayOfWeek().getValue();
    }

    public boolean isInMonth() {
        return inMonth;
    }

    /** The last day of the month: a block ending here may continue next month. */
    public boolean isMonthEnd() {
        return monthEnd;
    }

    public void setMonthEnd(boolean monthEnd) {
        this.monthEnd = monthEnd;
    }

    public boolean isHoliday() {
        return holiday;
    }

    public void setHoliday(boolean holiday) {
        this.holiday = holiday;
    }

    public boolean isWorkday() {
        return workday;
    }

    public void setWorkday(boolean workday) {
        this.workday = workday;
    }

    public boolean isWeekendDay() {
        DayOfWeek d = date.getDayOfWeek();
        return d == DayOfWeek.SATURDAY || d == DayOfWeek.SUNDAY;
    }

    public long getWeekend() {
        return weekend;
    }

    public void setWeekend(long weekend) {
        this.weekend = weekend;
    }

    public String getAbsenceCode() {
        return absenceCode;
    }

    public boolean isAbsenceCredits() {
        return absenceCredits;
    }

    public void setAbsence(String code, boolean credits) {
        this.absenceCode = code;
        this.absenceCredits = credits;
    }

    /** The day is before the entry or after the exit of the person. */
    public boolean isOutsideEmployment() {
        return outsideEmployment;
    }

    public void setOutsideEmployment(boolean outsideEmployment) {
        this.outsideEmployment = outsideEmployment;
    }

    /** A pinned shift id that is not in the shift catalogue (any more). */
    public String getMissingShiftId() {
        return missingShiftId;
    }

    public void setMissingShiftId(String missingShiftId) {
        this.missingShiftId = missingShiftId;
    }

    public Set<String> getOnRequest() {
        return onRequest;
    }

    public void setOnRequest(Set<String> onRequest) {
        this.onRequest = onRequest;
    }

    public Set<String> getNever() {
        return never;
    }

    public void setNever(Set<String> never) {
        this.never = never;
    }

    public List<Wish> getWishes() {
        return wishes;
    }

    public void setWishes(List<Wish> wishes) {
        this.wishes = wishes;
    }

    public void setFamily(int latestEnd, int earliestStart, boolean noNights) {
        this.familyLatestEnd = latestEnd;
        this.familyEarliestStart = earliestStart;
        this.familyNoNights = noNights;
    }

    public boolean hasFamilyRule() {
        return familyLatestEnd >= 0 || familyEarliestStart >= 0 || familyNoNights;
    }

    public boolean isPinned() {
        return pinned;
    }

    public void setPinned(boolean pinned) {
        this.pinned = pinned;
    }

    public boolean isPlannerPinned() {
        return plannerPinned;
    }

    public void setPlannerPinned(boolean plannerPinned) {
        this.plannerPinned = plannerPinned;
    }

    public ShiftOption getShift() {
        return shift;
    }

    public void setShift(ShiftOption shift) {
        this.shift = shift;
    }

    // Derived values used by the constraints.

    /** A real duty (not empty and not compensatory rest). */
    public boolean isWork() {
        return shift != null && !shift.isCompRest();
    }

    public boolean isCompRest() {
        return shift != null && shift.isCompRest();
    }

    public String getAreaId() {
        return shift == null ? null : shift.getAreaId();
    }

    /** Real duty start in minutes since the epoch (see {@link TimeLine}); only for work cells. */
    public long getStart() {
        return startOf(shift);
    }

    public long getEnd() {
        return endOf(shift);
    }

    /** Real minutes of the duty; one hour more or less in the nights the clock changes. */
    public int getDurationMinutes() {
        return isWork() ? (int) (getEnd() - getStart()) : 0;
    }

    /** Minutes that count towards the target hours: duties, credited absences and compensatory rest. */
    public int getCountedMinutes() {
        if (isWork()) {
            return getDurationMinutes();
        }
        if (isCompRest()) {
            return staff.getCreditMinutes();
        }
        return absenceCode != null && absenceCredits && workday ? staff.getCreditMinutes() : 0;
    }

    public long overlap(long from, long to) {
        return Math.max(0, Math.min(getEnd(), to) - Math.max(getStart(), from));
    }

    public int unmetWishes() {
        int unmet = 0;
        for (Wish wish : wishes) {
            if (!isMet(wish)) {
                unmet++;
            }
        }
        return unmet;
    }

    public boolean isMet(Wish wish) {
        return switch (wish.kind()) {
            case OFF -> !isWork();
            case DUTY -> isWork();
            case SHIFT -> isWork() && shift.getId().equals(wish.shiftId());
            case NOT_SHIFT -> !isWork() || !shift.getId().equals(wish.shiftId());
        };
    }

    public boolean violatesFamilyRule() {
        if (!isWork()) {
            return false;
        }
        int start = shift.getStartMinute();
        int end = start + shift.getDurationMinutes();
        return (familyLatestEnd >= 0 && end > familyLatestEnd)
                || (familyEarliestStart >= 0 && start < familyEarliestStart)
                || (familyNoNights && shift.isNight());
    }

    @Override
    public String toString() {
        return id + "=" + shift;
    }
}
