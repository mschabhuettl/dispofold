// Personen → Bereiche: a matrix of people (rows) and areas (columns), each cell the state of the area for the person:
// planbar, nur von Hand, nie, or not one of the person's areas. Planners change a cell with a click or the space key
// (planbar → nur von Hand → nie → leer → planbar), move with the arrow keys, set one state for selected rows and columns
// at once, and save all changes in one go (PUT /api/dataset/area-states), as the person page saves one person. A small
// mark shows exceptions of the person in the area; the source of the area shows on hover and in the line below the
// table for the focused cell. Viewers see the matrix read only.
import { exceptionLines } from '../lib/area-states.js';
import { h, toast } from '../lib/dom.js';
import { personName } from '../lib/grid-model.js';
import { t } from '../lib/texts.js';

const CYCLE = ['PLAN', 'ON_REQUEST', 'NEVER', ''];
const MARK = { PLAN: '●', ON_REQUEST: '◐', NEVER: '✕', '': '·' };
const word = (state) => ({ PLAN: t('areaState.plannable'), ON_REQUEST: t('areaState.manual'), NEVER: t('areaState.never'), '': t('areaState.out') })[state];
const CATEGORY = { EARLY: t('masterdata.category.early'), DAY: t('masterdata.category.day'), LATE: t('masterdata.category.late'), NIGHT: t('masterdata.category.night') };

/** The state of an area for a person as the cell shows it. */
export const cellState = (person, areaId) => (!(person.areaIds ?? []).includes(areaId) ? '' : person.areaStates?.[areaId] ?? 'PLAN');

/** The next state of a click. */
export const nextState = (state) => CYCLE[(CYCLE.indexOf(state) + 1) % CYCLE.length];

/** The row to save for a person from the states of its cells, in the order of the areas. */
export function rowOf(personId, areas, states) {
  const areaIds = areas.map((a) => a.id).filter((a) => states[a]);
  return { personId, areaIds, areaStates: Object.fromEntries(areaIds.filter((a) => states[a] !== 'PLAN').map((a) => [a, states[a]])) };
}

