// Plan nach Funktion: functions (a shift of an area) x days of the month, one row per position, gaps marked.
import { tintStyle } from '../lib/colors.js';
import { dayLabel, monthLabel } from '../lib/dates.js';
import { append, guarded, h } from '../lib/dom.js';
import { NAME_MODES, buildFunctionRows, personLabel, printParts } from '../lib/functions-model.js';
import { cellRequest, cellsEditable } from '../lib/grid-model.js';
import { needsRulesForCategories, onRequestTest } from '../lib/restrictions.js';
import { t } from '../lib/texts.js';
import { gapsWithCandidates } from './candidate-list.js';
import { cellChoice, createPopover, dayClass, dayHeader, rovingGrid } from './grid-shared.js';

const NAME_LABELS = { short: t('functions.names.short'), full: t('functions.names.full') };

export async function render(root, ctx) {
  const { api, month, canEdit } = ctx;
  const [dataset, period, initialState] = await Promise.all([ctx.dataset(), api.period(month), api.planState(month)]);
  let state = initialState;
  let analysis = state.plan.assignments.length && !state.solving ? await api.analysis(month).catch(() => null) : null;
  let focusKey = null;
  let disposed = false;
  const popover = createPopover('.cell-btn');
  /** Same as the roster: planners, no solve running, month not closed. */
  const editable = () => cellsEditable({ canEdit, solving: state.solving, closed: state.plan.status === 'CLOSED' });
  const rules = needsRulesForCategories(dataset) ? await api.rules().catch(() => null) : null;
  const onRequest = onRequestTest(dataset.restrictions, dataset.shifts, rules);
  const title = t('functions.title', { month: monthLabel(month) });
  const table = h('div.roster-wrap', {});
  const nav = rovingGrid(table);
  const print = h('div.print-only', {});
  // per viewer: short names or full names in the cells
  let nameMode = 'short';
  try { nameMode = NAME_MODES.includes(localStorage.getItem('functions-names')) ? localStorage.getItem('functions-names') : 'short'; } catch { /* no storage */ }
  const nameSelect = h('select', { id: 'fn-names', onchange: (e) => {
    nameMode = e.target.value;
    try { localStorage.setItem('functions-names', nameMode); } catch { /* no storage */ }
    draw();
  } }, NAME_MODES.map((m) => h('option', { value: m, selected: m === nameMode }, NAME_LABELS[m])));

  root.classList.add('function-view');
  append(root, [
    h('h1', {}, title),
    !dataset.people.length && h('p.alert', {}, t('common.noMasterdata')),
    dataset.people.length > 0 && !state.plan.assignments.length && h('p.alert', {}, t('common.noPlan')),
    state.solving && h('p.alert', {}, t('roster.lockedWhileSolving')),
    state.plan.status === 'CLOSED' && h('p.alert', {}, t('roster.closedHint')),
    h('div.toolbar', {}, h('div.field', {}, h('label', { for: 'fn-names' }, t('functions.names')), nameSelect)),
    legend(canEdit),
    table,
    print,
  ]);

  function draw() {
    const active = document.activeElement?.dataset?.cell ?? null;
    popover.close();
    const model = buildFunctionRows({ dataset, period, plan: state.plan, month, gaps: analysis?.gaps ?? [], onRequest });
    let r = 0;
    const body = model.rows.map((row) => (row.type === 'group'
      ? h('tr.group', {}, h('th', { colspan: model.days.length + 1, scope: 'colgroup' }, row.area.name))
      : positionRow(row, r++, model.days)));
    table.replaceChildren(h('table.roster.functions', { class: nameMode === 'full' ? 'full-names' : null, role: 'grid', 'aria-readonly': editable() ? null : 'true' },
      h('caption.visually-hidden', {}, title),
      h('thead', {}, h('tr', {}, h('th.person', { scope: 'col' }, t('functions.function')), model.days.map(dayHeader))),
      h('tbody', {}, body)));
    const key = focusKey ?? active;
    focusKey = null;
    nav.restore(key);
    print.replaceChildren(...printParts(model.days).map((days) => printTable(model, days)));
  }

  function rowHeader(row) {
    const s = row.shift;
    return h('th.person', { scope: 'row' },
      h('span.cell-code', s.color ? { class: 'tinted', style: tintStyle(s.color) } : { class: 'outlined' }, s.code), ' ', s.name,
      ' ', h('span.meta', {}, t('functions.position', { n: row.index + 1 })));
  }

  function positionRow(row, r, days) {
    return h('tr', { 'data-row': String(r) }, rowHeader(row),
      row.cells.map((c, i) => h('td.cell', { class: dayClass(days[i]) }, cellButton(row, c, days[i], r, i))));
  }

  function cellButton(row, c, day, r, col) {
    const open = editable() && c.kind !== 'empty';
    const shift = row.shift;
    let label;
    let cls = 'cell-btn';
    let inner = '';
    if (c.kind === 'person') {
      const cell = c.cell;
      const info = { name: c.name, day: dayLabel(day.date), label: cell.onRequest ? t('grid.onRequestLabel', { label: cell.label }) : cell.label };
      label = cell.pinned ? t('roster.cellTitlePinned', info) : t('roster.cellTitle', info);
      cls += `${cell.pinned ? ' cell-pinned' : ''}${cell.conflict ? ' cell-hard' : ''}${cell.onRequest ? ' cell-on-request' : ''}`;
      inner = h('span.cell-code', shift.color ? { class: 'tinted', style: tintStyle(shift.color) } : {}, personLabel(c.person, c.name, nameMode));
    } else if (c.kind === 'gap') {
      label = t('functions.gapTitle', { shift: shift.name, day: dayLabel(day.date) });
      cls += ' gap-marker';
      inner = h('span.cell-code', {}, t('functions.missing'));
    } else {
      label = t('functions.emptyTitle', { shift: shift.name, day: dayLabel(day.date) });
      cls += ' cell-free';
    }
    // always focusable for arrow navigation; read-only cells do nothing on Enter or click
    return h('button', { type: 'button', class: cls, title: label, 'aria-label': label, 'aria-haspopup': open ? 'dialog' : null,
      'aria-disabled': open ? null : 'true', tabindex: '-1', 'data-cell': `${shift.id}|${row.index}|${day.date}`,
      'data-r': String(r), 'data-c': String(col),
      onclick: (e) => {
        if (!editable()) return;
        if (c.kind === 'person') openCell(e.currentTarget, c, day);
        else if (c.kind === 'gap') openGap(e.currentTarget, shift, c, day);
      } }, inner);
  }

  /** The same choice as in the roster for the person's cell. */
  function openCell(anchor, c, day) {
    const { cell, person } = c;
    const pick = (opt, pinned) => async () => {
      if (cell.kind === 'absence' && !cell.conflict) return;
      if (!editable()) return popover.close();
      const res = await guarded(() => api.setCell(month, cellRequest(person.id, day.date, opt, pinned)));
      if (res) await afterSet(res, anchor.dataset.cell);
    };
    popover.open(anchor, t('roster.cellDialog', { name: c.name, day: dayLabel(day.date) }),
      cellChoice({ cell, person, shifts: dataset.shifts, pick }));
  }

  /** The gaps behind a marker, each with who could be asked. */
  function openGap(anchor, shift, c, day) {
    const el = popover.open(anchor, t('functions.gapDialog', { shift: shift.name, day: dayLabel(day.date) }),
      gapsWithCandidates({ api, month, gaps: c.gaps, dataset, onSet: (plan) => afterSet(plan, anchor.dataset.cell) }));
    // the names load later: focus the dialog so that Escape works meanwhile
    el.tabIndex = -1;
    el.focus();
  }

  /** A duty was set: new plan and analysis, keyboard focus back on the position that was clicked. */
  async function afterSet(plan, key) {
    if (disposed) return;
    state = { ...state, plan };
    analysis = await api.analysis(month).catch(() => analysis);
    focusKey = key;
    draw();
  }

  /** Plain table of some days for print (no buttons). */
  function printTable(model, days) {
    const from = model.days.indexOf(days[0]);
    const text = (c) => (c.kind === 'person'
      ? h('span.cell-code', { class: [c.cell.pinned && 'pinned', c.cell.conflict && 'hard', c.cell.onRequest && 'on-request'].filter(Boolean).join(' ') },
        personLabel(c.person, c.name, nameMode))
      : c.kind === 'gap' ? h('span.cell-code', {}, t('functions.missing')) : '');
    return h('table.roster.functions-print', {},
      h('caption', {}, `${title}, ${t('functions.printPart', { from: days[0].day, to: days.at(-1).day })}`),
      h('thead', {}, h('tr', {}, h('th.person', { scope: 'col' }, t('functions.function')), days.map(dayHeader))),
      h('tbody', {}, model.rows.map((row) => (row.type === 'group'
        ? h('tr.group', {}, h('th', { colspan: days.length + 1, scope: 'colgroup' }, row.area.name))
        : h('tr', {}, rowHeader(row), days.map((d, i) => {
          const c = row.cells[from + i];
          return h('td.cell', { class: `${dayClass(d)}${c.kind === 'gap' ? ' gap-marker' : ''}` }, text(c));
        }))))));
  }

  // printed on white paper: the light theme while printing
  let theme = null;
  const beforePrint = () => { theme = document.documentElement.dataset.theme; document.documentElement.dataset.theme = 'light'; };
  const afterPrint = () => { if (theme) document.documentElement.dataset.theme = theme; };
  window.addEventListener('beforeprint', beforePrint);
  window.addEventListener('afterprint', afterPrint);
  draw();
  return () => {
    disposed = true;
    popover.close();
    window.removeEventListener('beforeprint', beforePrint);
    window.removeEventListener('afterprint', afterPrint);
  };
}

function legend(canEdit) {
  return h('div.legend', { 'aria-label': t('roster.legend') },
    h('span', {}, h('span.swatch.weekend', {}), t('roster.legend.weekend')),
    h('span', {}, h('span.swatch.holiday', {}), t('roster.legend.holiday')),
    h('span', {}, h('span.cell-code.pinned', {}, 'ABC'), ' ', t('roster.legend.pinned')),
    h('span', {}, h('span.cell-code.strong.hard', {}, 'ABC'), ' ', t('roster.legend.conflict')),
    canEdit && h('span', {}, h('span.cell-code.on-request', {}, 'ABC'), ' ', t('roster.legend.onRequest')),
    h('span.gap-marker', {}, h('span.cell-code', {}, t('functions.missing')), ' ', t('functions.legend.missing')));
}
