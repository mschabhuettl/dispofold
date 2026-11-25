// Personen → Bereiche: a matrix of people (rows) and areas (columns), each cell the state of the area for the person:
// planbar, nur von Hand, nie, or not one of the person's areas. Planners change a cell with a click or the space key
// (planbar → nur von Hand → nie → leer → planbar), move with the arrow keys, set one state for selected rows and columns
// at once, and save all changes in one go (PUT /api/dataset/area-states), as the person page saves one person. A small
// mark shows exceptions of the person in the area; the source of the area shows on hover and in the line below the
// table for the focused cell. Viewers see the matrix read only. Two more views, remembered per viewer: the matrix with
// areas as rows, and one area with its people in a group per state, where a click cycles the state as in the matrix.
import { exceptionLines } from '../lib/area-states.js';
import { h, toast } from '../lib/dom.js';
import { personName } from '../lib/grid-model.js';
import { t } from '../lib/texts.js';
import { problemAlert } from './masterdata.js';

const CYCLE = ['PLAN', 'ON_REQUEST', 'NEVER', ''];
const VIEWS = ['people', 'areas', 'area'];
const VIEW_KEY = 'area-matrix-view';
const PICK_KEY = 'area-matrix-pick';
const MARK = { PLAN: '●', ON_REQUEST: '◐', NEVER: '✕', '': '·' };
const word = (state) => ({ PLAN: t('areaState.plannable'), ON_REQUEST: t('areaState.manual'), NEVER: t('areaState.never'), '': t('areaState.out') })[state];
const CATEGORY = { EARLY: t('masterdata.category.early'), DAY: t('masterdata.category.day'), LATE: t('masterdata.category.late'), NIGHT: t('masterdata.category.night') };

/** The state of an area for a person as the cell shows it. */
export const cellState = (person, areaId) => (!(person.areaIds ?? []).includes(areaId) ? '' : person.areaStates?.[areaId] ?? 'PLAN');

/** The next state of a click; an area without shifts can only be planbar or out (a state is held by its shifts). */
export const nextState = (state, shiftless = false) => (shiftless ? (state ? '' : 'PLAN') : CYCLE[(CYCLE.indexOf(state) + 1) % CYCLE.length]);

/**
 * The row to save for a person from the states of its cells: its areas in the order it had them (the first is its
 * row in the plan), new ones after them in the order of the areas.
 */
export function rowOf(personId, areas, states, before = []) {
  const chosen = areas.map((a) => a.id).filter((a) => states[a]);
  const areaIds = [...before.filter((a) => chosen.includes(a)), ...chosen.filter((a) => !before.includes(a))];
  return { personId, areaIds, areaStates: Object.fromEntries(areaIds.filter((a) => states[a] !== 'PLAN').map((a) => [a, states[a]])) };
}

