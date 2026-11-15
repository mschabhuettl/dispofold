// Usual shift mix of a person (docs/datenformat.md): shares per category and per area, between 0 and 1, each
// group summing to 1. Form values are percentages as text. Pure, no DOM.
import { t } from './texts.js';

export const MIX_CATEGORIES = ['EARLY', 'DAY', 'LATE', 'NIGHT'];
const CATEGORY = { EARLY: t('masterdata.category.early'), DAY: t('masterdata.category.day'), LATE: t('masterdata.category.late'), NIGHT: t('masterdata.category.night') };
const TOLERANCE = 0.01;

const pct = (share) => String(Math.round(share * 1000) / 10).replace('.', ',');

/** Form values of a mix: percentages as text per category and per area id ('' for none); null gives empty inputs. */
export function mixToForm(mix, areaIds = []) {
  const form = (map, keys) => Object.fromEntries(keys.map((k) => [k, map?.[k] == null ? '' : pct(map[k])]));
  return { categories: form(mix?.categories, MIX_CATEGORIES), areas: form(mix?.areas, [...new Set([...areaIds, ...Object.keys(mix?.areas ?? {})])]) };
}

/** One group: percentages as text to shares (3 decimals); no filled input gives null. */
function group(values, keys, name, nameOf) {
  const shares = {};
  for (const k of keys) {
    const s = String(values?.[k] ?? '').trim().replace(',', '.');
    if (!s) continue;
    const n = Number(s);
    if (!Number.isFinite(n) || n < 0 || n > 100) throw new Error(t('masterdata.form.mixRange', { field: nameOf(k) }));
    shares[k] = Math.round(n * 10) / 1000;
  }
  const keysSet = Object.keys(shares);
  if (!keysSet.length) return null;
  const sum = keysSet.reduce((s, k) => s + shares[k], 0);
  if (Math.abs(sum - 1) > TOLERANCE) throw new Error(t('masterdata.form.mixSum', { group: name, sum: pct(sum) }));
  return shares;
}

/** The mix from form values: only the person's areas count; nothing filled gives null. Throws with a German message. */
export function mixFromForm(values, areaIds, areaName = (id) => id) {
  const categories = group(values?.categories, MIX_CATEGORIES, t('masterdata.shiftMix.categories'), (k) => CATEGORY[k]);
  const areas = group(values?.areas, areaIds, t('masterdata.shiftMix.areas'), areaName);
  return categories || areas ? { categories: categories ?? {}, areas: areas ?? {} } : null;
}

/** "Tag 90 %, Nacht 10 %" (categories, then areas); empty without a mix. */
export function mixText(mix, areaName = (id) => id) {
  const part = (map, name) => Object.entries(map ?? {}).filter(([, v]) => v > 0)
    .map(([k, v]) => t('masterdata.shiftMix.part', { name: name(k), percent: pct(v) }));
  return [...part(mix?.categories, (k) => CATEGORY[k] ?? k), ...part(mix?.areas, areaName)].join(', ');
}

/**
 * Rows for the person view: planned against usual share, per category and area that appears in either.
 * Each row: { group: 'categories' | 'areas', key, name, planned, usual } with shares or null.
 */
export function mixRows(planned, usual, areaName = (id) => id) {
  const rows = [];
  const add = (g, keys, name) => {
    for (const k of keys) {
      if (planned?.[g]?.[k] == null && usual?.[g]?.[k] == null) continue;
      rows.push({ group: g, key: k, name: name(k), planned: planned?.[g]?.[k] ?? null, usual: usual?.[g]?.[k] ?? null });
    }
  };
  add('categories', MIX_CATEGORIES, (k) => CATEGORY[k]);
  add('areas', [...new Set([...Object.keys(usual?.areas ?? {}), ...Object.keys(planned?.areas ?? {})])].sort(), areaName);
  return rows;
}

/** A share as "90 %" (empty for null). */
export const sharePercent = (share) => (share == null ? '' : t('masterdata.shiftMix.percent', { percent: pct(share) }));
