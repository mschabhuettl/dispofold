// Front end of the plugin sp5 (import from a Schichtplaner 5 folder): its settings under Einstellungen →
// Erweiterungen (folder, mapping, options), its import source on the Daten page, and the result of an export as
// files for the comparison with sp5_export.py. Only lib/plugin-api.js and the core's classes.
import { folderPicker, guarded, h, info, saveBlob, t } from '../../../frontend/src/lib/plugin-api.js';

const ID = 'sp5';

/** The options of sp5_export.py: [key, label, help, kind] (number, optional number, demand, shift mix). */
const OPTIONS = [
  ['historyMonths', 'number'], ['areasFromHistory', 'optional'], ['shiftsFromHistory', 'optional'],
  ['demand', 'demand'], ['mixMinDuties', 'number'], ['hiddenShiftsMonths', 'number'], ['recentMonths', 'number'],
];

function monthFields(ctx, prefix) {
  const month = h('input', { id: `${prefix}-month`, type: 'month', value: ctx.month ?? '' });
  const to = h('input', { id: `${prefix}-to`, type: 'month' });
  const element = h('div.toolbar', {},
    h('div.field', {}, h('label', { for: `${prefix}-month` }, t('plugin.sp5.month')), month),
    h('div.field', {}, h('label', { for: `${prefix}-to` }, t('plugin.sp5.to')), to),
    info(t('plugin.sp5.to.help'), t('plugin.sp5.to')));
  return { element, value: () => (month.value ? { month: month.value, to: to.value || null } : null) };
}

