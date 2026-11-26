// Stammdaten: areas, absence types, shifts, demand and holidays (people have their own pages, views/person.js), each
// table with a filter field. Planners edit them here (one inline form at a time, every change with the revision it
// was made on, see docs/api.md); viewers see read-only tables. #/stammdaten/bedarf/<index> scrolls to a demand row
// and marks it (the link of the Prüfen page).
import { h, toast } from '../lib/dom.js';
import { tintStyle } from '../lib/colors.js';
import { WEEKDAY_SHORT } from '../lib/dates.js';
import { messageText } from '../lib/codes.js';
import { CATEGORIES, DEMAND_DAYS, conflictDecision, fromForm, inUseQuestion, itemLabel, keyOf, toForm } from '../lib/masterdata-edit.js';
import { info } from '../lib/info.js';
import { t } from '../lib/texts.js';

const CATEGORY = { EARLY: t('masterdata.category.early'), DAY: t('masterdata.category.day'), LATE: t('masterdata.category.late'), NIGHT: t('masterdata.category.night') };
const DAY_CODES = { MON: WEEKDAY_SHORT[0], TUE: WEEKDAY_SHORT[1], WED: WEEKDAY_SHORT[2], THU: WEEKDAY_SHORT[3], FRI: WEEKDAY_SHORT[4], SAT: WEEKDAY_SHORT[5],
  SUN: WEEKDAY_SHORT[6], HOL: t('masterdata.holiday') };
const TITLES = { areas: t('masterdata.section.areas'), absenceTypes: t('masterdata.section.absences'),
  shifts: t('masterdata.section.shifts'), demand: t('masterdata.section.demand'), holidays: t('masterdata.section.holidays') };
const inline = { fontWeight: 400, marginRight: '.6rem' };
const bare = { border: 0, padding: 0, margin: 0 };

