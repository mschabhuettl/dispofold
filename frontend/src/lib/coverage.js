// Coverage vs demand: turns CoverageSegments into a step chart model clipped to a time range. Pure, no DOM.
import { parseLocalDateTime, shiftInterval } from './dates.js';

/** Segments as epoch minutes, sorted. */
export function normalizeSegments(segments) {
  return segments
    .map((s) => ({ from: parseLocalDateTime(s.from), to: parseLocalDateTime(s.to), required: s.required,
      max: s.max ?? null, planned: s.planned }))
    .sort((a, b) => a.from - b.from);
}

/** Segments overlapping [start, end), clipped to it. */
export function clipSegments(segments, start, end) {
  return segments
    .filter((s) => s.to > start && s.from < end)
    .map((s) => ({ ...s, from: Math.max(s.from, start), to: Math.min(s.to, end) }));
}

/**
 * Gaps (planned < required), adjacent gap segments merged. missingMinutes is the sum of person minutes missing.
 */
export function findGaps(segments) {
  const gaps = [];
  for (const s of segments) {
    if (s.planned >= s.required) continue;
    const missing = (s.required - s.planned) * (s.to - s.from);
    const last = gaps.at(-1);
    if (last && last.to === s.from) {
      last.to = s.to;
      last.missingMinutes += missing;
      last.maxShortfall = Math.max(last.maxShortfall, s.required - s.planned);
    } else {
      gaps.push({ from: s.from, to: s.to, missingMinutes: missing, maxShortfall: s.required - s.planned });
    }
  }
  return gaps;
}

/** Overstaffing above max. */
export function findOver(segments) {
  return segments.filter((s) => s.max != null && s.planned > s.max);
}

/**
 * Everything the chart needs for one range: clipped segments, gaps and the y maximum.
 */
export function coverageModel(segments, start, end) {
  const clipped = clipSegments(normalizeSegments(segments), start, end);
  const yMax = Math.max(1, ...clipped.map((s) => Math.max(s.required, s.planned, s.max ?? 0)));
  return { start, end, segments: clipped, gaps: findGaps(clipped), over: findOver(clipped), yMax };
}

/**
 * Step lines for a value accessor in data units (x: epoch minutes, y: count). Returns one polyline per run of
 * adjacent segments; segments where the accessor returns null break the line.
 */
export function stepLines(segments, value) {
  const lines = [];
  let line = null;
  let lastTo = null;
  for (const s of segments) {
    const v = value(s);
    if (v == null) { line = null; continue; }
    if (!line || lastTo !== s.from) lines.push(line = []);
    line.push({ x: s.from, y: v }, { x: s.to, y: v });
    lastTo = s.to;
  }
  return lines;
}

/**
 * Duties of an area that overlap [start, end), as {personId, date, shift, start, end, pinned, previous}. Duties from
 * the previous month (Period.previousDuties) are included, so a night starting on the last day of the previous month
 * shows up on the first morning, as it does in the coverage.
 */
export function areaDuties(assignments, previousDuties, shiftsById, areaId, start, end) {
  const all = [
    ...assignments.map((a) => ({ ...a, previous: false })),
    ...(previousDuties ?? []).map((d) => ({ personId: d.personId, date: d.date, shiftId: d.shiftId, pinned: false, previous: true })),
  ];
  const out = [];
  for (const a of all) {
    const shift = a.shiftId && shiftsById.get(a.shiftId);
    if (!shift || shift.areaId !== areaId) continue;
    const iv = shiftInterval(a.date, shift);
    if (iv.end <= start || iv.start >= end) continue;
    out.push({ personId: a.personId, date: a.date, shift, start: iv.start, end: iv.end, pinned: Boolean(a.pinned), previous: a.previous });
  }
  return out;
}
