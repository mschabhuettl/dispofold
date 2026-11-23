// Checks of the master data for the Prüfen page, independent of the plan: people who cannot be planned, areas
// without demand, demand nobody can be planned for, a usual distribution that does not fit. Pure, no DOM.
import { WEEKDAY_SHORT, addDays, dayCode, epochMinutes, monthDates, parseTime, shiftInterval } from './dates.js';
import { personName } from './grid-model.js';
import { restrictionAppliesOn, shiftCategory } from './restrictions.js';
import { t } from './texts.js';

const CATEGORY = { EARLY: t('masterdata.category.early'), DAY: t('masterdata.category.day'), LATE: t('masterdata.category.late'),
  NIGHT: t('masterdata.category.night') };
/** Shares of a group must sum to 1 within this (as in the person form). */
const TOLERANCE = 0.01;

const pct = (share) => t('masterdata.shiftMix.percent', { percent: String(Math.round(share * 1000) / 10).replace('.', ',') });

/** The restriction applies on every day of the month: weekday 0 (or all weekdays together) and valid all month. */
function wholeMonth(list, first, last) {
  const valid = list.filter((r) => (!r.validFrom || r.validFrom <= first) && (!r.validTo || r.validTo >= last));
  const days = new Set(valid.map((r) => Number(r.weekday ?? 0)));
  return days.has(0) || [1, 2, 3, 4, 5, 6, 7].every((d) => days.has(d));
}

/**
 * Shifts of the person's areas that no NEVER restriction excludes on every day of the month. rules: for the category of
 * shifts without one in the dataset (derived from the times as the backend does).
 */
export function plannableShifts(dataset, person, month, rules = null) {
  const dates = monthDates(month);
  const never = (dataset.restrictions ?? []).filter((r) => r.personId === person.id && r.kind === 'NEVER');
  return dataset.shifts.filter((s) => (person.areaIds ?? []).includes(s.areaId)
    && !wholeMonth(never.filter((r) => (r.shiftId ? r.shiftId === s.id : r.category === shiftCategory(s, rules))), dates[0], dates.at(-1)));
}

/** A family rule "keine Nacht" on every weekday: the person never does nights anyway. */
const noNightsRule = (p) => (p.familyRules ?? []).some((f) => f.noNights && [1, 2, 3, 4, 5, 6, 7].every((d) => (f.weekdays ?? []).map(Number).includes(d)));

/**
 * The person never does nights as the solver sees it (RosterBuilder.neverNights): a family rule "keine Nacht" on every
 * weekday, or NEVER restrictions for every day of the week (weekday 0) leave no night shift of the person's areas; also
 * true when the areas have no night shift at all.
 */
export const neverNightShifts = (dataset, p, month, rules = null) => noNightsRule(p) || !plannableShifts({ ...dataset,
  restrictions: (dataset.restrictions ?? []).filter((r) => Number(r.weekday ?? 0) === 0) }, p, month, rules).some((s) => shiftCategory(s, rules) === 'NIGHT');

/**
 * Dates of the month a demand rule applies to, as the backend (RosterBuilder.rulesFor) picks the rules of an area per
 * date: rules with that date replace all others; on a holiday the HOL rules replace the weekday rules if the area has
 * any; else the rules of the weekday.
 */
function ruleDates(rule, areaRules, dates, holidays) {
  const hasHol = areaRules.some((r) => !r.date && (r.days ?? []).includes('HOL'));
  return dates.filter((date) => {
    if (areaRules.some((r) => r.date === date)) return rule.date === date;
    if (rule.date) return false;
    return (rule.days ?? []).includes(holidays.has(date) && hasHol ? 'HOL' : dayCode(date));
  });
}

/** Days relative to the rule's date (-1, 0, 1) on which a start of the shift overlaps the rule's time on that date. */
function startDays(rule, shift) {
  const s = shiftInterval('2000-01-02', shift);
  const from = epochMinutes('2000-01-02', parseTime(rule.from));
  let to = epochMinutes('2000-01-02', parseTime(rule.to));
  if (to <= from) to += 1440;
  return [-1, 0, 1].filter((k) => s.start + k * 1440 < to && s.end + k * 1440 > from);
}

/**
 * Someone could be planned for the rule on the date: a shift of the area overlapping the rule's time, and a person
 * with the area who on the day that shift starts (the day before for a night shift into the morning) is employed, not
 * excluded, not absent and not barred from the shift by a NEVER restriction (on request does not bar; family rules
 * are soft and do not count).
 */
function someoneFor(dataset, rule, date, absent, rules) {
  const starts = dataset.shifts.filter((s) => s.areaId === rule.areaId).flatMap((s) => startDays(rule, s).map((k) => [s, addDays(date, k)]));
  return dataset.people.some((p) => (p.areaIds ?? []).includes(rule.areaId) && starts.some(([s, day]) => (!p.entryDate || p.entryDate <= day)
    && (!p.exitDate || p.exitDate >= day) && (!p.excludedFrom || p.excludedFrom > day) && !absent.has(`${p.id}|${day}`)
    && !(dataset.restrictions ?? []).some((r) => r.personId === p.id && r.kind === 'NEVER' && restrictionAppliesOn(r, day)
      && (r.shiftId ? r.shiftId === s.id : r.category === shiftCategory(s, rules)))));
}

