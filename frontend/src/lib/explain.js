// Groups and summarises ConstraintHits of a person for the "Warum dieser Dienst?" list. Pure, no DOM.
import { t } from './texts.js';

/** Hits grouped by date; whole-month hits (date null) first, then by date. */
export function groupHitsByDate(hits) {
  const groups = new Map();
  for (const h of hits) {
    const key = h.date ?? null;
    if (!groups.has(key)) groups.set(key, { date: key, hits: [] });
    groups.get(key).hits.push(h);
  }
  const levelOrder = { HARD: 0, MEDIUM: 1, SOFT: 2 };
  return [...groups.values()]
    .sort((a, b) => (a.date === null ? -1 : b.date === null ? 1 : a.date.localeCompare(b.date)))
    .map((g) => ({ ...g, hits: g.hits.sort((a, b) => (levelOrder[a.level] ?? 3) - (levelOrder[b.level] ?? 3) || a.score - b.score) }));
}

const isWish = (hit) => /^wish/.test(hit.constraintId);

/**
 * Plain words for the hits of one day (or the whole month), without scores: rule violations, demand, wishes met
 * and not met, and the drawbacks and benefits of the other soft rules, each with its count.
 */
export function hitSummary(hits) {
  const count = (f) => hits.filter(f).length;
  const violations = count((x) => x.level === 'HARD' && x.score < 0); // like hitEffect: a hard hit without penalty is a hint
  const out = [violations ? t('person.summary.violations', { count: violations }) : t('person.summary.noViolation')];
  const demandOff = count((x) => x.level === 'MEDIUM' && x.score < 0);
  if (demandOff) out.push(t('person.summary.demandOff', { count: demandOff }));
  else if (count((x) => x.level === 'MEDIUM')) out.push(t('person.summary.demandMet'));
  let met = 0;
  let unmet = 0;
  for (const w of hits.filter(isWish)) {
    // a month-level hit counts the wishes in its params, a day-level hit is one wish
    if (w.params?.wishes != null) {
      met += w.params.wishes - (w.params.unmet ?? 0);
      unmet += w.params.unmet ?? 0;
    } else if (w.score < 0) unmet++;
    else met++;
  }
  if (met) out.push(t('person.summary.wishesMet', { count: met }));
  if (unmet) out.push(t('person.summary.wishesUnmet', { count: unmet }));
  const drawbacks = count((x) => x.level === 'SOFT' && !isWish(x) && x.score < 0);
  const benefits = count((x) => x.level === 'SOFT' && !isWish(x) && x.score > 0);
  if (drawbacks) out.push(t('person.summary.drawbacks', { count: drawbacks }));
  if (benefits) out.push(t('person.summary.benefits', { count: benefits }));
  return out;
}

/** One word for the effect of a hit instead of its score: violation, drawback, benefit, or nothing. */
export function hitEffect(hit) {
  if (hit.score < 0) return hit.level === 'HARD' ? t('person.effect.violation') : t('person.effect.drawback');
  return hit.score > 0 ? t('person.effect.benefit') : '';
}
