// The steps of an import, shared by the Daten page and plugin pages (lib/plugin-api.js): errors with all their
// messages, "master data changed" with keep or replace, the preview of an import keeping the changes made in
// Dispofold (conflicts, notes), and the summary.
import { monthLabel } from './dates.js';
import { guarded, h, saveBlob } from './dom.js';
import { messageText } from './codes.js';
import { conflictText, noteText, shortNames } from './data-files.js';
import { info } from './info.js';
import { t } from './texts.js';

/**
 * The steps of an import drawn into result. current() is the dataset in Dispofold (short names, null while unknown).
 * Returns { button, errorAlert, list, exportDataset, runImport, previewKeep, showSummary }.
 */
export function importSteps(ctx, result, current = () => null) {
  const { api } = ctx;
  const list = (items) => h('ul', {}, items.map((x) => h('li', {}, x)));
  const errorAlert = (e) => h('div.alert.alert-error', { role: 'alert' }, h('strong', {}, e.message),
    e.body?.errors?.length ? list(e.body.errors.map(messageText)) : null,
    e.body?.warnings?.length ? [h('p', {}, h('strong', {}, t('import.warnings', { count: e.body.warnings.length }))),
      list(e.body.warnings.map(messageText))] : null);
  const busy = (btn, fn) => async () => {
    btn.disabled = true;
    try { await fn(); } finally { btn.disabled = false; }
  };
  const button = (label, fn, primary = false, disabled = false) => {
    const b = h(`button.btn.${primary ? 'btn-primary' : 'btn-secondary'}`, { type: 'button', disabled }, label);
    b.addEventListener('click', busy(b, fn));
    return b;
  };
  const exportDataset = async () => {
    const file = await guarded(() => api.exportDataset());
    if (file) saveBlob(file.blob, file.filename);
  };

  /**
   * Runs an import (load(replace) calls the API). A rejected import lists all its errors ({ code, params }), not
   * only the first. Master data changed in Dispofold since the last import are only replaced after asking; an
   * uploaded dataset or a plugin's import can keep them instead (keepRun).
   */
  const runImport = async (load, replace = false, keepRun = null) => {
    try {
      return await load(replace);
    } catch (e) {
      if (e.code === 'import.masterChanged') {
        result.replaceChildren(h('div.alert', { role: 'alert' }, h('strong', {}, t('import.masterChanged.title')), ' ', t('import.masterChanged.text'),
          h('div.toolbar', {}, button(t('import.datasetExport'), exportDataset),
            keepRun && button(t('data.import.keepRun'), keepRun, true),
            button(t('import.masterChanged.replace'), async () => showSummary(await runImport(load, true)), !keepRun),
            button(t('common.cancel'), async () => result.replaceChildren()))));
        return undefined;
      }
      result.replaceChildren(errorAlert(e));
      return undefined;
    }
  };

  /**
   * Dry run of an import keeping the changes: what is carried over, conflicts and what is lost, then import. A plugin's
   * import source (via) has its own calls and no dataset here.
   */
  const previewKeep = async (data, via = { preview: () => api.importPreview(data), run: () => api.importDataset(data, false, true) }) => {
    let preview;
    try {
      preview = await via.preview();
    } catch (e) {
      result.replaceChildren(errorAlert(e));
      return;
    }
    const names = shortNames(current(), data);
    result.replaceChildren(h('div.alert', { id: 'import-preview' }, h('strong', {}, t('data.preview.title')),
      h('p', {}, preview.changed ? t('data.preview.kept', { count: preview.kept }) : t('data.preview.none')),
      preview.conflicts.length ? [h('p', {}, t('data.preview.conflicts')), list(preview.conflicts.map((c) => conflictText(c, names)))] : null,
      preview.notes.length ? [h('p', {}, t('data.preview.notes')), list(preview.notes.map((n) => noteText(n, names)))] : null,
      h('div.toolbar', {},
        button(t('data.preview.import'), async () => showSummary(await runImport(via.run)), true),
        button(t('common.cancel'), async () => result.replaceChildren()))));
  };

  const showSummary = (s) => {
    if (!s) return;
    ctx.invalidateDataset();

    result.replaceChildren(h('div.alert', {},
      h('strong', {}, t('import.summaryTitle', { source: s.source })), t('import.summary', { people: s.people, areas: s.areas, shifts: s.shifts, months: s.months.join(', ') || '–' }),
      s.kept ? h('p', {}, t('data.import.kept', { count: s.kept })) : null,
      s.warnings?.length ? [h('p', {}, h('strong', {}, t('import.warnings', { count: s.warnings.length }))), h('ul', {}, s.warnings.map((w) => h('li', {}, messageText(w))))] : null,
      s.months?.length && !s.months.includes(ctx.month) ? h('p', {}, button(t('import.switchMonth', { month: monthLabel(s.months[0]) }), () => ctx.setMonth(s.months[0]))) : null));
  };

  return { button, errorAlert, list, exportDataset, runImport, previewKeep, showSummary };
}

/**
 * The core's import of a plugin's request (docs/plugins.md), as on the Daten page: the choice "Änderungen aus
 * Dispofold behalten" and the button; then the preview with conflicts, "Änderungen behalten" or replace when master
 * data were changed, the summary and the errors, in result. request() gives the request, or null while it is
 * incomplete. Without result the outcome shows below the button (a live region); without current the dataset comes
 * from ctx.dataset(). Returns the element to place on the page.
 */
export function importFlow(ctx, id, request, { result = null, current } = {}) {
  const own = result ? null : h('div', { 'aria-live': 'polite' });
  let dataset = current ?? null;
  const steps = importSteps(ctx, result ?? own, () => dataset);
  const { api } = ctx;
  const keep = h('input', { id: `plugin-keep-${id}`, type: 'checkbox', checked: Boolean(dataset?.changedSinceImport) });
  if (current === undefined) {
    ctx.dataset().then((d) => {
      dataset = d;
      keep.checked = Boolean(d?.changedSinceImport);
    }, () => {});
  }
  const via = (req) => ({ preview: () => api.pluginImportPreview(id, req), run: () => api.pluginImport(id, req, false, true) });
  const toolbar = h('div.toolbar', {},
    h('label.check-inline', {}, keep, t('data.import.keep')), info(t('help.import.keep'), t('data.import.keep')),
    steps.button(t('data.plugin.run'), async () => {
      const req = request();
      if (!req) return guarded(() => Promise.reject(new Error(t('data.plugin.incomplete'))));
      if (keep.checked) return steps.previewKeep(null, via(req));
      if (!confirm(t('import.dataset.confirm'))) return undefined;
      return steps.showSummary(await steps.runImport((replace) => api.pluginImport(id, req, replace), false, () => steps.previewKeep(null, via(req))));
    }, true));
  return own ? h('div', {}, toolbar, own) : toolbar;
}
