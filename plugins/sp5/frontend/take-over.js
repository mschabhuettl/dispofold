// The source on Daten → Übernehmen (the core's page; this is the plugin's importSource): the folder, the mapping's
// state and the options in "Quelle" (always the stored ones: a change is saved at once), then two steps. "Einlesen"
// runs the export (POST lauf, with the stored options; nothing changes in Dispofold) and shows its result in plain
// words on top with the report, the comparison and the codes without a rule below; "Übernehmen" imports exactly that
// run with the core's import. A change of the folder or an option after Einlesen asks to read again first.
import { addMonths, folderPicker, guarded, h, importFlow, info, monthLabel, t, toast } from '../../../frontend/src/lib/plugin-api.js';
import * as compare from './compare.js';
import { unmatched } from './model.js';
import * as report from './report.js';
import { ID, monthFields, optionFields, optionsText, orNoRun, pageHref, problem } from './shared.js';

const COUNTS = ['people', 'areas', 'shifts', 'duties', 'demandRules', 'warnings', 'reportRows', 'corrections'];

/** The first month to offer: the months of the last run; without one the month after the last closed one. */
export function startMonths(last, months, current) {
  if (last?.request?.month) return { month: last.request.month, to: last.request.to ?? '' };
  const closed = (months ?? []).filter((m) => m.status === 'CLOSED').map((m) => m.month).sort();
  return { month: closed.length ? addMonths(closed.at(-1), 1) : current, to: '' };
}

/** Whether a run was made with these options; else Übernehmen waits for a new Einlesen. */
export function runMatches(run, settings) {
  const same = (a, b) => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
  const options = run?.request?.options ?? {};
  return Boolean(run) && Object.keys(settings.options ?? {}).every((k) => same(options[k], settings.options[k]));
}

export function render(root, ctx) {
  const api = ctx.api.plugin(ID);
  const sourceBox = h('div', {});
  const stepsBox = h('div', {});
  root.append(sourceBox, stepsBox);
  Promise.all([api.get('einstellungen'), orNoRun(() => api.get('lauf')), ctx.api.months().catch(() => [])])
    .then(([settings, last, months]) => draw(root, ctx, api, { settings, last, months, sourceBox, stepsBox }),
      (e) => root.append(problem(e)));
}

