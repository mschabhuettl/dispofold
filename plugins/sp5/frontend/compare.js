// Vergleich (inside the result of Einlesen on Daten → Übernehmen): what the last run would change against the last import (or an earlier dataset file): counts per section,
// people added and removed, changed fields and restrictions, duties per month and area, changed demand. With the
// corrections the run is what the import would load; without them their effect shows up too.
import { h, info, monthLabel, sortableTable, t } from '../../../frontend/src/lib/plugin-api.js';
import { byId, demandLabel, demandValues, difference, fieldName, fieldValue, restrictionLines, sectionName } from './model.js';
import { ID, noRun, problem } from './shared.js';

export async function render(root, ctx) {
  const api = ctx.api.plugin(ID);
  const dataset = await ctx.dataset().catch(() => null);
  const people = new Set((dataset?.people ?? []).map((p) => p.id));
  // excluded in Dispofold (not taken over from the source): the import keeps them although the run leaves them out
  const excluded = new Set((dataset?.people ?? []).filter((p) => p.excludedFrom).map((p) => p.id));
  const shifts = new Map((dataset?.shifts ?? []).map((s) => [s.id, s]));
  const areaNames = new Map((dataset?.areas ?? []).map((a) => [a.id, a.name]));
  const areaName = (id) => areaNames.get(id) ?? id;

  const corrections = h('input', { id: 'sp5-cmp-corrections', type: 'checkbox', checked: true });
  const againstImport = h('input', { id: 'sp5-cmp-import', type: 'radio', name: 'sp5-cmp-against', value: 'import', checked: true });
  const againstFile = h('input', { id: 'sp5-cmp-against-file', type: 'radio', name: 'sp5-cmp-against', value: 'datei' });
  const file = h('input', { id: 'sp5-cmp-file', type: 'file', accept: '.json,application/json' });
  const out = h('div', { id: 'sp5-compare', 'aria-live': 'polite' });
  let upload = null;
  let loading = 0; // the newest request; older answers are dropped

  const person = (id, name) => (people.has(id) ? h('a', { href: `#/person/${encodeURIComponent(id)}` }, name ? `${name} (${id})` : id)
    : name ? `${name} (${id})` : id);
  const none = () => h('p.muted', {}, t('plugin.sp5.compare.same'));
  const section = (key, content, help = null) => h('section.section', { 'aria-labelledby': `h-sp5-cmp-${key}`, 'data-section': key },
    h('h4', { id: `h-sp5-cmp-${key}` }, t(`plugin.sp5.compare.${key}`), help && info(help, t(`plugin.sp5.compare.${key}`))), content);
  const table = (key, columns, rows) => {
    if (!rows.length) return none();
    const x = sortableTable(columns, { label: t(`plugin.sp5.compare.${key}`) });
    x.show(rows);
    return x.element;
  };
  // numbers, dates (ISO) and texts sort as they are, lists and objects by their text
  const sortable = (v) => (v == null || typeof v !== 'object' ? v : JSON.stringify(v));
  const diffCell = (row) => (row.old !== row.new ? h('span.badge.badge-warn', {}, difference(row.old, row.new)) : '0');

  function show(r) {
    const counts = r.counts ?? [];
    const changed = r.people?.changed ?? [];
    const fields = changed.flatMap((p) => (p.fields ?? []).filter((f) => f.field !== 'restrictions').map((f) => ({ ...f, id: p.id, name: p.name })));
    const restrictions = changed.flatMap((p) => (p.fields ?? []).filter((f) => f.field === 'restrictions')
      .map((f) => ({ id: p.id, name: p.name, lines: restrictionLines(f, shifts) })));
    out.replaceChildren(
      h('p', { id: 'sp5-cmp-against' }, t(`plugin.sp5.compare.against.${r.against === 'datei' ? 'file' : 'import'}`), ' ',
        r.corrections ? t('plugin.sp5.compare.with') : t('plugin.sp5.compare.without')),
      section('counts', table('counts', [
        { label: t('plugin.sp5.compare.section'), value: (x) => sectionName(x.section),
          cell: (x) => (x.old !== x.new ? h('strong', {}, sectionName(x.section)) : sectionName(x.section)) },
        { label: t('plugin.sp5.compare.old'), value: (x) => x.old, num: true },
        { label: t('plugin.sp5.compare.new'), value: (x) => x.new, num: true },
        { label: t('plugin.sp5.compare.diff'), value: (x) => x.new - x.old, cell: diffCell, num: true },
      ], counts)),
      section('added', table('added', [
        { label: t('plugin.sp5.compare.id'), value: (x) => x.id, cell: (x) => person(x.id) },
        { label: t('plugin.sp5.compare.name'), value: (x) => x.name },
      ], byId(r.people?.added ?? []))),
      section('removed', table('removed', [
        { label: t('plugin.sp5.compare.id'), value: (x) => x.id, cell: (x) => person(x.id) },
        { label: t('plugin.sp5.compare.name'), value: (x) => x.name },
        { label: t('plugin.sp5.compare.note'), value: (x) => (excluded.has(x.id) ? 1 : 0),
          cell: (x) => (excluded.has(x.id) ? t('plugin.sp5.compare.excluded') : '') },
      ], byId(r.people?.removed ?? []))),
      section('fields', table('fields', [
        { label: t('plugin.sp5.compare.person'), value: (x) => x.id, cell: (x) => person(x.id, x.name) },
        { label: t('plugin.sp5.compare.field'), value: (x) => fieldName(x.field) },
        { label: t('plugin.sp5.compare.old'), value: (x) => sortable(x.old), cell: (x) => fieldValue(x.field, x.old, areaName) },
        { label: t('plugin.sp5.compare.new'), value: (x) => sortable(x.new), cell: (x) => fieldValue(x.field, x.new, areaName) },
      ], byId(fields))),
      section('restrictions', table('restrictions', [
        { label: t('plugin.sp5.compare.person'), value: (x) => x.id, cell: (x) => person(x.id, x.name) },
        { label: t('plugin.sp5.compare.change'), value: (x) => x.lines.join(' '), cell: (x) => h('ul', {}, x.lines.map((l) => h('li', {}, l))) },
      ], byId(restrictions))),
      section('duties', table('duties', [
        { label: t('plugin.sp5.compare.month'), value: (x) => x.month, cell: (x) => monthLabel(x.month) },
        { label: t('plugin.sp5.compare.area'), value: (x) => (x.areaId == null ? t('plugin.sp5.compare.unknownShift') : areaName(x.areaId)) },
        { label: t('plugin.sp5.compare.old'), value: (x) => x.old, num: true },
        { label: t('plugin.sp5.compare.new'), value: (x) => x.new, num: true },
        { label: t('plugin.sp5.compare.diff'), value: (x) => x.new - x.old, cell: diffCell, num: true },
      ], r.duties ?? [])),
      section('demand', table('demand', [
        { label: t('plugin.sp5.compare.demandRule'), value: demandLabel },
        { label: t('plugin.sp5.compare.oldStaff'), value: (x) => demandValues(x.old) },
        { label: t('plugin.sp5.compare.newStaff'), value: (x) => demandValues(x.new) },
      ], r.demand ?? []), t('plugin.sp5.compare.demand.help')));
  }

  async function load() {
    const token = ++loading;
    if (againstFile.checked && !upload) {
      out.replaceChildren(h('p.alert', {}, t('plugin.sp5.compare.pickFile')));
      return;
    }
    const path = `vergleich?korrekturen=${corrections.checked}`;
    let r;
    try {
      r = againstFile.checked ? await api.post(path, upload) : await api.get(path);
    } catch (e) {
      if (token !== loading) return;
      if (e.code === 'plugin.sp5.noRun') out.replaceChildren(noRun());
      else if (e.code === 'plugin.sp5.noBaseline') {
        out.replaceChildren(h('p.alert', { id: 'sp5-no-baseline' }, e.message, ' ',
          h('button.btn-link', { type: 'button', onclick: () => { againstFile.checked = true; file.focus(); } }, t('plugin.sp5.compare.offerFile'))));
      } else out.replaceChildren(problem(e));
      return;
    }
    if (token === loading) show(r);
  }

  corrections.addEventListener('change', load);
  againstImport.addEventListener('change', load);
  againstFile.addEventListener('change', load);
  file.addEventListener('change', async () => {
    const chosen = file.files[0];
    if (!chosen) return;
    againstFile.checked = true;
    try {
      upload = JSON.parse(await chosen.text());
      file.value = ''; // the same file can be chosen again
    } catch {
      upload = null;
      out.replaceChildren(h('div.alert.alert-error', { role: 'alert' }, t('plugin.sp5.compare.invalidJson')));
      return;
    }
    load();
  });

  root.append(
    h('div.toolbar', {},
      h('label.check-inline', {}, corrections, t('plugin.sp5.compare.corrections')),
      info(t('plugin.sp5.compare.corrections.help'), t('plugin.sp5.compare.corrections'))),
    h('fieldset.toolbar', {}, h('legend', {}, t('plugin.sp5.compare.against')),
      h('label.check-inline', {}, againstImport, t('plugin.sp5.compare.againstImport')),
      h('label.check-inline', {}, againstFile, t('plugin.sp5.compare.againstFile')),
      h('div', {}, h('label', { for: 'sp5-cmp-file' }, t('plugin.sp5.compare.file')), ' ', file),
      info(t('plugin.sp5.compare.file.help'), t('plugin.sp5.compare.file'))),
    out);
  await load();
}
