// Merging the Eingaben of a month after a 409 period.conflict, and absences as date ranges. Pure, no DOM.
// Entries are keyed by person and date (one absence, wish and fixed duty per person and day).
import { addDays } from './dates.js';

const LISTS = ['absences', 'wishes', 'fixed'];
const key = (e) => `${e.personId}|${e.date}`;
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/**
 * Applies the local changes (base -> local) onto the server's newer version.
 * Returns the merged period (with the server's revision) and the keys where both sides changed the same entry
 * differently; there the local change wins and the caller shows them for checking.
 */
export function mergePeriod(base, local, server) {
  const merged = { ...server };
  const conflicts = [];
  for (const list of LISTS) {
    const b = new Map((base[list] ?? []).map((e) => [key(e), e]));
    const l = new Map((local[list] ?? []).map((e) => [key(e), e]));
    const s = new Map((server[list] ?? []).map((e) => [key(e), e]));
    const out = new Map(s);
    for (const k of new Set([...b.keys(), ...l.keys()])) {
      const before = b.get(k);
      const mine = l.get(k);
      if (same(before, mine)) continue; // not changed locally: the server's version stays
      const theirs = s.get(k);
      if (!same(before, theirs) && !same(mine, theirs)) conflicts.push({ list, key: k });
      if (mine) out.set(k, mine);
      else out.delete(k);
    }
    merged[list] = [...out.values()];
  }
  return { period: merged, conflicts };
}

/**
 * Absences as date ranges: consecutive days of one person with the same type form one range
 * { personId, typeCode, from, to, entries } (entries: the per-day records), sorted by person and date.
 */
export function absenceRanges(absences) {
  const sorted = [...(absences ?? [])].sort((a, b) => a.personId.localeCompare(b.personId) || a.date.localeCompare(b.date));
  const ranges = [];
  for (const a of sorted) {
    const last = ranges.at(-1);
    if (last && last.personId === a.personId && last.typeCode === a.typeCode && addDays(last.to, 1) === a.date) {
      last.to = a.date;
      last.entries.push(a);
    } else ranges.push({ personId: a.personId, typeCode: a.typeCode, from: a.date, to: a.date, entries: [a] });
  }
  return ranges;
}

/**
 * The absences with a range entered: one record { personId, date, typeCode } per day from..to (both included), as the
 * backend stores them; the person's absences on these days are replaced, so a range inside another one splits it.
 */
export function addAbsenceRange(absences, { personId, from, to, typeCode }) {
  const days = [];
  for (let d = from; d <= to; d = addDays(d, 1)) days.push(d);
  const covered = new Set(days);
  return [...(absences ?? []).filter((a) => !(a.personId === personId && covered.has(a.date))), ...days.map((date) => ({ personId, date, typeCode }))];
}
