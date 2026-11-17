// Fairness statistics over PersonStats. Pure, no DOM.
import { sharePercent } from './shift-mix.js';
import { t } from './texts.js';

/**
 * Metrics shown in the fairness overview. perHours: divide by weekly hours and scale to 40 h, so part-time
 * staff compare fairly.
 */
export const METRICS = [
  { id: 'plannedHours', label: t('fairness.metric.plannedHours'), perHours: true, digits: 1 },
  { id: 'accountAfter', label: t('fairness.metric.accountAfter'), perHours: false, digits: 1, personal: true },
  { id: 'nights', label: t('fairness.metric.nights'), perHours: true },
  { id: 'weekendsWorked', label: t('fairness.metric.weekendsWorked'), perHours: true },
  { id: 'weekendsOff', label: t('fairness.metric.weekendsOff'), perHours: false },
  { id: 'holidays', label: t('fairness.metric.holidays'), perHours: true },
  { id: 'unpopular', label: t('fairness.metric.unpopular'), perHours: true },
  { id: 'singleDays', label: t('fairness.metric.singleDays'), perHours: false },
  { id: 'wishRate', label: t('fairness.metric.wishRate'), perHours: false, percent: true, personal: true },
  { id: 'compRestBalanceAfter', label: t('fairness.metric.compRestBalanceAfter'), perHours: false, personal: true },
  { id: 'historyNights', label: t('fairness.metric.historyNights'), perHours: true },
  { id: 'historyWeekends', label: t('fairness.metric.historyWeekends'), perHours: true },
  { id: 'historyHolidays', label: t('fairness.metric.historyHolidays'), perHours: true },
  { id: 'historyUnpopular', label: t('fairness.metric.historyUnpopular'), perHours: true },
  { id: 'historyWishRate', label: t('fairness.metric.historyWishRate'), perHours: false, percent: true, personal: true },
];

/** Metrics a role may see: wishes, hours account and compensatory rest are personal (viewers get null or 0). */
export function metricsFor(canEdit) {
  return canEdit ? METRICS : METRICS.filter((m) => !m.personal);
}

/** Share of met wishes, or null when no wishes were made. */
export function wishRate(made, met) {
  return made > 0 ? met / made : null;
}

/** Raw value of a metric for one PersonStats. */
export function metricValue(stats, id) {
  if (id === 'wishRate') return wishRate(stats.wishes, stats.wishesMet);
  if (id === 'historyWishRate') return wishRate(stats.historyWishes, stats.historyWishesMet);
  return stats[id] ?? null;
}

/** Value normalised to 40 weekly hours when the metric is perHours. */
export function normalizedValue(stats, metric, weeklyHours) {
  const v = metricValue(stats, metric.id);
  if (v == null || !metric.perHours || !weeklyHours) return v;
  return (v * 40) / weeklyHours;
}

/** min, max, mean, standard deviation and count of the non-null values. */
export function spread(values) {
  const xs = values.filter((v) => v != null && Number.isFinite(v));
  if (xs.length === 0) return { min: null, max: null, mean: null, sd: null, count: 0 };
  const mean = xs.reduce((a, b) => a + b, 0) / xs.length;
  const sd = Math.sqrt(xs.reduce((a, b) => a + (b - mean) ** 2, 0) / xs.length);
  return { min: Math.min(...xs), max: Math.max(...xs), mean, sd, count: xs.length };
}

/**
 * Rows for the fairness table: one per person with stats, joined with the dataset person.
 * @returns {{person: object, stats: object, values: Object<string, number|null>}[]}
 */
export function fairnessRows(statsList, people, normalize) {
  const byId = new Map(people.map((p) => [p.id, p]));
  return statsList
    .filter((s) => byId.has(s.personId))
    .map((stats) => {
      const person = byId.get(stats.personId);
      const values = {};
      for (const m of METRICS) values[m.id] = normalize ? normalizedValue(stats, m, person.weeklyHours) : metricValue(stats, m.id);
      return { person, stats, values };
    });
}

/** Spread of each metric over the rows. */
export function metricSpreads(rows) {
  return Object.fromEntries(METRICS.map((m) => [m.id, spread(rows.map((r) => r.values[m.id]))]));
}

/** Sorts rows by a metric id or 'name'; null values always last. */
export function sortRows(rows, key, ascending = true) {
  const dir = ascending ? 1 : -1;
  const value = (r) => (key === 'name' ? `${r.person.lastName} ${r.person.firstName}` : r.values[key]);
  return [...rows].sort((a, b) => {
    const va = value(a);
    const vb = value(b);
    if (va == null && vb == null) return 0;
    if (va == null) return 1;
    if (vb == null) return -1;
    return (typeof va === 'string' ? va.localeCompare(vb, 'de') : va - vb) * dir;
  });
}

const decimal = (v) => Number(v).toLocaleString('de-AT', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/**
 * Why a person is outside the night comparison: 'MIX' (the usual mix decides the nights), 'NEVER' (never nights) or
 * 'OUT' (not compared, e.g. no available day); null when inside or when the figures are not given (viewers).
 */
export function nightOutside(stats) {
  if (stats?.nightPool !== false) return null;
  return stats.nightReason === 'MIX' || stats.nightReason === 'NEVER' ? stats.nightReason : 'OUT';
}

/** Short reason for the fairness table. */
export function nightReasonText(reason) {
  return { MIX: t('fairness.night.mix'), NEVER: t('fairness.night.never'), OUT: t('fairness.night.out') }[reason];
}

/** Line of the person's night tile; null when the figures are not given (viewers). usual: the person's stored mix. */
export function nightStatus(stats, usual) {
  if (stats?.nightPool == null) return null;
  if (stats.nightPool) return t('person.kpi.nightsPool', { load: decimal(stats.nightLoad), mean: decimal(stats.nightMean) });
  const reason = nightOutside(stats);
  if (reason === 'MIX') return t('person.kpi.nightsMix', { percent: sharePercent(usual?.categories?.NIGHT ?? 0) });
  return reason === 'NEVER' ? t('person.kpi.nightsNever') : t('person.kpi.nightsOut');
}
