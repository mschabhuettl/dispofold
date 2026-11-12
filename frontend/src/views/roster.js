// Dienstplan: people x days of the month, cell editing, solving with progress polling.
import { parseScore } from '../lib/api.js';
import { tintStyle } from '../lib/colors.js';
import { WEEKDAY_SHORT, dayLabel, monthLabel } from '../lib/dates.js';
import { append, fmt, guarded, h, toast } from '../lib/dom.js';
import { NEUTRAL_ABSENCE, buildGrid, cellOptions, cellsEditable } from '../lib/grid-model.js';
import { moveCell } from '../lib/grid-nav.js';
import { t } from '../lib/texts.js';

const POLL_INTERVAL_MS = 2000;
const STATUS = {
  EMPTY: t('roster.status.empty'),
  SOLVING: t('roster.status.solving'),
  SOLVED: t('roster.status.solved'),
  STOPPED: t('roster.status.stopped'),
  FAILED: t('roster.status.failed'),
  CLOSED: t('roster.status.closed'),
};

export async function render(root, ctx) {
  const { api, month, canEdit } = ctx;
  const [dataset, period, initialState] = await Promise.all([ctx.dataset(), api.period(month), api.planState(month)]);
  let state = initialState;
  let analysis = state.plan.assignments.length && !state.solving ? await api.analysis(month).catch(() => null) : null;
  const storedArea = sessionStorage.getItem('roster-area');
  let areaId = dataset.areas.some((a) => a.id === storedArea) ? storedArea : '';
  let search = '';
  let timer = null;
  let popover = null;
  let disposed = false;
  let polling = false;
  let pollFailures = 0;
  let focusKey = null;
  let rovingKey = null; // the one cell in the tab order
  /** Cells can be changed: planner role and no solve running (the solver owns the plan meanwhile). */
  const closed = () => state.plan.status === 'CLOSED';
  const editable = () => cellsEditable({ canEdit, solving: state.solving, closed: closed() });

  const areaName = new Map(dataset.areas.map((a) => [a.id, a.name]));
  const status = h('div.statusbar', {});
  const table = h('div.roster-wrap', {});
  // empty fields leave seed, time and move limit to the server's reproducible defaults
  const seedInput = h('input', { id: 'seed', type: 'number', min: 0, placeholder: t('roster.serverDefault') });
  const limitInput = h('input', { id: 'limit', type: 'number', min: 5, placeholder: t('roster.serverDefault') });
  const moveLimitInput = h('input', { id: 'move-limit', type: 'number', min: 1, placeholder: t('roster.serverDefault') });
  const coldStartInput = h('input', { id: 'cold-start', type: 'checkbox' });
  const btnSolve = h('button.btn.btn-primary', { type: 'button', onclick: solve }, t('roster.solve'));
  const btnStop = h('button.btn.btn-secondary', { type: 'button', onclick: stop }, t('roster.stop'));
  const btnUnpin = h('button.btn.btn-secondary', { type: 'button', onclick: unpin }, t('roster.unpinAll'));

  append(root, [
    h('h1', {}, t('roster.title', { month: monthLabel(month) })),
    h('div.toolbar', {},
      h('div.field', {}, h('label', { for: 'area' }, t('roster.area')),
        h('select', { id: 'area', onchange: (e) => { areaId = e.target.value; sessionStorage.setItem('roster-area', areaId); drawTable(); } },
          h('option', { value: '' }, t('roster.allAreas')),
          dataset.areas.map((a) => h('option', { value: a.id, selected: a.id === areaId }, a.name)))),
      h('div.field', {}, h('label', { for: 'search' }, t('roster.search')),
        h('input', { id: 'search', type: 'search', placeholder: t('roster.searchPlaceholder'), oninput: (e) => { search = e.target.value; drawTable(); } })),
      h('span.spacer', {}),
      canEdit && [
        h('div.field', {}, h('label', { for: 'seed' }, t('roster.seed')), seedInput),
        h('div.field', {}, h('label', { for: 'limit' }, t('roster.timeLimit')), limitInput),
        h('div.field', {}, h('label', { for: 'move-limit' }, t('roster.moveLimit')), moveLimitInput),
        h('label.check-inline', { title: t('roster.coldStartHelp') }, coldStartInput, ' ', t('roster.coldStart')),
        btnSolve, btnStop, btnUnpin,
      ]),
    !dataset.people.length && h('p.alert', {}, t('common.noMasterdata')),
    status,
    legend(dataset, canEdit),
    table,
  ]);

  function drawStatus() {
    const plan = state.plan;
    const score = parseScore(plan.score);
    const gaps = analysis?.gaps?.length ?? null;
    const hard = state.solving ? state.hardViolations : (analysis?.hardViolations ?? state.hardViolations);
    status.className = state.solving ? 'statusbar solving' : 'statusbar';
    status.replaceChildren();
    append(status, [
      h('span', {}, t('roster.statusLabel'), ' ', h('strong', {}, STATUS[plan.status] ?? plan.status)),
      state.solving && h('span', {}, t('roster.runtime'), ' ', h('strong', {}, t('roster.seconds', { n: Math.round(state.elapsedMillis / 1000) }))),
      h('span', {}, t('roster.score'), ' ', h('strong', {}, plan.score ?? '–')),
      h('span', { class: hard > 0 ? 'badge badge-hard' : 'badge' }, t('roster.hardViolations', { count: hard ?? '–' })),
      score.medium != null && h('span', {}, t('roster.uncovered'), ' ', h('strong', {}, t('roster.personHours', { hours: fmt.num(Math.abs(score.medium) / 60, 1) }))),
      gaps != null && h('span', { class: gaps > 0 ? 'badge badge-hard' : 'badge' }, t('roster.gaps', { count: gaps })),
      canEdit && state.solving && h('span.muted', {}, t('roster.lockedWhileSolving')),
      closed() && h('span.muted', {}, t('roster.closedHint')),
      // the plan no longer matches its inputs or the previous month: plan again
      state.stale && h('span.badge.badge-warn', { title: t('roster.staleHelp') }, t('roster.stale')),
      (state.previousMonthChanged || analysis?.previousMonthChanged)
        && h('span.badge.badge-warn', { title: t('roster.previousChangedHelp') }, t('roster.previousChanged')),
    ]);
    if (canEdit) {
      btnSolve.disabled = state.solving || closed();
      btnStop.disabled = !state.solving;
      btnUnpin.disabled = state.solving || closed() || !plan.assignments.length;
    }
  }

  function drawTable() {
    const active = document.activeElement?.dataset?.cell ?? null;
    closePopover();
    const grid = buildGrid({ dataset, period, plan: state.plan, month, areaId: areaId || null, search });
    const gapDays = new Map();
    for (const g of analysis?.gaps ?? []) {
      if (areaId && g.areaId !== areaId) continue;
      const d = g.from.slice(0, 10);
      gapDays.set(d, (gapDays.get(d) ?? 0) + 1);
    }
    const dayClass = (d) => [d.weekend && 'weekend', d.holiday && 'holiday'].filter(Boolean).join(' ');
    const head = h('tr', {},
      h('th.person', { scope: 'col' }, t('roster.person')),
      grid.days.map((d) => h('th', { scope: 'col', class: dayClass(d), title: d.holiday ?? '' },
        WEEKDAY_SHORT[d.weekday - 1], h('br', {}), String(d.day),
        // holiday also as text, not only as colour
        d.holiday && [h('span.holiday-tag', { 'aria-hidden': 'true' }, t('roster.holidayShort')),
          h('span.visually-hidden', {}, t('roster.holidayName', { name: d.holiday }))])),
      h('th', { scope: 'col' }, t('roster.hoursShort')));
    const body = [];
    let group = null;
    let rowIndex = 0;
    for (const row of grid.rows) {
      const first = row.person.areaIds[0];
      if (!areaId && first !== group) {
        group = first;
        body.push(h('tr.group', {}, h('th', { colspan: grid.days.length + 2, scope: 'colgroup' }, areaName.get(first) ?? first)));
      }
      const r = rowIndex++;
      body.push(h('tr', { 'data-row': String(r) },
        h('th.person', { scope: 'row' },
          h('a', { href: `#/person/${encodeURIComponent(row.person.id)}` }, row.name),
          ' ', h('span.meta', {}, `${row.person.shortName ?? ''} · ${fmt.num(row.person.weeklyHours)} h`)),
        row.cells.map((c, i) => h('td.cell', { class: dayClass(grid.days[i]) }, cellContent(row, c, grid.days[i], r, i))),
        h('td.sum', {}, fmt.num(row.hours, 1))));
    }
    body.push(h('tr', {},
      h('th.person', { scope: 'row' }, t('roster.gapsRow')),
      grid.days.map((d) => h('td.cell', { class: dayClass(d) },
        gapDays.get(d.date) ? h('strong.text-danger', { title: t('roster.gapCount', { count: gapDays.get(d.date) }) }, String(gapDays.get(d.date))) : '')),
      h('td.sum', {}, '')));
    table.replaceChildren(h('table.roster', { role: 'grid', 'aria-readonly': editable() ? null : 'true' }, h('caption.visually-hidden', {}, t('roster.title', { month: monthLabel(month) })),
      h('thead', {}, head), h('tbody', {}, body)));
    // one tab stop: the last used cell, else the first; keep keyboard focus on the same cell after a redraw
    const key = focusKey ?? active;
    focusKey = null;
    const cells = table.querySelectorAll('[data-cell]');
    const roving = (rovingKey && table.querySelector(`[data-cell="${CSS.escape(rovingKey)}"]`)) || cells[0];
    if (roving) roving.tabIndex = 0;
    if (key) table.querySelector(`[data-cell="${CSS.escape(key)}"]`)?.focus();
  }

  function onGridFocus(e) {
    const cell = e.target.closest?.('[data-cell]');
    if (!cell || cell.dataset.cell === rovingKey) return;
    for (const c of table.querySelectorAll('[data-cell][tabindex="0"]')) c.tabIndex = -1;
    cell.tabIndex = 0;
    rovingKey = cell.dataset.cell;
  }

  function onGridKey(e) {
    const cell = e.target.closest?.('[data-cell]');
    if (!cell) return;
    const rows = table.querySelectorAll('tr[data-row]').length;
    const cols = table.querySelector('tr[data-row]')?.querySelectorAll('[data-cell]').length ?? 0;
    const next = moveCell({ row: Number(cell.dataset.r), col: Number(cell.dataset.c) }, e.key,
      { rows, cols, ctrl: e.ctrlKey || e.metaKey });
    if (!next) return;
    e.preventDefault();
    table.querySelector(`[data-r="${next.row}"][data-c="${next.col}"]`)?.focus();
  }

  function cellContent(row, cell, day, r, c) {
    const style = cell.color ? tintStyle(cell.color) : null;
    const info = { name: row.name, day: dayLabel(day.date), label: cell.label };
    const title = cell.pinned ? t('roster.cellTitlePinned', info) : t('roster.cellTitle', info);
    const inner = h('span.cell-code', { class: style ? 'tinted' : null, style }, cell.code || '·');
    const cls = `cell-btn cell-${cell.kind}${cell.pinned ? ' cell-pinned' : ''}${cell.conflict ? ' cell-hard' : ''}`;
    // always focusable for arrow navigation; read-only cells do nothing on Enter or click
    const open = editable();
    return h('button', { type: 'button', class: cls, title, 'aria-label': title, 'aria-haspopup': open ? 'dialog' : null,
      'aria-disabled': open ? null : 'true', tabindex: '-1', 'data-cell': `${row.person.id}|${day.date}`, 'data-r': String(r), 'data-c': String(c),
      onclick: (e) => { if (editable()) openPopover(e.currentTarget, row, cell, day); } }, inner);
  }

  function openPopover(anchor, row, cell, day) {
    closePopover();
    const pick = (opt, pinned) => async () => {
      if (cell.kind === 'absence' && !cell.conflict) return;
      if (!editable()) return closePopover();
      const res = await guarded(() => api.setCell(month, { personId: row.person.id, date: day.date, shiftId: opt.shiftId, compRest: opt.compRest, pinned }));
      if (res) {
        state = { ...state, plan: res };
        analysis = await api.analysis(month).catch(() => analysis);
        focusKey = `${row.person.id}|${day.date}`;
        drawStatus();
        drawTable();
      }
    };
    const current = { shiftId: cell.shiftId, compRest: cell.kind === 'compRest' };
    const content = cell.conflict
      ? [h('p.alert.alert-error', {}, t('roster.conflict', { label: cell.label })),
        h('button.btn.btn-primary.btn-small', { type: 'button', onclick: pick({ shiftId: null, compRest: false }, false) }, t('roster.removeDuty')),
        cell.fixedInPeriod && h('p.muted', {}, t('roster.conflictFixed'))]
      : cell.kind === 'absence'
      ? [h('p', {}, t('roster.absence', { label: cell.label })), h('p.muted', {}, t('roster.absenceHint'))]
      : [
        h('ul', {}, cellOptions(row.person, dataset.shifts).map((o) => {
          const isCurrent = o.shiftId === current.shiftId && o.compRest === current.compRest;
          return h('li', {}, h('button', { type: 'button', 'aria-current': isCurrent ? 'true' : 'false', onclick: pick(o, true) },
            h('span.cell-code', o.color ? { class: 'tinted', style: tintStyle(o.color) } : { class: 'outlined' }, o.code || '·'),
            o.label));
        })),
        cell.fixedInPeriod
          ? h('p.muted', {}, t('roster.fixedFromInputs'))
          : h('button.btn.btn-secondary.btn-small', { type: 'button', onclick: pick(current, !cell.pinned) }, cell.pinned ? t('roster.unpinCell') : t('roster.pinCell')),
      ];
    popover = h('div.popover', { role: 'dialog', 'aria-label': t('roster.cellDialog', { name: row.name, day: dayLabel(day.date) }) },
      h('h3', {}, t('roster.cellDialog', { name: row.name, day: dayLabel(day.date) })), content);
    popover.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        closePopover();
        anchor.focus();
      } else if (e.key === 'Tab') {
        // keep focus inside the dialog
        const items = [...popover.querySelectorAll('button')];
        if (!items.length) return;
        const i = items.indexOf(document.activeElement);
        const next = e.shiftKey ? (i <= 0 ? items.length - 1 : i - 1) : (i + 1) % items.length;
        e.preventDefault();
        items[next].focus();
      }
    });
    document.body.append(popover);
    const r = anchor.getBoundingClientRect();
    const left = Math.min(r.left + window.scrollX, window.scrollX + document.documentElement.clientWidth - popover.offsetWidth - 8);
    popover.style.left = `${Math.max(8, left)}px`;
    popover.style.top = `${r.bottom + window.scrollY + 4}px`;
    (popover.querySelector('button[aria-current="true"]') ?? popover.querySelector('button'))?.focus();
  }

  function closePopover() {
    popover?.remove();
    popover = null;
  }

  function onDocClick(e) {
    if (popover && !popover.contains(e.target) && !e.target.closest('.cell-btn')) closePopover();
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

  async function unpin() {
    const plan = await guarded(() => api.unpin(month), t('roster.unpinned'));
    if (plan) {
      state = { ...state, plan };
      drawTable();
    }
  }

  drawStatus();
  drawTable();
  table.addEventListener('keydown', onGridKey);
  table.addEventListener('focusin', onGridFocus);
  document.addEventListener('click', onDocClick, true);
  if (state.solving) timer = setTimeout(poll, POLL_INTERVAL_MS);
  return () => {
    disposed = true;
    clearTimeout(timer);
    timer = null;
    closePopover();
    document.removeEventListener('click', onDocClick, true);
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
    !canEdit && h('span', {}, h('span.cell-code.strong.absence.outlined', {}, NEUTRAL_ABSENCE), ` ${t('grid.absent')}`),
    (dataset.absenceTypes ?? []).filter((type) => !type.compensatoryRest).map((type) =>
      h('span', {}, h('span.cell-code.strong.absence.tinted', { style: tintStyle(type.color) }, type.code), ` ${type.name}`)));
}
