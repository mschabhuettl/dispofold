package app.dispofold.model;

import java.util.List;

/**
 * The import and master data format (see docs/datenformat.md). Every source of data (an export from the
 * current planning system, the synthetic demo data, later LOGA) delivers exactly this document.
 */
public record Dataset(String format, int version, String source, List<Area> areas, List<ShiftType> shifts,
        List<DemandRule> demand, List<Holiday> holidays, List<AbsenceType> absenceTypes, List<Person> people,
        List<Restriction> restrictions, List<HistoryEntry> history, List<Period> periods) {

    public static final String FORMAT = "dispofold/dataset";
    public static final int VERSION = 2;
}