export async function render(root, ctx, params = []) {
  const { api, canEdit } = ctx;
  let dataset = await ctx.dataset();
  const filters = {}; // filter text per kind, kept when the page is drawn again
  const hhmm = (s) => String(s ?? '').slice(0, 5);
  const yesNo = (v) => (v ? t('common.yes') : t('common.no'));

  /** Table columns per kind: [header, cell]. */
  function columns(kind) {
    const areaName = new Map(dataset.areas.map((a) => [a.id, a.name]));
    const chip = (color, label) => h('span.cell-code.strong.tinted', { style: tintStyle(color) }, label);
    return {
      areas: [[t('masterdata.col.code'), (a) => chip(a.color, a.id)], [t('masterdata.col.name'), (a) => a.name]],
      absenceTypes: [[t('masterdata.col.code'), (a) => a.code], [t('masterdata.col.name'), (a) => a.name], [t('masterdata.col.reducesTarget'), (a) => yesNo(a.reducesTarget)],
        [t('masterdata.col.chargesAccount'), (a) => yesNo(a.chargesAccount)], [t('masterdata.col.compRest'), (a) => yesNo(a.compensatoryRest)],
        [t('masterdata.col.credit'), (a) => t(a.credit === 'PLANNED_DUTY' ? 'masterdata.credit.plannedDuty' : 'masterdata.credit.weeklyHours')]],
      shifts: [[t('masterdata.col.code'), (s) => chip(s.color, s.code)], [t('masterdata.col.name'), (s) => s.name], [t('masterdata.col.area'), (s) => areaName.get(s.areaId) ?? s.areaId],
        [t('masterdata.col.start'), (s) => hhmm(s.start)], [t('masterdata.col.end'), (s) => hhmm(s.end)], [t('masterdata.col.category'), (s) => CATEGORY[s.category] ?? '–'],
        [t('masterdata.col.unpopular'), (s) => (s.unpopular ? t('common.yes') : '')],
        [t('masterdata.col.usage'), (s) => (s.usage == null ? '' : s.usage > 0 && s.usage < 0.0005 ? '< 0,1 %' : `${(s.usage * 100).toLocaleString('de-AT', { maximumFractionDigits: 1 })} %`), 'num']],
      demand: [[t('masterdata.col.area'), (d) => areaName.get(d.areaId) ?? d.areaId], [t('masterdata.col.valid'), (d) => (d.date ? de(d.date) : (d.days ?? []).map((x) => DAY_CODES[x] ?? x).join(', '))],
        [t('masterdata.col.from'), (d) => hhmm(d.from)], [t('masterdata.col.to'), (d) => hhmm(d.to)], [t('masterdata.col.min'), (d) => String(d.min), 'num'],
        [t('masterdata.col.max'), (d) => d.max ?? t('masterdata.maxIsMin'), 'num']],
      holidays: [[t('masterdata.col.date'), (x) => de(x.date)], [t('masterdata.col.name'), (x) => x.name]],
    }[kind];
  }

  /** Form fields per kind: { name, label, type, options, ... }; key is null for a new object. */
  function fields(kind, key) {
    const editing = key != null;
    const areas = dataset.areas.map((a) => [a.id, `${a.id} ${a.name}`]);
    return {
      areas: [{ name: 'id', label: t('masterdata.col.code'), help: t('help.field.areas.id'), required: true, readOnly: editing }, { name: 'name', label: t('masterdata.col.name'), help: t('help.field.areas.name'), required: true },
        { name: 'color', label: t('masterdata.col.color'), help: t('help.field.areas.color'), placeholder: '#RRGGBB' }],
      absenceTypes: [{ name: 'code', label: t('masterdata.col.code'), help: t('help.field.absenceTypes.code'), required: true, readOnly: editing }, { name: 'name', label: t('masterdata.col.name'), help: t('help.field.absenceTypes.name'), required: true },
        { name: 'reducesTarget', label: t('masterdata.col.reducesTarget'), help: t('help.field.absenceTypes.reducesTarget'), type: 'checkbox' }, { name: 'chargesAccount', label: t('masterdata.col.chargesAccount'), help: t('help.field.absenceTypes.chargesAccount'), type: 'checkbox' },
        { name: 'compensatoryRest', label: t('masterdata.col.compRest'), help: t('help.field.absenceTypes.compensatoryRest'), type: 'checkbox' },
        { name: 'credit', label: t('masterdata.col.credit'), help: t('help.field.absenceTypes.credit'), type: 'select',
          options: [['WEEKLY_HOURS', t('masterdata.credit.weeklyHours')], ['PLANNED_DUTY', t('masterdata.credit.plannedDuty')]] }, { name: 'color', label: t('masterdata.col.color'), help: t('help.field.absenceTypes.color'), placeholder: '#RRGGBB' }],
      shifts: [{ name: 'areaId', label: t('masterdata.col.area'), help: t('help.field.shifts.areaId'), type: 'select', options: areas }, { name: 'code', label: t('masterdata.col.code'), help: t('help.field.shifts.code'), required: true },
        { name: 'name', label: t('masterdata.col.name'), help: t('help.field.shifts.name'), required: true }, { name: 'start', label: t('masterdata.col.start'), help: t('help.field.shifts.start'), type: 'time', required: true },
        { name: 'end', label: t('masterdata.col.end'), help: t('help.field.shifts.end'), type: 'time', required: true },
        { name: 'category', label: t('masterdata.col.category'), help: t('help.field.shifts.category'), type: 'select', options: [['', t('masterdata.category.auto')], ...CATEGORIES.map((c) => [c, CATEGORY[c]])] },
        { name: 'color', label: t('masterdata.col.color'), help: t('help.field.shifts.color'), placeholder: '#RRGGBB' }, { name: 'unpopular', label: t('masterdata.col.unpopular'), help: t('help.field.shifts.unpopular'), type: 'checkbox' }],
      demand: [{ name: 'areaId', label: t('masterdata.col.area'), help: t('help.field.demand.areaId'), type: 'select', options: areas },
        { name: 'mode', label: t('masterdata.col.valid'), help: t('help.field.demand.mode'), type: 'select', options: [['days', t('masterdata.demand.byDays')], ['date', t('masterdata.demand.byDate')]] },
        { name: 'days', label: t('masterdata.demand.days'), help: t('help.field.demand.days'), type: 'checks', options: DEMAND_DAYS.map((d) => [d, DAY_CODES[d]]), mode: 'days' },
        { name: 'date', label: t('masterdata.col.date'), help: t('help.field.demand.date'), type: 'date', mode: 'date' }, { name: 'from', label: t('masterdata.col.from'), help: t('help.field.demand.from'), type: 'time', required: true },
        { name: 'to', label: t('masterdata.col.to'), help: t('help.field.demand.to'), type: 'time', required: true }, { name: 'min', label: t('masterdata.col.min'), help: t('help.field.demand.min'), type: 'number', step: 1, min: 0, required: true },
        { name: 'max', label: t('masterdata.col.max'), help: t('help.field.demand.max'), type: 'number', step: 1, min: 0 }],
      holidays: [{ name: 'date', label: t('masterdata.col.date'), help: t('help.field.holidays.date'), type: 'date', required: true }, { name: 'name', label: t('masterdata.col.name'), help: t('help.field.holidays.name'), required: true }],
    }[kind];
  }

  const list = (kind) => dataset[kind] ?? [];
  const find = (kind, key) => list(kind).find((x, i) => keyOf(kind, x, i) === key) ?? null;
  const sel = (kind, key) => `[data-kind="${kind}"][data-key="${CSS.escape(String(key))}"]`;

  function draw() {
    root.replaceChildren(h('h1', {}, t('nav.masterdata')),
      h('p.muted', {}, t('masterdata.source', { source: dataset.source ?? t('masterdata.unknown'), people: dataset.people.length, areas: dataset.areas.length, shifts: dataset.shifts.length }),
        ' ', h('a', { href: '#/person' }, t('masterdata.peopleLink'))),
      h('div.grid-2', {}, section('areas'), section('absenceTypes')),
      section('shifts'), section('demand'), section('holidays'));
  }

  function section(kind) {
    const id = `h-${kind}`;
    const cols = columns(kind);
    // filtering hides rows: holidays and demand are keyed by their position in the whole list
    const rows = list(kind).map((item, index) => ({ item, key: keyOf(kind, item, index) }));
    const button = (label, aria, onclick, data = {}) => h('button.btn-link', { type: 'button', 'aria-label': aria, onclick, ...data }, label);
    const actions = (item, key) => {
      const data = { 'data-kind': kind, 'data-key': String(key) };
      const label = itemLabel(kind, item);
      return h('td', {}, button(t('masterdata.edit'), t('masterdata.editLabel', { label }), () => openForm(kind, key), data), ' ',
        button(t('masterdata.remove'), t('masterdata.removeLabel', { label }), () => remove(kind, key)));
    };
    const table = h('table.data', { 'data-table': kind },
      h('thead', {}, h('tr', {}, cols.map(([label]) => h('th', {}, label)), canEdit && h('th', {}, ''))),
      h('tbody', {}, rows.map(({ item, key }) => h('tr', { 'data-row': String(key) }, cols.map(([, cell, cls]) => h(cls ? `td.${cls}` : 'td', {}, cell(item))), canEdit && actions(item, key)))));
    const none = h('p.muted', { hidden: true }, t('masterdata.noMatch'));
    const filterId = `filter-${kind}`;
    const filter = h('input', { id: filterId, type: 'search', 'data-filter': kind, value: filters[kind] ?? '', autocomplete: 'off',
      oninput: apply });
    function apply() {
      filters[kind] = filter.value;
      const terms = filter.value.trim().toLocaleLowerCase('de').split(/\s+/).filter(Boolean);
      let shown = 0;
      for (const tr of table.tBodies[0].rows) {
        if (tr.classList.contains('edit-row')) continue;
        const text = tr.textContent.toLocaleLowerCase('de');
        tr.hidden = !terms.every((term) => text.includes(term));
        if (!tr.hidden) shown++;
      }
      none.hidden = shown > 0 || !rows.length;
    }
    apply();
    return h('section.section', { 'aria-labelledby': id }, h('h2', { id }, TITLES[kind]),
      h('div.toolbar', {}, h('div.field', {}, h('label', { for: filterId }, t('masterdata.filterLabel', { section: TITLES[kind] })), filter),
        canEdit && h('button.btn.btn-secondary.btn-small', { type: 'button', 'data-new': kind, onclick: () => openForm(kind, null) }, t('masterdata.new'))),
      h('div', { style: { overflowX: 'auto' } }, table), none);
  }

  /** Puts a form into a row below the object's row (at the top for a new one); only one form at a time. */
  function placeForm(kind, key, form) {
    root.querySelector('tr.edit-row')?.remove();
    const table = root.querySelector(`[data-table="${kind}"]`);
    const cols = table.querySelector('thead tr').children.length;
    const row = h('tr.edit-row', {}, h('td', { colspan: cols }, form));
    const anchor = key == null ? null : table.querySelector(sel(kind, key))?.closest('tr');
    if (anchor) anchor.after(row);
    else table.tBodies[0].prepend(row);
    form.querySelector('input:not([readonly]), select')?.focus();
  }

  const problems = () => h('div.form-problems', { 'aria-live': 'polite', style: { flexBasis: '100%' } });
  const formButtons = () => [h('button.btn.btn-primary.btn-small', { type: 'submit' }, t('common.save')),
    h('button.btn.btn-secondary.btn-small', { type: 'button', onclick: draw }, t('common.cancel'))];

  /** The form of an object; values refill it (after a conflict), else it shows the object as stored. */
  function openForm(kind, key, values = null) {
    const base = key == null ? null : find(kind, key);
    const v = values ?? toForm(kind, base);
    const defs = fields(kind, key);
    const form = h('form.toolbar', { onsubmit: async (e) => {
      e.preventDefault();
      const read = readForm(form, defs);
      let item;
      try {
        item = fromForm(kind, read, base);
      } catch (err) {
        showProblem(err);
        return;
      }
      await busy(form, () => write({ kind, key, base, item, values: read }));
    } },
    defs.map((f) => control(f, v[f.name])), formButtons(), problems());
    if (kind === 'demand') {
      const mode = form.querySelector('#f-mode');
      const show = () => form.querySelectorAll('[data-mode]').forEach((el) => { el.hidden = el.dataset.mode !== mode.value; });
      mode.addEventListener('change', show);
      show();
    }
    placeForm(kind, key, form);
  }

  async function busy(form, action) {
    const submit = form.querySelector('button[type=submit]');
    submit.disabled = true;
    try { await action(); } finally { submit.disabled = false; }
  }

  function remove(kind, key) {
    const base = find(kind, key);
    if (confirm(t('masterdata.remove.confirm', { label: itemLabel(kind, base) }))) write({ kind, key, base, item: undefined });
  }

  /**
   * Sends a change with the revision it was made on. op: { kind, key (null to create), base (as read),
   * item (undefined to remove), values (the form's values, to refill it), confirmed }.
   */
  async function write(op) {
    const { kind, key, item } = op;
    const revision = dataset.revision ?? 0;
    let res;
    try {
      if (item === undefined) res = await api.deleteMaster(kind, key, revision, op.confirmed);
      else if (key == null) res = await api.createMaster(kind, item, revision);
      else res = await api.updateMaster(kind, key, item, revision);
    } catch (e) {
      if (e.code === 'masterdata.conflict') return resolveConflict(op);
      if (e.code === 'masterdata.inUse' && item === undefined && !op.confirmed) {
        if (confirm(inUseQuestion(kind, op.base, e.params))) return write({ ...op, confirmed: true });
        return undefined;
      }
      return showProblem(e, item !== undefined);
    }
    toast(item === undefined ? t('masterdata.removed') : t('masterdata.saved'));
    // deleting may also remove references (restrictions, demand, areas of people): always read everything again
    await reload();
    const target = item === undefined || res?.key == null ? root.querySelector(`[data-new="${kind}"]`) : root.querySelector(sel(kind, res.key));
    target?.focus();
    return undefined;
  }

  async function reload() {
    ctx.invalidateDataset();
    try {
      dataset = await ctx.dataset();
    } catch (e) {
      toast(e.message, true);
    }
    draw();
  }

  /** Someone else changed master data meanwhile: apply the change onto the newer version, or drop it and reload. */
  async function resolveConflict(op) {
    const previous = dataset;
    await reload();
    if (dataset === previous) return; // reading failed
    if (!confirm(t('masterdata.conflict.prompt'))) {
      toast(t('masterdata.conflict.reloaded'));
      return;
    }
    const decision = conflictDecision(op.kind, op.key, op.base, dataset);
    if (decision.action === 'retry') {
      // the own values back in the form on the newer version, so that a rejected retry can be corrected
      if (op.item !== undefined) openForm(op.kind, decision.key, op.values);
      await write({ ...op, key: decision.key });
    } else if (decision.action === 'changed' && op.item !== undefined) {
      // the object itself was changed too: do not save, let the user check
      openForm(op.kind, decision.key, op.values);
      showProblem(new Error(t('masterdata.conflict.changed')));
    } else if (decision.action === 'gone' && op.item !== undefined) {
      // changed or removed meanwhile (holidays and demand cannot tell): the own values as a new entry, not saved
      openForm(op.kind, null, op.values);
      showProblem(new Error(t('masterdata.conflict.goneForm')));
    } else {
      toast(decision.action === 'gone' ? t('masterdata.conflict.gone') : t('masterdata.conflict.changedRemove'), true);
    }
  }

  /** An error in the open form (with the lists of a rejected change), else (removing) as a message. */
  function showProblem(e, inForm = true) {
    const host = inForm ? root.querySelector('tr.edit-row .form-problems') : null;
    if (host) host.replaceChildren(problemAlert(e));
    else toast(e.message, true);
  }

  draw();
  if (params[0] === 'bedarf') {
    const row = root.querySelector(`[data-table="demand"] tr[data-row="${CSS.escape(params[1] ?? '')}"]`);
    row?.classList.add('row-target');
    // the view is placed on the page after it rendered
    if (row) setTimeout(() => row.scrollIntoView({ block: 'center' }));
  }
}

