// Daten → Übernehmen: choose where the data come from (a plugin's import source, e.g. a folder of another program, or
// a dataset file), then two steps: "Einlesen" reads and shows what came (nothing changes in Dispofold), "Übernehmen"
// replaces the data with it. A plugin's source either draws its steps itself or gives a request for the core's step.
import { monthLabel } from '../lib/dates.js';
import { guarded, h } from '../lib/dom.js';
import { importFlow, importSteps } from '../lib/import-flow.js';
import { info } from '../lib/info.js';
import { t } from '../lib/texts.js';

const SOURCE = 'importSource';

/** The JSON of the file chosen in a file input. */
export const readJson = (input) => new Promise((resolve, reject) => {
  const file = input.files[0];
  if (!file) return reject(new Error(t('import.pickFile')));
  file.text().then((text) => {
    try { resolve(JSON.parse(text)); } catch { reject(new Error(t('import.invalidJson'))); }
  }, reject);
});

/** What step 2 replaces: the data in Dispofold now, by their source. */
export const replacesText = (current) => (current?.people?.length
  ? t('takeOver.replaces', { source: current.source || t('takeOver.replaces.unknown') }) : t('takeOver.replaces.none'));

/**
 * A dataset file in two steps: Einlesen (read, counts, nothing changes), then Übernehmen (the import, keeping the
 * changes made in Dispofold if chosen). demo: the demo data under "Erweitert".
 */
export function fileSource(ctx, current, { demo = true } = {}) {
  const { api } = ctx;
  const result = h('div', { 'aria-live': 'polite' });
  const { button, runImport, previewKeep, showSummary } = importSteps(ctx, result, () => current);
  const input = h('input', { id: 'dataset-file', type: 'file', accept: '.json,application/json' });
  const keep = h('input', { id: 'dataset-keep', type: 'checkbox', checked: Boolean(current?.changedSinceImport) });
  const read = h('div', { id: 'dataset-read', 'aria-live': 'polite' });
  let data = null;
  const take = button(t('takeOver.run'), async () => {
    if (!data) return;
    if (keep.checked) return previewKeep(data);
    showSummary(await runImport((replace) => api.importDataset(data, replace), false, () => previewKeep(data)));
  }, true, true);
  take.id = 'dataset-take';
  const what = h('p.muted', { id: 'dataset-take-what' }, t('takeOver.firstRead'));
  // another file: read again first
  input.addEventListener('change', () => {
    data = null;
    take.disabled = true;
    read.replaceChildren();
    what.textContent = t('takeOver.firstRead');
  });
  const readButton = button(t('takeOver.read'), async () => {
    const d = await guarded(() => readJson(input));
    if (!d) return;
    if (!Array.isArray(d.people) || !Array.isArray(d.periods ?? [])) {
      data = null;
      take.disabled = true;
      read.replaceChildren(h('p.alert.alert-error', { role: 'alert' }, t('takeOver.notDataset')));
      return;
    }
    data = d;
    const months = (d.periods ?? []).map((p) => p.month).filter(Boolean);
    const counts = { people: d.people.length, areas: d.areas?.length ?? 0, shifts: d.shifts?.length ?? 0,
      months: months.map(monthLabel).join(', ') || '–' };
    read.replaceChildren(h('div.alert', { id: 'dataset-read-result' }, h('p', {}, h('strong', {}, t('takeOver.file.read', counts))),
      d.source ? h('p', {}, t('takeOver.file.source', { source: d.source })) : null));
    what.textContent = `${t('takeOver.file.what', counts)}; ${replacesText(current)}`;
    take.disabled = false;
  });
  readButton.id = 'dataset-read-run';
  return h('div', {},
    h('ol.steps', {},
      h('li', {}, h('h3', {}, t('takeOver.step1')),
        h('div.toolbar', {}, h('div.field', {}, h('label', { for: 'dataset-file' }, t('import.dataset.label')), input), readButton),
        h('p.muted', {}, t('takeOver.read.note')), read),
      h('li', {}, h('h3', {}, t('takeOver.step2')), what,
        h('div.toolbar', {}, h('label.check-inline', {}, keep, t('data.import.keep')), info(t('help.import.keep'), t('data.import.keep')), take))),
    result,
    // demo data are for trying the program out: collapsed
    demo && h('details.advanced', {}, h('summary', {}, t('roster.advanced')),
      h('div.toolbar', {},
        button(t('import.demo'), async () => {
          if (!confirm(t('import.demo.confirm'))) return;
          showSummary(await runImport((replace) => api.importDemo(replace)));
        }), info(t('help.import.demo'), t('import.demo')))));
}

export async function render(root, ctx, params = []) {
  const current = await ctx.dataset().catch(() => null);
  const sources = [...ctx.plugins.filter((p) => p.importSource).map((p) => ({ id: p.id, label: t(p.importSource.label), plugin: p })),
    { id: 'datei', label: t('takeOver.source.file') }];
  let stored = null;
  try { stored = localStorage.getItem(SOURCE); } catch { /* the first one then */ }
  // a link may name the source (#/uebernehmen/<id>); else the one chosen last
  let chosen = sources.find((s) => s.id === params[0]) ?? sources.find((s) => s.id === stored) ?? sources[0];
  if (params[0] && chosen.id === params[0]) {
    try { localStorage.setItem(SOURCE, chosen.id); } catch { /* not remembered */ }
  }
  const body = h('div', { id: 'take-over-source' });

  const draw = () => {
    body.replaceChildren();
    if (chosen.id === 'datei') {
      body.append(fileSource(ctx, current));
      return;
    }
    const box = h('div', {});
    body.append(box);
    const request = chosen.plugin.importSource.render(box, ctx);
    // a source that only gives its request: the core's step Übernehmen below its form
    if (typeof request === 'function') {
      body.append(h('h3', {}, t('takeOver.step2')), h('p.muted', {}, replacesText(current)),
        importFlow(ctx, chosen.id, request, { current }));
    }
  };

  root.append(h('h1', {}, t('nav.takeOver')));
  if (sources.length > 1) {
    root.append(h('fieldset.source-choice', { id: 'take-over-sources' }, h('legend', {}, t('takeOver.source')),
      sources.map((s) => {
        const radio = h('input', { type: 'radio', name: 'take-over-source', id: `source-${s.id}`, value: s.id, checked: s === chosen });
        radio.addEventListener('change', () => {
          if (!radio.checked || (ctx.isDirty?.() && !ctx.mayLeave())) return;
          chosen = s;
          try { localStorage.setItem(SOURCE, s.id); } catch { /* not remembered */ }
          ctx.isDirty = null;
          draw();
        });
        return h('label.check-inline', { for: `source-${s.id}` }, radio, ' ', s.label);
      })));
  }
  root.append(body);
  draw();
}
