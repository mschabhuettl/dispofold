// Dienstplan: people x days of the month, cell editing, solving with progress polling.
import { demandFigures, scoreFigures } from '../lib/api.js';
import { constraintLabel, hitText } from '../lib/codes.js';
import { tintStyle } from '../lib/colors.js';
import { dayLabel, monthLabel } from '../lib/dates.js';
import { append, fmt, guarded, h, toast } from '../lib/dom.js';
import { NAME_MODES, personLabel, printParts } from '../lib/functions-model.js';
import { NEUTRAL_ABSENCE, buildGrid, cellRequest, cellsEditable, personName } from '../lib/grid-model.js';
import { areasWithoutDemand, chipCount, planHeadline, problemConstraints, scoreWords } from '../lib/plan-status.js';
import { info } from '../lib/info.js';
import { printHeader, printStatus } from '../lib/print.js';
import { needsRulesForCategories, onRequestTest } from '../lib/restrictions.js';
import { t } from '../lib/texts.js';
import { gapsWithCandidates } from './candidate-list.js';
import { cellChoice, createPopover, dayClass, dayHeader, legendBox, lightThemeWhilePrinting, rovingGrid, stickyHeights } from './grid-shared.js';
import { closeMonth, exportItems, outputMenu, printItem } from './plan-output.js';
import { emptyStart } from './empty-start.js';

const POLL_INTERVAL_MS = 2000;
const NAME_LABELS = { short: t('functions.names.short'), full: t('functions.names.full') };

