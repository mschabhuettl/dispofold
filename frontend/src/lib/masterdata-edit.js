// Editing master data (docs/api.md, "Stammdaten bearbeiten"): form values to objects and back, the decision
// after a 409 masterdata.conflict, and texts for restrictions, family rules and the in-use question. Pure, no DOM.
// Form values are plain objects of strings, booleans and string lists as read from the inputs.
import { monthLabel, WEEKDAY_SHORT } from './dates.js';
import { t } from './texts.js';

export const KINDS = ['people', 'areas', 'shifts', 'demand', 'holidays', 'absenceTypes'];
/** Kinds whose key is the position in the list (0-based) instead of an id. */
const INDEXED = new Set(['holidays', 'demand']);
export const CATEGORIES = ['EARLY', 'DAY', 'LATE', 'NIGHT'];
export const DEMAND_DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN', 'HOL'];

const CATEGORY = { EARLY: t('masterdata.category.early'), DAY: t('masterdata.category.day'), LATE: t('masterdata.category.late'), NIGHT: t('masterdata.category.night') };
const KIND_ONE = { people: t('kind.person'), areas: t('kind.area'), shifts: t('kind.shift'), demand: t('kind.demand'),
  holidays: t('kind.holiday'), absenceTypes: t('kind.absenceType'), restrictions: t('kind.restrictions') };
const WEEKDAYS = ['', ...WEEKDAY_SHORT];
const DAY_NAMES = { MON: WEEKDAY_SHORT[0], TUE: WEEKDAY_SHORT[1], WED: WEEKDAY_SHORT[2], THU: WEEKDAY_SHORT[3], FRI: WEEKDAY_SHORT[4],
  SAT: WEEKDAY_SHORT[5], SUN: WEEKDAY_SHORT[6], HOL: t('masterdata.holiday') };

const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const text = (v) => String(v ?? '').trim();
/** Empty input as null, otherwise the trimmed text. */
export const orNull = (v) => text(v) || null;
const hhmm = (v) => (v == null ? '' : String(v).slice(0, 5));

/** A number from an input ('' and non-numbers throw); a decimal comma is accepted. */
export function number(v, field) {
  const s = text(v).replace(',', '.');
  const n = Number(s);
  if (!s || !Number.isFinite(n)) throw new Error(t('masterdata.form.number', { field }));
  return n;
}

/** "HH:MM" (also "H:MM" or "HH:MM:SS" from a time input) or null for an empty input. */
export function time(v, field) {
  const s = text(v);
  if (!s) return null;
  const m = /^(\d{1,2}):(\d{2})(?::\d{2})?$/.exec(s);
  if (!m || Number(m[1]) > 23 || Number(m[2]) > 59) throw new Error(t('masterdata.form.time', { field }));
  return `${m[1].padStart(2, '0')}:${m[2]}`;
}

const required = (v, field) => {
  const s = text(v);
  if (!s) throw new Error(t('masterdata.form.required', { field }));
  return s;
};

/** Key of an object in the API path: id, code or the position in the list. */
export function keyOf(kind, item, index) {
  if (INDEXED.has(kind)) return index;
  return kind === 'absenceTypes' ? item.code : item.id;
}

// ---- objects to form values and back ----

export function familyRuleToForm(f) {
  return { weekdays: (f.weekdays ?? []).map(String), latestEnd: hhmm(f.latestEnd), earliestStart: hhmm(f.earliestStart),
    noNights: Boolean(f.noNights), note: f.note ?? '' };
}

export function familyRuleFromForm(v) {
  const weekdays = (v.weekdays ?? []).map(Number).sort((a, b) => a - b);
  if (!weekdays.length) throw new Error(t('masterdata.form.familyDays'));
  return { weekdays, latestEnd: time(v.latestEnd, t('masterdata.family.col.latestEnd')),
    earliestStart: time(v.earliestStart, t('masterdata.family.col.earliestStart')), noNights: Boolean(v.noNights), note: text(v.note) };
}

const TO_FORM = {
  people: (p) => ({ firstName: p.firstName ?? '', lastName: p.lastName ?? '', shortName: p.shortName ?? '',
    weeklyHours: String(p.weeklyHours ?? ''), areaIds: [...(p.areaIds ?? [])], hoursAccount: String(p.hoursAccount ?? 0),
    compRestBalance: String(p.compRestBalance ?? 0), externalId: p.externalId ?? '', entryDate: p.entryDate ?? '',
    exitDate: p.exitDate ?? '', familyRules: (p.familyRules ?? []).map(familyRuleToForm) }),
  areas: (a) => ({ id: a.id ?? '', name: a.name ?? '', color: a.color ?? '' }),
  shifts: (s) => ({ areaId: s.areaId ?? '', code: s.code ?? '', name: s.name ?? '', start: hhmm(s.start), end: hhmm(s.end),
    category: s.category ?? '', color: s.color ?? '', unpopular: Boolean(s.unpopular) }),
  demand: (d) => ({ areaId: d.areaId ?? '', mode: d.date ? 'date' : 'days', days: [...(d.days ?? [])], date: d.date ?? '',
    from: hhmm(d.from), to: hhmm(d.to), min: String(d.min ?? ''), max: d.max == null ? '' : String(d.max) }),
  holidays: (x) => ({ date: x.date ?? '', name: x.name ?? '' }),
  absenceTypes: (a) => ({ code: a.code ?? '', name: a.name ?? '', reducesTarget: Boolean(a.reducesTarget),
    chargesAccount: Boolean(a.chargesAccount), compensatoryRest: Boolean(a.compensatoryRest), color: a.color ?? '' }),
};

