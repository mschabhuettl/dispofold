// "Wer kann gefragt werden?": people for whom a shift in a gap is only on request. Pure, no DOM.
import { dayLabel, formatTime, parseLocalDateTime } from './dates.js';
import { fmt } from './dom.js';
import { personName } from './grid-model.js';
import { t } from './texts.js';

/**
 * Rows of the list from GET .../candidates (already sorted by the server, kept in that order).
 * @param {object[]} candidates [{personId, date, shiftId, plannedHours, targetHours}]
 * @param {Map} people id -> person
 * @param {Map} shifts id -> shift
 */
export function candidateRows(candidates, people, shifts) {
  return candidates.map((c) => {
    const p = people.get(c.personId);
    const s = shifts.get(c.shiftId);
    const name = p ? personName(p) : c.personId;
    const code = s?.code ?? c.shiftId;
    const day = dayLabel(c.date);
    const hours = t('candidates.hours', { planned: fmt.num(c.plannedHours, 1), target: fmt.num(c.targetHours, 1) });
    return { key: `${c.personId}|${c.date}|${c.shiftId}`, name, code, color: s?.color ?? null, day, hours,
      label: t('candidates.item', { name, code, day, hours }), request: candidateRequest(c) };
  });
}

/** Body for PUT .../cells: the duty set by hand and fixed, as a choice in the cell popover does. */
export function candidateRequest(c) {
  return { personId: c.personId, date: c.date, shiftId: c.shiftId, compRest: false, pinned: true };
}

/** One line for a gap of the analysis: area, time and how many are missing. */
export function gapLabel(gap, areaName) {
  const from = parseLocalDateTime(gap.from);
  const to = parseLocalDateTime(gap.to);
  const sameDay = gap.from.slice(0, 10) === gap.to.slice(0, 10) || (to - from <= 1440 && formatTime(to) === '00:00');
  const time = sameDay ? `${formatTime(from)}-${formatTime(to)}`
    : `${formatTime(from)}-${dayLabel(gap.to.slice(0, 10))} ${formatTime(to)}`;
  return t('candidates.gap', { area: areaName ?? gap.areaId, time, count: gap.required - gap.planned });
}