export default {
  id: ID,
  texts: {
    'plugin.sp5.folder': 'Ordner von Schichtplaner 5',
    'plugin.sp5.folder.help': 'Der Datenordner von Schichtplaner 5 (mit 5EMPL.DBF und den anderen Tabellen). Die Erweiterung liest ihn nur. Am Server sind nur die Ordner wählbar, die die IT freigegeben hat; am eigenen Rechner öffnet „Ordner wählen …“ den Ordnerdialog.',
    'plugin.sp5.mapping': 'Zuordnung (JSON)',
    'plugin.sp5.mapping.help': 'Welche Schichten, Gruppen und Abwesenheiten von Schichtplaner 5 zu welchen Bereichen von Dispofold gehören, im Format von mapping.json. Datei wählen oder den Text einfügen; beim Speichern wird sie geprüft wie von sp5_export.py.',
    'plugin.sp5.mapping.file': 'Zuordnung aus Datei',
    'plugin.sp5.mapping.invalidJson': 'Die Zuordnung ist kein gültiges JSON.',
    'plugin.sp5.options': 'Optionen',
    'plugin.sp5.option.historyMonths': 'Monate Historie',
    'plugin.sp5.option.historyMonths.help': 'Monate vor dem ersten Planungsmonat, aus denen Historie, Bedarf und Schichtmix kommen (0 bis 24, Standard 12).',
    'plugin.sp5.option.areasFromHistory': 'Bereiche aus der Historie ab … Diensten',
    'plugin.sp5.option.areasFromHistory.help': 'Leer: aus. Sonst bekommt eine Person die Bereiche dazu, in denen sie in der Historie mindestens so viele Dienste hatte.',
    'plugin.sp5.option.shiftsFromHistory': 'Schichten seltener als … Dienste nur auf Anfrage',
    'plugin.sp5.option.shiftsFromHistory.help': 'Leer: aus. Sonst werden Schichten der eigenen Bereiche mit weniger Diensten in der Historie „nur auf Anfrage“.',
    'plugin.sp5.option.demand': 'Bedarf',
    'plugin.sp5.option.demand.help': 'Aus der tatsächlichen Besetzung in der Historie (Standard) oder aus dem Bedarf, der in Schichtplaner 5 eingetragen ist.',
    'plugin.sp5.option.demand.history': 'aus der Historie',
    'plugin.sp5.option.demand.sp5': 'aus Schichtplaner 5',
    'plugin.sp5.option.shiftMix': 'Gewohnte Verteilung übernehmen',
    'plugin.sp5.option.mixMinDuties': 'Gewohnte Verteilung ab … Diensten',
    'plugin.sp5.option.mixMinDuties.help': 'Nur Personen mit mindestens so vielen Diensten in der Historie bekommen eine gewohnte Verteilung (Standard 10).',
    'plugin.sp5.option.hiddenShiftsMonths': 'Ausgeblendete Schichten mit Diensten in den … Monaten davor',
    'plugin.sp5.option.hiddenShiftsMonths.help': 'Ausgeblendete Schichten werden übernommen, wenn sie in den Planungsmonaten oder so vielen Monaten davor Dienste haben (Standard 3, 0: nie).',
    'plugin.sp5.option.recentMonths': 'Bereiche ohne Dienst in den letzten … Monaten nur auf Anfrage',
    'plugin.sp5.option.recentMonths.help': 'Ein Bereich, in dem jemand in seinen letzten so vielen Monaten mit Diensten nicht mehr gearbeitet hat, wird „nur auf Anfrage“ (Standard 3, 0: aus).',
    'plugin.sp5.save': 'Einstellungen speichern',
    'plugin.sp5.saved': 'Einstellungen gespeichert',
    'plugin.sp5.import': 'Aus Schichtplaner 5',
    'plugin.sp5.month': 'Erster Monat',
    'plugin.sp5.to': 'Letzter Monat',
    'plugin.sp5.to.help': 'Leer: nur der erste Monat. Sonst bis zu 12 Monate danach.',
    'plugin.sp5.result': 'Ergebnis als Dateien speichern',
    'plugin.sp5.result.help': 'Speichert Datensatz, Bericht und Warnungen eines Exports als ZIP-Datei, mit allem, womit er gemacht wurde, für den Vergleich mit sp5_export.py (siehe Hilfe). Die Datei enthält personenbezogene Daten.',
    'plugin.sp5.result.run': 'Ergebnis speichern',
    'error.plugin.sp5.noMapping': 'Die Zuordnung fehlt: unter Einstellungen → Erweiterungen eintragen.',
    'error.plugin.sp5.noFolder': 'Der Ordner von Schichtplaner 5 fehlt oder ist nicht lesbar: unter Einstellungen → Erweiterungen wählen.',
    'error.plugin.sp5.setting': 'Eine Einstellung ist ungültig ({field}).',
  },
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
        const inputs = {};
        const field = (key, control) => h('div.field', {}, h('label', { for: `sp5-${key}` }, t(`plugin.sp5.option.${key}`),
          info(t(`plugin.sp5.option.${key}.help`), t(`plugin.sp5.option.${key}`))), control);
        const rows = OPTIONS.map(([key, kind]) => {
          const value = s.options?.[key];
          const control = kind === 'demand'
            ? h('select', { id: `sp5-${key}` }, ['history', 'sp5'].map((v) => h('option', { value: v, selected: value === v },
              t(`plugin.sp5.option.demand.${v}`))))
            : h('input', { id: `sp5-${key}`, type: 'number', min: kind === 'optional' ? 1 : 0, value: value ?? '' });
          inputs[key] = control;
          return field(key, control);
        });
        const shiftMix = h('input', { id: 'sp5-shiftMix', type: 'checkbox', checked: s.options?.shiftMix !== false });
        const save = h('button.btn.btn-primary', { id: 'sp5-save', type: 'button' }, t('plugin.sp5.save'));
        save.addEventListener('click', async () => {
          let parsed = null;
          if (mapping.value.trim()) {
            try { parsed = JSON.parse(mapping.value); } catch { return guarded(() => Promise.reject(new Error(t('plugin.sp5.mapping.invalidJson')))); }
          }
          const folder = picker.value() ?? { root: '', path: '' };
          const number = (v) => (v === '' ? null : Number(v));
          const options = Object.fromEntries(OPTIONS.map(([key, kind]) => [key, kind === 'demand' ? inputs[key].value : number(inputs[key].value)]));
          options.shiftMix = shiftMix.checked;
          return guarded(() => api.put('einstellungen', { root: folder.root, path: folder.path, mapping: parsed, options }), t('plugin.sp5.saved'));
        });
        const months = monthFields(ctx, 'sp5-result');
        const result = h('button.btn.btn-secondary', { id: 'sp5-result', type: 'button' }, t('plugin.sp5.result.run'));
        result.addEventListener('click', async () => {
          const body = months.value();
          if (!body) return;
          const file = await guarded(() => api.download('ergebnis', body, `dispofold-sp5-ergebnis-${body.month}.zip`));
          if (file) saveBlob(file.blob, file.filename);
        });
        box.append(
          h('div.with-info', {}, h('strong', {}, t('plugin.sp5.folder')), info(t('plugin.sp5.folder.help'), t('plugin.sp5.folder'))),
          picker.element,
          h('div.field', {}, h('label', { for: 'sp5-mapping' }, t('plugin.sp5.mapping'), info(t('plugin.sp5.mapping.help'), t('plugin.sp5.mapping'))),
            mapping),
          h('div.field', {}, h('label', { for: 'sp5-mapping-file' }, t('plugin.sp5.mapping.file')), file),
          h('h4', {}, t('plugin.sp5.options')),
          ...rows,
          h('label.check', {}, shiftMix, t('plugin.sp5.option.shiftMix')),
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
  help: [{ page: 'sp5', title: 'Import aus Schichtplaner 5' }, { page: 'vergleich', title: 'Vergleich mit sp5-export' }],
};