const DAY_LABEL = { MON: WEEKDAY_SHORT[0], TUE: WEEKDAY_SHORT[1], WED: WEEKDAY_SHORT[2], THU: WEEKDAY_SHORT[3], FRI: WEEKDAY_SHORT[4],
  SAT: WEEKDAY_SHORT[5], SUN: WEEKDAY_SHORT[6], HOL: t('masterdata.holiday') };
const shortDate = (iso) => `${Number(iso.slice(8, 10))}.${Number(iso.slice(5, 7))}.`;
const hhmm = (time) => String(time ?? '').slice(0, 5);

/** A group of shares (categories or areas) that is set and does not sum to 1, or has a share outside 0 to 1. */
function badSum(map) {
  const values = Object.values(map ?? {});
  if (!values.length) return null;
  const sum = values.reduce((a, b) => a + b, 0);
  return Math.abs(sum - 1) > TOLERANCE || values.some((v) => !(v >= 0 && v <= 1)) ? sum : null;
}

/**
 * Holidays, Saturdays and Sundays without an absence whose nearest workday before and after (Monday to Friday, no
 * holiday) both have an absence that lowers the target, and holidays whose other workdays of the week all have one,
 * as the solver keeps them free (RosterBuilder.insideAbsence).
 * Only the month's absences are known here, so days next to the month boundary are not free; a week counts as
 * covered only with an absence on each of its other workdays (the solver leaves out days outside the employment, so
 * this check may miss such a day, never warn wrongly). Keys personId|date.
 */
function freeInsideAbsence(dataset, dates, holidays, absences, absent) {
  const lowers = new Set((dataset.absenceTypes ?? []).filter((t) => t.reducesTarget).map((t) => t.code));
  const lowering = new Set(absences.filter((a) => lowers.has(a.typeCode)).map((a) => `${a.personId}|${a.date}`));
  const workday = (date) => { const d = new Date(`${date}T12:00:00Z`).getUTCDay(); return d >= 1 && d <= 5 && !holidays.has(date); };
  const nearest = (i, step) => { for (let j = i + step; j >= 0 && j < dates.length; j += step) if (workday(dates[j])) return dates[j]; return null; };
  // a holiday from Monday to Friday whose other workdays of the calendar week (inside the month) all have one
  const coveredWeek = (personId, i) => {
    const weekday = new Date(`${dates[i]}T12:00:00Z`).getUTCDay();
    if (weekday < 1 || weekday > 5) return false;
    const week = dates.slice(Math.max(0, i - weekday + 1), i + 6 - weekday).filter((d) => d !== dates[i] && workday(d));
    // the week must lie in the month: the absences of the neighbouring months are not known here
    if (i - weekday + 1 < 0 || i + 6 - weekday > dates.length) return false;
    return week.length > 0 && week.every((d) => lowering.has(`${personId}|${d}`));
  };
  const out = [];
  for (const personId of new Set(absences.map((a) => a.personId))) {
    dates.forEach((date, i) => {
      if (workday(date) || absent.has(`${personId}|${date}`)) return;
      const before = nearest(i, -1);
      const after = nearest(i, 1);
      if (before && after && lowering.has(`${personId}|${before}`) && lowering.has(`${personId}|${after}`)) out.push(`${personId}|${date}`);
      else if (coveredWeek(personId, i)) out.push(`${personId}|${date}`);
    });
  }
  return out;
}

/**
 * Findings on the master data for the month: [{ kind, severity: 'warning' | 'info', personId, areaId, demandIndex, text,
 * todo, link: 'masterdata' | 'rules' }]; demandIndex: the position of the demand rule in dataset.demand, else null.
 * People not employed in the month are left out. rules: the rules, for the night setting (missing: HABIT) and the
 * category of shifts without one in the dataset. absences: the absences of the month's period.
 */
