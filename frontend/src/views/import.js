// Daten: dataset import (optionally keeping the changes made in Dispofold), exports, corrections file, anonymised
// export, demo data, plan import, finalize month.
import { monthLabel } from '../lib/dates.js';
import { guarded, h, saveBlob, toast } from '../lib/dom.js';
import { CORRECTIONS_FILE, anonymizedFiles, correctionsBlob, fieldLabel, noteText, shortNames } from '../lib/data-files.js';
import { importFlow, importSteps } from '../lib/import-flow.js';
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

  const { button, errorAlert, list, exportDataset, runImport, previewKeep, showSummary } = importSteps(ctx, result, () => current);

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
            showSummary(await runImport((replace) => api.importDataset(data, replace), false, () => previewKeep(data)));
          }, true)),
        // demo data are for trying the program out: collapsed
        h('details.advanced', {}, h('summary', {}, t('roster.advanced')),
          h('div.toolbar', {},
            button(t('import.demo'), async () => {
              if (!confirm(t('import.demo.confirm'))) return;
              showSummary(await runImport((replace) => api.importDemo(replace)));
            }), info(t('help.import.demo'), t('import.demo'))))),
      // the import sources of the switched on plugins: their own form, then the same steps as a dataset file
      ...ctx.plugins.filter((p) => p.importSource).map((p) => {
        const form = h('div', {});
        const request = p.importSource.render(form, ctx);
        return h('section.section', { 'aria-labelledby': `h-plugin-${p.id}` },
          h('h2', { id: `h-plugin-${p.id}` }, t(p.importSource.label)),
          h('p.muted', {}, t('data.plugin.help')),
          form,
          importFlow(ctx, p.id, request, { result, current }));
      }),
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
        // without entries, but with changes since the import (an exclusion taken back, a value set back), the file may
        // have to replace one saved in between; not when it holds only the changes since the import
        const empty = !r.count && (r.withdrawn || (r.complete && current?.changedSinceImport));
        if (r.count || empty) saveBlob(correctionsBlob(r.file), CORRECTIONS_FILE);
        const names = shortNames(current);
        correctionsResult.replaceChildren(h('div.alert', {},
          h('p', {}, r.count ? t('data.corrections.done', { count: r.count }) : empty ? t('data.corrections.empty') : t('data.corrections.none')),
          r.count && r.complete ? h('p', {}, t('data.corrections.split', { earlier: r.earlier, changed: r.count - r.earlier })) : null,
          r.withdrawn ? h('p', {}, t('data.corrections.withdrawn', { count: r.withdrawn })) : null,
          r.complete ? null : h('p', {}, t('data.corrections.sinceImport')),
          r.notes.length ? [h('p', {}, t('data.corrections.notes')), list(r.notes.map((n) => noteText(n, names)))] : null));
      })),
      correctionsResult,
      loadCorrections(ctx, current, { button, errorAlert, list })));

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

/** The summary of a corrections file to load ({ version, people, existing, fields, skipped, overridden, replaced }). */
export function correctionsSummary(s, names) {
  const fields = Object.entries(s.fields).filter(([, n]) => n > 0);
  const note = (n) => noteText(n.params?.personId === '' ? { ...n, params: { ...n.params, personId: t('data.corrections.unknownId') } } : n, names);
  return h('div.corrections-summary', {},
    h('h3', {}, t('data.corrections.summary.title')),
    h('p', {}, t('data.corrections.summary.people', { version: s.version, people: s.people }), ' ',
      s.existing == null ? t('data.corrections.summary.noData') : t('data.corrections.summary.existing', { existing: s.existing })),
    fields.length ? [h('p', {}, t('data.corrections.summary.fields')), h('ul', {}, fields.map(([f, n]) => h('li', { 'data-field': f }, `${fieldLabel(f)}: ${n}`)))]
      : h('p', {}, t('data.corrections.summary.noFields')),
    s.skipped.length ? [h('p', {}, t('data.corrections.summary.skipped')), h('ul.corrections-skipped', {}, s.skipped.map((n) => h('li', {}, note(n))))] : null,
    s.overridden ? h('p', {}, t('data.corrections.summary.overridden', { count: s.overridden })) : null,
    s.replaced ? h('p', {}, t('data.corrections.summary.replaced', { count: s.replaced })) : null,
    h('p.muted', {}, t('data.corrections.summary.later')));
}

/** Loading a corrections file: pick, see its summary, confirm; the loaded one with remove. */
function loadCorrections(ctx, current, { button, errorAlert }) {
  const { api } = ctx;
  const input = h('input', { id: 'corrections-file', type: 'file', accept: '.json,application/json' });
  const out = h('div', { 'aria-live': 'polite' });
  const names = shortNames(current);
  const loadedBox = h('div', {});
  const showLoaded = async () => {
    const s = await api.correctionsState().catch(() => null);
    loadedBox.replaceChildren(s?.loaded ? h('div.alert', { id: 'corrections-loaded' },
      h('p', {}, t('data.corrections.loaded', { date: new Date(s.loaded).toLocaleString('de-DE') })),
      correctionsSummary(s.summary, names),
      h('div.toolbar', {}, button(t('data.corrections.remove'), async () => {
        if (!confirm(t('data.corrections.removeConfirm'))) return;
        if (await guarded(() => api.removeCorrections()) === undefined) return;
        toast(t('data.corrections.removed'));
        out.replaceChildren();
        await showLoaded();
      }))) : '');
    return s;
  };
  showLoaded();
  return h('div', {},
    h('h3', {}, t('data.corrections.load')),
    h('p.muted', {}, t('data.corrections.loadIntro')),
    h('div.toolbar', {}, h('div.field', {}, h('label', { for: 'corrections-file' }, t('data.corrections.loadLabel')), input),
      button(t('data.corrections.load'), async () => {
        const file = input.files[0];
        if (!file) return guarded(() => Promise.reject(new Error(t('import.pickFile'))));
        const text = await file.text();
        let summary;
        try {
          summary = await api.checkCorrections(text);
        } catch (e) {
          out.replaceChildren(errorAlert(e));
          return undefined;
        }
        const waiting = Boolean(loadedBox.querySelector('#corrections-loaded'));
        out.replaceChildren(h('div.alert', { id: 'corrections-confirm' }, correctionsSummary(summary, names),
          h('div.toolbar', {},
            button(waiting ? t('data.corrections.confirmReplace') : t('data.corrections.confirm'), async () => {
              try {
                await api.loadCorrections(text);
              } catch (e) {
                out.replaceChildren(errorAlert(e));
                return;
              }
              out.replaceChildren(h('div.alert', {}, h('p', {}, t('data.corrections.loadedDone'))));
              input.value = '';
              await showLoaded();
            }, true),
            h('button.btn.btn-secondary', { type: 'button', onclick: () => out.replaceChildren() }, t('data.corrections.cancel')))));
        return undefined;
      })),
    out, loadedBox);
}
