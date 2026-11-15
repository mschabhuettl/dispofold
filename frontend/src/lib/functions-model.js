// "Plan nach Funktion": functions (a shift of an area) x days of the month, one row per position. Pure, no DOM.
import { epochMinutes, parseLocalDateTime, parseTime, shiftInterval } from './dates.js';
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
 * Missing people per shift and day, as Map 'shiftId|date' -> {missing, gaps}.
 * Rule: a gap of the analysis ({areaId, from, to, required, planned}) counts for every shift of its area whose time
 * on a day of the month (start to end from the shift, a night ends on the next day) overlaps the gap's span; it
 * misses required - planned people there. If several gaps hit the same shift and day, the largest count applies.
 * A gap that no shift of its area overlaps does not show in this view.
 */
export function missingByShiftDay(gaps, shifts, days) {
  const out = new Map();
  for (const g of gaps ?? []) {
    const from = parseLocalDateTime(g.from);
    const to = parseLocalDateTime(g.to);
    const missing = g.required - g.planned;
    if (missing <= 0) continue;
    for (const s of shifts) {
      if (s.areaId !== g.areaId) continue;
      for (const date of days) {
        const iv = shiftInterval(date, s);
        if (iv.start >= to || iv.end <= from) continue;
        const k = `${s.id}|${date}`;
        const entry = out.get(k) ?? { missing: 0, gaps: [] };
        entry.missing = Math.max(entry.missing, missing);
        entry.gaps.push(g);
        out.set(k, entry);
      }
    }
  }
  return out;
}

/**
 * Rows of the view: a group row per area (dataset order) followed by the position rows of its shifts (dataset order).
 * Shown are shifts with at least one duty in the month or whose area has a demand rule overlapping the shift's time.
 * A shift gets as many rows as the largest number over the days of people on it plus missing people. Row k on a day
 * shows the k-th person (by name) on that shift, then the gap markers, else nothing.
 * Cells: {kind: 'person', person, name, cell} with the roster cell (pinned, conflict, on request), {kind: 'gap', gaps}
 * or {kind: 'empty'}.
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
  const missing = missingByShiftDay(gaps, dataset.shifts, dates);
  const rows = [];
  for (const area of dataset.areas) {
    const demand = (dataset.demand ?? []).filter((d) => d.areaId === area.id);
    const shown = dataset.shifts.filter((s) => s.areaId === area.id
      && (dates.some((d) => onShift.has(`${s.id}|${d}`)) || demand.some((d) => windowOverlapsShift(d, s))));
    if (!shown.length) continue;
    rows.push({ type: 'group', area });
    for (const shift of shown) {
      const columns = dates.map((d) => {
        const people = onShift.get(`${shift.id}|${d}`) ?? [];
        const gap = missing.get(`${shift.id}|${d}`);
        return { people, missing: gap?.missing ?? 0, gaps: gap?.gaps ?? [] };
      });
      const count = Math.max(1, ...columns.map((c) => c.people.length + c.missing));
      for (let k = 0; k < count; k++) {
        rows.push({ type: 'position', shift, index: k, first: k === 0,
          cells: columns.map((c) => (k < c.people.length ? c.people[k]
            : k < c.people.length + c.missing ? { kind: 'gap', gaps: c.gaps } : { kind: 'empty' })) });
      }
    }
  }
  return { days: grid.days, rows };
}

/** Day columns for print: the whole month does not fit an A4 landscape page, so days 1-15 and 16 to the end. */
export function printParts(days) {
  return [days.slice(0, 15), days.slice(15)].filter((part) => part.length);
}

