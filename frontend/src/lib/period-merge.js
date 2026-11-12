// Merging the Eingaben of a month after a 409 period.conflict. Pure, no DOM.
// Entries are keyed by person and date (one absence, wish and fixed duty per person and day).

const LISTS = ['absences', 'wishes', 'fixed'];
const key = (e) => `${e.personId}|${e.date}`;
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);

/**
 * Applies the local changes (base -> local) onto the server's newer version.
 * Returns the merged period (with the server's revision) and the keys where both sides changed the same entry
 * differently; there the local change wins and the caller shows them for checking.
 */
export function mergePeriod(base, local, server) {
  const merged = { ...server };
  const conflicts = [];
  for (const list of LISTS) {
    const b = new Map((base[list] ?? []).map((e) => [key(e), e]));
    const l = new Map((local[list] ?? []).map((e) => [key(e), e]));
    const s = new Map((server[list] ?? []).map((e) => [key(e), e]));
    const out = new Map(s);
    for (const k of new Set([...b.keys(), ...l.keys()])) {
      const before = b.get(k);
      const mine = l.get(k);
      if (same(before, mine)) continue; // not changed locally: the server's version stays
      const theirs = s.get(k);
      if (!same(before, theirs) && !same(mine, theirs)) conflicts.push({ list, key: k });
      if (mine) out.set(k, mine);
      else out.delete(k);
    }
    merged[list] = [...out.values()];
  }
  return { period: merged, conflicts };
}