/** The alert for a rejected change: the message (and hint) with the lists of errors and warnings the server sent. */
export function problemAlert(e, hint = '') {
  const errors = e.body?.errors ?? [];
  const warnings = e.body?.warnings ?? [];
  return h('div.alert.alert-error', { role: 'alert' }, h('strong', {}, e.message + hint),
    errors.length ? h('ul', {}, errors.map((x) => h('li', {}, messageText(x)))) : null,
    warnings.length ? [h('p', {}, h('strong', {}, t('import.warnings', { count: warnings.length }))),
      h('ul', {}, warnings.map((w) => h('li', {}, messageText(w))))] : null);
}

/**
 * One form field: a text, number, date or time input, a select, a checkbox or a group of checkboxes (id f-<name>),
 * with the help text f.help (if any) behind an "i" next to the label.
 */
export function control(f, value) {
  const id = `f-${f.name}`;
  const data = f.mode ? { 'data-mode': f.mode } : {};
  const help = f.help && info(f.help, f.label);
  const labelled = (label) => (help ? h('div.with-info', {}, label, help) : label);
  if (f.type === 'checks') {
    return h('fieldset', { style: bare, ...data }, h('legend', { style: { fontWeight: 600 } }, f.label, help),
      f.options.map(([v, label]) => h('label', { style: inline }, h('input', { type: 'checkbox', name: f.name, value: v, checked: (value ?? []).includes(v) }), ` ${label}`)));
  }
  if (f.type === 'checkbox') return h('div.field', data, labelled(h('label', {}, h('input', { id, type: 'checkbox', checked: Boolean(value) }), ` ${f.label}`)));
  const input = f.type === 'select'
    ? h('select', { id }, f.options.map(([v, label]) => h('option', { value: v, selected: v === value }, label)))
    : h('input', { id, type: f.type ?? 'text', value: value ?? '', required: f.required, readOnly: f.readOnly, step: f.step, min: f.min, placeholder: f.placeholder });
  return h('div.field', data, labelled(h('label', { for: id }, f.label)), input);
}

/** Form values of the fields (see control) as the pure module reads them (masterdata-edit.js). */
export function readForm(form, defs) {
  const out = {};
  for (const f of defs) {
    if (f.type === 'checks') out[f.name] = [...form.querySelectorAll(`input[name="${f.name}"]:checked`)].map((c) => c.value);
    else if (f.type === 'checkbox') out[f.name] = form.querySelector(`#f-${f.name}`).checked;
    else out[f.name] = form.querySelector(`#f-${f.name}`).value;
  }
  return out;
}

export const de = (iso) => `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}`;

/** Employment period of a person: entry and exit date, either may be open. */
export function employmentText(p) {
  if (p.entryDate && p.exitDate) return t('masterdata.employment.range', { from: de(p.entryDate), to: de(p.exitDate) });
  if (p.entryDate) return t('masterdata.employment.from', { from: de(p.entryDate) });
  if (p.exitDate) return t('masterdata.employment.until', { to: de(p.exitDate) });
  return '';
}