/** pending: changes kept from before a conflict or a change of the view, { personId: { areaId: state } }, laid onto the newer data. */
export async function render(root, ctx, params = [], pending = null) {
  const { api } = ctx;
  const dataset = await ctx.dataset();
  const canEdit = ctx.canEdit;
  const areas = dataset.areas ?? [];
  const people = [...(dataset.people ?? [])].sort((a, b) => personName(a).localeCompare(personName(b), 'de'));
  const shifts = new Map((dataset.shifts ?? []).map((s) => [s.id, s]));
  // per person the current state of each area; the changes against the read ones
  const states = new Map(people.map((p) => [p.id, Object.fromEntries(areas.map((a) => [a.id, cellState(p, a.id)]))]));
  const read = new Map([...states].map(([id, s]) => [id, { ...s }]));
  for (const [pid, own] of Object.entries(pending ?? {})) {
    for (const [aid, state] of Object.entries(own)) if (states.has(pid) && aid in states.get(pid)) states.get(pid)[aid] = state;
  }
  const shiftless = new Set(areas.filter((a) => !(dataset.shifts ?? []).some((sh) => sh.areaId === a.id)).map((a) => a.id));
  const changed = () => people.filter((p) => areas.some((a) => states.get(p.id)[a.id] !== read.get(p.id)[a.id]));
  const exceptions = new Map(people.map((p) => [p.id, exceptionLines(dataset.exceptions ?? [], p.id, shifts, CATEGORY, areas.map((a) => a.id))]));
  // an exception of the area: of one of its shifts, or of a category (e.g. every night shift) the area has a shift of
  const categories = new Map(areas.map((a) => [a.id, new Set((dataset.shifts ?? []).filter((sh) => sh.areaId === a.id).map((sh) => sh.category))]));
  const inArea = (a) => (l) => l.areaId === a.id || (l.areaId == null && l.rows[0]?.category != null && categories.get(a.id).has(l.rows[0].category));

  root.append(h('h1', {}, t('areaMatrix.title')));
  if (!people.length || !areas.length) {
    root.append(h('p.muted', {}, t('areaMatrix.empty')));
    return undefined;
  }
  const status = h('p.muted', { id: 'area-matrix-status', 'aria-live': 'polite' }, t('areaMatrix.statusHint'));
  const describe = (p, a) => {
    const state = states.get(p.id)[a.id];
    const source = state && p.areaSources ? p.areaSources[a.id] || t('people.area.byHand') : null;
    const own = (exceptions.get(p.id) ?? []).filter(inArea(a)).map((l) => l.text);
    return [`${personName(p)}, ${a.name}: ${word(state)}`, source && t('people.area.source', { source }),
      own.length && t('areaMatrix.exceptions', { list: own.join('; ') })].filter(Boolean).join('. ');
  };
  const mark = (p, a) => ((exceptions.get(p.id) ?? []).some(inArea(a)) && states.get(p.id)[a.id] ? h('sup.area-mark', { title: t('areaMatrix.hasExceptions') }, '*') : '');
  const cellContent = (p, a) => {
    const state = states.get(p.id)[a.id];
    return [h('span', { 'aria-hidden': 'true' }, MARK[state]), ` ${word(state)}`, mark(p, a)];
  };
  const cellAttrs = (p, a, i, j) => ({ 'data-person': p.id, 'data-area': a.id, 'data-row': i, 'data-col': j, title: describe(p, a),
    class: `area-cell state-${states.get(p.id)[a.id] || 'OUT'}` });

  // ---- save ----
  const save = h('button.btn.btn-primary', { type: 'button', id: 'area-matrix-save', disabled: true }, t('common.save'));
  const dirtyNote = h('span.badge.badge-warn', { id: 'area-matrix-dirty', hidden: true }, t('areaMatrix.unsaved'));
  function markDirty() {
    const n = changed().length;
    save.disabled = !n;
    dirtyNote.hidden = !n;
    dirtyNote.textContent = t('areaMatrix.unsaved', { count: n });
  }
  ctx.isDirty = () => changed().length > 0;
  // the own changes, laid onto the data read anew
  const own = () => Object.fromEntries(changed().map((p) => [p.id, Object.fromEntries(areas.filter((a) => states.get(p.id)[a.id] !== read.get(p.id)[a.id])
    .map((a) => [a.id, states.get(p.id)[a.id]]))]));
  const problems = h('div.form-problems', { 'aria-live': 'polite' });
  save.addEventListener('click', async () => {
    const list = changed().map((p) => rowOf(p.id, areas, states.get(p.id), p.areaIds ?? []));
    save.disabled = true;
    try {
      await api.saveAreaStates(list, dataset.revision ?? 0);
    } catch (e) {
      if (e.code === 'masterdata.conflict') {
        // someone else saved meanwhile: read anew, keep the own changes on top, save again
        const kept = own();
        ctx.invalidateDataset();
        root.replaceChildren();
        await render(root, ctx, params, kept);
        toast(t('areaMatrix.conflict'), true);
        return;
      }
      problems.replaceChildren(problemAlert(e));
      save.disabled = false;
      return;
    }
    toast(t('areaMatrix.saved', { count: list.length }));
    ctx.isDirty = null;
    ctx.invalidateDataset();
    root.replaceChildren();
    await render(root, ctx);
  });

  // ---- the view (people as rows, areas as rows, one area), remembered per viewer; unsaved changes stay ----
  let view = 'people';
  try { view = VIEWS.includes(localStorage.getItem(VIEW_KEY)) ? localStorage.getItem(VIEW_KEY) : 'people'; } catch { /* no storage */ }
  const viewSelect = h('select', { id: 'area-matrix-view', onchange: async (e) => {
    try { localStorage.setItem(VIEW_KEY, e.target.value); } catch { /* not remembered */ }
    const kept = own();
    root.replaceChildren();
    await render(root, ctx, params, kept);
  } }, VIEWS.map((v) => h('option', { value: v, selected: v === view }, ({ people: t('areaMatrix.view.people'), areas: t('areaMatrix.view.areas'), area: t('areaMatrix.view.area') })[v])));
  const viewField = h('div.field', {}, h('label', { for: 'area-matrix-view' }, t('areaMatrix.view')), viewSelect);
  const saveBar = (...more) => (canEdit ? h('div.toolbar', {}, ...more, save, dirtyNote) : '');

  if (view === 'area') {
    // who is in one area: its people in a group per state; a click or the space key cycles the state as in the matrix
    let chosen = null;
    try { chosen = localStorage.getItem(PICK_KEY); } catch { /* the first one then */ }
    const pick = h('select', { id: 'area-matrix-pick', onchange: () => {
      try { localStorage.setItem(PICK_KEY, pick.value); } catch { /* not remembered */ }
      draw();
    } }, areas.map((a) => h('option', { value: a.id, selected: a.id === chosen }, `${a.name} (${a.id})`)));
    const groups = h('div.area-groups', {});
    const draw = (focus = null) => {
      const a = areas.find((x) => x.id === pick.value);
      groups.replaceChildren(...CYCLE.map((state) => {
        const members = people.filter((p) => states.get(p.id)[a.id] === state);
        return h('section.area-group', { 'data-state': state || 'OUT' }, h('h2', {}, t('areaMatrix.group', { state: word(state), count: members.length })),
          members.length ? h('ul', {}, members.map((p) => h('li', {}, h(canEdit ? 'button' : 'span', { type: canEdit ? 'button' : null, 'data-person': p.id,
            'data-area': a.id, title: describe(p, a), class: `area-cell state-${state || 'OUT'}` },
          h('span', { 'aria-hidden': 'true' }, MARK[state]), ` ${personName(p)}`, mark(p, a)))))
            : h('p.muted', {}, t('areaMatrix.groupEmpty')));
      }));
      // the person moved to its new group keeps the focus
      [...groups.querySelectorAll('button')].find((b) => b.dataset.person === focus)?.focus();
    };
    if (canEdit) {
      groups.addEventListener('click', (e) => {
        const cell = e.target.closest('button[data-person]');
        if (!cell) return;
        const mine = states.get(cell.dataset.person);
        mine[cell.dataset.area] = nextState(mine[cell.dataset.area], shiftless.has(cell.dataset.area));
        draw(cell.dataset.person);
        markDirty();
      });
    }
    groups.addEventListener('focusin', (e) => {
      const cell = e.target.closest('[data-person][data-area]');
      if (cell) status.textContent = describe(people.find((x) => x.id === cell.dataset.person), areas.find((x) => x.id === cell.dataset.area));
    });
    status.textContent = t('areaMatrix.statusHintArea');
    root.append(
      h('p.muted', {}, canEdit ? t('areaMatrix.introArea') : t('areaMatrix.introAreaViewer')),
      h('div.toolbar', {}, viewField, h('div.field', {}, h('label', { for: 'area-matrix-pick' }, t('areaMatrix.filterArea')), pick)),
      saveBar(), problems, groups, status);
    draw();
    if (pending) markDirty();
    return () => { ctx.isDirty = null; };
  }

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
  const areaShown = (a) => !areaFilter.value || a.id === areaFilter.value;

  // ---- table: people as rows and areas as columns, or swapped ----
  const swapped = view === 'areas';
  const rowItems = swapped ? areas : people;
  const colItems = swapped ? people : areas;
  const showRow = swapped ? areaShown : visible;
  const showCol = swapped ? visible : areaShown;
  const personBoxes = new Map();
  const areaBoxes = new Map();
  const box = (boxes, id, label) => {
    if (!canEdit) return '';
    const b = h('input', { type: 'checkbox', 'aria-label': label, onchange: updateBulk });
    boxes.set(id, b);
    return b;
  };
  const personBox = (p) => box(personBoxes, p.id, t('areaMatrix.row', { name: personName(p) }));
  const areaBox = (a) => box(areaBoxes, a.id, t('areaMatrix.column', { area: a.name }));
  const personLink = (p, label) => h('a', { href: `#/person/${encodeURIComponent(p.id)}` }, label);
  const rowBoxes = swapped ? areaBoxes : personBoxes;
  const head = h('tr', {}, h('th', { scope: 'col' }, canEdit ? h('input', { type: 'checkbox', id: 'area-matrix-all-rows',
    'aria-label': swapped ? t('areaMatrix.allAreaRows') : t('areaMatrix.allRows'),
    onchange: (e) => { for (const [, b] of rowBoxes) if (!b.closest('tr').hidden) b.checked = e.target.checked; updateBulk(); } }) : '', ' ',
  swapped ? t('areaMatrix.area') : t('areaMatrix.person')),
  swapped
    ? people.map((p) => h('th', { scope: 'col', 'data-col-person': p.id, title: personName(p) }, personBox(p), canEdit ? ' ' : '', personLink(p, p.shortName || personName(p))))
    : areas.map((a) => h('th', { scope: 'col', 'data-col-area': a.id, title: a.name }, canEdit ? h('label', {}, areaBox(a), ` ${a.id}`) : a.id)));
  const body = h('tbody', {});
  const rows = rowItems.map((r, i) => h('tr', { [swapped ? 'data-area-row' : 'data-person-row']: r.id },
    swapped
      ? h('th', { scope: 'row' }, canEdit ? h('label', {}, areaBox(r), ` ${r.name}`) : r.name)
      : h('th', { scope: 'row' }, personBox(r), canEdit ? ' ' : '', personLink(r, personName(r))),
    colItems.map((c, j) => {
      const [p, a] = swapped ? [c, r] : [r, c];
      return canEdit
        ? h('td', {}, h('button', { type: 'button', tabindex: i === 0 && j === 0 ? '0' : '-1', ...cellAttrs(p, a, i, j) }, cellContent(p, a)))
        : h('td', cellAttrs(p, a, i, j), cellContent(p, a));
    })));
  body.append(...rows);
  const cellIndex = new Map([...body.querySelectorAll('button[data-person]')].map((b) => [`${b.dataset.person}|${b.dataset.area}`, b]));
  const visibleCols = () => colItems.map((c, j) => (showCol(c) ? j : null)).filter((j) => j != null);
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
      set(cell, nextState(states.get(cell.dataset.person)[cell.dataset.area], shiftless.has(cell.dataset.area)));
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
        // only the shown rows and columns
        const cols = visibleCols();
        const row = shownRows[Math.min(shownRows.length - 1, Math.max(0, at + move[0]))];
        const col = cols[Math.min(cols.length - 1, Math.max(0, cols.indexOf(j) + move[1]))];
        focusCell(cellAt(row, col));
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

  // ---- filters apply, to the rows and the columns ----
  const count = h('p.muted', { id: 'area-matrix-count' });
  const apply = () => {
    rows.forEach((tr, i) => { tr.hidden = !showRow(rowItems[i]); });
    const cols = new Set(visibleCols());
    for (const tr of [head, ...rows]) [...tr.children].slice(1).forEach((cell, j) => { cell.hidden = !cols.has(j); });
    count.textContent = t('areaMatrix.count', { shown: people.filter(visible).length, total: people.length });
    // the cell reached with Tab stays a shown one
    const current = table.querySelector('button[data-row][tabindex="0"]');
    if (canEdit && (!current || current.closest('tr').hidden || current.closest('td').hidden)) {
      const first = rows.findIndex((tr) => !tr.hidden);
      const col = visibleCols()[0];
      if (current) current.tabIndex = -1;
      if (first >= 0 && col != null) cellAt(first, col).tabIndex = 0;
    }
  };
  nameFilter.addEventListener('input', apply);
  areaFilter.addEventListener('change', apply);
  stateFilter.addEventListener('change', apply);

  // ---- several at once: the ticked people and areas among the shown ones, else all shown ----
  const bulkState = h('select', { id: 'area-matrix-bulk-state' }, ['PLAN', 'ON_REQUEST', 'NEVER', ''].map((s) => h('option', { value: s || 'OUT' }, word(s))));
  const bulkButton = h('button.btn.btn-secondary', { type: 'button', id: 'area-matrix-bulk', disabled: true });
  const targets = () => {
    const shownPeople = people.filter(visible).map((p) => p.id);
    const shownAreas = areas.filter(areaShown).map((a) => a.id);
    const ticked = (boxes, shown) => [...boxes].filter(([id, b]) => b.checked && shown.includes(id)).map(([id]) => id);
    const pickedPeople = ticked(personBoxes, shownPeople);
    const pickedAreas = ticked(areaBoxes, shownAreas);
    return { any: pickedPeople.length + pickedAreas.length > 0, people: pickedPeople.length ? pickedPeople : shownPeople,
      areas: pickedAreas.length ? pickedAreas : shownAreas };
  };
  function updateBulk() {
    const target = targets();
    bulkButton.disabled = !target.any;
    bulkButton.textContent = t('areaMatrix.bulk', { count: target.any ? target.people.length * target.areas.length : 0 });
  }
  bulkButton.addEventListener('click', () => {
    const value = bulkState.value === 'OUT' ? '' : bulkState.value;
    const target = targets();
    for (const pid of target.people) {
      for (const aid of target.areas) {
        const cell = cellIndex.get(`${pid}|${aid}`);
        // an area without shifts can only be planbar or out
        if (cell) set(cell, shiftless.has(aid) && value && value !== 'PLAN' ? 'PLAN' : value);
      }
    }
    markDirty();
  });

  root.append(
    h('p.muted', {}, canEdit ? t('areaMatrix.intro') : t('areaMatrix.introViewer')),
    h('div.toolbar', {}, viewField, h('div.field', {}, h('label', { for: 'area-matrix-name' }, t('areaMatrix.filterName')), nameFilter),
      h('div.field', {}, h('label', { for: 'area-matrix-area' }, t('areaMatrix.filterArea')), areaFilter),
      h('div.field', {}, h('label', { for: 'area-matrix-state' }, t('areaMatrix.filterState')), stateFilter)),
    saveBar(h('div.field', {}, h('label', { for: 'area-matrix-bulk-state' }, t('areaMatrix.bulkState')), bulkState), bulkButton),
    problems, count, h('div.area-matrix-wrap', {}, table), status);
  apply();
  updateBulk();
  if (pending) markDirty();
  return () => { ctx.isDirty = null; };
}
