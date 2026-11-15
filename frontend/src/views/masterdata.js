// Stammdaten: people, areas, absence types, shifts, demand and holidays. Planners edit them here (one inline form
// at a time, every change with the revision it was made on, see docs/api.md); viewers see read-only tables.
import { fmt, h, toast } from '../lib/dom.js';
import { tintStyle } from '../lib/colors.js';
import { WEEKDAY_SHORT } from '../lib/dates.js';
import { personName } from '../lib/grid-model.js';
import { messageText } from '../lib/codes.js';
import { CATEGORIES, DEMAND_DAYS, conflictDecision, familyText, fromForm, inUseQuestion, itemLabel, keyOf, restrictionFromForm,
  restrictionText, toForm } from '../lib/masterdata-edit.js';
import { t } from '../lib/texts.js';

const CATEGORY = { EARLY: t('masterdata.category.early'), DAY: t('masterdata.category.day'), LATE: t('masterdata.category.late'), NIGHT: t('masterdata.category.night') };
const DAY_CODES = { MON: WEEKDAY_SHORT[0], TUE: WEEKDAY_SHORT[1], WED: WEEKDAY_SHORT[2], THU: WEEKDAY_SHORT[3], FRI: WEEKDAY_SHORT[4], SAT: WEEKDAY_SHORT[5],
  SUN: WEEKDAY_SHORT[6], HOL: t('masterdata.holiday') };
const WEEKDAY_OPTIONS = WEEKDAY_SHORT.map((d, i) => [String(i + 1), d]);
const TITLES = { people: t('masterdata.section.people'), areas: t('masterdata.section.areas'), absenceTypes: t('masterdata.section.absences'),
  shifts: t('masterdata.section.shifts'), demand: t('masterdata.section.demand'), holidays: t('masterdata.section.holidays') };
const inline = { fontWeight: 400, marginRight: '.6rem' };
const bare = { border: 0, padding: 0, margin: 0 };

