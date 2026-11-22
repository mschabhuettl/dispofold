// Front end of the plugin sp5 (import from a Schichtplaner 5 folder): its pages in its own menu group (Übersicht with
// the run and its import, Bericht, Zuordnung, Vergleich), its settings under Einstellungen → Erweiterungen (folder,
// mapping, options), its import source on the Daten page, and the result of an export as files for the comparison
// with sp5_export.py. Only lib/plugin-api.js, its own files and the core's classes.
import { folderPicker, guarded, h, info, saveBlob, t } from '../../../frontend/src/lib/plugin-api.js';
import * as compare from './compare.js';
import * as mappingPage from './mapping.js';
import * as overview from './overview.js';
import * as report from './report.js';
import { ID, monthFields, optionFields, pageHref } from './shared.js';
import { TEXTS } from './texts.js';

export default {
  id: ID,
  texts: TEXTS,
  group: { label: 'plugin.sp5.group' },
  pages: [
    { path: 'uebersicht', tab: 'plugin.sp5.tab.uebersicht', intro: 'plugin.sp5.page.uebersicht', help: 'uebersicht', render: overview.render },
    { path: 'bericht', tab: 'plugin.sp5.tab.bericht', intro: 'plugin.sp5.page.bericht', help: 'bericht', render: report.render },
    { path: 'zuordnung', tab: 'plugin.sp5.tab.zuordnung', intro: 'plugin.sp5.page.zuordnung', help: 'zuordnung', render: mappingPage.render },
    { path: 'lauf-vergleich', tab: 'plugin.sp5.tab.vergleich', intro: 'plugin.sp5.page.vergleich', help: 'lauf-vergleich', render: compare.render },
  ],
  settings: {
    render(root, ctx) {
      const api = ctx.api.plugin(ID);
      const box = h('div', {});
      root.append(box);
      api.get('einstellungen').then((s) => {
        const picker = folderPicker(ctx, { id: 'sp5-folder', start: { root: s.root, path: s.path } });
        const mapping = h('textarea', { id: 'sp5-mapping', rows: 8, spellcheck: 'false' });
        mapping.value = s.mapping ? JSON.stringify(s.mapping, null, 2) : '';
        const file = h('input', { id: 'sp5-mapping-file', type: 'file', accept: '.json,application/json' });
        file.addEventListener('change', async () => { if (file.files[0]) mapping.value = await file.files[0].text(); });
        const options = optionFields('sp5', s.options ?? {});
        const save = h('button.btn.btn-primary', { id: 'sp5-save', type: 'button' }, t('plugin.sp5.save'));
        save.addEventListener('click', async () => {
          let parsed = null;
          if (mapping.value.trim()) {
            try { parsed = JSON.parse(mapping.value); } catch { return guarded(() => Promise.reject(new Error(t('plugin.sp5.mapping.invalidJson')))); }
          }
          const folder = picker.value() ?? { root: '', path: '' };
          return guarded(() => api.put('einstellungen', { root: folder.root, path: folder.path, mapping: parsed, options: options.value() }), t('plugin.sp5.saved'));
        });
        const months = monthFields(ctx, 'sp5-result');
        const result = h('button.btn.btn-secondary', { id: 'sp5-result', type: 'button' }, t('plugin.sp5.result.run'));
        result.addEventListener('click', async () => {
          const body = months.value();
          if (!body) return;
          const zip = await guarded(() => api.download('ergebnis', body, `dispofold-sp5-ergebnis-${body.month}.zip`));
          if (zip) saveBlob(zip.blob, zip.filename);
        });
        box.append(
          h('div.with-info', {}, h('strong', {}, t('plugin.sp5.folder')), info(t('plugin.sp5.folder.help'), t('plugin.sp5.folder'))),
          picker.element,
          h('div.field', {}, h('label', { for: 'sp5-mapping' }, t('plugin.sp5.mapping'), info(t('plugin.sp5.mapping.help'), t('plugin.sp5.mapping'))),
            mapping),
          h('div.field', {}, h('label', { for: 'sp5-mapping-file' }, t('plugin.sp5.mapping.file')), file),
          h('p', {}, h('a', { href: pageHref('zuordnung') }, t('plugin.sp5.mapping.editor'))),
          h('h4', {}, t('plugin.sp5.options')),
          ...options.elements,
          h('div.row', {}, save),
          h('h4', {}, t('plugin.sp5.result'), info(t('plugin.sp5.result.help'), t('plugin.sp5.result'))),
          months.element, h('div.row', {}, result));
      }, (e) => box.append(h('p.alert.alert-error', { role: 'alert' }, e.message)));
    },
  },
  importSource: {
    label: 'plugin.sp5.import',
    render(root, ctx) {
      const months = monthFields(ctx, 'sp5-import');
      root.append(months.element);
      return () => months.value();
    },
  },
  help: [
    { page: 'ablauf', title: 'Ablauf: von Schichtplaner 5 zum Import' },
    { page: 'sp5', title: 'Import aus Schichtplaner 5' },
    { page: 'uebersicht', title: 'Schichtplaner 5: Übersicht' },
    { page: 'bericht', title: 'Schichtplaner 5: Bericht' },
    { page: 'zuordnung', title: 'Schichtplaner 5: Zuordnung' },
    { page: 'lauf-vergleich', title: 'Schichtplaner 5: Vergleich' },
    { page: 'vergleich', title: 'Vergleich mit sp5-export' },
  ],
};
