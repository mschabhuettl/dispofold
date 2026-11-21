// Restrictions of people ("nie", "nur auf Anfrage"), matched like the backend's Restriction.appliesOn. Pure, no DOM.
import { parseTime, weekday } from './dates.js';
import { restrictionFromForm } from './masterdata-edit.js';

/** Applies on this date: inside validFrom..validTo (null: open) and on the weekday (0: every day, 1..7 Mon..Sun). */
export function restrictionAppliesOn(r, date) {
  return (!r.validFrom || date >= r.validFrom) && (!r.validTo || date <= r.validTo)
    && (!r.weekday || r.weekday === weekday(date));
}

/**
 * Category of a shift as the backend sees it (RosterBuilder.option): the one in the dataset, else derived from the
 * times with the night window of the rules: night (enough minutes in the night window), early (start before 7:00),
 * day (end by 19:00), late. Without rules a shift without a category has none.
 */
export function shiftCategory(shift, rules) {
  if (shift.category) return shift.category;
  if (!rules) return null;
  const start = parseTime(shift.start);
  const end = parseTime(shift.end);
  const duration = end > start ? end - start : end + 1440 - start;
  const from = parseTime(rules.nightStart);
  const to = parseTime(rules.nightEnd);
  const length = to > from ? to - from : to + 1440 - from;
  let night = 0;
  for (let day = -1; day <= 1; day++) {
    const windowStart = day * 1440 + from;
    night += Math.max(0, Math.min(start + duration, windowStart + length) - Math.max(start, windowStart));
  }
  if (night >= Math.round(rules.nightMinHours * 60)) return 'NIGHT';
  if (start < 7 * 60) return 'EARLY';
  return start + duration <= 19 * 60 ? 'DAY' : 'LATE';
}

/** The rules are needed to match category restrictions only when a shift has no category in the dataset. */
export function needsRulesForCategories(dataset) {
  return (dataset.restrictions ?? []).some((r) => r.kind === 'ON_REQUEST' && r.category) && dataset.shifts.some((s) => !s.category);
}

/**
 * A test (personId, date, shiftId) => boolean: the shift is only on request for the person that day, by shift or by
 * its category (see shiftCategory). Viewers get no restrictions, so nothing matches for them.
 */
export function onRequestTest(restrictions, shifts, rules = null) {
  const categories = new Map(shifts.map((s) => [s.id, shiftCategory(s, rules)]));
  const byPerson = new Map();
  for (const r of restrictions ?? []) {
    if (r.kind !== 'ON_REQUEST') continue;
    if (!byPerson.has(r.personId)) byPerson.set(r.personId, []);
    byPerson.get(r.personId).push(r);
  }
  return (personId, date, shiftId) => {
    const category = categories.get(shiftId) ?? null;
    return (byPerson.get(personId) ?? []).some((r) => restrictionAppliesOn(r, date)
      && (r.shiftId ? r.shiftId === shiftId : category != null && r.category === category));
  };
}

/** Restrictions of one shift of the person for every date (no validFrom, no validTo); the area switch acts on these. */
const undatedFor = (r, personId, ids) => r.personId === personId && r.shiftId != null && ids.has(r.shiftId) && r.validFrom == null && r.validTo == null;

/**
 * State of a whole area for the person: 'NEVER' or 'ON_REQUEST' when every shift of the area
 * has exactly that restriction on every day and no other undated one, 'NONE' when no shift has an undated one,
 * 'MIXED' otherwise. Dated restrictions and those by category do not count.
 */
export function areaRestriction(restrictions, personId, shiftIds) {
  const undated = (restrictions ?? []).filter((r) => undatedFor(r, personId, new Set(shiftIds)));
  const per = shiftIds.map((id) => {
    const own = undated.filter((r) => r.shiftId === id);
    if (!own.length) return 'NONE';
    return own.length === 1 && !own[0].weekday ? own[0].kind : 'MIXED';
  });
  if (!per.length) return 'NONE';
  return per.every((x) => x === per[0]) ? per[0] : 'MIXED';
}

/**
 * Sets the whole area to kind ('NONE', 'ON_REQUEST' or 'NEVER'): the undated restrictions of its shifts are replaced by
 * one per shift for every day, the same records as entered shift by shift. Dated and category restrictions stay.
 */
export function setAreaRestriction(restrictions, personId, shiftIds, kind) {
  const ids = new Set(shiftIds);
  const kept = (restrictions ?? []).filter((r) => !undatedFor(r, personId, ids));
  if (kind === 'NONE') return kept;
  return [...kept, ...shiftIds.map((id) => restrictionFromForm(personId, { target: `shift:${id}`, weekday: '0', kind }))];
}
