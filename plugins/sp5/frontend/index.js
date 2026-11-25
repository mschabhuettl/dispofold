// Front end of the plugin sp5 (import from a Schichtplaner 5 folder): its source on Daten → Übernehmen (folder,
// options and the two steps Einlesen and Übernehmen, with the report and the comparison of a run), its page Zuordnung
// under Daten, on Einstellungen → Erweiterungen only a link to Übernehmen, and the result of an export as files for
// the comparison with sp5_export.py under Daten → Sichern. Only lib/plugin-api.js, its own files and the core's classes.
import { guarded, h, info, saveBlob, t } from '../../../frontend/src/lib/plugin-api.js';
import * as mappingPage from './mapping.js';
import { ID, TAKE_OVER, monthFields } from './shared.js';
import * as takeOver from './take-over.js';
import { TEXTS } from './texts.js';

export default {
  id: ID,
  texts: TEXTS,
  group: 'data',
  pages: [
    { path: 'zuordnung', tab: 'plugin.sp5.tab.zuordnung', intro: 'plugin.sp5.page.zuordnung', help: 'zuordnung', after: 'uebernehmen', render: mappingPage.render },
  ],
  // the pages of earlier versions: their content is on Daten → Übernehmen now
  moved: { uebersicht: `uebernehmen/${ID}`, bericht: `uebernehmen/${ID}`, 'lauf-vergleich': `uebernehmen/${ID}` },
  settings: {
    render(root) {
      root.append(h('p', {}, t('plugin.sp5.settings.where'), ' ', h('a', { id: 'sp5-set-up', href: TAKE_OVER }, t('plugin.sp5.settings.setUp'))));
    },
  },
  importSource: { label: 'plugin.sp5.source.label', render: takeOver.render },
  backup: {
    label: 'plugin.sp5.result',
    render(root, ctx) {
      const api = ctx.api.plugin(ID);
      const months = monthFields(ctx, 'sp5-result');
      const result = h('button.btn.btn-secondary', { id: 'sp5-result', type: 'button' }, t('plugin.sp5.result.run'));
      result.addEventListener('click', async () => {
        const body = months.value();
        if (!body) return;
        const zip = await guarded(() => api.download('ergebnis', body, `dispofold-sp5-ergebnis-${body.month}.zip`));
        if (zip) saveBlob(zip.blob, zip.filename);
      });
      root.append(h('p.muted', {}, t('plugin.sp5.result.help')), months.element, h('div.row', {}, result, info(t('plugin.sp5.result.help'), t('plugin.sp5.result'))));
    },
  },
  help: [
    { page: 'echte-daten', title: 'Echte Daten aus Schichtplaner 5' },
    { page: 'ablauf', title: 'Ablauf: von Schichtplaner 5 zum Import' },
    { page: 'sp5', title: 'Import aus Schichtplaner 5' },
    { page: 'uebersicht', title: 'Schichtplaner 5: Einlesen und Übernehmen' },
    { page: 'bericht', title: 'Schichtplaner 5: Bericht' },
    { page: 'zuordnung', title: 'Schichtplaner 5: Zuordnung' },
    { page: 'lauf-vergleich', title: 'Schichtplaner 5: Vergleich mit dem letzten Import' },
    { page: 'technik', title: 'Schichtplaner 5: Technik und Vergleich mit sp5-export' },
  ],
};
