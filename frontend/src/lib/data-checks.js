// Checks of the master data for the Prüfen page, independent of the plan: people who cannot be planned, areas
// without demand, demand nobody can be planned for, a usual distribution that does not fit. Pure, no DOM.
import { WEEKDAY_SHORT, dayCode, monthDates } from './dates.js';
import { windowOverlapsShift } from './functions-model.js';
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

/**
 * Someone could be planned for the rule on the date: employed and not excluded, the area, not absent, and a shift of
 * the area overlapping the rule's time that no NEVER restriction excludes that day (on request does not exclude;
 * family rules are soft and do not count).
 */
function someoneFor(dataset, rule, date, absent, rules) {
  const shifts = dataset.shifts.filter((s) => s.areaId === rule.areaId && windowOverlapsShift(rule, s));
  return shifts.length > 0 && dataset.people.some((p) => (!p.entryDate || p.entryDate <= date) && (!p.exitDate || p.exitDate >= date)
    && (!p.excludedFrom || p.excludedFrom > date) && (p.areaIds ?? []).includes(rule.areaId) && !absent.has(`${p.id}|${date}`)
    && shifts.some((s) => !(dataset.restrictions ?? []).some((r) => r.personId === p.id && r.kind === 'NEVER' && restrictionAppliesOn(r, date)
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