export async function render(root, ctx) {
  const { api } = ctx;
  const dataset = await ctx.dataset();
  const canEdit = ctx.canEdit;
  const areas = dataset.areas ?? [];
  const people = [...(dataset.people ?? [])].sort((a, b) => personName(a).localeCompare(personName(b), 'de'));
  const shifts = new Map((dataset.shifts ?? []).map((s) => [s.id, s]));
  // per person the current state of each area; the changes against the read ones
  const states = new Map(people.map((p) => [p.id, Object.fromEntries(areas.map((a) => [a.id, cellState(p, a.id)]))]));
  const read = new Map([...states].map(([id, s]) => [id, { ...s }]));
  const changed = () => people.filter((p) => areas.some((a) => states.get(p.id)[a.id] !== read.get(p.id)[a.id]));
  const exceptions = new Map(people.map((p) => [p.id, exceptionLines(dataset.exceptions ?? [], p.id, shifts, CATEGORY, areas.map((a) => a.id))]));

  root.append(h('h1', {}, t('areaMatrix.title')));
  if (!people.length || !areas.length) {
    root.append(h('p.muted', {}, t('areaMatrix.empty')));
    return undefined;
  }
  const status = h('p.muted', { id: 'area-matrix-status', 'aria-live': 'polite' }, t('areaMatrix.statusHint'));
  const describe = (p, a) => {
    const state = states.get(p.id)[a.id];
    const source = state && p.areaSources ? p.areaSources[a.id] || t('people.area.byHand') : null;
    const own = (exceptions.get(p.id) ?? []).filter((l) => l.areaId === a.id).map((l) => l.text);
    return [`${personName(p)}, ${a.name}: ${word(state)}`, source && t('people.area.source', { source }),
      own.length && t('areaMatrix.exceptions', { list: own.join('; ') })].filter(Boolean).join('. ');
  };
  const cellContent = (p, a) => {
    const state = states.get(p.id)[a.id];
    const marked = (exceptions.get(p.id) ?? []).some((l) => l.areaId === a.id) && state;
    return [h('span', { 'aria-hidden': 'true' }, MARK[state]), ` ${word(state)}`, marked ? h('sup.area-mark', { title: t('areaMatrix.hasExceptions') }, '*') : ''];
  };
  const cellAttrs = (p, a, i, j) => ({ 'data-person': p.id, 'data-area': a.id, 'data-row': i, 'data-col': j, title: describe(p, a),
    class: `area-cell state-${states.get(p.id)[a.id] || 'OUT'}` });

  // ---- filters ----
  const nameFilter = h('input', { id: 'area-matrix-name', type: 'search', autocomplete: 'off' });
  const areaFilter = h('select', { id: 'area-matrix-area' }, h('option', { value: '' }, t('areaMatrix.allAreas')),
    areas.map((a) => h('option', { value: a.id }, `${a.name} (${a.id})`)));
  const stateFilter = h('select', { id: 'area-matrix-state' }, h('option', { value: '' }, t('areaMatrix.allStates')),
    ['PLAN', 'ON_REQUEST', 'NEVER', ''].map((s) => h('option', { value: s || 'OUT' }, word(s))));
  const visible = (p) => {
    const q = nameFilter.value.trim().toLocaleLowerCase('de');
    if (q && !`${personName(p)} ${p.shortName ?? ''} ${p.id}`.toLocaleLowerCase('de').includes(q)) return false;
    const wanted = stateFilter.value === 'OUT' ? '' : stateFilter.value;
    const cols = areaFilter.value ? [areaFilter.value] : areas.map((a) => a.id);
    if (areaFilter.value && !stateFilter.value && !states.get(p.id)[areaFilter.value]) return false;
    return !stateFilter.value || cols.some((a) => states.get(p.id)[a] === wanted);
  };

  // ---- table ----
  const rowBoxes = new Map();
  const colBoxes = new Map();
  const head = h('tr', {}, h('th', { scope: 'col' }, canEdit ? h('input', { type: 'checkbox', id: 'area-matrix-all-rows', 'aria-label': t('areaMatrix.allRows'),
    onchange: (e) => { for (const [id, box] of rowBoxes) if (!box.closest('tr').hidden) box.checked = e.target.checked; updateBulk(); } }) : '', ' ', t('areaMatrix.person')),
  areas.map((a) => h('th', { scope: 'col', 'data-col-area': a.id, title: a.name }, canEdit ? h('label', {}, (() => {
    const box = h('input', { type: 'checkbox', 'aria-label': t('areaMatrix.column', { area: a.name }), onchange: updateBulk });
    colBoxes.set(a.id, box);
    return box;
  })(), ` ${a.id}`) : a.id)));
  const body = h('tbody', {});
  const rows = people.map((p, i) => {
    const box = canEdit ? h('input', { type: 'checkbox', 'aria-label': t('areaMatrix.row', { name: personName(p) }), onchange: updateBulk }) : null;
    if (box) rowBoxes.set(p.id, box);
    const tr = h('tr', { 'data-person-row': p.id }, h('th', { scope: 'row' }, box ?? '', box ? ' ' : '', h('a', { href: `#/person/${encodeURIComponent(p.id)}` }, personName(p))),
      areas.map((a, j) => (canEdit
        ? h('td', {}, h('button', { type: 'button', tabindex: i === 0 && j === 0 ? '0' : '-1', ...cellAttrs(p, a, i, j) }, cellContent(p, a)))
        : h('td', cellAttrs(p, a, i, j), cellContent(p, a)))));
    return tr;
  });
  body.append(...rows);
  const table = h('table.data.area-matrix', { 'aria-label': t('areaMatrix.title') }, h('thead', {}, head), body);

  /** Shows the state of a cell anew after a change. */
  const redraw = (cell) => {
    const p = people.find((x) => x.id === cell.dataset.person);
    const a = areas.find((x) => x.id === cell.dataset.area);
    cell.className = `area-cell state-${states.get(p.id)[a.id] || 'OUT'}`;
    cell.title = describe(p, a);
    cell.replaceChildren(...cellContent(p, a));
    if (document.activeElement === cell) status.textContent = describe(p, a);
  };
  const set = (cell, state) => {
    states.get(cell.dataset.person)[cell.dataset.area] = state;
    redraw(cell);
  };
  const cellAt = (i, j) => table.querySelector(`button[data-row="${i}"][data-col="${j}"]`);
  const focusCell = (cell) => {
    if (!cell) return;
    table.querySelectorAll('button[data-row][tabindex="0"]').forEach((b) => { b.tabIndex = -1; });
    cell.tabIndex = 0;
    cell.focus();
  };
  if (canEdit) {
    table.addEventListener('click', (e) => {
      const cell = e.target.closest('button[data-row]');
      if (!cell) return;
      set(cell, nextState(states.get(cell.dataset.person)[cell.dataset.area]));
      focusCell(cell);
      markDirty();
    });
    table.addEventListener('keydown', (e) => {
      const cell = e.target.closest('button[data-row]');
      if (!cell) return;
      const i = Number(cell.dataset.row);
      const j = Number(cell.dataset.col);
      const shownRows = rows.map((r, k) => (r.hidden ? null : k)).filter((k) => k != null);
      const at = shownRows.indexOf(i);
      const move = { ArrowRight: [0, 1], ArrowLeft: [0, -1], ArrowDown: [1, 0], ArrowUp: [-1, 0] }[e.key];
      if (move) {
        e.preventDefault();
        const row = shownRows[Math.min(shownRows.length - 1, Math.max(0, at + move[0]))];
        focusCell(cellAt(row, Math.min(areas.length - 1, Math.max(0, j + move[1]))));
      } else if (e.key === ' ' || e.key === 'Enter') {
        e.preventDefault();
        cell.click();
      }
    });
  }
  table.addEventListener('focusin', (e) => {
    const cell = e.target.closest('[data-person][data-area]');
    if (!cell) return;
    status.textContent = describe(people.find((x) => x.id === cell.dataset.person), areas.find((x) => x.id === cell.dataset.area));
  });

  // ---- filters apply ----
  const count = h('p.muted', { id: 'area-matrix-count' });
  const apply = () => {
    let shown = 0;
    rows.forEach((tr, i) => {
      tr.hidden = !visible(people[i]);
      if (!tr.hidden) shown += 1;
    });
    // the area filter also narrows the columns
    table.querySelectorAll('[data-col-area], [data-area]').forEach((el) => {
      const a = el.dataset.colArea ?? el.dataset.area;
      const cell = el.closest('td') ?? el;
      cell.hidden = Boolean(areaFilter.value) && a !== areaFilter.value;
    });
    count.textContent = t('areaMatrix.count', { shown, total: people.length });
  };
  nameFilter.addEventListener('input', apply);
  areaFilter.addEventListener('change', apply);
  stateFilter.addEventListener('change', apply);

  // ---- bulk and save ----
  const bulkState = h('select', { id: 'area-matrix-bulk-state' }, ['PLAN', 'ON_REQUEST', 'NEVER', ''].map((s) => h('option', { value: s || 'OUT' }, word(s))));
  const bulkButton = h('button.btn.btn-secondary', { type: 'button', id: 'area-matrix-bulk', disabled: true });
  function updateBulk() {
    const r = [...rowBoxes].filter(([, b]) => b.checked && !b.closest('tr').hidden).length;
    const c = [...colBoxes].filter(([, b]) => b.checked).length;
    const cells = (r || rows.filter((tr) => !tr.hidden).length) * (c || areas.length);
    bulkButton.disabled = !r && !c;
    bulkButton.textContent = t('areaMatrix.bulk', { count: r || c ? cells : 0 });
  }
  bulkButton.addEventListener('click', () => {
    const value = bulkState.value === 'OUT' ? '' : bulkState.value;
    const pickedRows = [...rowBoxes].filter(([, b]) => b.checked && !b.closest('tr').hidden).map(([id]) => id);
    const targetRows = pickedRows.length ? pickedRows : people.filter((p, i) => !rows[i].hidden).map((p) => p.id);
    const pickedCols = [...colBoxes].filter(([, b]) => b.checked).map(([id]) => id);
    const targetCols = pickedCols.length ? pickedCols : areas.map((a) => a.id);
    for (const pid of targetRows) {
      for (const aid of targetCols) {
        const cell = table.querySelector(`button[data-person="${CSS.escape(pid)}"][data-area="${CSS.escape(aid)}"]`);
        if (cell) set(cell, value);
      }
    }
    markDirty();
  });
  const save = h('button.btn.btn-primary', { type: 'button', id: 'area-matrix-save', disabled: true }, t('common.save'));
  const dirtyNote = h('span.badge.badge-warn', { id: 'area-matrix-dirty', hidden: true }, t('areaMatrix.unsaved'));
  function markDirty() {
    const n = changed().length;
    save.disabled = !n;
    dirtyNote.hidden = !n;
    dirtyNote.textContent = t('areaMatrix.unsaved', { count: n });
  }
  ctx.isDirty = () => changed().length > 0;
  save.addEventListener('click', async () => {
    const list = changed().map((p) => rowOf(p.id, areas, states.get(p.id)));
    save.disabled = true;
    try {
      await api.saveAreaStates(list, dataset.revision ?? 0);
    } catch (e) {
      toast(e.code === 'masterdata.conflict' ? t('areaMatrix.conflict') : e.message, true);
      save.disabled = false;
      return;
    }
    toast(t('areaMatrix.saved', { count: list.length }));
    ctx.isDirty = null;
    ctx.invalidateDataset();
    root.replaceChildren();
    await render(root, ctx);
  });

  root.append(
    h('p.muted', {}, canEdit ? t('areaMatrix.intro') : t('areaMatrix.introViewer')),
    h('div.toolbar', {}, h('div.field', {}, h('label', { for: 'area-matrix-name' }, t('areaMatrix.filterName')), nameFilter),
      h('div.field', {}, h('label', { for: 'area-matrix-area' }, t('areaMatrix.filterArea')), areaFilter),
      h('div.field', {}, h('label', { for: 'area-matrix-state' }, t('areaMatrix.filterState')), stateFilter)),
    canEdit ? h('div.toolbar', {}, h('div.field', {}, h('label', { for: 'area-matrix-bulk-state' }, t('areaMatrix.bulkState')), bulkState), bulkButton, save, dirtyNote) : '',
    count, h('div.area-matrix-wrap', {}, table), status);
  apply();
  updateBulk();
  return () => { ctx.isDirty = null; };
}
