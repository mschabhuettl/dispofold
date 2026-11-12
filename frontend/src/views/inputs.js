// Eingaben of the month: absences, wishes and fixed duties. Saved together via PUT /api/periods/{month}.
import { dayLabel, monthDates, monthLabel } from '../lib/dates.js';
import { append, guarded, h, toast } from '../lib/dom.js';
import { absenceName, personName } from '../lib/grid-model.js';
import { mergePeriod } from '../lib/period-merge.js';
import { messageText } from '../lib/codes.js';
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

  root.append(h('h1', {}, t('inputs.title', { month: monthLabel(month) })),
    canEdit ? h('div', {}, h('div.toolbar', {}, saveBtn, h('span.muted', { id: 'dirty-note' }, '')), problems)
      : h('p.alert', {}, closed ? t('inputs.closed') : t('inputs.readonly')),
    section(t('inputs.section.absences'), 'absences', canEdit && addForm('abs', [
      [t('inputs.person'), personSelect('abs-person')], [t('inputs.date'), dateSelect('abs-date')],
      [t('inputs.type'), h('select', { id: 'abs-type' }, dataset.absenceTypes.map((type) => h('option', { value: type.code }, `${type.code} ${type.name}`)))],
    ], () => add('absences', { personId: val('abs-person'), date: val('abs-date'), typeCode: val('abs-type') }))),
    // '' instead of false: root.append would print "false"
    !showWishes ? '' : section(t('inputs.section.wishes'), 'wishes', canEdit && addForm('wish', [
      [t('inputs.person'), personSelect('wish-person')], [t('inputs.date'), dateSelect('wish-date')],
      [t('inputs.type'), h('select', { id: 'wish-kind' }, Object.entries(WISH_KINDS).map(([k, v]) => h('option', { value: k }, v)))],
      [t('inputs.wishShift'), shiftSelect('wish-shift', false, 'wish-person')],
    ], () => {
      const kind = val('wish-kind');
      add('wishes', { personId: val('wish-person'), date: val('wish-date'), kind, shiftId: kind === 'SHIFT' || kind === 'NOT_SHIFT' ? val('wish-shift') : null });
    }), h('p.muted', {}, t('inputs.wishLimit', { limit }))),
    section(t('inputs.section.fixed'), 'fixed', canEdit && addForm('fix', [
      [t('inputs.person'), personSelect('fix-person')], [t('inputs.date'), dateSelect('fix-date')], [t('inputs.duty'), shiftSelect('fix-shift', true, 'fix-person')],
    ], () => add('fixed', { personId: val('fix-person'), date: val('fix-date'), shiftId: val('fix-shift') || null }))),
  );

  function section(title, key, form, note) {
    const id = `h-${key}`;
    return h('section.section', { 'aria-labelledby': id }, h('h2', { id }, title), note, form, sections[key]);
  }

  function addForm(prefix, fields, onAdd) {
    return h('div.toolbar', {}, fields.map(([label, control]) => h('div.field', {}, h('label', { for: control.id }, label), control)),
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

  function setSaved() {
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
      }
      toast(e.message, true);
      return;
    }
    problems.replaceChildren();
    toast(t('inputs.saved'));
    if (res) Object.assign(period, res); // the server drops entries with unknown ids and counts up the revision
    draw();
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
      draw();
      setSaved();
      toast(t('inputs.conflict.reloaded'));
    }
  }

  function table(key, columns, rows) {
    const sorted = [...rows].sort((a, b) => a.date.localeCompare(b.date) || name(a.personId).localeCompare(name(b.personId), 'de'));
    if (!sorted.length) return h('p.muted', {}, t('inputs.none'));
    return h('table.data', {},
      h('thead', {}, h('tr', {}, h('th', {}, t('inputs.date')), h('th', {}, t('inputs.person')), columns.map(([label]) => h('th', {}, label)), canEdit && h('th', {}, ''))),
      h('tbody', {}, sorted.map((r) => h('tr', {}, h('td', {}, dayLabel(r.date)), h('td', {}, name(r.personId)),
        columns.map(([, get]) => h('td', {}, get(r))),
        canEdit && h('td', {}, h('button.btn-link', { type: 'button', onclick: () => remove(key, r), 'aria-label': t('inputs.removeLabel', { day: dayLabel(r.date), name: name(r.personId) }) }, t('inputs.remove')))))));
  }

  function draw() {
    sections.absences.replaceChildren(table('absences', [[t('inputs.type'), (r) => `${r.typeCode} ${absenceName(r.typeCode, types)}`]], period.absences));
    const counts = new Map();
    for (const w of period.wishes) counts.set(w.personId, (counts.get(w.personId) ?? 0) + 1);
    sections.wishes.replaceChildren(table('wishes', [
      [t('inputs.col.wish'), (r) => `${WISH_KINDS[r.kind] ?? r.kind}${r.shiftId ? `: ${shiftLabel(r.shiftId)}` : ''}`],
      [t('inputs.col.wishCount'), (r) => {
        const n = counts.get(r.personId);
        return n > limit ? h('strong.text-danger', {}, t('inputs.tooMany', { value: n, total: limit })) : t('common.outOf', { value: n, total: limit });
      }],
    ], period.wishes));
    sections.fixed.replaceChildren(table('fixed', [[t('inputs.duty'), (r) => shiftLabel(r.shiftId)]], period.fixed));
  }
  draw();
  ctx.isDirty = () => dirty;
}