export function dataChecks(dataset, { month, rules = null, absences = [] }) {
  const nightDistribution = rules?.nightDistribution ?? 'HABIT';
  const out = [];
  const dates = monthDates(month);
  const areaName = new Map(dataset.areas.map((a) => [a.id, a.name ?? a.id]));
  const add = (kind, severity, ref, params, link = 'masterdata') => out.push({ kind, severity, personId: ref.personId ?? null,
    areaId: ref.areaId ?? null, demandIndex: ref.demandIndex ?? null, text: TEXT[kind](params), todo: TODO[kind](params), link });

  for (const p of dataset.people) {
    if ((p.exitDate && p.exitDate < dates[0]) || (p.entryDate && p.entryDate > dates.at(-1)) || (p.excludedFrom && p.excludedFrom <= dates[0])) continue;
    const name = personName(p);
    const areas = p.areaIds ?? [];
    const own = dataset.shifts.filter((s) => areas.includes(s.areaId));
    if (!areas.length) add('noArea', 'warning', { personId: p.id }, { name });
    else if (!own.length) add('noShift', 'warning', { personId: p.id }, { name, count: areas.length });
    else if (!plannableShifts(dataset, p, month, rules).length) add('allNever', 'warning', { personId: p.id }, { name });

    const mix = p.shiftMix;
    if (!mix) continue;
    for (const [group, label] of [['categories', t('masterdata.shiftMix.categories')], ['areas', t('masterdata.shiftMix.areas')]]) {
      const sum = badSum(mix[group]);
      if (sum != null) add('mixSum', 'warning', { personId: p.id }, { name, group: label, sum: pct(sum) });
    }
    for (const a of Object.keys(mix.areas ?? {})) {
      if (!areas.includes(a)) add('mixArea', 'warning', { personId: p.id }, { name, area: areaName.get(a) ?? a });
    }
    for (const [c, share] of Object.entries(mix.categories ?? {})) {
      if (share > 0 && !own.some((s) => shiftCategory(s, rules) === c)) add('mixCategory', 'warning', { personId: p.id }, { name, category: CATEGORY[c] ?? c });
    }
    // the night note of the person form, where it changes what the person gets
    const nightShare = mix.categories?.NIGHT ?? 0;
    const hasCategories = Object.keys(mix.categories ?? {}).length > 0;
    const night = (s) => shiftCategory(s, rules) === 'NIGHT';
    const neverNights = noNightsRule(p) || !plannableShifts(dataset, p, month, rules).some(night);
    // never nights beats the night share in both modes (only where the areas have night shifts at all); as the
    // solver (RosterBuilder.neverNights) only NEVER restrictions for every day of the week count, not seven weekdays
    const solverNever = neverNightShifts(dataset, p, month, rules);
    if (nightShare > 0 && solverNever && own.some((s) => shiftCategory(s, rules) === 'NIGHT')) add('nightNever', 'info', { personId: p.id }, { name });
    else if (nightDistribution === 'EVEN' && nightShare > 0) add('nightIgnored', 'info', { personId: p.id }, { name }, 'rules');
    else if (nightDistribution !== 'EVEN' && hasCategories && nightShare === 0 && !neverNights) add('noNights', 'info', { personId: p.id }, { name });
  }

  for (const a of dataset.areas) {
    if (!dataset.demand.some((d) => d.areaId === a.id && (d.min ?? 0) > 0)) add('areaNoDemand', 'warning', { areaId: a.id }, { area: a.name ?? a.id });
  }

  const holidays = new Set((dataset.holidays ?? []).map((x) => x.date));
  const absent = new Set(absences.map((x) => `${x.personId}|${x.date}`));
  if (rules?.freeInsideAbsence !== false) for (const key of freeInsideAbsence(dataset, dates, holidays, absences, absent)) absent.add(key);
  dataset.demand.forEach((rule, index) => {
    if (!((rule.min ?? 0) > 0)) return;
    const areaRules = dataset.demand.filter((r) => r.areaId === rule.areaId);
    const none = ruleDates(rule, areaRules, dates, holidays).filter((date) => !someoneFor(dataset, rule, date, absent, rules));
    if (!none.length) return;
    const days = rule.date ? shortDate(rule.date) : (rule.days ?? []).map((d) => DAY_LABEL[d] ?? d).join(', ');
    add('demandNoPeople', 'warning', { areaId: rule.areaId, demandIndex: index }, { area: areaName.get(rule.areaId) ?? rule.areaId, days,
      time: `${hhmm(rule.from)}-${hhmm(rule.to)}`, count: none.length, first: shortDate(none[0]) });
  });
  return out;
}

const TEXT = {
  noArea: (p) => t('check.data.noArea', p),
  noShift: (p) => t('check.data.noShift', p),
  allNever: (p) => t('check.data.allNever', p),
  areaNoDemand: (p) => t('check.data.areaNoDemand', p),
  demandNoPeople: (p) => t('check.data.demandNoPeople', p),
  mixSum: (p) => t('check.data.mixSum', p),
  mixArea: (p) => t('check.data.mixArea', p),
  mixCategory: (p) => t('check.data.mixCategory', p),
  nightIgnored: (p) => t('check.data.nightIgnored', p),
  nightNever: (p) => t('check.data.nightNever', p),
  noNights: (p) => t('check.data.noNights', p),
};

const TODO = {
  noArea: () => t('check.data.noArea.todo'),
  noShift: () => t('check.data.noShift.todo'),
  allNever: () => t('check.data.allNever.todo'),
  areaNoDemand: () => t('check.data.areaNoDemand.todo'),
  demandNoPeople: () => t('check.data.demandNoPeople.todo'),
  mixSum: () => t('check.data.mixSum.todo'),
  mixArea: () => t('check.data.mixArea.todo'),
  mixCategory: () => t('check.data.mixCategory.todo'),
  nightIgnored: () => t('check.data.nightIgnored.todo'),
  nightNever: () => t('check.data.nightNever.todo'),
  noNights: () => t('check.data.noNights.todo'),
};
