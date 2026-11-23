// Fairness statistics over PersonStats. Pure, no DOM.
import { nightShareIgnored, sharePercent } from './shift-mix.js';
import { t } from './texts.js';

/**
 * Metrics shown in the fairness overview. The loads come first: they are what the fairness rules compare (duties of
 * this and the history months per 30 available days of a 40 h person). The counts follow as they are.
 */
export const METRICS = [
  { id: 'nightLoad', label: t('fairness.metric.nightLoad'), digits: 2, personal: true },
  { id: 'weekendLoad', label: t('fairness.metric.weekendLoad'), digits: 2, personal: true },
  { id: 'holidayLoad', label: t('fairness.metric.holidayLoad'), digits: 2, personal: true },
  { id: 'unpopularLoad', label: t('fairness.metric.unpopularLoad'), digits: 2, personal: true },
  { id: 'plannedHours', label: t('fairness.metric.plannedHours'), digits: 1 },
  { id: 'accountAfter', label: t('fairness.metric.accountAfter'), digits: 1, personal: true },
  { id: 'periodBalance', label: t('fairness.metric.periodBalance'), digits: 1, personal: true },
  { id: 'nights', label: t('fairness.metric.nights') },
  { id: 'weekendsWorked', label: t('fairness.metric.weekendsWorked') },
  { id: 'weekendsOff', label: t('fairness.metric.weekendsOff') },
  { id: 'holidays', label: t('fairness.metric.holidays') },
  { id: 'unpopular', label: t('fairness.metric.unpopular') },
  { id: 'singleDays', label: t('fairness.metric.singleDays') },
  { id: 'wishRate', label: t('fairness.metric.wishRate'), percent: true, personal: true },
  { id: 'compRestBalanceAfter', label: t('fairness.metric.compRestBalanceAfter'), personal: true },
  { id: 'historyNights', label: t('fairness.metric.historyNights') },
  { id: 'historyWeekends', label: t('fairness.metric.historyWeekends') },
  { id: 'historyHolidays', label: t('fairness.metric.historyHolidays') },
  { id: 'historyUnpopular', label: t('fairness.metric.historyUnpopular') },
  { id: 'historyWishRate', label: t('fairness.metric.historyWishRate'), percent: true, personal: true },
];

/**
 * Metrics a role may see: wishes, hours account, compensatory rest and the loads (the night load would tell the usual
 * mix, restrictions or family rules) are planners only (viewers get null or 0).
 */
export function metricsFor(canEdit) {
  return canEdit ? METRICS : METRICS.filter((m) => !m.personal);
}

/**
 * The part of an account correction a month cannot take (PersonStats.accountRest, accountCap) as text: hours
 * missing at the cap, or plus hours left at a goal of 0.
 */
export function accountRestText(s) {
  const p = restParams(s);
  return s.accountRest > 0 ? t('check.account.minus', p) : t('check.account.plus', p);
}

/** The same, short, for the tile on the person page. */
export function accountRestShort(s) {
  const p = restParams(s);
  return s.accountRest > 0 ? t('person.kpi.accountRest.minus', p) : t('person.kpi.accountRest.plus', p);
}

function restParams(s) {
  const num = (v) => Number(v).toLocaleString('de-AT', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  return { rest: num(Math.abs(s.accountRest)), cap: num(s.accountCap) };
}

/** Share of met wishes, or null when no wishes were made. */
export function wishRate(made, met) {
  return made > 0 ? met / made : null;
}

/** Value of a metric for one PersonStats; a load is null when the person is not in that comparison. */
export function metricValue(stats, id) {
  if (id === 'wishRate') return wishRate(stats.wishes, stats.wishesMet);
  if (id === 'historyWishRate') return wishRate(stats.historyWishes, stats.historyWishesMet);
  return stats[id] ?? null;
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
export function fairnessRows(statsList, people) {
  const byId = new Map(people.map((p) => [p.id, p]));
  return statsList
    .filter((s) => byId.has(s.personId))
    .map((stats) => {
      const person = byId.get(stats.personId);
      const values = {};
      for (const m of METRICS) values[m.id] = metricValue(stats, m.id);
      return { person, stats, values };
    });
}

/** Spread of each metric over the rows. */
export function metricSpreads(rows) {
  return Object.fromEntries(METRICS.map((m) => [m.id, spread(rows.map((r) => r.values[m.id]))]));
}

/**
 * The figures "Fällt auf" looks at, with the direction that stands out: for planners the loads the fairness rules
 * compare, single duties, the account after the month and the share of met wishes; for viewers the counts they see.
 */
const STANDOUT = {
  planner: [['nightLoad', 'high'], ['weekendLoad', 'high'], ['holidayLoad', 'high'], ['unpopularLoad', 'high'], ['singleDays', 'high'],
    ['accountAfter', 'both'], ['wishRate', 'low']],
  viewer: [['nights', 'high'], ['weekendsWorked', 'high'], ['holidays', 'high'], ['unpopular', 'high'], ['singleDays', 'high']],
};

/** A value stands out at this many standard deviations from the mean. */
export const STANDOUT_SD = 2;

/**
 * "Fällt auf": the people whose value of a figure lies far from the mean of everybody (STANDOUT_SD), the farthest
 * first, at most limit. Each { person, metric, value, mean, high, distance (in standard deviations) }.
 */
export function standouts(rows, canEdit, limit = 5) {
  const metrics = new Map(METRICS.map((m) => [m.id, m]));
  const out = [];
  for (const [id, direction] of STANDOUT[canEdit ? 'planner' : 'viewer']) {
    const s = spread(rows.map((r) => r.values[id]));
    if (!s.count || !s.sd) continue;
    for (const r of rows) {
      const v = r.values[id];
      if (v == null) continue;
      const z = (v - s.mean) / s.sd;
      if (Math.abs(z) < STANDOUT_SD || (direction === 'high' && z < 0) || (direction === 'low' && z > 0)) continue;
      out.push({ person: r.person, metric: metrics.get(id), value: v, mean: s.mean, high: z > 0, distance: Math.abs(z) });
    }
  }
  return out.sort((a, b) => b.distance - a.distance).slice(0, limit);
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
  if (nightShareIgnored(stats, usual)) return t('mix.never.tile', { percent: sharePercent(usual.categories.NIGHT) });
  return reason === 'NEVER' ? t('person.kpi.nightsNever') : t('person.kpi.nightsOut');
}
