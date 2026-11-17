// Checks of the master data for the Prüfen page, independent of the plan: people who cannot be planned, areas
// without demand, a usual distribution that does not fit. Pure, no DOM.
import { monthDates } from './dates.js';
import { personName } from './grid-model.js';
import { shiftCategory } from './restrictions.js';
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

/** A group of shares (categories or areas) that is set and does not sum to 1, or has a share outside 0 to 1. */
function badSum(map) {
  const values = Object.values(map ?? {});
  if (!values.length) return null;
  const sum = values.reduce((a, b) => a + b, 0);
  return Math.abs(sum - 1) > TOLERANCE || values.some((v) => !(v >= 0 && v <= 1)) ? sum : null;
}

/**
 * Findings on the master data for the month: [{ kind, severity: 'warning' | 'info', personId, areaId, text, todo,
 * link: 'masterdata' | 'rules' }]. People not employed in the month are left out. rules: the rules, for the night
 * setting (missing: HABIT) and the category of shifts without one in the dataset.
 */
export function dataChecks(dataset, { month, rules = null }) {
  const nightDistribution = rules?.nightDistribution ?? 'HABIT';
  const out = [];
  const dates = monthDates(month);
  const areaName = new Map(dataset.areas.map((a) => [a.id, a.name ?? a.id]));
  const add = (kind, severity, ref, params, link = 'masterdata') => out.push({ kind, severity, personId: ref.personId ?? null,
    areaId: ref.areaId ?? null, text: TEXT[kind](params), todo: TODO[kind](params), link });

  for (const p of dataset.people) {
    if ((p.exitDate && p.exitDate < dates[0]) || (p.entryDate && p.entryDate > dates.at(-1))) continue;
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
    const plannable = plannableShifts(dataset, p, month, rules);
    const neverNights = noNightsRule(p) || !plannable.some((s) => shiftCategory(s, rules) === 'NIGHT');
    // never nights beats the night share in both modes (only where the areas have night shifts at all)
    if (nightShare > 0 && neverNights && own.some((s) => shiftCategory(s, rules) === 'NIGHT')) add('nightNever', 'info', { personId: p.id }, { name });
    else if (nightDistribution === 'EVEN' && nightShare > 0) add('nightIgnored', 'info', { personId: p.id }, { name }, 'rules');
    else if (nightDistribution !== 'EVEN' && hasCategories && nightShare === 0 && !neverNights) add('noNights', 'info', { personId: p.id }, { name });
  }

  for (const a of dataset.areas) {
    if (!dataset.demand.some((d) => d.areaId === a.id && (d.min ?? 0) > 0)) add('areaNoDemand', 'warning', { areaId: a.id }, { area: a.name ?? a.id });
  }
  return out;
}

const TEXT = {
  noArea: (p) => t('check.data.noArea', p),
  noShift: (p) => t('check.data.noShift', p),
  allNever: (p) => t('check.data.allNever', p),
  areaNoDemand: (p) => t('check.data.areaNoDemand', p),
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
  mixSum: () => t('check.data.mixSum.todo'),
  mixArea: () => t('check.data.mixArea.todo'),
  mixCategory: () => t('check.data.mixCategory.todo'),
  nightIgnored: () => t('check.data.nightIgnored.todo'),
  nightNever: () => t('check.data.nightNever.todo'),
  noNights: () => t('check.data.noNights.todo'),
};
