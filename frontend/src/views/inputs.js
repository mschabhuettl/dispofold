// Eingaben of the month: absences (entered and shown as date ranges, stored per day), wishes and fixed duties, with a
// person filter for all three and links to the three sections. Saved together via PUT /api/periods/{month} from a bar
// that stays at the bottom of the window; rows added since then are marked.
import { dayLabel, monthDates, monthLabel } from '../lib/dates.js';
import { append, guarded, h, toast } from '../lib/dom.js';
import { absenceName, personName } from '../lib/grid-model.js';
import { absenceRanges, addAbsenceRange, mergePeriod } from '../lib/period-merge.js';
import { messageText } from '../lib/codes.js';
import { info } from '../lib/info.js';
import { t } from '../lib/texts.js';

const WISH_KINDS = { OFF: t('inputs.wish.off'), DUTY: t('inputs.wish.duty'), SHIFT: t('inputs.wish.shift'), NOT_SHIFT: t('inputs.wish.notShift') };

export async function render(root, ctx) {
  const { api, month } = ctx;
  const [dataset, loaded, rules, state] = await Promise.all([ctx.dataset(), api.period(month), api.rules(),
    api.planState(month).catch(() => null)]);
  const closed = state?.plan?.status === 'CLOSED';
  // closed months are read-only; viewers never see wishes (the server does not send them)
  const canEdit = ctx.canEdit && !closed;
  const showWishes = ctx.canEdit;
  const emptyPeriod = () => ({ month, absences: [], wishes: [], fixed: [], previousDuties: [] });
  const period = { ...emptyPeriod(), ...loaded };
  let base = structuredClone(period); // the version last read from the server, for merging after a conflict
  const people = [...dataset.people].sort((a, b) => personName(a).localeCompare(personName(b), 'de'));
  const byId = new Map(people.map((p) => [p.id, p]));
  const shifts = new Map(dataset.shifts.map((s) => [s.id, s]));
  const types = new Map(dataset.absenceTypes.map((type) => [type.code, type]));
  const dates = monthDates(month);
  const limit = rules.maxWishesPerPerson;
  let dirty = false;
  // "<personId>|<date>" of the days added since the last save, per list; the last one added is scrolled to
  const added = { absences: new Set(), wishes: new Set(), fixed: new Set() };
  let lastAdded = null;

  const name = (id) => (byId.has(id) ? personName(byId.get(id)) : id);
  const shiftLabel = (id) => (id ? `${shifts.get(id)?.code ?? id} ${shifts.get(id)?.name ?? ''}` : t('inputs.free'));
  const personSelect = (id) => h('select', { id }, people.map((p) => h('option', { value: p.id }, personName(p))));
  const dateSelect = (id) => h('select', { id }, dates.map((d) => h('option', { value: d }, dayLabel(d))));
  /** Shift choices limited to the areas of the person picked in personId's select. */
  const shiftSelect = (id, withFree, personSelectId) => {
    const select = h('select', { id });
    const fill = () => {
      const person = byId.get(root.querySelector(`#${personSelectId}`)?.value) ?? people[0];
      select.replaceChildren();
      append(select, [withFree && h('option', { value: '' }, t('inputs.freeDay')),
        dataset.shifts.filter((s) => person?.areaIds.includes(s.areaId)).map((s) => h('option', { value: s.id }, `${s.code} ${s.name}`))]);
    };
    queueMicrotask(() => {
      fill();
      root.querySelector(`#${personSelectId}`)?.addEventListener('change', fill);
    });
    return select;
  };
  const saveBtn = h('button.btn.btn-primary', { type: 'button', onclick: save, disabled: true }, t('common.save'));
  const problems = h('div', { 'aria-live': 'polite' });

  const sections = {
    absences: h('div', {}),
    wishes: h('div', {}),
    fixed: h('div', {}),
  };

  // display only: saving always sends the whole month, and the wish counts are those of all wishes
  let shownPerson = '';
  const filter = h('select', { id: 'inputs-filter', onchange: () => {
    shownPerson = filter.value;
    // new entries are for the shown person
    if (shownPerson) root.querySelectorAll('#abs-person, #wish-person, #fix-person').forEach((s) => { s.value = shownPerson; s.dispatchEvent(new Event('change')); });
    draw();
  } }, h('option', { value: '' }, t('inputs.filter.all')), people.map((p) => h('option', { value: p.id }, personName(p))));

  // links to the three sections (buttons: a link to an anchor would be taken for a page of the program)
  const jumpTo = (key, label) => h('button.btn-link', { type: 'button', onclick: () => {
    const heading = root.querySelector(`#h-${key}`);
    heading.scrollIntoView({ block: 'start' });
    heading.focus({ preventScroll: true });
  } }, label);
  root.append(h('h1', {}, t('inputs.title', { month: monthLabel(month) })),
    canEdit ? problems
      : h('p.alert', {}, closed ? (ctx.canEdit ? t('inputs.closed') : t('common.closedReadonly')) : t('inputs.readonly')),
    h('nav.jump-links', { 'aria-label': t('inputs.jumpLabel') }, jumpTo('absences', t('inputs.section.absences')),
      showWishes && jumpTo('wishes', t('inputs.section.wishes')), jumpTo('fixed', t('inputs.section.fixed'))),
    h('div.toolbar', {}, h('div.field', {}, h('div.with-info', {}, h('label', { for: 'inputs-filter' }, t('inputs.filter')),
      info(t('help.field.inputs.filter'), t('inputs.filter'))), filter)),
    section(t('inputs.section.absences'), 'absences', t('help.field.inputs.absences'), canEdit && addForm('abs', [
      [t('inputs.person'), personSelect('abs-person'), t('help.field.inputs.absencePerson')],
      [t('inputs.from'), dateSelect('abs-from'), t('help.field.inputs.absenceFrom')], [t('inputs.to'), dateSelect('abs-to'), t('help.field.inputs.absenceTo')],
      [t('inputs.type'), h('select', { id: 'abs-type' }, dataset.absenceTypes.map((type) => h('option', { value: type.code }, `${type.code} ${type.name}`))), t('help.field.inputs.absenceType')],
    ], () => {
      const range = { personId: val('abs-person'), from: val('abs-from'), to: val('abs-to'), typeCode: val('abs-type') };
      if (range.to < range.from) {
        toast(t('inputs.rangeOrder'), true);
        return;
      }
      period.absences = addAbsenceRange(period.absences, range);
      for (const a of period.absences) {
        if (a.personId === range.personId && a.date >= range.from && a.date <= range.to) added.absences.add(`${a.personId}|${a.date}`);
      }
      lastAdded = { key: 'absences', id: `${range.personId}|${range.from}` };
      changed();
    })),
    // '' instead of false: root.append would print "false"
    !showWishes ? '' : section(t('inputs.section.wishes'), 'wishes', t('help.field.inputs.wishes'), canEdit && addForm('wish', [
      [t('inputs.person'), personSelect('wish-person'), t('help.field.inputs.wishPerson')], [t('inputs.date'), dateSelect('wish-date'), t('help.field.inputs.wishDate')],
      [t('inputs.type'), h('select', { id: 'wish-kind', onchange: wishShiftNeeded }, Object.entries(WISH_KINDS).map(([k, v]) => h('option', { value: k }, v))), t('help.field.inputs.wishKind')],
      [t('inputs.wishShift'), shiftSelect('wish-shift', false, 'wish-person'), t('help.field.inputs.wishShift')],
    ], () => {
      const kind = val('wish-kind');
      add('wishes', { personId: val('wish-person'), date: val('wish-date'), kind, shiftId: kind === 'SHIFT' || kind === 'NOT_SHIFT' ? val('wish-shift') : null });
    }), h('p.muted', {}, t('inputs.wishLimit', { limit }))),
    section(t('inputs.section.fixed'), 'fixed', t('help.field.inputs.fixed'), canEdit && addForm('fix', [
      [t('inputs.person'), personSelect('fix-person'), t('help.field.inputs.fixedPerson')], [t('inputs.date'), dateSelect('fix-date'), t('help.field.inputs.fixedDate')],
      [t('inputs.duty'), shiftSelect('fix-shift', true, 'fix-person'), t('help.field.inputs.fixedShift')],
    ], () => add('fixed', { personId: val('fix-person'), date: val('fix-date'), shiftId: val('fix-shift') || null }))),
    // as on the rules page: the save button stays at the bottom of the window
    canEdit ? h('div.save-bar', {}, saveBtn, h('span.muted', { id: 'dirty-note', role: 'status' }, '')) : '',
  );
  if (canEdit) wishShiftNeeded();

  /** The shift of a wish only for "diese Schicht" and "diese Schicht nicht". */
  function wishShiftNeeded() {
    const kind = root.querySelector('#wish-kind')?.value;
    const shift = root.querySelector('#wish-shift');
    if (shift) shift.disabled = kind !== 'SHIFT' && kind !== 'NOT_SHIFT';
  }

  function section(title, key, help, form, note) {
    const id = `h-${key}`;
    return h('section.section', { 'aria-labelledby': id }, h('h2', { id, tabindex: '-1' }, title, info(help, title)), note, form, sections[key]);
  }

  /** Fields: [label, control, help (optional)]. */
  function addForm(prefix, fields, onAdd) {
    return h('div.toolbar', {}, fields.map(([label, control, help]) => h('div.field', {},
      help ? h('div.with-info', {}, h('label', { for: control.id }, label), info(help, label)) : h('label', { for: control.id }, label), control)),
      h('button.btn.btn-secondary', { type: 'button', onclick: onAdd }, t('inputs.add')));
  }

  function val(id) {
    return root.querySelector(`#${id}`).value;
  }

  function add(key, item) {
    const list = period[key];
    const i = list.findIndex((x) => x.personId === item.personId && x.date === item.date);
    if (i >= 0) list.splice(i, 1);
    list.push(item);
    added[key].add(`${item.personId}|${item.date}`);
    lastAdded = { key, id: `${item.personId}|${item.date}` };
    changed();
  }

  function remove(key, item) {
    period[key] = period[key].filter((x) => x !== item);
    changed();
  }

  function changed() {
    dirty = true;
    saveBtn.disabled = false;
    root.querySelector('#dirty-note').textContent = t('inputs.unsaved');
    draw();
  }

  /** Saved or reloaded: no row is new any more. */
  function setSaved() {
    for (const set of Object.values(added)) set.clear();
    draw();
    base = structuredClone(period);
    dirty = false;
    saveBtn.disabled = true;
    root.querySelector('#dirty-note').textContent = '';
  }

  async function save() {
    let res;
    try {
      res = await api.savePeriod(month, period); // carries the revision it was read with
    } catch (e) {
      if (e.code === 'period.conflict') return resolveConflict();
      const errors = e.body?.errors ?? [];
      if (errors.length) {
        // every finding, not only the first (e.g. a date outside the month)
        problems.replaceChildren(h('div.alert.alert-error', { role: 'alert' }, h('strong', {}, e.message),
          h('ul', {}, errors.map((x) => h('li', {}, messageText(x))))));
        problems.scrollIntoView({ block: 'nearest' });
      }
      toast(e.message, true);
      return;
    }
    problems.replaceChildren();
    toast(t('inputs.saved'));
    if (res) Object.assign(period, res); // the server drops entries with unknown ids and counts up the revision
    setSaved();
  }

  /** Someone else saved meanwhile: merge the own changes into the newer version, or drop them and reload. */
  async function resolveConflict() {
    const loadedNow = await guarded(() => api.period(month));
    if (!loadedNow) return;
    const server = { ...emptyPeriod(), ...loadedNow };
    if (confirm(t('inputs.conflict.prompt'))) {
      const { period: merged, conflicts } = mergePeriod(base, period, server);
      Object.assign(period, merged);
      base = structuredClone(server);
      changed();
      toast(conflicts.length ? t('inputs.conflict.mergedClashes', { count: conflicts.length }) : t('inputs.conflict.merged'));
    } else {
      Object.assign(period, server);
      setSaved();
      toast(t('inputs.conflict.reloaded'));
    }
  }

  const shown = (rows) => rows.filter((r) => !shownPerson || r.personId === shownPerson);

  /** Absences: one row per range of consecutive days of one person and type; removing a row removes all its days. */
  function rangeTable() {
    const ranges = shown(absenceRanges(period.absences)).sort((a, b) => a.from.localeCompare(b.from) || name(a.personId).localeCompare(name(b.personId), 'de'));
    if (!ranges.length) return h('p.muted', {}, t('inputs.none'));
    const label = (r) => (r.from === r.to ? dayLabel(r.from) : t('inputs.range', { from: dayLabel(r.from), to: dayLabel(r.to) }));
    return h('table.data', { 'data-table': 'absences' },
      h('thead', {}, h('tr', {}, h('th', {}, t('inputs.col.range')), h('th', {}, t('inputs.person')), h('th', {}, t('inputs.type')), h('th.num', {}, t('inputs.col.days')),
        canEdit && h('th', {}, ''))),
      h('tbody', {}, ranges.map((r) => h('tr', addedRow('absences', r.entries), h('td', {}, label(r)), h('td', {}, name(r.personId)), h('td', {}, `${r.typeCode} ${absenceName(r.typeCode, types)}`),
        h('td.num', {}, String(r.entries.length)),
        canEdit && h('td', {}, addedNote('absences', r.entries), h('button.btn-link', { type: 'button', 'aria-label': t('inputs.removeRangeLabel', { range: label(r), name: name(r.personId) }),
          onclick: () => {
            period.absences = period.absences.filter((x) => !r.entries.includes(x));
            changed();
          } }, t('inputs.remove')))))));
  }

  const isAdded = (key, entries) => entries.some((x) => added[key].has(`${x.personId}|${x.date}`));
  /** Attributes of a row: marked when one of its days was added since the last save. */
  function addedRow(key, entries) {
    if (!isAdded(key, entries)) return {};
    const scroll = lastAdded?.key === key && entries.some((x) => `${x.personId}|${x.date}` === lastAdded.id);
    return { class: 'row-added', 'data-scroll': scroll ? 'true' : null };
  }

  function addedNote(key, entries) {
    return isAdded(key, entries) && h('span.added-note', {}, t('inputs.added'));
  }

  function table(key, columns, rows) {
    const sorted = shown(rows).sort((a, b) => a.date.localeCompare(b.date) || name(a.personId).localeCompare(name(b.personId), 'de'));
    if (!sorted.length) return h('p.muted', {}, t('inputs.none'));
    return h('table.data', {},
      h('thead', {}, h('tr', {}, h('th', {}, t('inputs.date')), h('th', {}, t('inputs.person')), columns.map(([label]) => h('th', {}, label)), canEdit && h('th', {}, ''))),
      h('tbody', {}, sorted.map((r) => h('tr', addedRow(key, [r]), h('td', {}, dayLabel(r.date)), h('td', {}, name(r.personId)),
        columns.map(([, get]) => h('td', {}, get(r))),
        canEdit && h('td', {}, addedNote(key, [r]), h('button.btn-link', { type: 'button', onclick: () => remove(key, r), 'aria-label': t('inputs.removeLabel', { day: dayLabel(r.date), name: name(r.personId) }) }, t('inputs.remove')))))));
  }

  function draw() {
    sections.absences.replaceChildren(rangeTable());
    const counts = new Map();
    for (const w of period.wishes) counts.set(w.personId, (counts.get(w.personId) ?? 0) + 1);
    sections.wishes.replaceChildren(table('wishes', [
      [t('inputs.col.wish'), (r) => `${WISH_KINDS[r.kind] ?? r.kind}${r.shiftId ? `: ${shiftLabel(r.shiftId)}` : ''}`],
      [t('inputs.col.wishCount', { limit }), (r) => {
        const n = counts.get(r.personId);
        return n > limit ? h('strong.text-danger', {}, t('inputs.tooMany', { value: n, total: limit })) : t('common.outOf', { value: n, total: limit });
      }],
    ], period.wishes));
    sections.fixed.replaceChildren(table('fixed', [[t('inputs.duty'), (r) => shiftLabel(r.shiftId)]], period.fixed));
    // the row just added: into view (it may be far down the list); hidden by the person filter it stays unseen
    lastAdded = null;
    root.querySelector('tr[data-scroll]')?.scrollIntoView({ block: 'center' });
  }
  draw();
  ctx.isDirty = () => dirty;
}
