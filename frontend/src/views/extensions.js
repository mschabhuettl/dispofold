// Einstellungen → Erweiterungen (planners): the plugins built into this program, each switched on or off after a
// confirmation; a switched on plugin shows its own settings below. Switching off keeps its settings.
import { guarded, h } from '../lib/dom.js';
import { info } from '../lib/info.js';
import { t } from '../lib/texts.js';

/** plugins: the front ends built into this program (lib/plugins.js); reload: shows the program with the change. */
export function view(plugins, reload) {
  return {
    async render(root, ctx) {
      const listed = (await ctx.api.extensions()) ?? [];
      const byId = new Map(plugins.map((p) => [p.id, p]));
      root.append(h('h1', {}, t('extensions.title')));
      if (!listed.length) {
        root.append(h('p.muted', { id: 'extensions-none' }, t('extensions.none')));
        return;
      }
      const box = (x) => {
        const id = `ext-${x.id}`;
        const toggle = h('input', { id, type: 'checkbox', checked: x.enabled });
        toggle.addEventListener('change', async () => {
          const on = toggle.checked;
          if (!confirm(on ? t('extensions.confirmOn', { name: x.name }) : t('extensions.confirmOff', { name: x.name }))) {
            toggle.checked = !on;
            return;
          }
          toggle.disabled = true;
          const res = await guarded(() => ctx.api.setExtension(x.id, on), on ? t('extensions.switchedOn', { name: x.name }) : t('extensions.switchedOff', { name: x.name }));
          toggle.disabled = false;
          if (res === undefined) toggle.checked = !on;
          else reload();
        });
        const settings = x.enabled && byId.get(x.id)?.settings;
        const own = settings ? h('div.extension-settings', {}) : null;
        if (own) settings.render(own, ctx);
        return h('section.extension', { 'aria-labelledby': `${id}-name` },
          h('h3', { id: `${id}-name` }, x.name, ' ', h('span.muted', {}, t('extensions.version', { version: x.version }))),
          h('p', {}, x.description),
          h('div.with-info', {}, h('label.check', {}, toggle, t('extensions.enabled')), info(t('help.field.extensions.enabled'), t('extensions.enabled'))),
          !byId.has(x.id) && h('p.muted', {}, t('extensions.noFrontEnd')),
          own);
      };
      root.append(h('div.settings', {}, listed.map(box)));
    },
  };
}
