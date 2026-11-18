// In-memory imitation of the backend REST API (docs/api.md) for frontend development and screenshots.
// The "solver" is a simple greedy fill, good enough to produce plausible plans, gaps and statistics.
import { addDays, dayCode, epochMinutes, formatLocalDateTime, isWeekend, monthDates, parseLocalDateTime, parseTime, shiftHours,
  shiftInterval, weekday } from '../src/lib/dates.js';
import { CONSTRAINTS, DEFAULT_RULES, TEMPLATE, makeDataset } from './mock-data.mjs';

export function createMockApi({ dataset: initial = null, month = '2026-12', desktop = false, updateSource = '', askUpdates = false,
  release = '' } = {}) {
  let dataset = initial ? structuredClone(initial) : makeDataset(month);
  let rules = structuredClone(DEFAULT_RULES);
  const periods = new Map(dataset.periods.map((p) => [p.month, p]));
  // Daten page: the master data of the last import (baseline) and whether they were changed since (PUT people)
  const masterOf = (d) => structuredClone({ ...d, periods: [] });
  let baseline = masterOf(dataset);
  let changedSinceImport = false;
  const plans = new Map();
  const solving = new Map(); // month -> { started, limitMs }

  const shiftsById = () => new Map(dataset.shifts.map((s) => [s.id, s]));
  const holidaySet = () => new Set(dataset.holidays.map((h) => h.date));
  const emptyPeriod = (m) => ({ month: m, absences: [], wishes: [], fixed: [], previousDuties: [] });
  const emptyPlan = (m) => ({ format: 'dispofold/plan', version: 1, month: m, status: 'EMPTY', score: null, seed: 0,
    updated: new Date().toISOString(), assignments: [] });

  function demandWindows(areaId, date) {
    const hol = holidaySet().has(date);
    const rulesOfArea = dataset.demand.filter((d) => d.areaId === areaId);
    const dated = rulesOfArea.filter((d) => d.date === date);
    let active = dated;
    if (!dated.length) {
      const hasHol = rulesOfArea.some((d) => d.days?.includes('HOL'));
      const type = hol && hasHol ? 'HOL' : dayCode(date);
      active = rulesOfArea.filter((d) => !d.date && d.days?.includes(type));
    }
    return active.map((d) => {
      const from = epochMinutes(date, parseTime(d.from));
      let to = epochMinutes(date, parseTime(d.to));
      if (to <= from) to += 1440;
      return { from, to, min: d.min, max: d.max ?? null };
    });
  }

  function greedyPlan(m, seed) {
    const period = periods.get(m) ?? emptyPeriod(m);
    const dates = monthDates(m);
    const shifts = shiftsById();
    const blocked = new Set(period.absences.map((a) => `${a.personId}|${a.date}`));
    const fixed = new Map(period.fixed.map((f) => [`${f.personId}|${f.date}`, f]));
    const old = new Map((plans.get(m)?.assignments ?? []).filter((a) => a.pinned).map((a) => [`${a.personId}|${a.date}`, a]));
    const hours = new Map(dataset.people.map((p) => [p.id, 0]));
    const lastEnd = new Map();
    const result = [];
    const taken = new Set();
    let offset = seed % 7;
    for (const date of dates) {
      for (const p of dataset.people) {
        const k = `${p.id}|${date}`;
        const pin = fixed.get(k) ?? old.get(k);
        if (!pin) continue;
        const shiftId = pin.shiftId ?? null;
        result.push({ personId: p.id, date, shiftId, compRest: Boolean(pin.compRest), pinned: true });
        taken.add(k);
        if (shiftId) {
          hours.set(p.id, hours.get(p.id) + shiftHours(shifts.get(shiftId)));
          lastEnd.set(p.id, shiftInterval(date, shifts.get(shiftId)).end);
        }
      }
      for (const [areaId, template] of Object.entries(TEMPLATE)) {
        const need = isWeekend(date) && areaId === 'TV' ? template.slice(0, 1) : template;
        for (const shiftId of need) {
          const sh = shifts.get(shiftId);
          if (!sh) continue;
          const { start, end } = shiftInterval(date, sh);
          const target = (p) => (p.weeklyHours / 5) * dates.length * 5 / 7;
          const candidates = dataset.people
            .filter((p) => p.areaIds.includes(areaId) && !taken.has(`${p.id}|${date}`) && !blocked.has(`${p.id}|${date}`))
            .filter((p) => (lastEnd.get(p.id) ?? -1e9) + rules.minDailyRestHours * 60 <= start)
            .filter((p) => hours.get(p.id) + shiftHours(sh) <= target(p) + 6)
            // like the solver: neither restricted nor only on request (restrictions by shift only in the mock)
            .filter((p) => !dataset.restrictions.some((r) => r.personId === p.id && r.shiftId === shiftId))
            .sort((a, b) => hours.get(a.id) / a.weeklyHours - hours.get(b.id) / b.weeklyHours || ((a.id.charCodeAt(4) + offset) % 5) - ((b.id.charCodeAt(4) + offset) % 5));
          const p = candidates[0];
          if (!p) continue;
          taken.add(`${p.id}|${date}`);
          hours.set(p.id, hours.get(p.id) + shiftHours(sh));
          lastEnd.set(p.id, end);
          result.push({ personId: p.id, date, shiftId, compRest: false, pinned: false });
        }
      }
      offset++;
      for (const p of dataset.people) {
        const k = `${p.id}|${date}`;
        if (taken.has(k)) continue;
        const er = p.compRestBalance > 0 && weekday(date) === 3 && !blocked.has(k);
        result.push({ personId: p.id, date, shiftId: null, compRest: er, pinned: false });
      }
    }
    return result;
  }

  function coverage(m, areaId) {
    const plan = plans.get(m);
    const shifts = shiftsById();
    const dates = monthDates(m);
    const windows = [addDays(dates[0], -1), ...dates].flatMap((d) => demandWindows(areaId, d));
    const duties = [...(plan?.assignments ?? []), ...(periods.get(m)?.previousDuties ?? [])]
      .filter((a) => a.shiftId && shifts.get(a.shiftId)?.areaId === areaId)
      .map((a) => shiftInterval(a.date, shifts.get(a.shiftId)));
    const monthStart = epochMinutes(dates[0]);
    const monthEnd = epochMinutes(addDays(dates.at(-1), 1));
    const cuts = new Set([monthStart, monthEnd]);
    for (const w of [...windows, ...duties.map((d) => ({ from: d.start, to: d.end }))]) {
      if (w.from > monthStart && w.from < monthEnd) cuts.add(w.from);
      if (w.to > monthStart && w.to < monthEnd) cuts.add(w.to);
    }
    const sorted = [...cuts].sort((a, b) => a - b);
    const segments = [];
    for (let i = 0; i + 1 < sorted.length; i++) {
      const from = sorted[i];
      const to = sorted[i + 1];
      const active = windows.filter((w) => w.from <= from && w.to >= to);
      const required = active.reduce((s, w) => s + w.min, 0);
      const maxes = active.map((w) => w.max);
      const max = active.length && maxes.every((x) => x != null) ? maxes.reduce((a, b) => a + b, 0) : null;
      const planned = duties.filter((d) => d.start <= from && d.end >= to).length;
      if (required === 0 && planned === 0) continue;
      const last = segments.at(-1);
      if (last && last.to === from && last.required === required && last.max === max && last.planned === planned) last.to = to;
      else segments.push({ areaId, from, to, required, max, planned });
    }
    return segments.map((sg) => ({ ...sg, from: formatLocalDateTime(sg.from) + ':00', to: formatLocalDateTime(sg.to) + ':00' }));
  }

  /** Who could be asked in a gap: a shift of the area overlapping the span is on request for the person, the day is free. */
  function candidates(m, areaId, fromText, toText) {
    let from;
    let to;
    try { from = parseLocalDateTime(fromText); to = parseLocalDateTime(toText); } catch { from = NaN; }
    if (!(to > from)) return err(400, 'Zeitraum von bis erwartet', 'candidates.span');
    if (!dataset.areas.some((x) => x.id === areaId)) return err(404, 'Unbekannter Bereich', 'area.unknown', { areaId });
    const busy = new Set((plans.get(m)?.assignments ?? []).filter((x) => x.shiftId || x.pinned || x.compRest).map((x) => `${x.personId}|${x.date}`));
    for (const x of periods.get(m)?.absences ?? []) busy.add(`${x.personId}|${x.date}`);
    const out = [];
    for (const r of dataset.restrictions.filter((x) => x.kind === 'ON_REQUEST' && x.shiftId)) {
      const shift = shiftsById().get(r.shiftId);
      const p = dataset.people.find((x) => x.id === r.personId);
      if (!shift || !p || shift.areaId !== areaId || !p.areaIds.includes(areaId)) continue;
      for (const date of monthDates(m)) {
        if (r.weekday && r.weekday !== weekday(date)) continue;
        const iv = shiftInterval(date, shift);
        if (iv.start >= to || iv.end <= from || busy.has(`${p.id}|${date}`)) continue;
        const s = personStats(m, p);
        out.push({ personId: p.id, date, shiftId: shift.id, plannedHours: Math.round(s.plannedHours * 10) / 10,
          targetHours: Math.round(s.targetHours * 10) / 10 });
      }
    }
    return json(out.sort((x, y) => (x.plannedHours - x.targetHours) - (y.plannedHours - y.targetHours) || x.personId.localeCompare(y.personId)));
  }

  function personStats(m, p) {
    const plan = plans.get(m);
    const period = periods.get(m) ?? emptyPeriod(m);
    const shifts = shiftsById();
    const hol = holidaySet();
    const dates = monthDates(m);
    const mine = (plan?.assignments ?? []).filter((a) => a.personId === p.id);
    const duties = mine.filter((a) => a.shiftId);
    const absences = period.absences.filter((a) => a.personId === p.id);
    const wishes = period.wishes.filter((w) => w.personId === p.id);
    const dutyDates = new Set(duties.map((a) => a.date));
    const workdays = dates.filter((d) => weekday(d) <= 5 && !hol.has(d)).length;
    const targetHours = (p.weeklyHours / 5) * workdays;
    const creditedHours = (absences.filter((a) => weekday(a.date) <= 5).length + mine.filter((a) => a.compRest).length) * (p.weeklyHours / 5);
    const plannedHours = duties.reduce((s, a) => s + shiftHours(shifts.get(a.shiftId)), 0);
    const saturdays = dates.filter((d) => weekday(d) === 6);
    const weekendsWorked = saturdays.filter((d) => dutyDates.has(d) || dutyDates.has(addDays(d, 1))).length;
    const met = wishes.filter((w) => (w.kind === 'OFF' ? !dutyDates.has(w.date) : w.kind === 'DUTY' ? dutyDates.has(w.date)
      : w.kind === 'SHIFT' ? duties.some((a) => a.date === w.date && a.shiftId === w.shiftId)
        : !duties.some((a) => a.date === w.date && a.shiftId === w.shiftId))).length;
    const hist = dataset.history.filter((h) => h.personId === p.id);
    const sum = (k) => hist.reduce((s, h) => s + h[k], 0);
    const compRestPlanned = mine.filter((a) => a.compRest).length;
    const compRestEarned = duties.filter((a) => hol.has(a.date) && weekday(a.date) !== 7).length;
    let singleDays = 0;
    for (const a of duties) if (!dutyDates.has(addDays(a.date, -1)) && !dutyDates.has(addDays(a.date, 1))) singleDays++;
    const round = (x) => Math.round(x * 100) / 100;
    const share = (key) => {
      const out = {};
      for (const a of duties) { const k = key(a); out[k] = (out[k] ?? 0) + 1; }
      return Object.fromEntries(Object.entries(out).map(([k, n]) => [k, Math.round((n / duties.length) * 1000) / 1000]));
    };
    return {
      plannedMix: duties.length ? { categories: share((a) => shifts.get(a.shiftId).category), areas: share((a) => shifts.get(a.shiftId).areaId) } : null,
      personId: p.id, targetHours: round(targetHours), creditedHours: round(creditedHours), plannedHours: round(plannedHours),
      goalHours: round(targetHours + p.hoursAccount * 0.5), scaledGoalHours: round((targetHours + p.hoursAccount * 0.5) * 0.92),
      accountBefore: p.hoursAccount, accountAfter: round(p.hoursAccount + plannedHours + creditedHours - targetHours),
      duties: duties.length, nights: duties.filter((a) => shifts.get(a.shiftId).category === 'NIGHT').length,
      weekendsWorked, weekendsOff: saturdays.length - weekendsWorked,
      holidays: duties.filter((a) => hol.has(a.date)).length, unpopular: duties.filter((a) => shifts.get(a.shiftId).unpopular).length,
      wishes: wishes.length, wishesMet: met, compRestPlanned, compRestEarned,
      compRestBalanceAfter: p.compRestBalance + compRestEarned - compRestPlanned, singleDays,
      historyNights: sum('nights'), historyWeekends: sum('weekendsWorked'), historyHolidays: sum('holidays'),
      historyUnpopular: sum('unpopular'), historyWishes: sum('wishes'), historyWishesMet: sum('wishesMet'),
      ...nightComparison(m, p),
      ...fairnessLoads(m, p),
    };
  }

  /**
   * Like the backend: weekends, holidays and unpopular duties of this and the history months per 30 available days
   * at 40 h; null when not compared (no available day, or unpopular duties without such a shift in the areas).
   */
  function fairnessLoads(m, p) {
    const shifts = shiftsById();
    const hol = holidaySet();
    const absent = (periods.get(m)?.absences ?? []).filter((a) => a.personId === p.id).length;
    const hist = dataset.history.filter((h) => h.personId === p.id);
    const days = monthDates(m).length - absent + hist.length * 30;
    const duties = (plans.get(m)?.assignments ?? []).filter((a) => a.personId === p.id && shifts.get(a.shiftId));
    const dates = new Set(duties.map((a) => a.date));
    const weekends = monthDates(m).filter((d) => weekday(d) === 6 && (dates.has(d) || dates.has(addDays(d, 1)))).length;
    const load = (count, histKey) => (monthDates(m).length > absent
      ? Math.round(((count + hist.reduce((s, h) => s + h[histKey], 0)) * 30 * 40) / (p.weeklyHours || 40) / days * 100) / 100 : null);
    const unpopular = duties.filter((a) => shifts.get(a.shiftId).unpopular).length;
    const canUnpopular = dataset.shifts.some((s) => s.unpopular && p.areaIds.includes(s.areaId));
    return { weekendLoad: load(weekends, 'weekendsWorked'), holidayLoad: load(duties.filter((a) => hol.has(a.date)).length, 'holidays'),
      unpopularLoad: canUnpopular || unpopular ? load(unpopular, 'unpopular') : null };
  }

  /** Overstaffing above a maximum, a match of no person (like the backend's overstaffing). */
  function overstaffing(m) {
    const out = [];
    for (const area of dataset.areas) {
      for (const sg of coverage(m, area.id)) {
        if (sg.max == null || sg.planned <= sg.max) continue;
        out.push({ personId: null, constraintId: 'overstaffing', level: 'MEDIUM', date: sg.from.slice(0, 10),
          score: -(sg.planned - sg.max) * (parseLocalDateTime(sg.to) - parseLocalDateTime(sg.from)), code: 'hit.overstaffing',
          params: { areaId: area.id, planned: sg.planned, upper: sg.max, from: sg.from.slice(11, 16), to: sg.to.slice(11, 16) } });
      }
    }
    return out;
  }

  /** Like the backend: why a person is outside the night comparison ('NEVER', 'MIX'), null when in it. */
  function nightExclusion(m, p) {
    const dates = monthDates(m);
    const nights = dataset.shifts.filter((x) => x.category === 'NIGHT' && p.areaIds.includes(x.areaId));
    const never = (x) => dataset.restrictions.some((r) => r.personId === p.id && r.kind === 'NEVER' && !r.weekday
      && (!r.validFrom || r.validFrom <= dates[0]) && (!r.validTo || r.validTo >= dates.at(-1))
      && (r.shiftId ? r.shiftId === x.id : r.category === x.category));
    const everyDay = (f) => [1, 2, 3, 4, 5, 6, 7].every((d) => (f.weekdays ?? []).map(Number).includes(d));
    if ((p.familyRules ?? []).some((f) => f.noNights && everyDay(f)) || nights.every(never)) return 'NEVER';
    if ((rules.nightDistribution ?? 'HABIT') === 'HABIT' && Object.keys(p.shiftMix?.categories ?? {}).length) return 'MIX';
    return null;
  }

  /** Night load as the solver compares it: nights of this and the history months per 30 available days at 40 h. */
  function nightComparison(m, p) {
    const shifts = shiftsById();
    const absences = periods.get(m)?.absences ?? [];
    const load = (q) => {
      const hist = dataset.history.filter((h) => h.personId === q.id);
      const nights = (plans.get(m)?.assignments ?? []).filter((a) => a.personId === q.id && shifts.get(a.shiftId)?.category === 'NIGHT').length
        + hist.reduce((s, h) => s + h.nights, 0);
      const days = monthDates(m).length - absences.filter((a) => a.personId === q.id).length + (hist.length * 365.25) / 12;
      return (nights * 30 * 40) / (q.weeklyHours || 40) / days;
    };
    const loads = dataset.people.filter((q) => !nightExclusion(m, q)).map(load);
    const round = (x) => Math.round(x * 100) / 100;
    const reason = nightExclusion(m, p);
    return { nightPool: !reason, nightReason: reason, nightLoad: reason ? null : round(load(p)),
      nightMean: loads.length ? round(loads.reduce((a, b) => a + b, 0) / loads.length) : null };
  }

  function weightOf(id) {
    return rules.weights[id] ?? CONSTRAINTS.find((c) => c.id === id)?.defaultWeight ?? 1;
  }

  function hitsOf(m, p, stats) {
    const plan = plans.get(m);
    const period = periods.get(m) ?? emptyPeriod(m);
    const shifts = shiftsById();
    const hits = [];
    const diff = stats.plannedHours + stats.creditedHours - stats.targetHours;
    if (Math.abs(diff) > 2) {
      hits.push({ constraintId: 'targetHours', level: 'SOFT', date: null, score: -Math.round(Math.abs(diff) * weightOf('targetHours')),
        text: `Geplant und angerechnet ${diff > 0 ? 'über' : 'unter'} Soll um ${Math.abs(diff).toFixed(1)} h.` });
    }
    const label = (id) => { const s = shifts.get(id); return s ? `${s.code} (${s.start}-${s.end})` : id; };
    for (const a of (plan?.assignments ?? []).filter((x) => x.personId === p.id && x.shiftId)) {
      const sh = shifts.get(a.shiftId);
      hits.push({ constraintId: 'demand', level: 'MEDIUM', date: a.date, score: 0,
        text: `${label(a.shiftId)} deckt Bedarf im Bereich ${sh.areaId}.` });
      if (a.pinned) hits.push({ constraintId: 'pinned', level: 'SOFT', date: a.date, score: 0, text: 'Von Hand fixiert.' });
      if (sh.unpopular) hits.push({ constraintId: 'fairUnpopular', level: 'SOFT', date: a.date, score: -weightOf('fairUnpopular'),
        text: `Unbeliebter Dienst, Person hat in den letzten Monaten ${stats.historyUnpopular} gemacht.` });
    }
    for (const w of period.wishes.filter((x) => x.personId === p.id)) {
      const a = plan?.assignments.find((x) => x.personId === p.id && x.date === w.date);
      const working = Boolean(a?.shiftId);
      const ok = w.kind === 'OFF' ? !working : w.kind === 'DUTY' ? working : w.kind === 'SHIFT' ? a?.shiftId === w.shiftId : a?.shiftId !== w.shiftId;
      const id = 'wishes';
      const what = { OFF: 'frei', DUTY: 'Dienst', SHIFT: `Schicht ${label(w.shiftId)}`, NOT_SHIFT: `nicht ${label(w.shiftId)}` }[w.kind];
      hits.push({ constraintId: id, level: 'SOFT', date: w.date, score: ok ? 0 : -weightOf(id),
        text: ok ? `Wunsch „${what}“ erfüllt.` : `Wunsch „${what}“ nicht erfüllt, Bedarf hatte Vorrang.` });
    }
    if (stats.singleDays > 0) hits.push({ constraintId: 'blockLength', level: 'SOFT', date: null, score: -stats.singleDays * weightOf('blockLength'),
      text: `${stats.singleDays} Einzeldienst(e) ohne Nachbardienst.` });
    return hits;
  }

  function analysis(m) {
    const plan = plans.get(m);
    const gaps = [];
    let overstaffedHours = 0;
    const hours = (sg) => (parseLocalDateTime(sg.to) - parseLocalDateTime(sg.from)) / 60; // epoch minutes
    for (const area of dataset.areas) {
      for (const sg of coverage(m, area.id)) {
        if (sg.planned < sg.required) gaps.push({ areaId: area.id, from: sg.from, to: sg.to, required: sg.required, planned: sg.planned });
        overstaffedHours += Math.max(0, sg.planned - (sg.max ?? sg.required)) * hours(sg);
      }
    }
    const people = dataset.people.map((p) => personStats(m, p));
    const constraints = CONSTRAINTS.map((c) => {
      let matches = 0;
      if (c.id === 'demand') matches = gaps.length;
      else if (c.level === 'SOFT') matches = people.reduce((s, st) => s + (c.id === 'wishes' ? st.wishes - st.wishesMet : c.id === 'blockLength' ? st.singleDays : c.id === 'fairUnpopular' ? st.unpopular : 0), 0);
      const w = weightOf(c.id);
      const score = c.id === 'demand' ? -gaps.reduce((s, g) => s + (g.required - g.planned) * (parseLocalDateTime(g.to) - parseLocalDateTime(g.from)), 0) : c.level === 'SOFT' ? -matches * w : 0;
      return { id: c.id, level: c.level, label: c.label, weight: w, score, matches };
    });
    const uncoveredHours = gaps.reduce((sum, g) => sum + (g.required - g.planned) * hours(g), 0);
    return { score: plan?.score ?? null, hardViolations: 0, constraints, gaps, people, uncoveredHours, overstaffedHours,
      singleDaysOff: people.reduce((s, st) => s + st.singleDays, 0), blockDays: people.reduce((s, st) => s + st.singleDays * 2, 0) };
  }

  function scoreFor(m) {
    const a = analysis(m);
    const medium = a.constraints.find((c) => c.id === 'demand').score;
    const soft = a.constraints.filter((c) => c.level === 'SOFT').reduce((s, c) => s + c.score, 0);
    return `0hard/${medium}medium/${soft}soft`;
  }

  function planState(m) {
    const run = solving.get(m);
    const plan = plans.get(m) ?? emptyPlan(m);
    if (run) {
      const elapsed = Date.now() - run.started;
      if (elapsed >= run.limitMs) {
        solving.delete(m);
        plan.status = 'SOLVED';
        plan.score = scoreFor(m);
      } else {
        return { plan, solving: true, elapsedMillis: elapsed, hardViolations: Math.max(0, 3 - Math.floor(elapsed / 1000)) };
      }
    }
    return { plan, solving: false, elapsedMillis: 0, hardViolations: 0, stale: stale.has(m) };
  }

  function toCsv(m) {
    const plan = plans.get(m);
    const shifts = shiftsById();
    const rows = ['personId;date;shift;compRest;pinned'];
    for (const a of plan?.assignments ?? []) rows.push([a.personId, a.date, a.shiftId ? shifts.get(a.shiftId)?.code : '', a.compRest, a.pinned].join(';'));
    return rows.join('\n');
  }

  // Desktop mode: update state of the program (see docs/api.md, desktop endpoints).
  const releasePage = (v) => `https://github.com/mschabhuettl/dispofold/releases/tag/v${v}`;
  const NEXT = { version: '1.3.0', notes: 'Neue Auswertung der Wochenenden.\nKleinere Korrekturen.', title: 'Dispofold 1.3.0',
    url: releasePage('1.3.0') };
  // no update source by default: the program never goes online (the start check reports noSource, not a fault);
  // ask: no settings saved yet (askUpdates), the page asks once whether to check automatically
  const update = { current: '1.2.0', source: updateSource, checkOnStart: true, available: null,
    errorCode: null, errorParams: null, error: null, ask: askUpdates && !updateSource,
    releases: 'https://api.github.com/repos/mschabhuettl/dispofold/releases/latest' };
  // Server build: what the server's release check found (GET /api/release, planners only)
  const serverRelease = { current: '1.2.0', available: release ? { version: release, title: `Dispofold ${release}`,
    notes: 'Neu\n- Auswertung der Wochenenden in der Fairness-Übersicht\n- Kleinere Korrekturen', url: releasePage(release) } : null };
  const check = () => {
    Object.assign(update, { available: null, errorCode: null, errorParams: null, error: null });
    if (!update.source) Object.assign(update, { errorCode: 'desktop.update.noSource', error: 'Keine Update-Quelle' });
    else if (/^https?:\/\/offline/.test(update.source)) {
      Object.assign(update, { errorCode: 'desktop.update.sourceUnreachable', error: 'Update-Quelle nicht erreichbar' });
    } else update.available = update.current === NEXT.version ? null : NEXT;
  };
  check();
  let downUntil = 0;
  const restart = (version) => {
    update.current = version;
    update.available = null;
    downUntil = Date.now() + 4000; // the program is "restarting" for a few seconds
  };

  const json = (body, status = 200) => ({ status, body });
  // error scheme of docs/api.md: { code, params, error }
  const err = (status, error, code = 'mock.error', params = {}) => ({ status, body: { code, params, error } });
  const revisions = new Map(); // month -> revision of the period
  let masterRevision = 0; // revision of the master data
  let newPeople = 0; // ids PN1, PN2, ... of people created here
  const stale = new Set(); // months whose inputs changed after planning

  /** Viewers get no wishes, restrictions or family rules; absences only as the neutral "ABW". */
  const forViewer = (period) => ({ ...period, wishes: [],
    absences: (period.absences ?? []).map((x) => ({ ...x, typeCode: 'ABW' })) });

  /** Personal figures viewers do not get: wishes 0, hours account and compensatory rest null. */
  const redactStats = (s) => ({ ...s, wishes: 0, wishesMet: 0, historyWishes: 0, historyWishesMet: 0, accountBefore: null, goalHours: null, scaledGoalHours: null,
    accountAfter: null, compRestBalanceAfter: null, compRestEarned: null, chargedHours: null, creditedHours: null,
    nightPool: null, nightReason: null, nightLoad: null, nightMean: null, weekendLoad: null, holidayLoad: null, unpopularLoad: null });

  // Changes made "in Dispofold" since the last import, person fields only (the backend also has restrictions,
  // removed people and demand), compared like the backend: absent and null are the same.
  const PERSON_FIELDS = ['weeklyHours', 'areaIds', 'hoursAccount', 'compRestBalance', 'familyRules', 'entryDate', 'exitDate', 'shiftMix'];
  const same = (x, y) => JSON.stringify(x ?? null) === JSON.stringify(y ?? null);
  function changes() {
    const before = new Map(baseline.people.map((p) => [p.id, p]));
    const out = [];
    for (const p of dataset.people) {
      const old = before.get(p.id);
      if (old) for (const field of PERSON_FIELDS) if (!same(old[field], p[field])) out.push({ personId: p.id, field, value: p[field] ?? null });
    }
    const notes = dataset.people.filter((p) => !before.has(p.id)).map((p) => ({ code: 'corrections.personAdded', params: { personId: p.id } }));
    return { changed: out, notes };
  }
  /** The changes applied to a new import; a field the import changed as well is a conflict, Dispofold's value wins. */
  function keepChanges(incoming) {
    const { changed, notes } = changes();
    const merged = structuredClone(incoming);
    const was = new Map(baseline.people.map((p) => [p.id, p]));
    const conflicts = [];
    let kept = 0;
    for (const c of changed) {
      const target = merged.people.find((p) => p.id === c.personId);
      if (!target) {
        notes.push({ code: 'corrections.personMissing', params: { personId: c.personId } });
        continue;
      }
      if (!same(was.get(c.personId)[c.field], target[c.field])) conflicts.push({ personId: c.personId, areaId: null, field: c.field });
      target[c.field] = structuredClone(c.value);
      kept++;
    }
    return { merged, conflicts, kept, notes };
  }
  /** Pseudonymised dataset (all months) and plan: people sorted by id are P001, P002, ...; free texts neutral. */
  function anonymized(m) {
    const ids = dataset.people.map((p) => p.id).sort();
    const byId = new Map(dataset.people.map((p) => [p.id, p]));
    const pseudonym = new Map(ids.map((id, i) => [id, `P${String(i + 1).padStart(3, '0')}`]));
    const swap = (x) => (Array.isArray(x) ? x.map(swap) : x && typeof x === 'object'
      ? Object.fromEntries(Object.entries(x).map(([k, v]) => [k, k === 'personId' ? (pseudonym.get(v) ?? 'P999') : swap(v)])) : x);
    const shared = swap({ ...dataset, periods: [...periods.values()] });
    Object.assign(shared, { source: 'anonymisiert',
      areas: dataset.areas.map((x) => ({ ...x, name: x.id })),
      shifts: dataset.shifts.map((x) => ({ ...x, name: x.code })),
      absenceTypes: dataset.absenceTypes.map((x) => ({ ...x, name: x.code })),
      holidays: dataset.holidays.map((x) => ({ ...x, name: 'Feiertag' })),
      people: ids.map((id) => {
        const p = byId.get(id);
        const q = pseudonym.get(id);
        return { ...p, id: q, firstName: q, lastName: q, shortName: q, externalId: q, familyRules: (p.familyRules ?? []).map((f) => ({ ...f, note: null })) };
      }) });
    const key = ['﻿pseudonym;id;externe_id;nachname;vorname;kuerzel', ...ids.map((id) => {
      const p = byId.get(id);
      return [pseudonym.get(id), id, p.externalId ?? '', p.lastName, p.firstName, p.shortName].join(';');
    })].join('\r\n');
    return { dataset: shared, plan: swap(plans.get(m) ?? emptyPlan(m)), key: `${key}\r\n`, people: ids.length };
  }

  /** True while the mock program "restarts" after an update. */
  handle.restarting = () => Date.now() < downUntil;
  handle.restartFor = (ms) => { downUntil = Date.now() + ms; };

  return handle;

  /** Handles one request. path without query, query as URLSearchParams, body parsed JSON (raw Buffer for uploads) or null. */
  function handle(method, path, query, body, role) {
    const parts = path.split('/').filter(Boolean).slice(1); // drop 'api'
    const write = method !== 'GET';
    if (write && role !== 'planner') return err(403, 'Nur für Planung');
    const [a, b, c, d] = parts;
    if (a === 'me') return json(desktop ? { username: 'lokal', role, desktop: true }
      : { username: role === 'planner' ? 'planung' : 'ansicht', role, desktop: false });
    if (a === 'desktop') {
      if (!desktop) return err(404, `Unbekannter Pfad ${path}`);
      if (b === 'quit' && method === 'POST') return json(null, 204);
      if (b === 'update' && !c && method === 'GET') {
        // as the backend: only check=true asks the source
        if (query.get('check') === 'true') {
          check();
          return json({ ...update });
        }
        return json({ ...update, available: null, errorCode: null, errorParams: null, error: null });
      }
      if (b === 'update' && c === 'settings' && method === 'PUT') {
        const source = String(body?.source ?? '').trim();
        if (source && !/^https?:\/\/\S+$/.test(source) && !/^(\/|[A-Za-z]:\\|\\\\)/.test(source)) {
          return err(400, 'Die Update-Quelle muss eine http(s)-Adresse oder ein Ordner sein', 'desktop.update.sourceInvalid');
        }
        update.source = source;
        update.checkOnStart = Boolean(body?.checkOnStart);
        update.ask = false;
        return json(null, 204);
      }
      if (b === 'update' && c === 'install' && method === 'POST') {
        if (!update.available) return err(409, 'Kein Update verfügbar', 'desktop.update.noneAvailable');
        restart(update.available.version);
        return json(null, 202);
      }
      if (b === 'update' && c === 'file' && method === 'POST') {
        // body is the raw upload (Buffer); a real bundle is a ZIP file
        if (!body?.length || body[0] !== 0x50 || body[1] !== 0x4b) return err(400, 'Die Datei ist kein signiertes Dispofold-Update', 'desktop.bundle.notZip');
        restart(NEXT.version);
        return json(null, 202);
      }
    }
    if (a === 'release' && !b && method === 'GET') {
      if (desktop) return err(404, `Unbekannter Pfad ${path}`);
      return role === 'planner' ? json(serverRelease) : err(403, 'Nur für Planung');
    }
    if (a === 'logout') return json(null, 204);
    if (a === 'dataset' && !b && method === 'GET') {
      if (role !== 'planner') {
        return json({ ...dataset, revision: masterRevision, periods: [], restrictions: [], changedSinceImport, people: dataset.people.map((p) => ({ ...p, familyRules: [], hoursAccount: null,
          compRestBalance: null, shiftMix: null, externalId: null })) });
      }
      return json({ ...dataset, revision: masterRevision, periods: [], changedSinceImport });
    }
    // master data of people as docs/api.md (answers { revision, key, item }); the revision is counted but not checked
    if (a === 'dataset' && b === 'people' && d === 'restrictions' && method === 'PUT') {
      if (!dataset.people.some((p) => p.id === c)) return err(404, `Person ${c} nicht gefunden`, 'masterdata.unknown');
      dataset.restrictions = [...dataset.restrictions.filter((r) => r.personId !== c), ...body.map((r) => ({ ...r, personId: c }))];
      changedSinceImport = true;
      return json({ revision: ++masterRevision, key: c, item: body });
    }
    if (a === 'dataset' && b === 'people' && !c && method === 'POST') {
      const item = { ...body, id: `PN${++newPeople}` };
      dataset.people.push(item);
      changedSinceImport = true;
      return json({ revision: ++masterRevision, key: item.id, item });
    }
    if (a === 'dataset' && b === 'people' && c && !d && method === 'DELETE') {
      if (!dataset.people.some((p) => p.id === c)) return err(404, `Person ${c} nicht gefunden`, 'masterdata.unknown');
      dataset.people = dataset.people.filter((p) => p.id !== c);
      dataset.restrictions = dataset.restrictions.filter((r) => r.personId !== c);
      changedSinceImport = true;
      return json({ revision: ++masterRevision, key: c });
    }
    if (a === 'dataset' && b === 'people' && c && !d && method === 'PUT') {
      const i = dataset.people.findIndex((p) => p.id === c);
      if (i < 0) return err(404, `Person ${c} nicht gefunden`);
      dataset.people[i] = { ...body, id: c };
      changedSinceImport = true;
      return json({ revision: ++masterRevision, key: c, item: dataset.people[i] });
    }
    if (a === 'import') {
      const summary = (source, warnings = [], kept = 0, notes = []) => json({ source, people: dataset.people.length, areas: dataset.areas.length,
        shifts: dataset.shifts.length, months: dataset.periods.map((p) => p.month), warnings, kept, notes });
      // rejected like the real validator: every finding with code and params
      const errors = [];
      if (b !== 'demo') {
        if (body?.format !== 'dispofold/dataset') errors.push({ code: 'import.format', params: {} });
        else if (body.version !== 2) errors.push({ code: 'import.version', params: { version: body.version ?? 0 } });
        if (!body?.people?.length) errors.push({ code: 'import.noPeople', params: {} });
      }
      if (errors.length) {
        return { status: 400, body: { code: 'import.invalid', params: { count: errors.length }, error: 'Datensatz abgelehnt',
          errors, warnings: [] } };
      }
      if (b === 'preview') {
        if (!changedSinceImport) return json({ changed: false, kept: 0, conflicts: [], notes: [] });
        const k = keepChanges(body);
        return json({ changed: true, kept: k.kept, conflicts: k.conflicts, notes: k.notes });
      }
      const keep = b === 'dataset' && query.get('keep') === 'true' && changedSinceImport;
      if (changedSinceImport && !keep && query.get('replace') !== 'true') {
        return err(409, 'Die Stammdaten wurden seit dem letzten Import in Dispofold geändert', 'import.masterChanged');
      }
      if (b === 'demo') {
        dataset = initial ? structuredClone(initial) : makeDataset(query.get('month') ?? month, 1);
        for (const p of dataset.periods) periods.set(p.month, p);
        baseline = masterOf(dataset);
        changedSinceImport = false;
        return summary('synthetic');
      }
      if (b === 'dataset') {
        const k = keep ? keepChanges(body) : null;
        baseline = masterOf(body);
        dataset = k ? k.merged : body;
        changedSinceImport = Boolean(k?.kept);
        for (const p of dataset.periods ?? []) periods.set(p.month, p);
        return summary(body.source ?? 'upload', ['Hochgeladener Datensatz ersetzt die Stammdaten.'], k?.kept ?? 0, k?.notes ?? []);
      }
    }
    if (a === 'data') {
      // like the backend: planners only, also for reading
      if (role !== 'planner') return err(403, 'Nur für Planung', 'security.forbidden');
      if (b === 'corrections') {
        const { changed, notes } = changes();
        const people = {};
        for (const x of changed) (people[x.personId] ??= {})[x.field] = x.value;
        return json({ file: { format: 'dispofold/corrections', version: 2, people, demand: {} }, notes, count: changed.length });
      }
      if (b === 'anonymized') {
        const m = query.get('month') ?? '';
        if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(m)) return err(400, 'Monat im Format JJJJ-MM erwartet', 'request.month');
        if (solving.has(m) && planState(m).solving) return err(409, 'Planung läuft', 'plan.solving', { month: m });
        return json(anonymized(m));
      }
    }
    if (a === 'rules') {
      if (method === 'PUT') rules = body;
      return json(rules);
    }
    if (a === 'constraints') return json(CONSTRAINTS.map((x) => ({ ...x, weight: weightOf(x.id) })));
    if (a === 'months') {
      const all = new Set([...periods.keys(), ...plans.keys()]);
      return json([...all].sort().map((m) => ({ month: m, hasPeriod: periods.has(m), status: planState(m).plan.status, score: plans.get(m)?.score ?? null })));
    }
    if (a === 'periods') {
      const revision = revisions.get(b) ?? 0;
      if (method === 'PUT') {
        if (plans.get(b)?.status === 'CLOSED') return err(409, 'Monat abgeschlossen', 'plan.closed', { month: b });
        if ((body?.revision ?? -1) !== revision) return err(409, 'Eingaben wurden geändert', 'period.conflict', { revision });
        const { revision: _, ...rest } = body;
        periods.set(b, { ...rest, month: b });
        revisions.set(b, revision + 1);
        if (plans.get(b)?.assignments.length) stale.add(b);
      }
      const period = { ...(periods.get(b) ?? emptyPeriod(b)), revision: revisions.get(b) ?? 0 };
      return json(role === 'planner' ? period : forViewer(period));
    }
    if (a === 'plans') {
      const m = b;
      const busy = solving.has(m) && planState(m).solving;
      if (!c) return json(planState(m));
      const closed = plans.get(m)?.status === 'CLOSED';
      const writes = ['solve', 'cells', 'unpin', 'import', 'finalize'];
      if (closed && writes.includes(c)) return err(409, 'Monat abgeschlossen', 'plan.closed', { month: m });
      if (c === 'reopen') {
        if (closed) plans.get(m).status = 'SOLVED';
        return json(null, 204);
      }
      if (c === 'solve') {
        if (busy) return err(409, 'Planung läuft bereits', 'plan.solving');
        stale.delete(m);
        const seed = body?.seed ?? 1;
        const plan = { ...emptyPlan(m), status: 'SOLVING', seed, assignments: greedyPlan(m, seed), updated: new Date().toISOString() };
        plans.set(m, plan);
        solving.set(m, { started: Date.now(), limitMs: Math.min(8, body?.timeLimitSeconds ?? 8) * 1000 });
        return json(planState(m), 202);
      }
      if (c === 'stop') {
        if (solving.has(m)) solving.get(m).limitMs = 0;
        return json(planState(m));
      }
      if (c === 'cells') {
        if (busy) return err(409, 'Planung läuft', 'plan.solving');
        const plan = plans.get(m) ?? emptyPlan(m);
        plans.set(m, plan);
        plan.assignments = plan.assignments.filter((x) => !(x.personId === body.personId && x.date === body.date));
        plan.assignments.push(body);
        plan.score = scoreFor(m);
        return json(plan);
      }
      if (c === 'unpin') {
        const fixed = new Set((periods.get(m)?.fixed ?? []).map((f) => `${f.personId}|${f.date}`));
        for (const x of plans.get(m)?.assignments ?? []) if (!fixed.has(`${x.personId}|${x.date}`)) x.pinned = false;
        return json(plans.get(m) ?? emptyPlan(m));
      }
      if (c === 'analysis') {
        const a = analysis(m);
        return json(role === 'planner' ? a : { ...a, people: (a.people ?? []).map(redactStats) });
      }
      if (c === 'people') {
        const p = dataset.people.find((x) => x.id === d);
        if (!p) return err(404, `Person ${d} nicht gefunden`);
        const stats = personStats(m, p);
        if (role !== 'planner') return json({ stats: redactStats(stats), hits: [] });
        return json({ stats, hits: hitsOf(m, p, stats) });
      }
      if (c === 'hits') {
        // like the person explanations: viewers get no hits here
        const groups = {};
        if (role !== 'planner') return json(groups);
        const only = query.get('constraint');
        for (const p of dataset.people) {
          for (const hit of hitsOf(m, p, personStats(m, p))) {
            if (!only || hit.constraintId === only) (groups[hit.constraintId] ??= []).push({ personId: p.id, ...hit });
          }
        }
        return json(groups);
      }
      if (c === 'problems') {
        if (role !== 'planner') return err(403, 'Nur für Planung', 'auth.forbidden');
        const out = [];
        for (const p of dataset.people) {
          for (const hit of hitsOf(m, p, personStats(m, p))) if (hit.score < 0) out.push({ personId: p.id, ...hit });
        }
        return json([...out, ...overstaffing(m)]);
      }
      if (c === 'coverage') return json(coverage(m, d));
      if (c === 'candidates') {
        if (role !== 'planner') return err(403, 'Nur für Planung', 'auth.forbidden');
        return candidates(m, query.get('areaId'), query.get('from'), query.get('to'));
      }
      if (c === 'finalize') {
        if (busy) return err(409, 'Planung läuft', 'plan.solving');
        const plan = plans.get(m) ?? emptyPlan(m);
        plans.set(m, { ...plan, status: 'CLOSED' });
        return json(null, 204);
      }
      if (c === 'import') {
        if (body?.format !== 'dispofold/plan') return err(400, 'Kein Plan (format fehlt oder falsch)');
        plans.set(m, { ...body, month: m });
        return json(plans.get(m));
      }
      if (c === 'export') {
        if (role !== 'planner') return err(403, 'Nur für Planung', 'auth.forbidden');
        if (busy) return err(409, 'Planung läuft', 'plan.solving');
        const format = query.get('format');
        if (format === 'json') return { status: 200, body: plans.get(m) ?? emptyPlan(m), download: `dienstplan-${m}.json` };
        if (format === 'csv') return { status: 200, raw: toCsv(m), type: 'text/csv; charset=utf-8', download: `dienstplan-${m}.csv` };
        // stand-in bytes: the dev server has no Excel writer, the file only exercises the download path
        return { status: 200, raw: Buffer.from(`PK\u0003\u0004 Testdatei ${m}`), download: `dienstplan-${m}.xlsx`,
          type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' };
      }
    }
    return err(404, `Unbekannter Pfad ${path}`);
  };
}
