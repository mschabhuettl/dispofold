// The state of a plan in words: the headline of the Dienstplan, the rules shown as chips below it and the checklist
// before closing a month. Pure, no DOM.
import { t } from './texts.js';

/** Person hours with at most one decimal: "16", "7,5". */
const hours = (v) => (v == null ? '–' : Number(v).toLocaleString('de-AT', { maximumFractionDigits: 1 }));

/**
 * Headline of the status line: { level, text } with level 'good', 'warn' (gaps), 'bad' (rule violations),
 * 'solving' or 'none' (no plan yet, or no analysis to judge it). gaps is the number of gaps of the analysis, null
 * without one (while solving only the uncovered person hours of the score are known).
 */
export function planHeadline({ solving = false, seconds = 0, planned = true, hard = 0, gaps = null, uncoveredHours = null,
  duties = null, areas = 0, areasWithoutDemand = 0 }) {
  const parts = [];
  if (hard > 0) parts.push(t('roster.headline.hard', { count: hard }));
  if (gaps > 0) parts.push(t('roster.headline.gaps', { count: gaps, hours: hours(uncoveredHours) }));
  else if (gaps == null && uncoveredHours > 0) parts.push(t('roster.headline.uncovered', { hours: hours(uncoveredHours) }));
  if (solving) return { level: 'solving', text: [t('roster.headline.solving', { seconds }), ...parts].join(', ') };
  if (!planned) return { level: 'none', text: t('common.noPlan') };
  // no gap is no reassurance where no area asks for anyone: an empty plan or areas without minimum demand
  if (areas > 0 && areasWithoutDemand >= areas) {
    parts.push(duties === 0 ? t('roster.headline.noDemandEmpty') : t('roster.headline.noDemand'));
  } else if (areasWithoutDemand > 0) parts.push(t('roster.headline.someWithoutDemand', { count: areasWithoutDemand }));
  else if (duties === 0) parts.push(t('roster.headline.empty'));
  if (parts.length) return { level: hard > 0 ? 'bad' : 'warn', text: parts.join(', ') };
  // usable only when the analysis says so
  return gaps === 0 ? { level: 'good', text: t('roster.headline.good') } : { level: 'none', text: t('roster.headline.unknown') };
}

/**
 * The score in words for "Erweitert": rule violations (their count, not the hard score), missing and surplus staff
 * in person hours, and the deductions of the plan quality (the soft score, without its sign); '–' without a score.
 */
export function scoreWords({ score, hard = 0, uncovered = null, overstaffed = null }) {
  const soft = /(-?\d+)soft/.exec(String(score ?? ''));
  if (!soft) return '–';
  return t('roster.scoreWords', { hard: hard ?? 0, uncovered: hours(uncovered ?? 0), overstaffed: hours(overstaffed ?? 0),
    soft: Math.abs(Number(soft[1])).toLocaleString('de-AT') });
}

/** A soft rule gets a chip when it has at least this share of the plan's soft penalty ... */
export const SOFT_SHARE = 0.1;
/** ... and is among the largest this many. */
export const SOFT_CHIPS = 5;

/**
 * Rules to point at below the headline, from PlanAnalysis.constraints: every hard rule with matches (most first),
 * then the soft rules that cause a high share of the soft penalty (SOFT_SHARE of the total, at most SOFT_CHIPS,
 * largest first). Demand (medium) is left out: its gaps are in the headline.
 */
export function problemConstraints(constraints = []) {
  const hard = constraints.filter((c) => c.level === 'HARD' && c.matches > 0).sort((a, b) => b.matches - a.matches);
  const soft = constraints.filter((c) => c.level === 'SOFT' && c.score < 0 && c.matches > 0);
  const total = soft.reduce((sum, c) => sum + c.score, 0);
  const high = soft.filter((c) => c.score / total >= SOFT_SHARE).sort((a, b) => a.score - b.score).slice(0, SOFT_CHIPS);
  return [...hard, ...high];
}

/** The fairness rules compare everybody in one match: their match count says nothing. */
const COMPARISONS = new Set(['fairNights', 'fairWeekends', 'fairHolidays', 'fairUnpopular']);

/** The number on a rule's chip: its matches, null for a fairness comparison. */
export const chipCount = (c) => (COMPARISONS.has(c.id) ? null : c.matches);

/**
 * Checklist before closing a month: rule violations, open gaps, plan out of date; each { ok, text }. Without an
 * analysis the gaps are unknown and count as open.
 */
export function closeChecklist(state, analysis) {
  const hard = analysis?.hardViolations ?? state.hardViolations ?? 0;
  const gaps = analysis?.gaps?.length ?? null;
  const stale = Boolean(state.stale || state.previousMonthChanged || analysis?.previousMonthChanged);
  return [
    { ok: hard === 0, text: hard ? t('roster.headline.hard', { count: hard }) : t('closeMonth.hardNone') },
    gaps == null ? { ok: false, text: t('closeMonth.noAnalysis') }
      : { ok: gaps === 0, text: gaps ? t('roster.headline.gaps', { count: gaps, hours: hours(analysis.uncoveredHours) }) : t('closeMonth.gapsNone') },
    { ok: !stale, text: stale ? t('closeMonth.stale') : t('closeMonth.current') },
  ];
}

/** How many areas have no minimum demand at all (no demand rule asks for at least one person): nobody is planned there. */
export const areasWithoutDemand = (dataset) => (dataset.areas ?? [])
  .filter((a) => !(dataset.demand ?? []).some((d) => d.areaId === a.id && Number(d.min) > 0)).length;