/** Form values of an object; null gives the values of a new, empty object. */
export function toForm(kind, item) {
  return TO_FORM[kind](item ?? {});
}

const FROM_FORM = {
  people: (v, base) => {
    const entryDate = orNull(v.entryDate);
    const exitDate = orNull(v.exitDate);
    if (entryDate && exitDate && exitDate < entryDate) throw new Error(t('masterdata.exitBeforeEntry'));
    // fields the form does not show (e.g. from a newer server) stay as they were
    return { ...base, firstName: required(v.firstName, t('masterdata.col.firstName')), lastName: required(v.lastName, t('masterdata.col.lastName')),
      shortName: orNull(v.shortName), weeklyHours: number(v.weeklyHours, t('masterdata.col.weeklyHours')), areaIds: [...(v.areaIds ?? [])],
      hoursAccount: number(v.hoursAccount, t('masterdata.col.account')), compRestBalance: number(v.compRestBalance, t('masterdata.col.compRestOpen')),
      familyRules: (v.familyRules ?? []).map(familyRuleFromForm), externalId: orNull(v.externalId), entryDate, exitDate };
  },
  areas: (v, base) => ({ ...base, id: required(v.id, t('masterdata.col.shortName')), name: required(v.name, t('masterdata.col.name')), color: orNull(v.color) }),
  shifts: (v, base) => ({ ...base, areaId: required(v.areaId, t('masterdata.col.area')), code: required(v.code, t('masterdata.col.code')),
    name: required(v.name, t('masterdata.col.name')), start: time(required(v.start, t('masterdata.col.start')), t('masterdata.col.start')),
    end: time(required(v.end, t('masterdata.col.end')), t('masterdata.col.end')), category: orNull(v.category), color: orNull(v.color),
    unpopular: Boolean(v.unpopular) }),
  demand: (v, base) => {
    const byDate = v.mode === 'date';
    const days = DEMAND_DAYS.filter((d) => (v.days ?? []).includes(d));
    if (!byDate && !days.length) throw new Error(t('masterdata.form.demandDays'));
    const min = number(v.min, t('masterdata.col.min'));
    const max = text(v.max) ? number(v.max, t('masterdata.col.max')) : null;
    if (max != null && max < min) throw new Error(t('masterdata.form.maxBelowMin'));
    return { ...base, areaId: required(v.areaId, t('masterdata.col.area')), days: byDate ? null : days,
      date: byDate ? required(v.date, t('masterdata.col.date')) : null,
      from: time(required(v.from, t('masterdata.col.from')), t('masterdata.col.from')), to: time(required(v.to, t('masterdata.col.to')), t('masterdata.col.to')), min, max };
  },
  holidays: (v, base) => ({ ...base, date: required(v.date, t('masterdata.col.date')), name: required(v.name, t('masterdata.col.name')) }),
  absenceTypes: (v, base) => ({ ...base, code: required(v.code, t('masterdata.col.code')), name: required(v.name, t('masterdata.col.name')),
    reducesTarget: Boolean(v.reducesTarget), chargesAccount: Boolean(v.chargesAccount), compensatoryRest: Boolean(v.compensatoryRest), color: orNull(v.color) }),
};

/**
 * The object to send from form values; base is the object as read (null when creating), so fields the form does
 * not show are kept. New people and shifts get their id from the server. Throws an Error with a German message.
 */
export function fromForm(kind, values, base = null) {
  const item = FROM_FORM[kind](values, base ?? {});
  if (!base && (kind === 'people' || kind === 'shifts')) delete item.id;
  return item;
}

/** One restriction from the form: target 'shift:<id>' or 'category:<CATEGORY>', weekday '0'..'7'. */
export function restrictionFromForm(personId, v) {
  const [type, value] = text(v.target).split(/:(.*)/s);
  if (!value || (type !== 'shift' && type !== 'category')) throw new Error(t('masterdata.form.restrictionTarget'));
  const validFrom = orNull(v.validFrom);
  const validTo = orNull(v.validTo);
  if (validFrom && validTo && validTo < validFrom) throw new Error(t('masterdata.form.validity'));
  return { personId, shiftId: type === 'shift' ? value : null, category: type === 'category' ? value : null,
    weekday: Number(v.weekday ?? 0), kind: v.kind === 'ON_REQUEST' ? 'ON_REQUEST' : 'NEVER', validFrom, validTo };
}

