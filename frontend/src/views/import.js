// Daten: dataset import (optionally keeping the changes made in Dispofold), exports, corrections file, anonymised
// export, demo data, plan import, finalize month.
import { monthLabel } from '../lib/dates.js';
import { guarded, h, saveBlob, toast } from '../lib/dom.js';
import { messageText } from '../lib/codes.js';
import { CORRECTIONS_FILE, anonymizedFiles, conflictText, correctionsBlob, noteText, shortNames } from '../lib/data-files.js';
import { info } from '../lib/info.js';
import { t } from '../lib/texts.js';
import { EXPORT_FORMATS, closeMonth, exportPlanFile } from './plan-output.js';

export async function render(root, ctx) {
  const { api, month, canEdit } = ctx;
  const result = h('div', { 'aria-live': 'polite' });
  const state = await api.planState(month).catch(() => null);
  const solving = Boolean(state?.solving);
  const closed = state?.plan?.status === 'CLOSED';
  const current = canEdit ? await ctx.dataset().catch(() => null) : null;

  const list = (items) => h('ul', {}, items.map((x) => h('li', {}, x)));
  const errorAlert = (e) => h('div.alert.alert-error', { role: 'alert' }, h('strong', {}, e.message),
    e.body?.errors?.length ? list(e.body.errors.map(messageText)) : null,
    e.body?.warnings?.length ? [h('p', {}, h('strong', {}, t('import.warnings', { count: e.body.warnings.length }))),
      list(e.body.warnings.map(messageText))] : null);

  /**
   * Runs an import (load(replace) calls the API). A rejected import lists all its errors ({ code, params }), not
   * only the first. Master data changed in Dispofold since the last import are only replaced after asking; an
   * uploaded dataset (data) can keep them instead.
   */
  const runImport = async (load, replace = false, data = null) => {
    try {
      return await load(replace);
    } catch (e) {
      if (e.code === 'import.masterChanged') {
        result.replaceChildren(h('div.alert', { role: 'alert' }, h('strong', {}, t('import.masterChanged.title')), ' ', t('import.masterChanged.text'),
          h('div.toolbar', {}, button(t('import.datasetExport'), exportDataset),
            data && button(t('data.import.keepRun'), () => previewKeep(data), true),
            button(t('import.masterChanged.replace'), async () => showSummary(await runImport(load, true)), !data),
            button(t('common.cancel'), async () => result.replaceChildren()))));
        return undefined;
      }
      result.replaceChildren(errorAlert(e));
      return undefined;
    }
  };
  const exportDataset = async () => {
    const file = await guarded(() => api.exportDataset());
    if (file) saveBlob(file.blob, file.filename);
  };

  /** Dry run of an import keeping the changes: what is carried over, conflicts and what is lost, then import. */
  const previewKeep = async (data) => {
    let preview;
    try {
      preview = await api.importPreview(data);
    } catch (e) {
      result.replaceChildren(errorAlert(e));
      return;
    }
    const names = shortNames(current, data);
    result.replaceChildren(h('div.alert', { id: 'import-preview' }, h('strong', {}, t('data.preview.title')),
      h('p', {}, preview.changed ? t('data.preview.kept', { count: preview.kept }) : t('data.preview.none')),
      preview.conflicts.length ? [h('p', {}, t('data.preview.conflicts')), list(preview.conflicts.map((c) => conflictText(c, names)))] : null,
      preview.notes.length ? [h('p', {}, t('data.preview.notes')), list(preview.notes.map((n) => noteText(n, names)))] : null,
      h('div.toolbar', {},
        button(t('data.preview.import'), async () => showSummary(await runImport(() => api.importDataset(data, false, true))), true),
        button(t('common.cancel'), async () => result.replaceChildren()))));
  };

  const showSummary = (s) => {
    if (!s) return;
    ctx.invalidateDataset();

    result.replaceChildren(h('div.alert', {},
      h('strong', {}, t('import.summaryTitle', { source: s.source })), t('import.summary', { people: s.people, areas: s.areas, shifts: s.shifts, months: s.months.join(', ') || '–' }),
      s.kept ? h('p', {}, t('data.import.kept', { count: s.kept })) : null,
      s.warnings?.length ? [h('p', {}, h('strong', {}, t('import.warnings', { count: s.warnings.length }))), h('ul', {}, s.warnings.map((w) => h('li', {}, messageText(w))))] : null,
      s.months?.length && !s.months.includes(month) ? h('p', {}, button(t('import.switchMonth', { month: monthLabel(s.months[0]) }), () => ctx.setMonth(s.months[0]))) : null));
  };

  const readJson = (input) => new Promise((resolve, reject) => {
    const file = input.files[0];
    if (!file) return reject(new Error(t('import.pickFile')));
    file.text().then((text) => {
      try { resolve(JSON.parse(text)); } catch { reject(new Error(t('import.invalidJson'))); }
    }, reject);
  });

  const datasetFile = h('input', { id: 'dataset-file', type: 'file', accept: '.json,application/json' });
  const keep = h('input', { id: 'dataset-keep', type: 'checkbox', checked: Boolean(current?.changedSinceImport) });
  const planFile = h('input', { id: 'plan-file', type: 'file', accept: '.json,application/json' });
  const busy = (btn, fn) => async () => {
    btn.disabled = true;
    try { await fn(); } finally { btn.disabled = false; }
  };
  const button = (label, fn, primary = false, disabled = false) => {
    const b = h(`button.btn.${primary ? 'btn-primary' : 'btn-secondary'}`, { type: 'button', disabled }, label);
    b.addEventListener('click', busy(b, fn));
    return b;
  };

  root.append(h('h1', {}, t('nav.import')), result);

  if (canEdit) {
    root.append(
      h('section.section', { 'aria-labelledby': 'h-import' }, h('h2', { id: 'h-import' }, t('data.import.heading'), info(t('help.import.dataset'), t('data.import.heading'))),
        h('p.muted', {}, t('import.dataset.help')),
        h('div.toolbar', {},
          h('div.field', {}, h('label', { for: 'dataset-file' }, t('import.dataset.label')), datasetFile),
          h('label.check-inline', {}, keep, t('data.import.keep')), info(t('help.import.keep'), t('data.import.keep')),
          button(t('import.dataset.import'), async () => {
            const data = await guarded(() => readJson(datasetFile));
            if (!data) return;
            if (keep.checked) return previewKeep(data);
            if (!confirm(t('import.dataset.confirm'))) return;
            showSummary(await runImport((replace) => api.importDataset(data, replace), false, data));
          }, true)),
        // demo data are for trying the program out: collapsed
        h('details.advanced', {}, h('summary', {}, t('roster.advanced')),
          h('div.toolbar', {},
            button(t('import.demo'), async () => {
              if (!confirm(t('import.demo.confirm'))) return;
              showSummary(await runImport((replace) => api.importDemo(replace)));
            }), info(t('help.import.demo'), t('import.demo'))))),
    );
  }

  // exports are for planners only (viewers get 403) and blocked while a solve runs
  if (canEdit) {
    const correctionsResult = h('div', { 'aria-live': 'polite' });
    const anonymizeResult = h('div', { 'aria-live': 'polite' });
    root.append(h('section.section', { 'aria-labelledby': 'h-export' }, h('h2', { id: 'h-export' }, t('data.export.heading')),
      h('p.muted', {}, t('data.export.intro')),
      h('div.toolbar', {}, button(t('import.datasetExport'), exportDataset), info(t('help.import.datasetExport'), t('import.datasetExport'))),
      h('p.muted', {}, t('import.datasetExport.help')),
      h('h3', {}, t('import.export.heading', { month: monthLabel(month) }), info(t('help.import.planExport'), t('import.export.heading', { month: monthLabel(month) }))),
      solving && h('p.muted', {}, t('import.export.solving')),
      h('div.toolbar', {}, EXPORT_FORMATS.map((f) => (solving
        ? h('button.btn.btn-secondary', { type: 'button', disabled: true }, f.toUpperCase())
        : button(f.toUpperCase(), () => exportPlanFile(api, month, f)))))));

    root.append(h('section.section', { 'aria-labelledby': 'h-corrections' }, h('h2', { id: 'h-corrections' }, t('data.corrections.heading'), info(t('help.import.corrections'), t('data.corrections.heading'))),
      h('p.muted', {}, t('data.corrections.intro')),
      h('div.toolbar', {}, button(t('data.corrections.export'), async () => {
        let r;
        try {
          r = await api.correctionsFile();
        } catch (e) {
          correctionsResult.replaceChildren(errorAlert(e));
          return;
        }
        // without entries but with exclusions taken back the file still replaces the previous one
        if (r.count || r.withdrawn) saveBlob(correctionsBlob(r.file), CORRECTIONS_FILE);
        const names = shortNames(current);
        correctionsResult.replaceChildren(h('div.alert', {},
          h('p', {}, r.count ? t('data.corrections.done', { count: r.count }) : r.withdrawn ? t('data.corrections.empty') : t('data.corrections.none')),
          r.count && r.complete ? h('p', {}, t('data.corrections.split', { earlier: r.earlier, changed: r.count - r.earlier })) : null,
          r.withdrawn ? h('p', {}, t('data.corrections.withdrawn', { count: r.withdrawn })) : null,
          r.complete ? null : h('p', {}, t('data.corrections.sinceImport')),
          r.notes.length ? [h('p', {}, t('data.corrections.notes')), list(r.notes.map((n) => noteText(n, names)))] : null));
      })),
      correctionsResult));

    root.append(h('section.section', { 'aria-labelledby': 'h-anonymize' }, h('h2', { id: 'h-anonymize' }, t('data.anonymize.heading'), info(t('help.import.anonymize'), t('data.anonymize.heading'))),
      h('p.muted', {}, t('data.anonymize.intro', { month: monthLabel(month) })),
      h('div.toolbar', {}, solving ? h('button.btn.btn-secondary', { type: 'button', disabled: true }, t('data.anonymize.run'))
        : button(t('data.anonymize.run'), async () => {
          let r;
          try {
            r = await api.anonymized(month);
          } catch (e) {
            anonymizeResult.replaceChildren(errorAlert(e));
            return;
          }
          const files = anonymizedFiles(r, month);
          const save = (f) => async () => saveBlob(f.blob, f.name);
          anonymizeResult.replaceChildren(h('div.alert', {},
            h('p', {}, t('data.anonymize.ready', { count: r.people })),
            h('div.toolbar', {}, button(t('data.anonymize.dataset'), save(files.dataset), true),
              button(t('data.anonymize.plan'), save(files.plan), true)),
            h('p', {}, h('strong', {}, t('data.anonymize.keyWarning'))),
            h('div.toolbar', {}, button(t('data.anonymize.key'), save(files.key)))));
        })),
      anonymizeResult));
  }

  if (canEdit) {
    root.append(
      h('section.section', { 'aria-labelledby': 'h-plan-import' }, h('h2', { id: 'h-plan-import' }, t('import.plan.import'), info(t('help.import.planImport'), t('import.plan.import'))),
        h('p.muted', {}, t('import.plan.help')),
        h('div.toolbar', {}, h('div.field', {}, h('label', { for: 'plan-file' }, t('import.plan.label')), planFile),
          button(t('import.plan.import'), async () => {
            const plan = await guarded(() => readJson(planFile));
            if (!plan) return;
            if (plan.month !== month) {
              toast(t('import.plan.mismatch', { found: plan.month ?? t('import.plan.unknownMonth'), month }), true);
              return;
            }
            if (confirm(t('import.plan.confirm', { month: monthLabel(month) }))) await guarded(() => api.importPlan(month, plan), t('import.plan.done'));
          }))),
      closed
        ? h('section.section', { 'aria-labelledby': 'h-final' }, h('h2', { id: 'h-final' }, t('import.reopen'), info(t('help.import.reopen'), t('import.reopen'))),
          h('p.muted', {}, t('import.reopen.help', { month: monthLabel(month) })),
          button(t('import.reopen'), async () => {
            if (!confirm(t('import.reopen.confirm', { month: monthLabel(month) }))) return;
            const ok = await guarded(() => api.reopen(month));
            if (ok !== undefined) {
              toast(t('import.reopen.done', { month: monthLabel(month) }));
              ctx.setMonth(month); // draw again with the new status
            }
          }))
        : h('section.section', { 'aria-labelledby': 'h-final' }, h('h2', { id: 'h-final' }, t('import.finalize'), info(t('help.import.finalize'), t('import.finalize'))),
          h('p.muted', {}, t('import.finalize.help')),
          button(t('import.finalize'), () => closeMonth(ctx), false, solving)),
    );
  }
}
