// Tables that sort by a click on a column header and the search of their rows, for core pages and plugins
// (lib/plugin-api.js). Numbers sort as numbers, text in German order with digit runs as numbers (E9 before E10).
import { h } from './dom.js';

const collator = new Intl.Collator('de', { numeric: true, sensitivity: 'base' });
const NUMBER = /^-?\d+(?:[.,]\d+)?$/;
const empty = (v) => v == null || v === '';
const asNumber = (v) => (typeof v === 'number' ? v : NUMBER.test(String(v).trim()) ? Number(String(v).trim().replace(',', '.')) : NaN);

/** Compares two cell values: both numbers (or texts like "38.5") numerically, else as text; empty values last. */
export function compareValues(a, b) {
  if (empty(a) || empty(b)) return empty(a) === empty(b) ? 0 : empty(a) ? 1 : -1;
  const x = asNumber(a);
  const y = asNumber(b);
  if (Number.isFinite(x) && Number.isFinite(y)) return x - y;
  return collator.compare(String(a), String(b));
}

/** The rows sorted by value(row) (a stable sort); descending turns the order round but keeps empty values last. */
export function sortRows(rows, value, ascending = true) {
  return rows.map((row, i) => ({ row, i, v: value(row) })).sort((p, q) => {
    if (empty(p.v) || empty(q.v)) return compareValues(p.v, q.v) || p.i - q.i;
    return (ascending ? compareValues(p.v, q.v) : compareValues(q.v, p.v)) || p.i - q.i;
  }).map((x) => x.row);
}

/** The words of a search, in lower case. */
export const searchTerms = (query) => String(query ?? '').trim().toLocaleLowerCase('de').split(/\s+/).filter(Boolean);

/** True when the text holds every term (searchTerms), in any order. */
export function matchesTerms(text, terms) {
  const lower = String(text ?? '').toLocaleLowerCase('de');
  return terms.every((term) => lower.includes(term));
}

/**
 * A table.data whose headers are buttons that sort it (again: the other way round), with aria-sort, in a box that
 * scrolls sideways. columns: [{ label, title, num, value(row) (what it sorts and shows), cell(row) (node or text to
 * show instead), sortable (default true) }]. options: label (the table's name for screen readers), sort (index of
 * the column sorted first, null: rows as given), ascending. Returns { element, show(rows) }; show draws the rows
 * (the caller filters them) and keeps the chosen order.
 */
export function sortableTable(columns, { label = '', sort = null, ascending = true } = {}) {
  let key = sort;
  let up = ascending;
  let current = [];
  const head = h('thead', {});
  const body = h('tbody', {});
  const table = h('table.data', { 'aria-label': label || null }, head, body);
  const element = h('div', { style: { overflowX: 'auto' } }, table);
  const text = (c, row) => (c.cell ? c.cell(row) : c.value(row) ?? '');
  function draw() {
    head.replaceChildren(h('tr', {}, columns.map((c, i) => {
      const sorted = key === i;
      const content = c.sortable === false ? c.label
        : h('button', { type: 'button', title: c.title || null, 'data-column': i, onclick: () => {
          up = sorted ? !up : true;
          key = i;
          draw();
          head.querySelector(`[data-column="${i}"]`).focus(); // the keyboard stays on the header
        } },
          c.label, sorted ? (up ? ' ▲' : ' ▼') : '');
      return h('th', { class: c.num ? 'num' : '', scope: 'col', title: c.sortable === false ? c.title || null : null,
        'aria-sort': c.sortable === false ? null : sorted ? (up ? 'ascending' : 'descending') : 'none' }, content);
    })));
    const rows = key == null ? current : sortRows(current, columns[key].value, up);
    body.replaceChildren(...rows.map((row) => h('tr', {}, columns.map((c) => h(c.num ? 'td.num' : 'td', {}, text(c, row))))));
  }
  draw();
  return { element, show(rows) { current = rows; draw(); } };
}