export async function render(root, ctx) {
  const { api, month, canEdit } = ctx;
  const [dataset, period, initialState] = await Promise.all([ctx.dataset(), api.period(month), api.planState(month)]);
  let state = initialState;
  let analysis = state.plan.assignments.length && !state.solving ? await api.analysis(month).catch(() => null) : null;
  const storedArea = sessionStorage.getItem('roster-area');
  let areaId = dataset.areas.some((a) => a.id === storedArea) ? storedArea : '';
  let search = '';
  let timer = null;
  const popover = createPopover('.cell-btn, .gap-btn');
  let disposed = false;
  let polling = false;
  let pollFailures = 0;
  let focusKey = null;
  /** Cells can be changed: planner role and no solve running (the solver owns the plan meanwhile). */
  const closed = () => state.plan.status === 'CLOSED';
  const editable = () => cellsEditable({ canEdit, solving: state.solving, closed: closed() });

  const areaName = new Map(dataset.areas.map((a) => [a.id, a.name]));
  const rules = needsRulesForCategories(dataset) ? await api.rules().catch(() => null) : null;
  const onRequest = onRequestTest(dataset.restrictions, dataset.shifts, rules);
  const status = h('div.statusbar', {});
  const table = h('div.roster-wrap', {});
  const print = h('div.print-only', {});
  const title = t('roster.title', { month: monthLabel(month) });
  // per viewer: "Nachname Vorname" (as before) or the short name in the first column, on screen and in print
  let nameMode = 'full';
  try { nameMode = NAME_MODES.includes(localStorage.getItem('roster-names')) ? localStorage.getItem('roster-names') : 'full'; } catch { /* no storage */ }
  const nav = rovingGrid(table);
  // empty fields leave seed, time and move limit to the server's reproducible defaults
  const seedInput = h('input', { id: 'seed', type: 'number', min: 0, placeholder: t('roster.serverDefault') });
  const limitInput = h('input', { id: 'limit', type: 'number', min: 5, placeholder: t('roster.serverDefault') });
  const moveLimitInput = h('input', { id: 'move-limit', type: 'number', min: 1, placeholder: t('roster.serverDefault') });
  const coldStartInput = h('input', { id: 'cold-start', type: 'checkbox' });
  const btnSolve = h('button.btn.btn-primary', { type: 'button', onclick: solve }, t('roster.solve'));
  const btnStop = h('button.btn.btn-secondary', { type: 'button', onclick: stop }, t('roster.stop'));
  const btnUnpin = h('button.btn.btn-secondary', { type: 'button', onclick: unpin }, t('roster.unpinAll'));
  const btnPin = h('button.btn.btn-secondary', { type: 'button', onclick: pinAll }, t('roster.pinAll'));
  // a link to the first conflict cell, else the first gap of the shown rows: the grid may have to scroll sideways
  const jump = h('div.jump-first', {});
  const scoreText = h('span', {});
  const sticky = stickyHeights();
  // help buttons of the status line, made once: it is drawn again every few seconds while solving, an open help
  // and the focus on its button stay
  const helps = {};
  const help = (key, label) => (helps[key] ??= info(t(key), label));
  let ruleHits = null; // { analysis, lists: rule id -> promise } of the lists behind the chips, loaded on the first click

  root.classList.add('roster-view', 'print-view');
  append(root, [
    h('h1', {}, title),
    h('div.toolbar', {},
      h('div.field', {}, h('label', { for: 'area' }, t('roster.area')),
        h('select', { id: 'area', onchange: (e) => { areaId = e.target.value; sessionStorage.setItem('roster-area', areaId); drawTable(); } },
          h('option', { value: '' }, t('roster.allAreas')),
          dataset.areas.map((a) => h('option', { value: a.id, selected: a.id === areaId }, a.name)))),
      h('div.field', {}, h('label', { for: 'search' }, t('roster.search')),
        h('input', { id: 'search', type: 'search', placeholder: t('roster.searchPlaceholder'), oninput: (e) => { search = e.target.value; drawTable(); } })),
      h('div.field', {}, h('label', { for: 'roster-names' }, t('functions.names')),
        h('select', { id: 'roster-names', onchange: (e) => {
          nameMode = e.target.value;
          try { localStorage.setItem('roster-names', nameMode); } catch { /* no storage */ }
          drawTable();
        } }, NAME_MODES.map((m) => h('option', { value: m, selected: m === nameMode }, NAME_LABELS[m])))),
      h('span.spacer', {}),
      outputMenu(() => [printItem(), canEdit && exportItems(api, month, state.solving),
        // planners close the month here as well as under Import/Export
        canEdit && !closed() && h('button', { type: 'button', disabled: state.solving, onclick: () => closeMonth(ctx) }, t('import.finalize'))])),
    canEdit && h('div.toolbar.plan-actions', {},
      h('div.btn-row', {}, btnSolve, btnStop, btnPin, btnUnpin, info(t('help.plan.pinAll'), t('roster.pinAll'))),
      // solver options and the score in words: rarely needed, collapsed
      h('details.advanced', {}, h('summary', {}, t('roster.advanced')),
        h('div.advanced-body', {},
          h('div.field', {}, h('div.with-info', {}, h('label', { for: 'seed' }, t('roster.seed')), info(t('help.solve.seed'), t('roster.seed'))), seedInput),
          h('div.field', {}, h('div.with-info', {}, h('label', { for: 'limit' }, t('roster.timeLimit')), info(t('help.solve.timeLimit'), t('roster.timeLimit'))), limitInput),
          h('div.field', {}, h('div.with-info', {}, h('label', { for: 'move-limit' }, t('roster.moveLimit')), info(t('help.solve.moveLimit'), t('roster.moveLimit'))), moveLimitInput),
          h('div.check-inline', {}, h('label.check-inline', {}, coldStartInput, ' ', t('roster.coldStart')), info(t('help.solve.coldStart'), t('roster.coldStart'))),
          h('span.score', {}, t('roster.score'), ' ', scoreText, info(t('help.solve.score'), t('roster.score')))))),
    // without data: what to do first (planners), and no empty grid
    !dataset.people.length && (canEdit ? emptyStart(ctx) : h('p.alert', {}, t('common.noMasterdataReadonly'))),
    status,
    dataset.people.length > 0 && [jump, legendBox(legend(dataset, canEdit)), table],
    print,
  ]);

  function drawStatus() {
    const plan = state.plan;
    // while solving there is no analysis: the medium score holds both figures exactly
    const figures = demandFigures(analysis) ?? scoreFigures(plan.score);
    const hard = state.solving ? state.hardViolations : (analysis?.hardViolations ?? state.hardViolations);
    const headline = planHeadline({ solving: state.solving, seconds: Math.round(state.elapsedMillis / 1000),
      planned: plan.assignments.length > 0, hard, gaps: state.solving ? null : (analysis?.gaps?.length ?? null),
      uncoveredHours: figures?.uncovered ?? null, duties: plan.assignments.filter((a) => a.shiftId).length,
      areas: dataset.areas.length, areasWithoutDemand: areasWithoutDemand(dataset) });
    // the score in words under "Erweitert", the raw score as its tooltip
    scoreText.textContent = scoreWords({ score: plan.score, hard, uncovered: figures?.uncovered, overstaffed: figures?.overstaffed });
    scoreText.title = plan.score ?? '';
    const staleReasons = [state.stale && t('roster.staleHelp'),
      (state.previousMonthChanged || analysis?.previousMonthChanged) && t('roster.previousChangedHelp')].filter(Boolean);
    status.className = `statusbar status-${headline.level}`;
    // "Einzelne freie Tage" and the block days once: their chips (with who and when) replace the figures
    const chipFor = (id) => canEdit && !state.solving && problemConstraints(analysis?.constraints).some((c) => c.id === id);
    const singleDaysChip = chipFor('isolatedDayOff');
    const blockDaysChip = chipFor('blockLength');
    const focused = status.contains(document.activeElement) ? document.activeElement : null;
    status.replaceChildren();
    append(status, [
      h('div.with-info', {}, h('p.plan-headline', { class: `headline-${headline.level}` }, headline.text),
        help('help.plan.headline', t('help.plan.headlineLabel'))),
      !state.solving && chips(),
      h('div.status-details', {},
        h('span', {}, t('roster.statusLabel'), ' ', h('strong', {}, plan.assignments.length || state.solving || closed() ? printStatus(plan.status) : t('roster.status.noPlan')),
          help('help.plan.status', t('roster.statusLabel'))),
        plan.status === 'FAILED' && h('span.badge.badge-hard', {}, t('roster.failed')),
        // these figures are no errors: said next to each
        figures?.overstaffed > 0 && h('span', {}, t('roster.overstaffed'), ' ', t('roster.personHours', { hours: fmt.num(figures.overstaffed, 1) }),
          ' ', h('span.muted', {}, t('roster.noError')), help('help.plan.overstaffed', t('roster.overstaffed'))),
        analysis?.singleDaysOff != null && !singleDaysChip && h('span', {}, t('roster.singleDaysOff', { count: analysis.singleDaysOff }),
          ' ', h('span.muted', {}, t('roster.qualityNoError')), info(t('help.plan.singleDaysOff'), t('roster.singleDaysOff', { count: analysis.singleDaysOff }))),
        analysis?.blockDays != null && !blockDaysChip && h('span', {}, t('roster.blockDays', { count: analysis.blockDays }), ' ', h('span.muted', {}, t('roster.qualityNoError')),
          info(t('help.plan.blockDays'), t('roster.blockDays', { count: analysis.blockDays }))),
        canEdit && state.solving && h('span', {}, t('roster.lockedWhileSolving')),
        closed() && h('span', {}, canEdit ? t('roster.closedHint') : t('common.closedReadonly')),
        // the plan no longer matches its inputs or the previous month: plan again (the reasons as tooltip)
        staleReasons.length > 0 && h('span.badge.badge-warn', { title: staleReasons.join(' ') }, t('closeMonth.stale'))),
    ]);
    if (focused?.isConnected && document.activeElement !== focused) focused.focus();
    if (canEdit) {
      btnSolve.disabled = state.solving || closed() || !dataset.people.length;
      btnStop.disabled = !state.solving;
      btnUnpin.disabled = state.solving || closed() || !plan.assignments.length;
      btnPin.disabled = btnUnpin.disabled;
    }
    print.querySelector('.print-header')?.replaceChildren(printHeader(title, plan.status));
  }

  /** Chips of the rules to look at (hard rules with matches, soft rules with a high share); a click lists who and when. */
  function chips() {
    // viewers get no list behind a chip, so no chips
    if (!canEdit) return null;
    const list = problemConstraints(analysis?.constraints);
    if (!list.length) return null;
    // the quality chips are no errors: a label before the first one says so
    const firstSoft = list.find((c) => c.level !== 'HARD');
    return h('div.chips', { role: 'group', 'aria-label': t('roster.problems') }, list.map((c) => {
      const label = constraintLabel(c);
      const count = chipCount(c);
      const info = { label, count };
      const name = count == null ? t('roster.chipCompare', info) : c.level === 'HARD' ? t('roster.chipHard', info) : t('roster.chipSoft', info);
      return [c === firstSoft && h('span.chips-label.muted', {}, t('roster.chipsQuality')),
        h('button.chip', { type: 'button', class: c.level === 'HARD' ? 'chip-hard' : null, 'aria-haspopup': 'dialog',
          'aria-label': name, onclick: (e) => openHits(e.currentTarget, c, label) },
        label, count != null && [' ', h('span.chip-count', {}, String(count))])];
    }), info(t('help.plan.chips'), t('roster.problems')));
  }

  /**
   * Who and when for one rule: the penalties of that rule among the hits of the plan (the analysis has only totals
   * per rule). Loaded once per rule and analysis; viewers get only the hits the server shows them.
   */
  function openHits(anchor, c, label) {
    const box = h('div.hit-list', {}, h('p.muted', {}, t('common.loading')));
    const el = popover.open(anchor, label, box);
    // the list loads later: focus the dialog so that Escape works meanwhile
    el.tabIndex = -1;
    el.focus();
    if (ruleHits?.analysis !== analysis) ruleHits = { analysis, lists: new Map() };
    const { lists } = ruleHits;
    if (!lists.has(c.id)) lists.set(c.id, loadHits(c.id));
    const loading = lists.get(c.id);
    loading.then((rows) => {
      // a failed request is tried again at the next click
      if (!rows && lists.get(c.id) === loading) lists.delete(c.id);
      if (!box.isConnected) return;
      box.replaceChildren();
      append(box, [
        !rows ? h('p.alert.alert-error', {}, t('roster.chipFailed'))
        : rows.length
          ? h('ul', {}, rows.map(({ person, hit }) => h('li', {},
            h('button', { type: 'button', onclick: () => ctx.navigate(`person/${encodeURIComponent(person.id)}`) },
              h('span.candidate-name', {}, personName(person)), h('span', {}, hit.date ? dayLabel(hit.date) : t('roster.chipMonth')),
              h('span.hit-text.muted', {}, hitText(hit, areaName))))))
          : h('p.muted', {}, t('roster.chipNone'))]);
    });
  }

  /** The penalties of one rule with their person, sorted by name; null when the request failed. */
  async function loadHits(id) {
    const groups = await api.hits(month, id).catch(() => null);
    if (!groups) return null;
    const people = new Map(dataset.people.map((p) => [p.id, p]));
    return (groups[id] ?? []).filter((hit) => hit.score < 0 && people.has(hit.personId))
      .map((hit) => ({ person: people.get(hit.personId), hit }))
      .sort((a, b) => personName(a.person).localeCompare(personName(b.person), 'de'));
  }

  function drawTable() {
    const active = document.activeElement?.dataset?.cell ?? null;
    closePopover();
    const grid = buildGrid({ dataset, period, plan: state.plan, month, areaId: areaId || null, search, onRequest });
    const gapDays = new Map();
    for (const g of analysis?.gaps ?? []) {
      if (areaId && g.areaId !== areaId) continue;
      const d = g.from.slice(0, 10);
      gapDays.set(d, (gapDays.get(d) ?? 0) + 1);
    }
    const head = h('tr', {},
      h('th.person', { scope: 'col' }, t('roster.person')),
      grid.days.map(dayHeader),
      h('th', { scope: 'col' }, t('roster.hoursShort')));
    // the gap row first: it sticks below the day header while the grid scrolls
    const body = [h('tr.gap-row.sticky-gaps', { 'data-row': '0' },
      h('th.person', { scope: 'row' }, t('roster.gapsRow')),
      grid.days.map((d, i) => h('td.cell', { class: dayClass(d) }, gapCell(d, gapDays.get(d.date), i))),
      h('td.sum', {}, ''))];
    let group = null;
    let rowIndex = 1;
    for (const row of grid.rows) {
      const first = row.person.areaIds[0];
      if (!areaId && first !== group) {
        group = first;
        body.push(h('tr.group', {}, h('th', { colspan: grid.days.length + 2, scope: 'colgroup' }, areaName.get(first) ?? first)));
      }
      const r = rowIndex++;
      body.push(h('tr', { 'data-row': String(r) },
        h('th.person', { scope: 'row' },
          h('a', { href: `#/person/${encodeURIComponent(row.person.id)}` }, personLabel(row.person, row.name, nameMode)),
          ' ', h('span.meta', {}, personMeta(row.person))),
        row.cells.map((c, i) => h('td.cell', { class: dayClass(grid.days[i]) }, cellContent(row, c, grid.days[i], r, i))),
        h('td.sum', {}, fmt.num(row.hours, 1))));
    }
    const tableEl = h('table.roster', { role: 'grid', 'aria-readonly': editable() ? null : 'true' }, h('caption.visually-hidden', {}, title),
      h('thead', {}, head), h('tbody', {}, body));
    table.replaceChildren(tableEl);
    sticky.watch(tableEl);
    drawJump(grid, gapDays);
    print.replaceChildren(h('p.print-header', {}, printHeader(title, state.plan.status)), legend(dataset, canEdit),
      ...printParts(grid.days).map((days) => printTable(grid, days, gapDays)));
    // one tab stop: the last used cell, else the first; keep keyboard focus on the same cell after a redraw
    const key = focusKey ?? active;
    focusKey = null;
    nav.restore(key);
  }

  /** The link to the first conflict cell of the shown rows, else to the first day with a gap; none without either. */
  function drawJump(grid, gapDays) {
    let key = null;
    for (const row of grid.rows) {
      const i = row.cells.findIndex((c) => c.conflict);
      if (i >= 0 && (!key || grid.days[i].date < key.split('|')[1])) key = `${row.person.id}|${grid.days[i].date}`;
    }
    const gapDay = !key && grid.days.find((d) => gapDays.get(d.date));
    if (gapDay) key = `gaps|${gapDay.date}`;
    jump.replaceChildren();
    if (!key) return;
    jump.append(h('button.btn-link', { type: 'button', onclick: () => {
      const cell = table.querySelector(`[data-cell="${CSS.escape(key)}"]`);
      cell?.scrollIntoView({ block: 'center', inline: 'center' });
      cell?.focus({ preventScroll: true });
    } }, gapDay ? t('roster.jumpGap') : t('roster.jumpConflict')));
  }

  /** Meta text after the name: with the full name the short name, then the weekly hours. */
  function personMeta(p) {
    const short = nameMode === 'full' ? (p.shortName ?? '') : '';
    return `${short}${short ? ' · ' : ''}${fmt.num(p.weeklyHours)} h`;
  }

  /** Plain table of some days for print (no buttons): the same rows, groups and gap counts as on screen. */
  function printTable(grid, days, gapDays) {
    const from = grid.days.indexOf(days[0]);
    const body = [];
    let group = null;
    for (const row of grid.rows) {
      const first = row.person.areaIds[0];
      if (!areaId && first !== group) {
        group = first;
        body.push(h('tr.group', {}, h('th', { colspan: days.length + 2, scope: 'colgroup' }, areaName.get(first) ?? first)));
      }
      body.push(h('tr', {},
        h('th.person', { scope: 'row' }, personLabel(row.person, row.name, nameMode), ' ', h('span.meta', {}, personMeta(row.person))),
        days.map((d, i) => {
          const cell = row.cells[from + i];
          const style = cell.color ? tintStyle(cell.color) : null;
          const cls = [style && 'tinted', cell.kind === 'absence' && 'absence strong', cell.kind === 'compRest' && 'comp-rest',
            cell.pinned && 'pinned', cell.conflict && 'strong hard', cell.onRequest && 'on-request'].filter(Boolean).join(' ');
          // a free day that was pinned keeps its frame, as on screen
          return h('td.cell', { class: dayClass(d) },
            cell.code || cell.pinned ? h('span.cell-code', { class: cls || null, style }, cell.code || '·') : '');
        }),
        h('td.sum', {}, fmt.num(row.hours, 1))));
    }
    body.push(h('tr', {}, h('th.person', { scope: 'row' }, t('roster.gapsRow')),
      days.map((d) => h('td.cell', { class: dayClass(d) }, gapDays.get(d.date) ? h('strong.text-danger', {}, String(gapDays.get(d.date))) : '')),
      h('td.sum', {}, '')));
    return h('table.roster.plan-print', {},
      h('caption', {}, `${title}, ${t('functions.printPart', { from: days[0].day, to: days.at(-1).day })}`),
      h('thead', {}, h('tr', {}, h('th.person', { scope: 'col' }, t('roster.person')), days.map(dayHeader), h('th', { scope: 'col' }, t('roster.hoursShort')))),
      h('tbody', {}, body));
  }

  /** Gap count of a day, row 0 of the arrow key grid; planners open the day's gaps with who could be asked. */
  function gapCell(d, count, c) {
    const open = editable() && count > 0;
    const label = open ? t('roster.gapButton', { count, day: dayLabel(d.date) }) : t('roster.gapDay', { count: count ?? 0, day: dayLabel(d.date) });
    // always focusable for arrow navigation; read-only cells do nothing on Enter or click
    return h('button', { type: 'button', class: count ? 'cell-btn gap-btn' : 'cell-btn', title: count ? t('roster.gapCount', { count }) : null,
      'aria-label': label, 'aria-haspopup': open ? 'dialog' : null, 'aria-disabled': open ? null : 'true', tabindex: '-1',
      'data-cell': `gaps|${d.date}`, 'data-r': '0', 'data-c': String(c),
      onclick: (e) => { if (editable() && count) openGaps(e.currentTarget, d.date); } }, count ? String(count) : '');
  }

  function cellContent(row, cell, day, r, c) {
    const style = cell.color ? tintStyle(cell.color) : null;
    const info = { name: row.name, day: dayLabel(day.date), label: cell.onRequest ? t('grid.onRequestLabel', { label: cell.label }) : cell.label };
    const title = cell.pinned ? t('roster.cellTitlePinned', info) : t('roster.cellTitle', info);
    const inner = h('span.cell-code', { class: style ? 'tinted' : null, style }, cell.code || '·');
    const cls = `cell-btn cell-${cell.kind}${cell.pinned ? ' cell-pinned' : ''}${cell.conflict ? ' cell-hard' : ''}${cell.onRequest ? ' cell-on-request' : ''}`;
    // always focusable for arrow navigation; read-only cells do nothing on Enter or click
    const open = editable();
    return h('button', { type: 'button', class: cls, title, 'aria-label': title, 'aria-haspopup': open ? 'dialog' : null,
      'aria-disabled': open ? null : 'true', tabindex: '-1', 'data-cell': `${row.person.id}|${day.date}`, 'data-r': String(r), 'data-c': String(c),
      onclick: (e) => { if (editable()) openPopover(e.currentTarget, row, cell, day); } }, inner);
  }

  function openPopover(anchor, row, cell, day) {
    const pick = (opt, pinned) => async () => {
      if (cell.kind === 'absence' && !cell.conflict) return;
      if (!editable()) return closePopover();
      const res = await guarded(() => api.setCell(month, cellRequest(row.person.id, day.date, opt, pinned)));
      if (res) await afterSet(res, `${row.person.id}|${day.date}`);
    };
    const title = t('roster.cellDialog', { name: row.name, day: dayLabel(day.date) });
    popover.open(anchor, title, cellChoice({ cell, person: row.person, shifts: dataset.shifts, pick }));
  }

  /** The gaps of a day (in the chosen area), each with the list of who could be asked. */
  function openGaps(anchor, date) {
    const gaps = (analysis?.gaps ?? []).filter((g) => (!areaId || g.areaId === areaId) && g.from.slice(0, 10) === date);
    const el = popover.open(anchor, t('roster.gapDialog', { day: dayLabel(date) }), gapsWithCandidates({ api, month, gaps, dataset,
      onSet: (plan, req) => afterSet(plan, `${req.personId}|${req.date}`) }));
    // the names load later: focus the dialog so that Escape works meanwhile
    el.tabIndex = -1;
    el.focus();
  }

  /** A cell was set: new plan and analysis, keyboard focus on that cell. */
  async function afterSet(plan, key) {
    if (disposed) return;
    state = { ...state, plan };
    analysis = await api.analysis(month).catch(() => analysis);
    focusKey = key;
    drawStatus();
    drawTable();
  }

  function closePopover() {
    popover.close();
  }

  async function poll() {
    timer = null;
    if (polling || disposed) return;
    polling = true;
    let next;
    try {
      next = await api.planState(month);
      pollFailures = 0;
    } catch (e) {
      // keep polling through short outages, slower each time
      pollFailures++;
      if (pollFailures === 3) toast(t('roster.pollFailed', { message: e.message }), true);
    } finally {
      polling = false;
    }
    if (disposed) return;
    if (!next) {
      timer = setTimeout(poll, POLL_INTERVAL_MS * Math.min(pollFailures + 1, 5));
      return;
    }
    const finished = state.solving && !next.solving;
    const changed = next.plan.updated !== state.plan.updated || next.plan.status !== state.plan.status
      || next.plan.score !== state.plan.score;
    state = next;
    if (finished) {
      analysis = await api.analysis(month).catch(() => null);
      toast(t('roster.finished'));
    }
    drawStatus();
    if (changed || finished) drawTable();
    if (state.solving) timer = setTimeout(poll, POLL_INTERVAL_MS);
  }

  async function solve() {
    const body = solveOptions({ seed: seedInput.value, timeLimit: limitInput.value, moveLimit: moveLimitInput.value,
      coldStart: coldStartInput.checked });
    const res = await guarded(() => api.solve(month, body), t('roster.started'));
    if (res) {
      state = res;
      analysis = null;
      drawStatus();
      drawTable();
      clearTimeout(timer);
      timer = setTimeout(poll, POLL_INTERVAL_MS);
    }
  }

  async function stop() {
    await guarded(() => api.stop(month), t('roster.stopping'));
    clearTimeout(timer);
    timer = setTimeout(poll, 0);
  }

  async function pinAll() {
    const plan = await guarded(() => api.pin(month), t('roster.pinnedAll'));
    if (plan) {
      state = { ...state, plan };
      drawTable();
    }
  }

  async function unpin() {
    const plan = await guarded(() => api.unpin(month), t('roster.unpinned'));
    if (plan) {
      state = { ...state, plan };
      drawTable();
    }
  }

  const stopPrintTheme = lightThemeWhilePrinting(() => print.querySelector('.print-header')
    ?.replaceChildren(printHeader(title, state.plan.status)));
  drawTable();
  drawStatus();
  if (state.solving) timer = setTimeout(poll, POLL_INTERVAL_MS);
  return () => {
    disposed = true;
    stopPrintTheme();
    sticky.stop();
    clearTimeout(timer);
    timer = null;
    closePopover();
  };
}

