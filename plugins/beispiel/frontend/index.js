// Front end of the example plugin (only in test builds): a page readers see too, a setting, an import source and a
// help page. It uses only lib/plugin-api.js and the core's classes.
import { folderPicker, guarded, h, info, t } from '../../../frontend/src/lib/plugin-api.js';

const ID = 'beispiel';

export default {
  id: ID,
  texts: {
    'plugin.beispiel.group': 'Beispiel',
    'plugin.beispiel.tab.start': 'Gruß',
    'plugin.beispiel.page.start': 'Die Seite der Beispiel-Erweiterung zeigt den Gruß aus ihren Einstellungen.',
    'plugin.beispiel.title': 'Beispiel',
    'plugin.beispiel.greeting': 'Gruß',
    'plugin.beispiel.greeting.help': 'Der Text auf der Seite der Erweiterung, 1 bis 40 Zeichen.',
    'plugin.beispiel.save': 'Gruß speichern',
    'plugin.beispiel.saved': 'Gruß gespeichert',
    'plugin.beispiel.import': 'Aus der Beispiel-Erweiterung',
    'plugin.beispiel.corrections': 'Die Korrekturdatei als angewendet mitgeben',
    'error.plugin.beispiel.greeting': 'Der Gruß braucht 1 bis {max} Zeichen.',
    'error.plugin.beispiel.unreadable': 'Die Datei ist kein Datensatz.',
  },
  group: { label: 'plugin.beispiel.group' },
  pages: [{
    path: 'start', tab: 'plugin.beispiel.tab.start', intro: 'plugin.beispiel.page.start', help: 'beispiel', viewer: true,
    async render(root, ctx) {
      const { gruss } = await ctx.api.plugin(ID).get('gruss');
      root.append(h('h1', {}, t('plugin.beispiel.title')), h('p', { id: 'beispiel-gruss' }, gruss));
    },
  }],
  settings: {
    render(root, ctx) {
      const input = h('input', { id: 'beispiel-greeting', type: 'text', maxlength: 40 });
      ctx.api.plugin(ID).get('einstellungen').then((s) => { input.value = s.gruss ?? ''; }, () => {});
      const save = h('button.btn.btn-primary', { id: 'beispiel-save', type: 'button' }, t('plugin.beispiel.save'));
      save.addEventListener('click', () => guarded(() => ctx.api.plugin(ID).put('einstellungen', { gruss: input.value }),
        t('plugin.beispiel.saved')));
      root.append(h('div.field', {}, h('label', { for: 'beispiel-greeting' }, t('plugin.beispiel.greeting'),
        info(t('plugin.beispiel.greeting.help'), t('plugin.beispiel.greeting'))), input), h('div.row', {}, save));
    },
  },
  importSource: {
    label: 'plugin.beispiel.import',
    render(root, ctx) {
      const picker = folderPicker(ctx, { id: 'beispiel-file', files: true });
      const corrections = h('input', { id: 'beispiel-corrections', type: 'checkbox' });
      root.append(picker.element, h('label.check-inline', {}, corrections, t('plugin.beispiel.corrections')));
      return () => {
        const v = picker.value();
        return v && { root: v.root, file: v.path, corrections: corrections.checked };
      };
    },
  },
  help: [{ page: 'beispiel', title: 'Beispiel-Erweiterung' }],
};