export async function render(root, ctx) {
  const { api, canEdit } = ctx;
  let dataset = await ctx.dataset();
  const hhmm = (s) => String(s ?? '').slice(0, 5);
  const yesNo = (v) => (v ? t('common.yes') : t('common.no'));

  /** Table columns per kind: [header, cell]. */
  function columns(kind) {
    const areaName = new Map(dataset.areas.map((a) => [a.id, a.name]));
    const shifts = new Map(dataset.shifts.map((s) => [s.id, s]));
    const restrictions = (p) => (dataset.restrictions ?? []).filter((r) => r.personId === p.id);
    const chip = (color, label) => h('span.cell-code.strong.tinted', { style: tintStyle(color) }, label);
    return {
      people: [
        [t('masterdata.col.name'), (p) => h('a', { href: `#/person/${encodeURIComponent(p.id)}` }, personName(p))], [t('masterdata.col.shortName'), (p) => p.shortName ?? ''],
        [t('masterdata.col.id'), (p) => p.id], [t('masterdata.col.weeklyHours'), (p) => fmt.num(p.weeklyHours, 1), 'num'], [t('masterdata.col.areas'), (p) => p.areaIds.join(', ')],
        [t('masterdata.col.employment'), employmentText],
        // hours account, compensatory rest, family rules and restrictions are personal: planners only (viewers get null)
        canEdit && [t('masterdata.col.account'), (p) => fmt.num(p.hoursAccount, 1), 'num'], canEdit && [t('masterdata.col.compRestOpen'), (p) => fmt.num(p.compRestBalance), 'num'],
        canEdit && [t('masterdata.col.familyRules'), (p) => (p.familyRules ?? []).map(familyText).join('; ')],
        canEdit && [t('masterdata.col.restrictions'), (p) => restrictions(p).map((r) => restrictionText(r, shifts)).join('; ')]],
      areas: [[t('masterdata.col.shortName'), (a) => chip(a.color, a.id)], [t('masterdata.col.name'), (a) => a.name]],
      absenceTypes: [[t('masterdata.col.code'), (a) => a.code], [t('masterdata.col.name'), (a) => a.name], [t('masterdata.col.reducesTarget'), (a) => yesNo(a.reducesTarget)],
        [t('masterdata.col.chargesAccount'), (a) => yesNo(a.chargesAccount)], [t('masterdata.col.compRest'), (a) => yesNo(a.compensatoryRest)]],
      shifts: [[t('masterdata.col.code'), (s) => chip(s.color, s.code)], [t('masterdata.col.name'), (s) => s.name], [t('masterdata.col.area'), (s) => areaName.get(s.areaId) ?? s.areaId],
        [t('masterdata.col.start'), (s) => hhmm(s.start)], [t('masterdata.col.end'), (s) => hhmm(s.end)], [t('masterdata.col.category'), (s) => CATEGORY[s.category] ?? '–'],
        [t('masterdata.col.unpopular'), (s) => (s.unpopular ? t('common.yes') : '')]],
      demand: [[t('masterdata.col.area'), (d) => areaName.get(d.areaId) ?? d.areaId], [t('masterdata.col.valid'), (d) => (d.date ? de(d.date) : (d.days ?? []).map((x) => DAY_CODES[x] ?? x).join(', '))],
        [t('masterdata.col.from'), (d) => hhmm(d.from)], [t('masterdata.col.to'), (d) => hhmm(d.to)], [t('masterdata.col.min'), (d) => String(d.min), 'num'],
        [t('masterdata.col.max'), (d) => d.max ?? '–', 'num']],
      holidays: [[t('masterdata.col.date'), (x) => de(x.date)], [t('masterdata.col.name'), (x) => x.name]],
    }[kind].filter(Boolean);
  }

  /** Form fields per kind: { name, label, type, options, ... }; key is null for a new object. */
  function fields(kind, key) {
    const editing = key != null;
    const areas = dataset.areas.map((a) => [a.id, `${a.id} ${a.name}`]);
    return {
      people: [{ name: 'firstName', label: t('masterdata.col.firstName'), required: true }, { name: 'lastName', label: t('masterdata.col.lastName'), required: true },
        { name: 'shortName', label: t('masterdata.col.shortName') },
        { name: 'weeklyHours', label: t('masterdata.col.weeklyHours'), type: 'number', step: 0.1, min: 0, required: true },
        { name: 'hoursAccount', label: t('masterdata.col.account'), type: 'number', step: 0.1, required: true },
        { name: 'compRestBalance', label: t('masterdata.col.compRestOpen'), type: 'number', step: 1, min: 0, required: true },
        { name: 'externalId', label: t('masterdata.col.externalId') },
        { name: 'entryDate', label: t('masterdata.entryDate'), type: 'date' }, { name: 'exitDate', label: t('masterdata.exitDate'), type: 'date' },
        { name: 'areaIds', label: t('masterdata.col.areas'), type: 'checks', options: dataset.areas.map((a) => [a.id, a.id]) },
        { name: 'familyRules', type: 'family' }],
      areas: [{ name: 'id', label: t('masterdata.col.shortName'), required: true, readOnly: editing }, { name: 'name', label: t('masterdata.col.name'), required: true },
        { name: 'color', label: t('masterdata.col.color'), placeholder: '#RRGGBB' }],
      absenceTypes: [{ name: 'code', label: t('masterdata.col.code'), required: true, readOnly: editing }, { name: 'name', label: t('masterdata.col.name'), required: true },
        { name: 'reducesTarget', label: t('masterdata.col.reducesTarget'), type: 'checkbox' }, { name: 'chargesAccount', label: t('masterdata.col.chargesAccount'), type: 'checkbox' },
        { name: 'compensatoryRest', label: t('masterdata.col.compRest'), type: 'checkbox' }, { name: 'color', label: t('masterdata.col.color'), placeholder: '#RRGGBB' }],
      shifts: [{ name: 'areaId', label: t('masterdata.col.area'), type: 'select', options: areas }, { name: 'code', label: t('masterdata.col.code'), required: true },
        { name: 'name', label: t('masterdata.col.name'), required: true }, { name: 'start', label: t('masterdata.col.start'), type: 'time', required: true },
        { name: 'end', label: t('masterdata.col.end'), type: 'time', required: true },
        { name: 'category', label: t('masterdata.col.category'), type: 'select', options: [['', t('masterdata.category.auto')], ...CATEGORIES.map((c) => [c, CATEGORY[c]])] },
        { name: 'color', label: t('masterdata.col.color'), placeholder: '#RRGGBB' }, { name: 'unpopular', label: t('masterdata.col.unpopular'), type: 'checkbox' }],
      demand: [{ name: 'areaId', label: t('masterdata.col.area'), type: 'select', options: areas },
        { name: 'mode', label: t('masterdata.col.valid'), type: 'select', options: [['days', t('masterdata.demand.byDays')], ['date', t('masterdata.demand.byDate')]] },
        { name: 'days', label: t('masterdata.demand.days'), type: 'checks', options: DEMAND_DAYS.map((d) => [d, DAY_CODES[d]]), mode: 'days' },
        { name: 'date', label: t('masterdata.col.date'), type: 'date', mode: 'date' }, { name: 'from', label: t('masterdata.col.from'), type: 'time', required: true },
        { name: 'to', label: t('masterdata.col.to'), type: 'time', required: true }, { name: 'min', label: t('masterdata.col.min'), type: 'number', step: 1, min: 0, required: true },
        { name: 'max', label: t('masterdata.col.max'), type: 'number', step: 1, min: 0 }],
      holidays: [{ name: 'date', label: t('masterdata.col.date'), type: 'date', required: true }, { name: 'name', label: t('masterdata.col.name'), required: true }],
    }[kind];
  }

  const list = (kind) => dataset[kind] ?? [];
  const find = (kind, key) => list(kind).find((x, i) => keyOf(kind, x, i) === key) ?? null;
  const personRestrictions = (personId) => (dataset.restrictions ?? []).filter((r) => r.personId === personId);
  const sel = (kind, key) => `[data-kind="${kind}"][data-key="${CSS.escape(String(key))}"]`;

  function draw() {
    root.replaceChildren(h('h1', {}, t('nav.masterdata')),
      h('p.muted', {}, t('masterdata.source', { source: dataset.source ?? t('masterdata.unknown'), people: dataset.people.length, areas: dataset.areas.length, shifts: dataset.shifts.length })),
      section('people'),
      h('div.grid-2', {}, section('areas'), section('absenceTypes')),
      section('shifts'), section('demand'), section('holidays'));
  }

  function section(kind) {
    const id = `h-${kind}`;
    const cols = columns(kind);
    const rows = list(kind).map((item, index) => ({ item, key: keyOf(kind, item, index) }));
    if (kind === 'people') rows.sort((a, b) => personName(a.item).localeCompare(personName(b.item), 'de'));
    const button = (label, aria, onclick, data = {}) => h('button.btn-link', { type: 'button', 'aria-label': aria, onclick, ...data }, label);
    const actions = (item, key) => {
      const data = { 'data-kind': kind, 'data-key': String(key) };
      const label = itemLabel(kind, item);
      return h('td', {}, button(t('masterdata.edit'), t('masterdata.editLabel', { label }), () => openForm(kind, key), data), ' ',
        kind === 'people' && [button(t('masterdata.restrictions'), t('masterdata.restrictionsLabel', { label }), () => openRestrictions(key)), ' '],
        button(t('masterdata.remove'), t('masterdata.removeLabel', { label }), () => remove(kind, key)));
    };
    const table = h('table.data', { 'data-table': kind },
      h('thead', {}, h('tr', {}, cols.map(([label]) => h('th', {}, label)), canEdit && h('th', {}, ''))),
      h('tbody', {}, rows.map(({ item, key }) => h('tr', {}, cols.map(([, cell, cls]) => h(cls ? `td.${cls}` : 'td', {}, cell(item))), canEdit && actions(item, key)))));
    return h('section.section', { 'aria-labelledby': id }, h('h2', { id }, TITLES[kind]),
      canEdit && h('div.toolbar', {}, h('button.btn.btn-secondary.btn-small', { type: 'button', 'data-new': kind, onclick: () => openForm(kind, null) }, t('masterdata.new'))),
      h('div', { style: { overflowX: 'auto' } }, table));
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
    base && kind === 'people' && h('span.muted', {}, `${t('masterdata.col.id')}: ${base.id}`),
    defs.map((f) => control(f, v[f.name])), formButtons(), problems());
    if (kind === 'demand') {
      const mode = form.querySelector('#f-mode');
      const show = () => form.querySelectorAll('[data-mode]').forEach((el) => { el.hidden = el.dataset.mode !== mode.value; });
      mode.addEventListener('change', show);
      show();
    }
    placeForm(kind, key, form);
  }

  /** Restrictions of a person: a local list, saved as a whole. */
  function openRestrictions(personId, values = null) {
    const person = find('people', personId);
    const base = personRestrictions(personId);
    const local = (values ?? base).map((r) => ({ ...r }));
    const shifts = new Map(dataset.shifts.map((s) => [s.id, s]));
    const items = h('ul', {});
    const drawItems = () => items.replaceChildren(...(local.length ? local.map((r, i) => h('li', {}, restrictionText(r, shifts), ' ',
      h('button.btn-link', { type: 'button', 'aria-label': t('masterdata.restriction.removeLabel', { text: restrictionText(r, shifts) }),
        onclick: () => { local.splice(i, 1); drawItems(); } }, t('masterdata.remove')))) : [h('li.muted', {}, t('masterdata.restriction.none'))]));
    const add = [
      { name: 'target', label: t('masterdata.restriction.target'), type: 'select', options: [...CATEGORIES.map((c) => [`category:${c}`, t('masterdata.restriction.category', { category: CATEGORY[c] })]),
        ...dataset.shifts.map((s) => [`shift:${s.id}`, `${s.code} ${s.name}`])] },
      { name: 'weekday', label: t('masterdata.restriction.weekday'), type: 'select', options: [['0', t('masterdata.restriction.everyDay')], ...WEEKDAY_OPTIONS] },
      { name: 'kind', label: t('masterdata.restriction.kind'), type: 'select', options: [['NEVER', t('masterdata.restriction.never')], ['ON_REQUEST', t('masterdata.restriction.onRequest')]] },
      { name: 'validFrom', label: t('masterdata.restriction.validFrom'), type: 'date' }, { name: 'validTo', label: t('masterdata.restriction.validTo'), type: 'date' }];
    const form = h('form.toolbar', { onsubmit: (e) => {
      e.preventDefault();
      busy(form, () => write({ kind: 'restrictions', key: personId, base, item: local, values: local }));
    } },
    h('div', { style: { flexBasis: '100%' } }, h('strong', {}, t('masterdata.restriction.title', { name: personName(person) })), items),
    add.map((f) => control(f, f.type === 'date' ? '' : f.options[0][0])),
    h('button.btn.btn-secondary.btn-small', { type: 'button', onclick: () => {
      try {
        local.push(restrictionFromForm(personId, readForm(form, add)));
        drawItems();
      } catch (err) {
        showProblem(err);
      }
    } }, t('masterdata.restriction.add')),
    formButtons(), problems());
    drawItems();
    placeForm('people', personId, form);
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
      if (kind === 'restrictions') res = await api.saveRestrictions(key, item, revision);
      else if (item === undefined) res = await api.deleteMaster(kind, key, revision, op.confirmed);
      else if (key == null) res = await api.createMaster(kind, item, revision);
      else res = await api.updateMaster(kind, key, item, revision);
    } catch (e) {
      if (e.code === 'masterdata.conflict') return resolveConflict(op);
      if (e.code === 'masterdata.inUse' && item === undefined && !op.confirmed) {
        if (confirm(inUseQuestion(kind, op.base, e.params))) return write({ ...op, confirmed: true });
        return undefined;
      }
      return showProblem(e, kind, item !== undefined);
    }
    toast(item === undefined ? t('masterdata.removed') : t('masterdata.saved'));
    const focusKind = kind === 'restrictions' ? 'people' : kind;
    // deleting may also remove references (restrictions, demand, areas of people): always read everything again
    await reload();
    const target = item === undefined || res?.key == null ? root.querySelector(`[data-new="${focusKind}"]`) : root.querySelector(sel(focusKind, res.key));
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
    // the own values back in the form on the newer version, so that a rejected retry can be corrected
    const reopen = () => (op.kind === 'restrictions' ? openRestrictions(op.key, op.values) : openForm(op.kind, decision.key, op.values));
    if (decision.action === 'retry') {
      if (op.item !== undefined) reopen();
      await write({ ...op, key: decision.key });
    } else if (decision.action === 'changed' && op.item !== undefined) {
      // the object itself was changed too: do not save, let the user check
      reopen();
      showProblem(new Error(t('masterdata.conflict.changed')));
    } else if (decision.action === 'gone' && op.item !== undefined && op.kind !== 'restrictions') {
      // changed or removed meanwhile (holidays and demand cannot tell): the own values as a new entry, not saved
      openForm(op.kind, null, op.values);
      showProblem(new Error(t('masterdata.conflict.goneForm')));
    } else {
      toast(decision.action === 'gone' ? t('masterdata.conflict.gone') : t('masterdata.conflict.changedRemove'), true);
    }
  }

  /** An error in the open form (with the lists of a rejected change), else (removing) as a message. */
  function showProblem(e, kind = null, inForm = true) {
    const errors = e.body?.errors ?? [];
    const warnings = e.body?.warnings ?? [];
    // a person in a closed month stays: the exit date ends the employment instead
    const hint = e.code === 'masterdata.inUseClosed' && kind === 'people' ? ` ${t('masterdata.inUseClosed.person')}` : '';
    const host = inForm ? root.querySelector('tr.edit-row .form-problems') : null;
    if (host) {
      host.replaceChildren(h('div.alert.alert-error', { role: 'alert' }, h('strong', {}, e.message + hint),
        errors.length ? h('ul', {}, errors.map((x) => h('li', {}, messageText(x)))) : null,
        warnings.length ? [h('p', {}, h('strong', {}, t('import.warnings', { count: warnings.length }))),
          h('ul', {}, warnings.map((w) => h('li', {}, messageText(w))))] : null));
    } else toast(e.message + hint, true);
  }

  function control(f, value) {
    const id = `f-${f.name}`;
    const data = f.mode ? { 'data-mode': f.mode } : {};
    if (f.type === 'family') return familyEditor(value ?? []);
    if (f.type === 'checks') {
      return h('fieldset', { style: bare, ...data }, h('legend', { style: { fontWeight: 600 } }, f.label),
        f.options.map(([v, label]) => h('label', { style: inline }, h('input', { type: 'checkbox', name: f.name, value: v, checked: (value ?? []).includes(v) }), ` ${label}`)));
    }
    if (f.type === 'checkbox') return h('div.field', data, h('label', {}, h('input', { id, type: 'checkbox', checked: Boolean(value) }), ` ${f.label}`));
    const input = f.type === 'select'
      ? h('select', { id }, f.options.map(([v, label]) => h('option', { value: v, selected: v === value }, label)))
      : h('input', { id, type: f.type ?? 'text', value: value ?? '', required: f.required, readOnly: f.readOnly, step: f.step, min: f.min, placeholder: f.placeholder });
    return h('div.field', data, h('label', { for: id }, f.label), input);
  }

  /** Family rules of the person form: one line per rule, added and removed in the form, saved with the person. */
  function familyEditor(rules) {
    const host = h('div', {});
    let n = 0;
    const line = (r) => {
      const i = n++;
      const id = (k) => `f-fr${i}-${k}`;
      const el = h('fieldset.family-rule', { style: { ...bare, display: 'flex', flexWrap: 'wrap', gap: '.4rem .8rem', alignItems: 'end' } },
        h('legend', { style: { fontWeight: 600 } }, t('masterdata.family.rule', { number: i + 1 })),
        h('span', {}, WEEKDAY_OPTIONS.map(([v, label]) => h('label', { style: inline },
          h('input', { type: 'checkbox', 'data-fr': 'weekdays', value: v, checked: r.weekdays.includes(v) }), ` ${label}`))),
        h('div.field', {}, h('label', { for: id('latestEnd') }, t('masterdata.family.col.latestEnd')), h('input', { id: id('latestEnd'), 'data-fr': 'latestEnd', type: 'time', value: r.latestEnd })),
        h('div.field', {}, h('label', { for: id('earliestStart') }, t('masterdata.family.col.earliestStart')), h('input', { id: id('earliestStart'), 'data-fr': 'earliestStart', type: 'time', value: r.earliestStart })),
        h('label', { style: inline }, h('input', { type: 'checkbox', 'data-fr': 'noNights', checked: r.noNights }), ` ${t('masterdata.family.noNights')}`),
        h('div.field', {}, h('label', { for: id('note') }, t('masterdata.family.col.note')), h('input', { id: id('note'), 'data-fr': 'note', value: r.note })),
        h('button.btn-link', { type: 'button', onclick: () => el.remove() }, t('masterdata.family.remove')));
      return el;
    };
    const lines = h('div', {}, rules.map(line));
    host.append(h('strong', {}, t('masterdata.col.familyRules')), lines,
      h('button.btn.btn-secondary.btn-small', { type: 'button', onclick: () => lines.append(line({ weekdays: [], latestEnd: '', earliestStart: '', noNights: false, note: '' })) },
        t('masterdata.family.add')));
    return h('div', { style: { flexBasis: '100%' } }, host);
  }

  draw();
}

