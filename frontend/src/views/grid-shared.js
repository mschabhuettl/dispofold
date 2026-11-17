// Pieces shared by the roster and the function view: day header, popover shell, cell choices, keyboard navigation,
// legend box, sticky rows.
import { tintStyle } from '../lib/colors.js';
import { WEEKDAY_SHORT } from '../lib/dates.js';
import { h } from '../lib/dom.js';
import { cellOptions } from '../lib/grid-model.js';
import { moveCell } from '../lib/grid-nav.js';
import { t } from '../lib/texts.js';

export const dayClass = (d) => [d.weekend && 'weekend', d.holiday && 'holiday'].filter(Boolean).join(' ');

/** Column header of a day: weekday, day of month and, on a holiday, its name. */
export function dayHeader(d) {
  return h('th', { scope: 'col', class: dayClass(d), title: d.holiday ?? '' },
    WEEKDAY_SHORT[d.weekday - 1], h('br', {}), String(d.day),
    // holiday also as text, not only as colour
    d.holiday && [h('span.holiday-tag', { 'aria-hidden': 'true' }, t('roster.holidayShort')),
      h('span.visually-hidden', {}, t('roster.holidayName', { name: d.holiday }))]);
}

/**
 * One popover at a time below an anchor: a dialog with a heading, focus kept inside, Escape returns to the anchor.
 * A click outside closes it, except on elements matching keepOpen (they open their own popover).
 */
export function createPopover(keepOpen) {
  let el = null;

  function onDocClick(e) {
    if (el && !el.contains(e.target) && !e.target.closest(keepOpen)) close();
  }

  function close() {
    el?.remove();
    el = null;
    document.removeEventListener('click', onDocClick, true);
  }

  function open(anchor, title, content) {
    close();
    el = h('div.popover', { role: 'dialog', 'aria-label': title }, h('h3', {}, title), content);
    const popover = el;
    popover.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        close();
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
    document.addEventListener('click', onDocClick, true);
    return popover;
  }

  return { open, close };
}

/**
 * Content of the cell popover: a conflict offers to remove the duty, an absence only explains, otherwise the shifts of
 * the person's areas, free and compensatory rest, and fixing or unfixing. pick(option, pinned) returns the click handler.
 */
export function cellChoice({ cell, person, shifts, pick }) {
  const current = { shiftId: cell.shiftId, compRest: cell.kind === 'compRest' };
  return cell.conflict
    ? [h('p.alert.alert-error', {}, t('roster.conflict', { label: cell.label })),
      h('button.btn.btn-primary.btn-small', { type: 'button', onclick: pick({ shiftId: null, compRest: false }, false) }, t('roster.removeDuty')),
      cell.fixedInPeriod && h('p.muted', {}, t('roster.conflictFixed'))]
    : cell.kind === 'absence'
    ? [h('p', {}, t('roster.absence', { label: cell.label })), h('p.muted', {}, t('roster.absenceHint'))]
    : [
      h('ul', {}, cellOptions(person, shifts).map((o) => {
        const isCurrent = o.shiftId === current.shiftId && o.compRest === current.compRest;
        return h('li', {}, h('button', { type: 'button', 'aria-current': isCurrent ? 'true' : 'false', onclick: pick(o, true) },
          h('span.cell-code', o.color ? { class: 'tinted', style: tintStyle(o.color) } : { class: 'outlined' }, o.code || '·'),
          o.label));
      })),
      cell.fixedInPeriod
        ? h('p.muted', {}, t('roster.fixedFromInputs'))
        : h('button.btn.btn-secondary.btn-small', { type: 'button', onclick: pick(current, !cell.pinned) }, cell.pinned ? t('roster.unpinCell') : t('roster.pinCell')),
    ];
}

/**
 * Keyboard navigation in a grid of [data-cell] elements with data-r and data-c inside tr[data-row]: arrow keys and
 * Home/End/PageUp/PageDown move, one cell is in the tab order (roving tabindex).
 */
export function rovingGrid(container) {
  let rovingKey = null; // the one cell in the tab order
  container.addEventListener('focusin', (e) => {
    const cell = e.target.closest?.('[data-cell]');
    if (!cell || cell.dataset.cell === rovingKey) return;
    for (const c of container.querySelectorAll('[data-cell][tabindex="0"]')) c.tabIndex = -1;
    cell.tabIndex = 0;
    rovingKey = cell.dataset.cell;
  });
  container.addEventListener('keydown', (e) => {
    const cell = e.target.closest?.('[data-cell]');
    if (!cell) return;
    const rows = container.querySelectorAll('tr[data-row]').length;
    const cols = container.querySelector('tr[data-row]')?.querySelectorAll('[data-cell]').length ?? 0;
    const next = moveCell({ row: Number(cell.dataset.r), col: Number(cell.dataset.c) }, e.key,
      { rows, cols, ctrl: e.ctrlKey || e.metaKey });
    if (!next) return;
    e.preventDefault();
    container.querySelector(`[data-r="${next.row}"][data-c="${next.col}"]`)?.focus();
  });
  return {
    /** After a redraw: one tab stop, the last used cell, else the first; keyboard focus on the cell with this key. */
    restore(key) {
      const cells = container.querySelectorAll('[data-cell]');
      const roving = (rovingKey && container.querySelector(`[data-cell="${CSS.escape(rovingKey)}"]`)) || cells[0];
      if (roving) roving.tabIndex = 0;
      if (key) container.querySelector(`[data-cell="${CSS.escape(key)}"]`)?.focus();
    },
  };
}

/** The legend on screen: collapsed until opened; open or closed is remembered for both plan views. */
export function legendBox(legend) {
  let open = false;
  try { open = localStorage.getItem('plan-legend') === 'open'; } catch { /* no storage */ }
  const box = h('details.legend-box', { open }, h('summary', {}, t('roster.legend')), legend);
  box.addEventListener('toggle', () => {
    try { localStorage.setItem('plan-legend', box.open ? 'open' : 'closed'); } catch { /* no storage */ }
  });
  return box;
}

/**
 * Rows that stick below the day header need its height, and below a group row that one's too: kept as the CSS
 * variables --head-h and --group-h of the table, also when the layout changes (the view is attached after the first
 * draw, fonts load later). onMeasure runs after each measurement. watch(table) after every redraw, stop() at the end.
 */
export function stickyHeights(onMeasure = () => {}) {
  let table = null;
  const observer = new ResizeObserver(() => {
    if (!table?.isConnected) return;
    table.style.setProperty('--head-h', `${table.tHead.getBoundingClientRect().height}px`);
    const group = table.querySelector('tbody tr.group');
    if (group) table.style.setProperty('--group-h', `${group.getBoundingClientRect().height}px`);
    onMeasure();
  });
  return {
    watch(el) {
      observer.disconnect();
      table = el;
      observer.observe(el.tHead);
    },
    stop: () => observer.disconnect(),
  };
}

/**
 * Prints on white paper: the light theme while the print dialog is open; `refresh` runs before every print (for
 * example the print date of the header line). Returns the function that removes it.
 */
export function lightThemeWhilePrinting(refresh = () => {}) {
  let theme = null;
  const before = () => {
    refresh();
    theme = document.documentElement.dataset.theme;
    document.documentElement.dataset.theme = 'light';
  };
  const after = () => { if (theme) document.documentElement.dataset.theme = theme; };
  window.addEventListener('beforeprint', before);
  window.addEventListener('afterprint', after);
  return () => {
    window.removeEventListener('beforeprint', before);
    window.removeEventListener('afterprint', after);
  };
}
