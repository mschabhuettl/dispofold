// In-memory imitation of the backend REST API (docs/api.md) for frontend development and screenshots.
// The "solver" is a simple greedy fill, good enough to produce plausible plans, gaps and statistics.
import { addDays, dayCode, epochMinutes, formatLocalDateTime, isWeekend, monthDates, parseLocalDateTime, parseTime, shiftHours,
  shiftInterval, weekday } from '../src/lib/dates.js';
import { CONSTRAINTS, DEFAULT_RULES, TEMPLATE, makeDataset } from './mock-data.mjs';

export function createMockApi({ dataset: initial = null, month = '2026-12', desktop = false, updateSource = '' } = {}) {
  let dataset = initial ? structuredClone(initial) : makeDataset(month);
  let rules = structuredClone(DEFAULT_RULES);
  const periods = new Map(dataset.periods.map((p) => [p.month, p]));
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
            .filter((p) => !dataset.restrictions.some((r) => r.personId === p.id && r.shiftId === shiftId && r.kind === 'NEVER'))
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
    return {
      personId: p.id, targetHours: round(targetHours), creditedHours: round(creditedHours), plannedHours: round(plannedHours),
      accountBefore: p.hoursAccount, accountAfter: round(p.hoursAccount + plannedHours + creditedHours - targetHours),
      duties: duties.length, nights: duties.filter((a) => shifts.get(a.shiftId).category === 'NIGHT').length,
      weekendsWorked, weekendsOff: saturdays.length - weekendsWorked,
      holidays: duties.filter((a) => hol.has(a.date)).length, unpopular: duties.filter((a) => shifts.get(a.shiftId).unpopular).length,
      wishes: wishes.length, wishesMet: met, compRestPlanned, compRestEarned,
      compRestBalanceAfter: p.compRestBalance + compRestEarned - compRestPlanned, singleDays,
      historyNights: sum('nights'), historyWeekends: sum('weekendsWorked'), historyHolidays: sum('holidays'),
      historyUnpopular: sum('unpopular'), historyWishes: sum('wishes'), historyWishesMet: sum('wishesMet'),
    };
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
      hits.push({ constraintId: 'coverage', level: 'MEDIUM', date: a.date, score: 0,
        text: `${label(a.shiftId)} deckt Bedarf im Bereich ${sh.areaId}.` });
      if (a.pinned) hits.push({ constraintId: 'pinned', level: 'SOFT', date: a.date, score: 0, text: 'Von Hand fixiert.' });
      if (sh.unpopular) hits.push({ constraintId: 'fairUnpopular', level: 'SOFT', date: a.date, score: -weightOf('fairUnpopular'),
        text: `Unbeliebter Dienst, Person hat in den letzten Monaten ${stats.historyUnpopular} gemacht.` });
    }
    for (const w of period.wishes.filter((x) => x.personId === p.id)) {
      const a = plan?.assignments.find((x) => x.personId === p.id && x.date === w.date);
      const working = Boolean(a?.shiftId);
      const ok = w.kind === 'OFF' ? !working : w.kind === 'DUTY' ? working : w.kind === 'SHIFT' ? a?.shiftId === w.shiftId : a?.shiftId !== w.shiftId;
      const id = w.kind === 'OFF' ? 'wishOff' : 'wishShift';
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
    for (const area of dataset.areas) {
      for (const sg of coverage(m, area.id)) {
        if (sg.planned < sg.required) gaps.push({ areaId: area.id, from: sg.from, to: sg.to, required: sg.required, planned: sg.planned });
      }
    }
    const people = dataset.people.map((p) => personStats(m, p));
    const constraints = CONSTRAINTS.map((c) => {
      let matches = 0;
      if (c.id === 'coverage') matches = gaps.length;
      else if (c.level === 'SOFT') matches = people.reduce((s, st) => s + (c.id === 'wishOff' ? st.wishes - st.wishesMet : c.id === 'blockLength' ? st.singleDays : c.id === 'fairUnpopular' ? st.unpopular : 0), 0);
      const w = weightOf(c.id);
      const score = c.id === 'coverage' ? -gaps.reduce((s, g) => s + (g.required - g.planned) * (parseLocalDateTime(g.to) - parseLocalDateTime(g.from)), 0) : c.level === 'SOFT' ? -matches * w : 0;
      return { id: c.id, level: c.level, label: c.label, weight: w, score, matches };
    });
    return { score: plan?.score ?? null, hardViolations: 0, constraints, gaps, people };
  }

  function scoreFor(m) {
    const a = analysis(m);
    const medium = a.constraints.find((c) => c.id === 'coverage').score;
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
  const NEXT = { version: '1.3.0', notes: 'Neue Auswertung der Wochenenden.\nKleinere Korrekturen.' };
  // no update source by default: the program never goes online (the start check reports noSource, not a fault)
  const update = { current: '1.2.0', source: updateSource, checkOnStart: true, available: null,
    errorCode: null, errorParams: null, error: null };
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
  const stale = new Set(); // months whose inputs changed after planning

  /** Viewers get no wishes, restrictions or family rules; absences only as the neutral "ABW". */
  const forViewer = (period) => ({ ...period, wishes: [],
    absences: (period.absences ?? []).map((x) => ({ ...x, typeCode: 'ABW' })) });

  /** Personal figures viewers do not get: wishes 0, hours account and compensatory rest null. */
  const redactStats = (s) => ({ ...s, wishes: 0, wishesMet: 0, historyWishes: 0, historyWishesMet: 0, accountBefore: null,
    accountAfter: null, compRestBalanceAfter: null, compRestEarned: null, chargedHours: null, creditedHours: null });

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
        if (query.get('check') === 'true') check();
        return json({ ...update });
      }
      if (b === 'update' && c === 'settings' && method === 'PUT') {
        const source = String(body?.source ?? '').trim();
        if (source && !/^https?:\/\/\S+$/.test(source) && !/^(\/|[A-Za-z]:\\|\\\\)/.test(source)) {
          return err(400, 'Die Update-Quelle muss eine http(s)-Adresse oder ein Ordner sein', 'desktop.update.sourceInvalid');
        }
        update.source = source;
        update.checkOnStart = Boolean(body?.checkOnStart);
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
    if (a === 'logout') return json(null, 204);
    if (a === 'dataset' && !b && method === 'GET') {
      if (role !== 'planner') {
        return json({ ...dataset, periods: [], restrictions: [], people: dataset.people.map((p) => ({ ...p, familyRules: [], hoursAccount: null, compRestBalance: null })) });
      }
      return json({ ...dataset, periods: [] });
    }
    if (a === 'dataset' && b === 'people' && method === 'PUT') {
      const i = dataset.people.findIndex((p) => p.id === c);
      if (i < 0) return err(404, `Person ${c} nicht gefunden`);
      dataset.people[i] = { ...body, id: c };
      return json(dataset.people[i]);
    }
    if (a === 'import') {
      const summary = (source, warnings = []) => json({ source, people: dataset.people.length, areas: dataset.areas.length,
        shifts: dataset.shifts.length, months: dataset.periods.map((p) => p.month), warnings });
      if (b === 'demo') {
        dataset = initial ? structuredClone(initial) : makeDataset(query.get('month') ?? month, 1);
        for (const p of dataset.periods) periods.set(p.month, p);
        return summary('synthetic');
      }
      if (b === 'dataset') {
        // rejected like the real validator: every finding with code and params
        const errors = [];
        if (body?.format !== 'dispofold/dataset') errors.push({ code: 'import.format', params: {} });
        else if (body.version !== 2) errors.push({ code: 'import.version', params: { version: body.version ?? 0 } });
        if (!body?.people?.length) errors.push({ code: 'import.noPeople', params: {} });
        if (errors.length) {
          return { status: 400, body: { code: 'import.invalid', params: { count: errors.length }, error: 'Datensatz abgelehnt',
            errors, warnings: [] } };
        }
        dataset = body;
        for (const p of dataset.periods ?? []) periods.set(p.month, p);
        return summary(body.source ?? 'upload', ['Hochgeladener Datensatz ersetzt die Stammdaten.']);
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
      if (c === 'coverage') return json(coverage(m, d));
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
