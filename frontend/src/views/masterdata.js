// Stammdaten: people (editable for planners), areas, shifts and demand.
import { fmt, guarded, h, toast } from '../lib/dom.js';
import { tintStyle } from '../lib/colors.js';
import { WEEKDAY_SHORT } from '../lib/dates.js';
import { personName } from '../lib/grid-model.js';
import { t } from '../lib/texts.js';

const CATEGORY = { EARLY: t('masterdata.category.early'), DAY: t('masterdata.category.day'), LATE: t('masterdata.category.late'), NIGHT: t('masterdata.category.night') };
const WEEKDAYS = ['', ...WEEKDAY_SHORT];
const DAY_CODES = { MON: WEEKDAY_SHORT[0], TUE: WEEKDAY_SHORT[1], WED: WEEKDAY_SHORT[2], THU: WEEKDAY_SHORT[3], FRI: WEEKDAY_SHORT[4], SAT: WEEKDAY_SHORT[5],
  SUN: WEEKDAY_SHORT[6], HOL: t('masterdata.holiday') };

export async function render(root, ctx) {
  const { api, canEdit } = ctx;
  const dataset = await ctx.dataset();
  const areaName = new Map(dataset.areas.map((a) => [a.id, a.name]));
  const peopleHost = h('div', { style: { overflowX: 'auto' } });
  const hhmm = (s) => String(s ?? '').slice(0, 5);

  root.append(h('h1', {}, t('nav.masterdata')),
    h('p.muted', {}, t('masterdata.source', { source: dataset.source ?? t('masterdata.unknown'), people: dataset.people.length, areas: dataset.areas.length, shifts: dataset.shifts.length })),
    h('section.section', { 'aria-labelledby': 'h-people' }, h('h2', { id: 'h-people' }, t('masterdata.section.people')), peopleHost),
    h('div.grid-2', {},
      h('section.section', { 'aria-labelledby': 'h-areas' }, h('h2', { id: 'h-areas' }, t('masterdata.section.areas')),
        h('table.data', {}, h('thead', {}, h('tr', {}, h('th', {}, t('masterdata.col.shortName')), h('th', {}, t('masterdata.col.name')))),
          h('tbody', {}, dataset.areas.map((a) => h('tr', {}, h('td', {}, h('span.cell-code.strong.tinted', { style: tintStyle(a.color) }, a.id)), h('td', {}, a.name)))))),
      h('section.section', { 'aria-labelledby': 'h-abs' }, h('h2', { id: 'h-abs' }, t('masterdata.section.absences')),
        h('table.data', {}, h('thead', {}, h('tr', {}, h('th', {}, t('masterdata.col.code')), h('th', {}, t('masterdata.col.name')), h('th', {}, t('masterdata.col.reducesTarget')), h('th', {}, t('masterdata.col.chargesAccount')), h('th', {}, t('masterdata.col.compRest')))),
          h('tbody', {}, dataset.absenceTypes.map((a) => h('tr', {}, h('td', {}, a.code), h('td', {}, a.name), h('td', {}, yesNo(a.reducesTarget)),
            h('td', {}, yesNo(a.chargesAccount)), h('td', {}, yesNo(a.compensatoryRest)))))))),
    h('section.section', { 'aria-labelledby': 'h-shifts' }, h('h2', { id: 'h-shifts' }, t('masterdata.section.shifts')),
      h('table.data', {}, h('thead', {}, h('tr', {}, [t('masterdata.col.code'), t('masterdata.col.name'), t('masterdata.col.area'), t('masterdata.col.start'), t('masterdata.col.end'),
        t('masterdata.col.category'), t('masterdata.col.unpopular')].map((c) => h('th', {}, c)))),
        h('tbody', {}, dataset.shifts.map((s) => h('tr', {},
          h('td', {}, h('span.cell-code.strong.tinted', { style: tintStyle(s.color) }, s.code)),
          h('td', {}, s.name), h('td', {}, areaName.get(s.areaId) ?? s.areaId), h('td', {}, hhmm(s.start)), h('td', {}, hhmm(s.end)),
          h('td', {}, CATEGORY[s.category] ?? '–'), h('td', {}, s.unpopular ? t('common.yes') : '')))))),
    h('section.section', { 'aria-labelledby': 'h-demand' }, h('h2', { id: 'h-demand' }, t('masterdata.section.demand')),
      h('table.data', {}, h('thead', {}, h('tr', {}, [t('masterdata.col.area'), t('masterdata.col.valid'), t('masterdata.col.from'), t('masterdata.col.to'), t('masterdata.col.min'),
        t('masterdata.col.max')].map((c) => h('th', {}, c)))),
        h('tbody', {}, dataset.demand.map((d) => h('tr', {}, h('td', {}, areaName.get(d.areaId) ?? d.areaId),
          h('td', {}, d.date ?? (d.days ?? []).map((x) => DAY_CODES[x] ?? x).join(', ')),
          h('td', {}, hhmm(d.from)), h('td', {}, hhmm(d.to)), h('td.num', {}, String(d.min)), h('td.num', {}, d.max ?? '–')))))),
  );

  function drawPeople() {
    const people = [...dataset.people].sort((a, b) => personName(a).localeCompare(personName(b), 'de'));
    peopleHost.replaceChildren(h('table.data', {},
      h('thead', {}, h('tr', {}, [t('masterdata.col.name'), t('masterdata.col.shortName'), t('masterdata.col.id'), t('masterdata.col.weeklyHours'), t('masterdata.col.areas'),
        // hours account, compensatory rest and family rules are personal: planners only (viewers get null)
        t('masterdata.col.employment'), canEdit ? t('masterdata.col.account') : null, canEdit ? t('masterdata.col.compRestOpen') : null,
        canEdit ? t('masterdata.col.familyRules') : null,
        canEdit ? '' : null]
        .filter((c) => c !== null).map((c) => h('th', {}, c)))),
      h('tbody', {}, people.map((p) => h('tr', {},
        h('td', {}, h('a', { href: `#/person/${encodeURIComponent(p.id)}` }, personName(p))), h('td', {}, p.shortName ?? ''), h('td', {}, p.id),
        h('td.num', {}, fmt.num(p.weeklyHours, 1)), h('td', {}, p.areaIds.join(', ')), h('td', {}, employmentText(p)),
        canEdit && h('td.num', {}, fmt.num(p.hoursAccount, 1)), canEdit && h('td.num', {}, fmt.num(p.compRestBalance)),
        canEdit && h('td', {}, (p.familyRules ?? []).map((f) => familyText(f)).join('; ')),
        canEdit && h('td', {}, h('button.btn-link', { type: 'button', 'data-person': p.id, onclick: (e) => edit(p, e.currentTarget.closest('tr')) }, t('masterdata.edit'))))))));
  }

  function edit(p, row) {
    peopleHost.querySelector('tr.edit-row')?.remove(); // only one form at a time
    const id = (k) => `edit-${k}`;
    const field = (k, label, control) => h('div.field', {}, h('label', { for: id(k) }, label), control);
    const form = h('form.toolbar', { onsubmit: async (e) => {
      e.preventDefault();
      const entryDate = form.querySelector(`#${id('entry')}`).value || null;
      const exitDate = form.querySelector(`#${id('exit')}`).value || null;
      if (entryDate && exitDate && exitDate < entryDate) {
        toast(t('masterdata.exitBeforeEntry'), true);
        return;
      }
      const updated = { ...p, entryDate, exitDate,
        weeklyHours: Number(form.querySelector(`#${id('hours')}`).value),
        hoursAccount: Number(form.querySelector(`#${id('account')}`).value),
        compRestBalance: Number(form.querySelector(`#${id('er')}`).value),
        areaIds: [...form.querySelectorAll('input[name=area]:checked')].map((c) => c.value) };
      const saved = await guarded(() => api.savePerson(updated), t('masterdata.personSaved'));
      if (saved) {
        Object.assign(p, saved);
        drawPeople();
        peopleHost.querySelector(`[data-person="${CSS.escape(p.id)}"]`)?.focus();
      }
    } },
    field('hours', t('masterdata.col.weeklyHours'), h('input', { id: id('hours'), type: 'number', step: 0.1, min: 0, required: true, value: p.weeklyHours })),
    field('account', t('masterdata.col.account'), h('input', { id: id('account'), type: 'number', step: 0.1, required: true, value: p.hoursAccount })),
    field('er', t('masterdata.col.compRestOpen'), h('input', { id: id('er'), type: 'number', step: 1, min: 0, required: true, value: p.compRestBalance })),
    field('entry', t('masterdata.entryDate'), h('input', { id: id('entry'), type: 'date', value: p.entryDate ?? '' })),
    field('exit', t('masterdata.exitDate'), h('input', { id: id('exit'), type: 'date', value: p.exitDate ?? '' })),
    h('fieldset', { style: { border: 0, padding: 0, margin: 0 } }, h('legend', { style: { fontWeight: 600 } }, t('masterdata.col.areas')),
      dataset.areas.map((a) => h('label', { style: { fontWeight: 400, marginRight: '.6rem' } },
        h('input', { type: 'checkbox', name: 'area', value: a.id, checked: p.areaIds.includes(a.id) }), ` ${a.id}`))),
    h('button.btn.btn-primary.btn-small', { type: 'submit' }, t('common.save')),
    h('button.btn.btn-secondary.btn-small', { type: 'button', onclick: drawPeople }, t('common.cancel')));
    const cols = row.children.length;
    row.after(h('tr.edit-row', {}, h('td', { colspan: cols }, form)));
    form.querySelector('input').focus();
  }

  drawPeople();
}

const yesNo = (v) => (v ? t('common.yes') : t('common.no'));

const de = (iso) => `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}`;

/** Employment period for the people table: entry and exit date, either may be open. */
export function employmentText(p) {
  if (p.entryDate && p.exitDate) return t('masterdata.employment.range', { from: de(p.entryDate), to: de(p.exitDate) });
  if (p.entryDate) return t('masterdata.employment.from', { from: de(p.entryDate) });
  if (p.exitDate) return t('masterdata.employment.until', { to: de(p.exitDate) });
  return '';
}

function familyText(f) {
  const days = (f.weekdays ?? []).map((d) => WEEKDAYS[d]).join('/');
  const parts = [f.latestEnd && t('masterdata.family.latestEnd', { time: String(f.latestEnd).slice(0, 5) }),
    f.earliestStart && t('masterdata.family.earliestStart', { time: String(f.earliestStart).slice(0, 5) }), f.noNights && t('masterdata.family.noNights')];
  return t('masterdata.family', { days, parts: parts.filter(Boolean).join(', ') });
}
