// The first screen without data (Dienstplan, planners): load the demo data to try the program out, import a dataset,
// or open the pages of a switched on plugin (an import from another program); without one, where plugins are
// switched on.
import { h, toast } from '../lib/dom.js';
import { importSteps } from '../lib/import-flow.js';
import { pagePath } from '../lib/plugins.js';
import { t } from '../lib/texts.js';

/** The empty state with its actions; after the demo data are loaded the view shows their month. */
export function emptyStart(ctx) {
  const result = h('div', { 'aria-live': 'polite' });
  const { button, runImport } = importSteps(ctx, result);
  const withPages = ctx.plugins.filter((p) => p.pages?.length);
  return h('section.empty-start', { id: 'empty-start', 'aria-labelledby': 'h-empty-start' },
    h('h2', { id: 'h-empty-start' }, t('start.empty.title')),
    h('p', {}, t('start.empty.text')),
    h('div.toolbar', {},
      button(t('start.empty.demo'), async () => {
        const s = await runImport((replace) => ctx.api.importDemo(replace));
        if (!s) return;
        ctx.invalidateDataset();
        toast(t('start.empty.loaded'));
        ctx.setMonth(!s.months?.length || s.months.includes(ctx.month) ? ctx.month : s.months[0]);
      }, true),
      h('a.btn.btn-secondary', { href: '#/uebernehmen' }, t('start.empty.import')),
      // a switched on plugin: its first page, named like its menu entry
      withPages.map((p) => h('a.btn.btn-secondary', { href: `#/${pagePath(p.id, p.pages[0].path)}`, 'data-plugin': p.id },
        t(typeof p.group === 'object' && p.group ? p.group.label : p.pages[0].tab)))),
    !withPages.length && h('p.muted', {}, t('start.empty.plugins'), ' ', h('a', { href: '#/erweiterungen' }, t('start.empty.pluginsLink'))),
    result);
}
