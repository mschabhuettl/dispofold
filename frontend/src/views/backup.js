// Daten → Sichern: dataset export and import (a backup back), plan export and import, corrections file, pseudonymised
// export, and what plugins offer for experts (backup slots).
import { monthLabel } from '../lib/dates.js';
import { guarded, h, saveBlob, toast } from '../lib/dom.js';
import { CORRECTIONS_FILE, anonymizedFiles, correctionsBlob, fieldLabel, noteText, shortNames } from '../lib/data-files.js';
import { importSteps } from '../lib/import-flow.js';
import { info } from '../lib/info.js';
import { t } from '../lib/texts.js';
import { EXPORT_FORMATS, exportPlanFile } from './plan-output.js';
import { fileSource, readJson } from './take-over.js';

export async function render(root, ctx, params = []) {
  const { api, month, canEdit } = ctx;
  const result = h('div', { 'aria-live': 'polite' });
  const state = await api.planState(month).catch(() => null);
  const solving = Boolean(state?.solving);
  const current = canEdit ? await ctx.dataset().catch(() => null) : null;

  const { button, errorAlert, list, exportDataset } = importSteps(ctx, result, () => current);

  const planFile = h('input', { id: 'plan-file', type: 'file', accept: '.json,application/json' });
  root.append(h('h1', {}, t('nav.backup')), result);

  if (canEdit) {
    // a dataset file back (a backup): the same two steps as on Übernehmen
    root.append(h('section.section', { 'aria-labelledby': 'h-import' }, h('h2', { id: 'h-import' }, t('data.import.heading'), info(t('help.import.dataset'), t('data.import.heading'))),
      h('p.muted', {}, t('backup.import.intro')),
      fileSource(ctx, current, { demo: false, level: 'h3' })));
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
          }))));
    // what plugins offer here: for experts, collapsed
    const slots = ctx.plugins.filter((p) => p.backup);
    if (slots.length) {
      root.append(h('section.section', { 'aria-labelledby': 'h-experts' },
        h('details.advanced', { id: 'backup-experts' }, h('summary', { id: 'h-experts' }, t('backup.experts')),
          slots.map((p) => {
            const box = h('div', { 'data-backup': p.id });
            p.backup.render(box, ctx);
            return h('div', {}, h('h3', {}, t(p.backup.label)), box);
          }))));
    }
  }
  // from a link to the corrections file (e.g. the note before an import): there
  if (params[0] === 'korrekturen') {
    const target = document.getElementById('corrections-file') ?? root.querySelector('#corrections-file');
    setTimeout(() => { target?.scrollIntoView({ block: 'center' }); target?.focus(); }, 0);
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
    fields.length ? [h('p', {}, t('data.corrections.summary.fields')), h('ul', {}, fields.map(([f, n]) => h('li', { 'data-field': f },
      `${f === 'exclude' ? t('data.corrections.summary.exclude') : f === 'demand' ? t('data.corrections.summary.demand') : fieldLabel(f)}: ${n}`)))]
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
        document.dispatchEvent(new Event('corrections-changed'));
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
        // the bytes as they are: checked and read on the server as the export program reads the file
        const bytes = new Uint8Array(await file.arrayBuffer());
        let summary;
        try {
          summary = await api.checkCorrections(bytes);
        } catch (e) {
          out.replaceChildren(errorAlert(e));
          return undefined;
        }
        const waiting = Boolean((await api.correctionsState().catch(() => null))?.loaded);
        out.replaceChildren(h('div.alert', { id: 'corrections-confirm' }, correctionsSummary(summary, names),
          h('div.toolbar', {},
            button(waiting ? t('data.corrections.confirmReplace') : t('data.corrections.confirm'), async () => {
              try {
                await api.loadCorrections(bytes);
              } catch (e) {
                out.replaceChildren(errorAlert(e));
                return;
              }
              out.replaceChildren(h('div.alert', {}, h('p', {}, t('data.corrections.loadedDone'))));
              input.value = '';
              await showLoaded();
              document.dispatchEvent(new Event('corrections-changed'));
            }, true),
            h('button.btn.btn-secondary', { type: 'button', onclick: () => out.replaceChildren() }, t('data.corrections.cancel')))));
        return undefined;
      })),
    out, loadedBox);
}
