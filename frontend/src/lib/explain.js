// Groups ConstraintHits of a person for the "Warum dieser Dienst?" list. Pure, no DOM.

/** Hits grouped by date; whole-month hits (date null) first, then by date. Each group has its score total. */
export function groupHitsByDate(hits) {
  const groups = new Map();
  for (const h of hits) {
    const key = h.date ?? null;
    if (!groups.has(key)) groups.set(key, { date: key, hits: [], total: 0 });
    const g = groups.get(key);
    g.hits.push(h);
    g.total += h.score;
  }
  const levelOrder = { HARD: 0, MEDIUM: 1, SOFT: 2 };
  return [...groups.values()]
    .sort((a, b) => (a.date === null ? -1 : b.date === null ? 1 : a.date.localeCompare(b.date)))
    .map((g) => ({ ...g, hits: g.hits.sort((a, b) => (levelOrder[a.level] ?? 3) - (levelOrder[b.level] ?? 3) || a.score - b.score) }));
}
