// "Plan nach Funktion": functions (a shift of an area) x days of the month, one row per position. Pure, no DOM.
import { epochMinutes, formatTime, parseLocalDateTime, parseTime, shiftInterval } from './dates.js';
import { buildGrid } from './grid-model.js';

/** The demand window [from, to) of a rule overlaps the shift's time of day (both may cross midnight). */
export function windowOverlapsShift(rule, shift) {
  const s = shiftInterval('2000-01-02', shift);
  const from = epochMinutes('2000-01-02', parseTime(rule.from));
  let to = epochMinutes('2000-01-02', parseTime(rule.to));
  if (to <= from) to += 1440;
  return [-1440, 0, 1440].some((shiftBy) => from + shiftBy < s.end && to + shiftBy > s.start);
}

/**
 * Rows of the view: a group row per area (dataset order), the position rows of its shifts (dataset order) and, if the
 * area has a gap in the month, one row "offen" with the gaps.
 * Shown are shifts with at least one duty in the month or whose area has a demand rule overlapping the shift's time.
 * A shift gets as many rows as the largest number over the days of people on it. Row k on a day shows the k-th
 * person (by name) on that shift, else nothing.
 * A gap of the analysis ({areaId, from, to, required, planned}) belongs to the day of its start (a gap from 19:00
 * past midnight to the day it starts) and shows once, in the gap row of its area; several gaps of a day share a cell.
 * Cells: position rows {kind: 'person', person, name, cell} with the roster cell (pinned, conflict, on request) or
 * {kind: 'empty'}; gap rows {kind: 'gaps', gaps} (sorted by start) or {kind: 'empty'}.
 */
export function buildFunctionRows({ dataset, period, plan, month, gaps = [], onRequest = null }) {
  const grid = buildGrid({ dataset, period, plan, month, onRequest });
  const dates = grid.days.map((d) => d.date);
  // people per shift and day, from the roster cells so that marks agree with the roster
  const onShift = new Map();
  for (const row of [...grid.rows].sort((a, b) => a.name.localeCompare(b.name, 'de'))) {
    row.cells.forEach((cell, i) => {
      if (!cell.shiftId) return;
      const k = `${cell.shiftId}|${dates[i]}`;
      if (!onShift.has(k)) onShift.set(k, []);
      onShift.get(k).push({ kind: 'person', person: row.person, name: row.name, cell });
    });
  }
  const gapsByArea = new Map();
  for (const g of [...gaps].sort((a, b) => a.from.localeCompare(b.from))) {
    const i = dates.indexOf(g.from.slice(0, 10));
    if (g.required - g.planned <= 0 || i < 0) continue;
    if (!gapsByArea.has(g.areaId)) gapsByArea.set(g.areaId, dates.map(() => []));
    gapsByArea.get(g.areaId)[i].push(g);
  }
  const rows = [];
  for (const area of dataset.areas) {
    const demand = (dataset.demand ?? []).filter((d) => d.areaId === area.id);
    const shown = dataset.shifts.filter((s) => s.areaId === area.id
      && (dates.some((d) => onShift.has(`${s.id}|${d}`)) || demand.some((d) => windowOverlapsShift(d, s))));
    const areaGaps = gapsByArea.get(area.id);
    if (!shown.length && !areaGaps) continue;
    rows.push({ type: 'group', area });
    for (const shift of shown) {
      const columns = dates.map((d) => onShift.get(`${shift.id}|${d}`) ?? []);
      const count = Math.max(1, ...columns.map((people) => people.length));
      for (let k = 0; k < count; k++) {
        rows.push({ type: 'position', shift, index: k, first: k === 0,
          cells: columns.map((people) => people[k] ?? { kind: 'empty' }) });
      }
    }
    if (areaGaps) rows.push({ type: 'gaps', area, cells: areaGaps.map((list) => (list.length ? { kind: 'gaps', gaps: list } : { kind: 'empty' })) });
  }
  return { days: grid.days, rows };
}

/** One line of a gap cell: missing people and the time span, e.g. {count: 1, from: '07:00', to: '19:00'}. */
export function gapLine(gap) {
  return { count: gap.required - gap.planned, from: formatTime(parseLocalDateTime(gap.from)), to: formatTime(parseLocalDateTime(gap.to)) };
}

/** Day columns for print: the whole month does not fit an A4 landscape page, so days 1-15 and 16 to the end. */
export function printParts(days) {
  return [days.slice(0, 15), days.slice(15)].filter((part) => part.length);
}


/** Name modes of the function view: the short name (Kürzel) or "Nachname Vorname". */
export const NAME_MODES = ['short', 'full'];

/** Text of a person cell; the short name falls back to the full name when a person has none. */
export function personLabel(person, name, mode) {
  return mode === 'full' ? name : person.shortName || name;
}