/** Request body for POST .../solve from the form values; empty fields are left to the server's defaults. */
export function solveOptions({ seed = '', timeLimit = '', moveLimit = '', coldStart = false }) {
  const body = {};
  if (timeLimit !== '' && Number(timeLimit) > 0) body.timeLimitSeconds = Number(timeLimit);
  if (seed !== '') body.seed = Number(seed);
  if (moveLimit !== '' && Number(moveLimit) > 0) body.moveLimit = Number(moveLimit);
  if (coldStart) body.coldStart = true;
  return body;
}

function legend(dataset, canEdit) {
  return h('div.legend', { 'aria-label': t('roster.legend') },
    h('span', {}, h('span.swatch.weekend', {}), t('roster.legend.weekend')),
    h('span', {}, h('span.swatch.holiday', {}), t('roster.legend.holiday')),
    h('span', {}, h('span.cell-code.pinned', {}, 'A1'), ' ', t('roster.legend.pinned')),
    h('span', {}, h('span.cell-code.comp-rest', {}, t('grid.compRestCode')), ' ', t('grid.compRest')),
    h('span', {}, h('span.cell-code.strong.hard', {}, 'K/A1'), ' ', t('roster.legend.conflict')),
    canEdit && h('span', {}, h('span.cell-code.on-request', {}, 'A1'), ' ', t('roster.legend.onRequest')),
    !canEdit && h('span', {}, h('span.cell-code.strong.absence.outlined', {}, NEUTRAL_ABSENCE), ` ${t('grid.absent')}`),
    // viewers see every absence as "ABW": the absence types only for planners
    canEdit && (dataset.absenceTypes ?? []).filter((type) => !type.compensatoryRest).map((type) =>
      h('span', {}, h('span.cell-code.strong.absence.tinted', { style: tintStyle(type.color) }, type.code), ` ${type.name}`)));
}