/** Form values as the pure module reads them (masterdata-edit.js). */
function readForm(form, defs) {
  const out = {};
  for (const f of defs) {
    if (f.type === 'family') {
      out[f.name] = [...form.querySelectorAll('fieldset.family-rule')].map((el) => ({
        weekdays: [...el.querySelectorAll('[data-fr=weekdays]:checked')].map((c) => c.value),
        latestEnd: el.querySelector('[data-fr=latestEnd]').value, earliestStart: el.querySelector('[data-fr=earliestStart]').value,
        noNights: el.querySelector('[data-fr=noNights]').checked, note: el.querySelector('[data-fr=note]').value }));
    } else if (f.type === 'checks') out[f.name] = [...form.querySelectorAll(`input[name="${f.name}"]:checked`)].map((c) => c.value);
    else if (f.type === 'checkbox') out[f.name] = form.querySelector(`#f-${f.name}`).checked;
    else out[f.name] = form.querySelector(`#f-${f.name}`).value;
  }
  return out;
}

const de = (iso) => `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}`;

/** Employment period for the people table: entry and exit date, either may be open. */
export function employmentText(p) {
  if (p.entryDate && p.exitDate) return t('masterdata.employment.range', { from: de(p.entryDate), to: de(p.exitDate) });
  if (p.entryDate) return t('masterdata.employment.from', { from: de(p.entryDate) });
  if (p.exitDate) return t('masterdata.employment.until', { to: de(p.exitDate) });
  return '';
}
