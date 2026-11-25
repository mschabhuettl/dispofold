// The state of an area for a person: planbar (no entry), "nur von Hand" (ON_REQUEST) or "nie" (NEVER) for every shift
// of the area on every day. The server stores it as restrictions and the pages read it folded (backend
// app.dispofold.model.AreaStates): person.areaStates and only the other restrictions, the exceptions. Pure, no DOM.
import { t } from './texts.js';
import { WEEKDAY_SHORT } from './dates.js';

export const STATES = ['', 'ON_REQUEST', 'NEVER'];

/** A restriction for every day without dates: what a whole-area set is made of. */
const whole = (r) => r.shiftId != null && !Number(r.weekday ?? 0) && r.validFrom == null && r.validTo == null;

const shiftsByArea = (dataset) => {
  const byArea = new Map();
  for (const s of dataset.shifts ?? []) {
    if (!byArea.has(s.areaId)) byArea.set(s.areaId, []);
    byArea.get(s.areaId).push(s.id);
  }
  return byArea;
};

/** The restrictions with every area state as its rows (one undated every-day row per shift of the area). */
export function expandRestrictions(dataset) {
  const byArea = shiftsByArea(dataset);
  const out = [...(dataset.restrictions ?? [])];
  const key = (r) => [r.personId, r.shiftId, r.category ?? null, Number(r.weekday ?? 0), r.kind, r.validFrom ?? null, r.validTo ?? null].join('|');
  const present = new Set(out.map(key));
  for (const p of dataset.people ?? []) {
    for (const [areaId, kind] of Object.entries(p.areaStates ?? {})) {
      if (!(p.areaIds ?? []).includes(areaId)) continue;
      for (const shiftId of byArea.get(areaId) ?? []) {
        const r = { personId: p.id, shiftId, category: null, weekday: 0, kind, validFrom: null, validTo: null };
        if (!present.has(key(r))) {
          present.add(key(r));
          out.push(r);
        }
      }
    }
  }
  return out;
}

/**
 * The folded view (as the backend's AreaStates.fold): per person the areas whose every shift has whole-day rows of one
 * kind only become a state, those rows leave the restrictions. For the dev server's mock.
 */
export function foldDataset(dataset) {
  const byArea = shiftsByArea(dataset);
  const drop = new Set();
  const people = (dataset.people ?? []).map((p) => {
    const own = (dataset.restrictions ?? []).filter((r) => r.personId === p.id);
    const states = {};
    for (const a of p.areaIds ?? []) {
      const shifts = byArea.get(a) ?? [];
      let kind = p.areaStates?.[a] ?? null;
      if (!kind && shifts.length) {
        const kinds = new Set();
        const all = shifts.every((s) => {
          const rows = own.filter((r) => r.shiftId === s && whole(r));
          rows.forEach((r) => kinds.add(r.kind));
          return rows.length > 0;
        });
        kind = all && kinds.size === 1 ? [...kinds][0] : null;
      }
      if (kind) {
        states[a] = kind;
        own.filter((r) => shifts.includes(r.shiftId) && whole(r) && r.kind === kind).forEach((r) => drop.add(r));
      }
    }
    const { areaStates, ...rest } = p;
    return Object.keys(states).length ? { ...rest, areaStates: states } : rest;
  });
  return { ...dataset, people, restrictions: (dataset.restrictions ?? []).filter((r) => !drop.has(r)) };
}

/** The dataset as the pages use it: `exceptions` as read, `restrictions` with the states as rows (for planning checks). */
export const withEffectiveRestrictions = (dataset) => ({ ...dataset, exceptions: dataset.restrictions ?? [], restrictions: expandRestrictions(dataset) });

const de = (iso) => {
  const [, m, d] = iso.split('-');
  return `${Number(d)}. ${Number(m)}.`;
};

export const kindWord = (kind) => (kind === 'NEVER' ? t('areaState.never') : t('areaState.manual'));

/**
 * The exceptions of a person folded into one line per shift or category, kind and validity, the weekdays in it:
 * [{ areaId (null: a category or a shift of no area of the person), target, text, rows }].
 */
export function exceptionLines(exceptions, personId, shifts, categories, areaIds = []) {
  const groups = new Map();
  for (const r of exceptions ?? []) {
    if (r.personId !== personId) continue;
    const key = [r.shiftId ?? `category:${r.category}`, r.kind, r.validFrom ?? '', r.validTo ?? ''].join('|');
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(r);
  }
  return [...groups.values()].map((rows) => {
    const r = rows[0];
    const shift = r.shiftId ? shifts.get(r.shiftId) : null;
    const target = r.shiftId ? (shift ? `${shift.code} ${shift.name}` : r.shiftId) : t('areaState.category', { category: categories[r.category] ?? r.category });
    const days = rows.some((x) => !Number(x.weekday ?? 0)) ? [] : [...new Set(rows.map((x) => Number(x.weekday)))].sort((a, b) => a - b);
    const validity = r.validFrom && r.validTo ? t('areaState.range', { from: de(r.validFrom), to: de(r.validTo) })
      : r.validFrom ? t('areaState.from', { from: de(r.validFrom) }) : r.validTo ? t('areaState.until', { to: de(r.validTo) }) : '';
    const text = `${target}: ${kindWord(r.kind)}${days.length ? ` (${days.map((d) => WEEKDAY_SHORT[d - 1]).join(', ')})` : ''}${validity ? ` ${validity}` : ''}`;
    const areaId = shift && areaIds.includes(shift.areaId) ? shift.areaId : null;
    return { areaId, target, text, rows };
  });
}
