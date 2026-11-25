// The problem list of the Prüfen page: rule violations, overstaffing and the quality rules the Dienstplan points at,
// filtered and grouped by rule, person or day. Pure, no DOM.
import { personName } from './grid-model.js';
import { PERSONLESS, problemConstraints } from './plan-status.js';

const LEVELS = { HARD: 0, MEDIUM: 1, SOFT: 2 };

/**
 * Entries from GET .../problems: every penalty of a hard or medium rule, and of the soft rules that the chips of
 * the Dienstplan show (problemConstraints of the analysis), and always of the soft rules of no person (staffing above
 * the minimum). Each { hit, person } with person null for a match of no person (overstaffing); hits of people not in the dataset are dropped. Sorted by level, day and name.
 */
export function problemEntries(problems, constraints, people) {
  const soft = new Set(problemConstraints(constraints).filter((c) => c.level === 'SOFT').map((c) => c.id));
  const byId = new Map(people.map((p) => [p.id, p]));
  return problems
    .filter((x) => x.score < 0 && (x.level !== 'SOFT' || soft.has(x.constraintId) || PERSONLESS.has(x.constraintId)) && (x.personId == null || byId.has(x.personId)))
    .map((hit) => ({ hit, person: hit.personId == null ? null : byId.get(hit.personId) }))
    .sort((a, b) => (LEVELS[a.hit.level] ?? 3) - (LEVELS[b.hit.level] ?? 3) || (a.hit.date ?? '').localeCompare(b.hit.date ?? '')
      || name(a).localeCompare(name(b), 'de'));
}

const name = (e) => (e.person ? personName(e.person) : '');

/** Group key of an entry: rule id, person id ('' for none) or day ('' for the whole month). */
export const groupKey = (entry, by) => (by === 'rule' ? entry.hit.constraintId : by === 'person' ? (entry.hit.personId ?? '') : (entry.hit.date ?? ''));

/** Entries matching every set filter { rule, person, day } (values as groupKey gives them, null: all). */
export function filterProblems(entries, { rule = null, person = null, day = null } = {}) {
  return entries.filter((e) => (rule == null || groupKey(e, 'rule') === rule) && (person == null || groupKey(e, 'person') === person)
    && (day == null || groupKey(e, 'day') === day));
}

/**
 * Groups { key, level, person, entries } in order: rules by level and then most entries first, people by name (no
 * person last), days by date (whole month first). Entries keep their order.
 */
export function groupProblems(entries, by) {
  const groups = new Map();
  for (const e of entries) {
    const key = groupKey(e, by);
    if (!groups.has(key)) groups.set(key, { key, level: e.hit.level, person: e.person, entries: [] });
    groups.get(key).entries.push(e);
  }
  const list = [...groups.values()];
  if (by === 'rule') return list.sort((a, b) => (LEVELS[a.level] ?? 3) - (LEVELS[b.level] ?? 3) || b.entries.length - a.entries.length);
  if (by === 'person') return list.sort((a, b) => (!a.person) - (!b.person) || name(a).localeCompare(name(b), 'de'));
  return list.sort((a, b) => a.key.localeCompare(b.key));
}
