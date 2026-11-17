// Import/Export: dataset upload, demo data, plan export/import, finalize month.
import { monthLabel } from '../lib/dates.js';
import { guarded, h, saveBlob, toast } from '../lib/dom.js';
import { messageText } from '../lib/codes.js';
import { t } from '../lib/texts.js';
import { EXPORT_FORMATS, closeMonth, exportPlanFile } from './plan-output.js';

export async function render(root, ctx) {
  const { api, month, canEdit } = ctx;
  const result = h('div', { 'aria-live': 'polite' });
  const state = await api.planState(month).catch(() => null);
  const solving = Boolean(state?.solving);
  const closed = state?.plan?.status === 'CLOSED';

  /**
   * Runs an import (load(replace) calls the API). A rejected import lists all its errors ({ code, params }), not
   * only the first. Master data changed in Dispofold since the last import are only replaced after asking.
   */
  const runImport = async (load, replace = false) => {
    try {
      return await load(replace);
    } catch (e) {
      if (e.code === 'import.masterChanged') {
        result.replaceChildren(h('div.alert', { role: 'alert' }, h('strong', {}, t('import.masterChanged.title')), ' ', t('import.masterChanged.text'),
          h('div.toolbar', {}, button(t('import.datasetExport'), exportDataset),
            button(t('import.masterChanged.replace'), async () => showSummary(await runImport(load, true)), true),
            button(t('common.cancel'), async () => result.replaceChildren()))));
        return undefined;
      }
      const errors = e.body?.errors ?? [];
      const warnings = e.body?.warnings ?? [];
      result.replaceChildren(h('div.alert.alert-error', { role: 'alert' }, h('strong', {}, e.message),
        errors.length ? h('ul', {}, errors.map((x) => h('li', {}, messageText(x)))) : null,
        warnings.length ? [h('p', {}, h('strong', {}, t('import.warnings', { count: warnings.length }))),
          h('ul', {}, warnings.map((w) => h('li', {}, messageText(w))))] : null));
      return undefined;
    }
  };
  const exportDataset = async () => {
    const file = await guarded(() => api.exportDataset());
    if (file) saveBlob(file.blob, file.filename);
  };

  const showSummary = (s) => {
    if (!s) return;
    ctx.invalidateDataset();

    result.replaceChildren(h('div.alert', {},
      h('strong', {}, t('import.summaryTitle', { source: s.source })), t('import.summary', { people: s.people, areas: s.areas, shifts: s.shifts, months: s.months.join(', ') || '–' }),
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
      h('section.section', { 'aria-labelledby': 'h-import' }, h('h2', { id: 'h-import' }, t('import.dataset.import')),
        h('p.muted', {}, t('import.dataset.help')),
        h('div.toolbar', {},
          h('div.field', {}, h('label', { for: 'dataset-file' }, t('import.dataset.label')), datasetFile),
          button(t('import.dataset.import'), async () => {
            const data = await guarded(() => readJson(datasetFile));
            if (data && !confirm(t('import.dataset.confirm'))) return;
            if (data) showSummary(await runImport((replace) => api.importDataset(data, replace)));
          }, true)),
        // demo data are for trying the program out: collapsed
        h('details.advanced', {}, h('summary', {}, t('roster.advanced')),
          h('div.toolbar', {},
            button(t('import.demo'), async () => {
              if (!confirm(t('import.demo.confirm'))) return;
              showSummary(await runImport((replace) => api.importDemo(replace)));
            })))),
    );
  }

  // exports are for planners only (viewers get 403) and blocked while a solve runs
  if (canEdit) {
    root.append(h('section.section', { 'aria-labelledby': 'h-dataset-export' }, h('h2', { id: 'h-dataset-export' }, t('import.datasetExport')),
      h('p.muted', {}, t('import.datasetExport.help')),
      h('div.toolbar', {}, button(t('import.datasetExport'), exportDataset))));
    root.append(h('section.section', { 'aria-labelledby': 'h-export' }, h('h2', { id: 'h-export' }, t('import.export.heading', { month: monthLabel(month) })),
      solving && h('p.muted', {}, t('import.export.solving')),
      h('div.toolbar', {}, EXPORT_FORMATS.map((f) => (solving
        ? h('button.btn.btn-secondary', { type: 'button', disabled: true }, f.toUpperCase())
        : button(f.toUpperCase(), () => exportPlanFile(api, month, f)))))));
  }

  if (canEdit) {
    root.append(
      h('section.section', { 'aria-labelledby': 'h-plan-import' }, h('h2', { id: 'h-plan-import' }, t('import.plan.import')),
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
        ? h('section.section', { 'aria-labelledby': 'h-final' }, h('h2', { id: 'h-final' }, t('import.reopen')),
          h('p.muted', {}, t('import.reopen.help', { month: monthLabel(month) })),
          button(t('import.reopen'), async () => {
            if (!confirm(t('import.reopen.confirm', { month: monthLabel(month) }))) return;
            const ok = await guarded(() => api.reopen(month));
            if (ok !== undefined) {
              toast(t('import.reopen.done', { month: monthLabel(month) }));
              ctx.setMonth(month); // draw again with the new status
            }
          }))
        : h('section.section', { 'aria-labelledby': 'h-final' }, h('h2', { id: 'h-final' }, t('import.finalize')),
          h('p.muted', {}, t('import.finalize.help')),
          button(t('import.finalize'), () => closeMonth(ctx), false, solving)),
    );
  }
}