function draw(root, ctx, api, { settings: stored, last: lastRun, months: knownMonths, sourceBox, stepsBox }) {
  let settings = stored;
  let last = lastRun;
  let codes = null;
  let folderChanged = false; // the folder was saved after the run shown

  // --- Quelle: folder, mapping, options; collapsed once folder and mapping are set ---
  const sourceLine = h('span', { id: 'sp5-source-line' });
  const picker = folderPicker(ctx, { id: 'sp5-folder', start: { root: settings.root, path: settings.path } });
  const options = optionFields('sp5', settings.options ?? {});
  const mappingLine = h('p', { id: 'sp5-mapping-line' });
  const saved = h('span.muted', { id: 'sp5-options-saved', 'aria-live': 'polite' });
  // saved on top of the settings as they are now: the mapping may have been saved meanwhile on Zuordnung
  const save = async (change, done) => {
    const fresh = await guarded(() => api.get('einstellungen'));
    if (!fresh) return false;
    const result = await guarded(() => api.put('einstellungen', { ...fresh, ...change }), done);
    if (!result) return false;
    settings = result;
    if ('root' in change) folderChanged = true;
    refreshSource();
    stale();
    return true;
  };
  const saveFolder = h('button.btn.btn-secondary', { id: 'sp5-folder-save', type: 'button' }, t('plugin.sp5.folder.save'));
  saveFolder.addEventListener('click', () => {
    const folder = picker.value() ?? { root: '', path: '' };
    return save({ root: folder.root, path: folder.path }, t('plugin.sp5.folder.saved'));
  });
  const optionsBox = h('div.settings', { id: 'sp5-options' }, ...options.elements);
  let saving = null;
  optionsBox.addEventListener('change', () => {
    clearTimeout(saving);
    saving = setTimeout(async () => {
      if (await save({ options: options.value() })) saved.textContent = t('plugin.sp5.options.saved');
    }, 150);
  });
  const source = h('details.source', { id: 'sp5-source', open: !settings.root || !settings.mapping },
    h('summary', {}, h('strong', {}, t('plugin.sp5.source')), ' ', sourceLine),
    h('div.with-info', {}, h('strong', {}, t('plugin.sp5.folder')), info(t('plugin.sp5.folder.help'), t('plugin.sp5.folder'))),
    picker.element, h('div.row', {}, saveFolder),
    mappingLine,
    h('h4', {}, t('plugin.sp5.options'), ' ', saved),
    h('p.muted', {}, t('plugin.sp5.options.stored')),
    optionsBox);
  sourceBox.append(source);

  function refreshSource() {
    const folder = settings.root ? [settings.root, settings.path].filter(Boolean).join('/') : null;
    sourceLine.textContent = folder ? t('plugin.sp5.source.folder', { folder }) : t('plugin.sp5.source.noFolder');
    mappingLine.replaceChildren(settings.mapping
      ? h('span', {}, t('plugin.sp5.source.mapping'), ' ', h('a', { href: pageHref('zuordnung') }, t('plugin.sp5.mapping.editor')))
      : h('span', {}, t('plugin.sp5.source.noMapping'), ' ', h('a', { href: pageHref('zuordnung') }, t('plugin.sp5.source.setUp'))));
    codes = null;
    if (!settings.root) return;
    api.post('zuordnung/codes', settings.mapping ?? {}).then((c) => {
      codes = c;
      if (c.people != null) sourceLine.textContent = t('plugin.sp5.source.found', { folder, count: c.people });
    }, () => {});
  }
  refreshSource();

  // --- 1. Einlesen ---
  const months = monthFields(ctx, 'sp5-run', startMonths(last, knownMonths, ctx.month));
  const result = h('div', { id: 'sp5-summary', 'aria-live': 'polite' });
  const readButton = h('button.btn.btn-primary', { id: 'sp5-run', type: 'button' }, t('plugin.sp5.run.start'));
  readButton.addEventListener('click', async () => {
    const body = months.value();
    if (!body) return toast(t('plugin.sp5.run.noMonth'), true);
    readButton.disabled = true;
    // nothing to take over while reading (or after reading failed): the run shown before is not what was asked for
    flow.setEnabled(false);
    what.textContent = t('plugin.sp5.take.first');
    try {
      // the stored options: the run names them
      last = await api.post('lauf', body);
      folderChanged = false;
    } catch (e) {
      result.replaceChildren(problem(e));
      return undefined;
    } finally {
      readButton.disabled = false;
    }
    await showRun();
    return undefined;
  });

  // --- 2. Übernehmen ---
  const what = h('p', { id: 'sp5-take-what' });
  const closedBox = h('div', { id: 'sp5-import-closed' });
  const flow = importFlow(ctx, ID, () => (last ? { ...last.request, run: last.id } : null),
    { label: t('plugin.sp5.take'), confirmFirst: false });
  stepsBox.append(
    h('div.steps', {},
      h('section.step', {}, h('h2', {}, t('plugin.sp5.step1')), months.element,
        h('div.toolbar', {}, readButton, h('span.muted', { id: 'sp5-run-note' }, t('plugin.sp5.run.note'))), result),
      h('section.step', {}, h('h2', { id: 'h-sp5-import', tabindex: '-1' }, t('plugin.sp5.step2')), what, closedBox, flow)));

  /** After a change of the folder or an option: the run shown was made otherwise; read again first. */
  let whatText = '';
  function stale() {
    if (!last || !whatText) return;
    const fits = runMatches(last, settings) && !folderChanged;
    flow.setEnabled(fits);
    if (fits) what.textContent = whatText;
    else what.replaceChildren(h('span.alert.alert-warn', { id: 'sp5-run-stale', role: 'note' }, t('plugin.sp5.run.stale')));
  }

  async function showRun() {
    if (!last) {
      result.replaceChildren();
      what.textContent = t('plugin.sp5.take.first');
      flow.setEnabled(false);
      return;
    }
    const r = last;
    const time = new Date(r.ranAt).toLocaleString('de-AT', { dateStyle: 'medium', timeStyle: 'short' });
    const monthText = r.request.to && r.request.to !== r.request.month
      ? t('plugin.sp5.import.range', { from: monthLabel(r.request.month), to: monthLabel(r.request.to) }) : monthLabel(r.request.month);
    const reportData = await orNoRun(() => api.get('lauf/bericht')).catch(() => null);
    const lazy = (id, label, fill) => {
      const box = h('div', {});
      const d = h('details.advanced', { id }, h('summary', {}, label), box);
      d.addEventListener('toggle', () => { if (d.open && !box.childElementCount) fill(box); });
      return d;
    };
    const mappingDetails = h('details.advanced', { id: 'sp5-details-mapping', hidden: true });
    result.replaceChildren(h('div', { id: 'sp5-run-summary' },
      reportData ? report.verdict(reportData, r) : '',
      h('p', {}, h('strong', {}, t('plugin.sp5.run.done', { time })), ' ', t('plugin.sp5.run.months', { months: r.months.map(monthLabel).join(', ') })),
      h('p.muted', { id: 'sp5-run-options' }, t('plugin.sp5.run.options', { options: optionsText(r.request.options ?? {}) })),
      h('div.kpis', {}, COUNTS.map((k) => h('div.kpi', { 'data-count': k }, h('div.label', {}, t(`plugin.sp5.count.${k}`)),
        h('div.value', {}, String(r.counts?.[k] ?? '–'))))),
      r.corrections && r.counts?.corrections ? h('p', { id: 'sp5-run-corrections' }, t('plugin.sp5.run.corrections.applied', { count: r.counts.corrections })) : '',
      lazy('sp5-details-report', t('plugin.sp5.details.report'), (box) => report.render(box, ctx)),
      lazy('sp5-details-compare', t('plugin.sp5.details.compare'), (box) => compare.render(box, ctx)),
      mappingDetails));
    // shifts or groups of the folder without a rule: named, with the way to the mapping
    const named = codes ?? (settings.mapping ? await api.post('zuordnung/codes', settings.mapping).catch(() => null) : null);
    const u = named ? unmatched(named) : { shifts: [], groups: [] };
    const count = u.shifts.length + u.groups.length;
    if (count) {
      mappingDetails.hidden = false;
      mappingDetails.replaceChildren(h('summary', {}, t('plugin.sp5.run.toMapping', { count })),
        h('ul', { id: 'sp5-unmatched' }, u.shifts.map((code) => h('li', {}, t('plugin.sp5.details.shift', { code }))),
          u.groups.map((name) => h('li', {}, t('plugin.sp5.details.group', { name })))),
        h('p', {}, h('a', { href: pageHref('zuordnung') }, t('plugin.sp5.mapping.editor'))));
    }
    // what Übernehmen will change
    const current = await ctx.dataset().catch(() => null);
    whatText = t('plugin.sp5.take.what', { people: r.counts?.people ?? 0, areas: r.counts?.areas ?? 0, months: monthText,
      replaces: current?.people?.length ? t('plugin.sp5.take.replaces', { source: current.source || '–' }) : t('plugin.sp5.take.empty') });
    what.textContent = whatText;
    flow.setEnabled(true);
    stale();
    // a closed month of the run: the import would be refused if it changed it; the way on before trying
    const shut = r.months.filter((m) => (knownMonths ?? []).some((x) => x.month === m && x.status === 'CLOSED'));
    closedBox.replaceChildren(shut.length ? h('div.alert', { role: 'note' }, h('p', {}, t('plugin.sp5.import.closed', { months: shut.map(monthLabel).join(', ') })),
      h('p', {}, h('a', { href: '#/monate' }, t('plugin.sp5.import.toMonths')))) : '');
  }
  showRun();
}
