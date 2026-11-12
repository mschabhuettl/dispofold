// Builds the roster grid (people x days) from dataset, period and plan. Pure, no DOM.
import { isWeekend, monthDates, shiftHours, weekday } from './dates.js';
import { t } from './texts.js';

export function personName(p) {
  return `${p.lastName} ${p.firstName}`;
}

/** Absence code the server sends to viewers instead of the real type (no health data for them). */
export const NEUTRAL_ABSENCE = 'ABW';

/** Name of an absence type; the neutral code reads "abwesend". */
export function absenceName(code, absenceTypes) {
  const type = absenceTypes.get(code);
  if (type) return type.name;
  return code === NEUTRAL_ABSENCE ? t('grid.absent') : code;
}

/**
 * Columns of the month: date, weekday, weekend and holiday flags.
 * @returns {{date: string, day: number, weekday: number, weekend: boolean, holiday: string|null}[]}
 */
export function buildDays(month, holidays = []) {
  const names = new Map(holidays.map((h) => [h.date, h.name]));
  return monthDates(month).map((date) => ({
    date,
    day: Number(date.slice(8)),
    weekday: weekday(date),
    weekend: isWeekend(date),
    holiday: names.get(date) ?? null,
  }));
}

/**
 * One cell. kind is 'shift', 'absence', 'compRest' or 'free'. Absences come from the period, not the plan.
 * A cell is pinned when the plan says so or when the period fixes it (those stay pinned after unpinning).
 * An absence with a planned duty on the same day (e.g. sickness entered after the duty was pinned) is a conflict:
 * the cell keeps kind 'absence', shows both codes and carries the duty's shiftId so it can be cleared.
 */
export function buildCell(assignment, absence, fixed, shiftsById, absenceTypes) {
  const pinned = Boolean(assignment?.pinned || fixed);
  const fixedInPeriod = Boolean(fixed);
  if (absence) {
    const type = absenceTypes.get(absence.typeCode);
    const name = absenceName(absence.typeCode, absenceTypes);
    if (assignment?.shiftId) {
      const s = shiftsById.get(assignment.shiftId);
      const duty = s?.code ?? assignment.shiftId;
      return { kind: 'absence', conflict: true, code: `${absence.typeCode}/${duty}`, label: t('grid.absenceConflict', { name, duty }),
        color: type?.color ?? null, pinned, fixedInPeriod, shiftId: assignment.shiftId };
    }
    return { kind: 'absence', conflict: false, code: absence.typeCode, label: name, color: type?.color ?? null,
      pinned, fixedInPeriod, shiftId: null };
  }
  if (assignment?.shiftId) {
    const s = shiftsById.get(assignment.shiftId);
    return { kind: 'shift', code: s?.code ?? assignment.shiftId, label: s?.name ?? assignment.shiftId,
      color: s?.color ?? null, areaId: s?.areaId ?? null, pinned, fixedInPeriod, shiftId: assignment.shiftId };
  }
  if (assignment?.compRest) {
    return { kind: 'compRest', code: t('grid.compRestCode'), label: t('grid.compRest'), color: null, pinned, fixedInPeriod, shiftId: null };
  }
  return { kind: 'free', code: '', label: t('grid.free'), color: null, pinned, fixedInPeriod, shiftId: null };
}

/**
 * @param {object} p
 * @param {object} p.dataset Dataset (areas, shifts, people, holidays, absenceTypes)
 * @param {object|null} p.period Period of the month
 * @param {object|null} p.plan Plan of the month
 * @param {string} p.month 'YYYY-MM'
 * @param {string|null} [p.areaId] only people qualified for this area
 * @param {string} [p.search] case-insensitive filter on name, short name or id
 */
export function buildGrid({ dataset, period, plan, month, areaId = null, search = '' }) {
  const days = buildDays(month, dataset.holidays);
  const shiftsById = new Map(dataset.shifts.map((s) => [s.id, s]));
  const absenceTypes = new Map((dataset.absenceTypes ?? []).map((t) => [t.code, t]));
  const key = (personId, date) => `${personId}|${date}`;
  const assignments = new Map((plan?.assignments ?? []).map((a) => [key(a.personId, a.date), a]));
  const absences = new Map((period?.absences ?? []).map((a) => [key(a.personId, a.date), a]));
  const fixed = new Map((period?.fixed ?? []).map((f) => [key(f.personId, f.date), f]));
  const needle = search.trim().toLowerCase();

  const rows = dataset.people
    .filter((p) => !areaId || p.areaIds.includes(areaId))
    .filter((p) => !needle || [personName(p), p.shortName ?? '', p.id].some((t) => t.toLowerCase().includes(needle)))
    .map((person) => {
      const cells = days.map((d) => buildCell(assignments.get(key(person.id, d.date)),
        absences.get(key(person.id, d.date)), fixed.get(key(person.id, d.date)), shiftsById, absenceTypes));
      const hours = cells.reduce((sum, c) => sum + (c.kind === 'shift' && shiftsById.has(c.shiftId) ? shiftHours(shiftsById.get(c.shiftId)) : 0), 0);
      return { person, name: personName(person), cells, hours };
    })
    .sort((a, b) => (a.person.areaIds[0] ?? '').localeCompare(b.person.areaIds[0] ?? '') || a.name.localeCompare(b.name, 'de'));

  return { days, rows };
}

/** Choices for the cell popover: shifts of the person's areas, free, compensatory rest. */
export function cellOptions(person, shifts) {
  const own = shifts.filter((s) => person.areaIds.includes(s.areaId));
  return [
    ...own.map((s) => ({ shiftId: s.id, compRest: false, code: s.code, label: `${s.name} (${s.start.slice(0, 5)}-${s.end.slice(0, 5)})`, color: s.color })),
    { shiftId: null, compRest: false, code: '', label: t('grid.free'), color: null },
    { shiftId: null, compRest: true, code: t('grid.compRestCode'), label: t('grid.compRest'), color: null },
  ];
}


/** Grid cells can be changed only by planners, not while a solve runs and not in a closed month. */
export function cellsEditable({ canEdit, solving = false, closed = false }) {
  return Boolean(canEdit) && !solving && !closed;
}