// ---- texts ----

const de = (iso) => `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}`;

/** "Nie N1 (Nacht AN), Mo, ab 01.01.2027" for a restriction; shifts maps ids to shift types. */
export function restrictionText(r, shifts) {
  const shift = r.shiftId ? shifts.get(r.shiftId) : null;
  const target = r.shiftId ? (shift ? `${shift.code} ${shift.name}` : r.shiftId) : t('masterdata.restriction.category', { category: CATEGORY[r.category] ?? r.category });
  const validity = r.validFrom && r.validTo ? t('masterdata.employment.range', { from: de(r.validFrom), to: de(r.validTo) })
    : r.validFrom ? t('masterdata.employment.from', { from: de(r.validFrom) }) : r.validTo ? t('masterdata.employment.until', { to: de(r.validTo) }) : '';
  const kind = r.kind === 'ON_REQUEST' ? t('masterdata.restriction.onRequest') : t('masterdata.restriction.never');
  return [`${kind}: ${target}`, r.weekday ? WEEKDAYS[r.weekday] : t('masterdata.restriction.everyDay'), validity].filter(Boolean).join(', ');
}

/** "Di/Do: Ende bis 18:00, keine Nacht (Kinderbetreuung)" for a family rule. */
export function familyText(f) {
  const days = (f.weekdays ?? []).map((d) => WEEKDAYS[d]).join('/');
  const parts = [f.latestEnd && t('masterdata.family.latestEnd', { time: hhmm(f.latestEnd) }),
    f.earliestStart && t('masterdata.family.earliestStart', { time: hhmm(f.earliestStart) }), f.noNights && t('masterdata.family.noNights')];
  const out = t('masterdata.family', { days, parts: parts.filter(Boolean).join(', ') });
  return f.note ? `${out} (${f.note})` : out;
}

/** Short label of an object for questions and messages, e.g. "Schicht N1", "Bedarfsregel AN Mo, Di 07:00-19:00". */
export function itemLabel(kind, item) {
  const name = kind === 'people' ? [item.firstName, item.lastName].filter(Boolean).join(' ') || item.id
    : kind === 'shifts' ? item.code : kind === 'areas' ? item.id : kind === 'absenceTypes' ? item.code
      : kind === 'holidays' ? de(item.date)
        : `${item.areaId} ${item.date ? de(item.date) : (item.days ?? []).map((d) => DAY_NAMES[d] ?? d).join(', ')} ${hhmm(item.from)}-${hhmm(item.to)}`;
  return `${KIND_ONE[kind]} ${name}`;
}

/** The question after 409 masterdata.inUse ("months" is a comma string and may be empty). */
export function inUseQuestion(kind, item, params) {
  const months = text(params?.months).split(',').map((m) => m.trim()).filter(Boolean);
  const count = Number(params?.count ?? 0);
  const label = itemLabel(kind, item);
  return months.length ? t('masterdata.inUse.confirmMonths', { label, count, months: months.map(monthLabel).join(', ') })
    : t('masterdata.inUse.confirm', { label, count });
}

// ---- conflict ----

/** The list of a kind in a dataset; for 'restrictions' the restrictions of the person key. */
function listOf(kind, dataset, key) {
  if (kind === 'restrictions') return (dataset.restrictions ?? []).filter((r) => r.personId === key);
  return dataset[kind] ?? [];
}

/**
 * After 409 masterdata.conflict: what to do with a change made on `base` (the object as read before the change,
 * null when creating; for 'restrictions' the person's list as read) now that `fresh` is the newer dataset.
 * - { action: 'retry', key }: the object is unchanged on the server, repeat the change with the new revision
 *   (for holidays and demand under its possibly moved position).
 * - { action: 'changed', key }: someone changed the object too; do not save, let the user check.
 * - { action: 'gone' }: the object was removed (holidays and demand: changed or removed, it cannot be found).
 */
export function conflictDecision(kind, key, base, fresh) {
  if (base == null) return { action: 'retry', key: null };
  if (kind === 'restrictions') {
    if (!(fresh.people ?? []).some((p) => p.id === key)) return { action: 'gone' };
    return { action: same(listOf(kind, fresh, key), base) ? 'retry' : 'changed', key };
  }
  const list = listOf(kind, fresh, key);
  if (INDEXED.has(kind)) {
    if (same(list[key], base)) return { action: 'retry', key };
    const moved = list.findIndex((x) => same(x, base));
    return moved >= 0 ? { action: 'retry', key: moved } : { action: 'gone' };
  }
  const now = list.find((x, i) => keyOf(kind, x, i) === key);
  if (!now) return { action: 'gone' };
  return { action: same(now, base) ? 'retry' : 'changed', key };
}
