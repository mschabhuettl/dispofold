// Plugins built into this program (docs/plugins.md). scripts/build.mjs bundles each plugin's front end
// (plugins/<id>/frontend/index.js, default export, see below) into the module 'dispofold:plugins'; main.js registers
// them here. Only the ones the server lists as switched on (GET /api/extensions) get pages, menu entries and help.
//
// A plugin's front end:
// { id, texts: { 'plugin.<id>.…': '…', 'error.plugin.<id>.…': '…' },
//   group: { id, label } (own menu entry; label a text key) or the id of a core group ('settings', ...),
//   pages: [{ path, tab, intro, help, viewer, render(root, ctx, params) }] (text keys; help: '<page>' or
//     '<page>#<anchor>' of its help pages; viewer: readers see the page too),
//   settings: { render(root, ctx) } (Einstellungen → Erweiterungen, while switched on),
//   importSource: { label, render(root, ctx) → () => request | null } (Daten; the request goes to its import source),
//   help: [{ page, title }] (plugins/<id>/docs/<page>.md, in the guide as plugin-<id>-<page>) }
import { TEXTS } from './texts.js';

const ID = /^[a-z][a-z0-9]{1,30}$/;

/** Checks a plugin's front end and adds its texts; a plugin can neither replace a core text nor another plugin's. */
export function register(plugin, texts = TEXTS) {
  if (!ID.test(plugin?.id ?? '')) throw new Error(`plugin id ${plugin?.id} is not lower-case letters and digits`);
  const prefixes = [`plugin.${plugin.id}.`, `error.plugin.${plugin.id}.`];
  for (const [key, value] of Object.entries(plugin.texts ?? {})) {
    if (!prefixes.some((p) => key.startsWith(p))) throw new Error(`plugin ${plugin.id}: text ${key} must start with ${prefixes.join(' or ')}`);
    if (Object.hasOwn(texts, key)) throw new Error(`plugin ${plugin.id}: text ${key} exists already`);
    if (typeof value !== 'string') throw new Error(`plugin ${plugin.id}: text ${key} is not a string`);
    texts[key] = value;
  }
  for (const page of plugin.pages ?? []) {
    if (!/^[a-z0-9-]+$/.test(page.path ?? '')) throw new Error(`plugin ${plugin.id}: page path ${page.path}`);
    if (typeof page.render !== 'function') throw new Error(`plugin ${plugin.id}: page ${page.path} has no render`);
  }
  return plugin;
}

/** Address of a plugin page: #/plugin/<id>/<path>. */
export const pagePath = (id, path) => `plugin/${id}/${path}`;

/** Id of a plugin help page in the guide, and so its file name (anleitung/plugin-<id>-<page>). */
export const helpPage = (id, page) => `plugin-${id}-${page}`;

/** The guide section of a plugin page's help ('<page>' or '<page>#<anchor>'). */
export function helpSection(plugin, help) {
  if (!help) return null;
  const [page, anchor] = help.split('#');
  return `${helpPage(plugin.id, page)}${anchor ? `#${anchor}` : ''}`;
}

/**
 * The routes of the switched on plugins for main.js: { path, group, label, tab, intro, section, view, viewer }.
 * groups: the labels of the menu groups by id, extended by the plugins' own groups.
 */
export function routes(plugins, enabled, groups, t) {
  const out = [];
  for (const p of plugins.filter((x) => enabled.has(x.id))) {
    if (!p.pages?.length) continue; // settings, an import source or help only: no menu entry
    const own = typeof p.group === 'object' && p.group;
    const group = own ? `plugin-${p.id}` : p.group;
    if (!group || !(own || Object.hasOwn(groups, group))) throw new Error(`plugin ${p.id}: unknown menu group ${p.group}`);
    if (own) groups[group] = t(own.label);
    for (const page of p.pages ?? []) {
      const label = t(page.tab);
      out.push({ path: pagePath(p.id, page.path), group, label, tab: label, view: { render: page.render },
        intro: page.intro ? t(page.intro) : '', section: helpSection(p, page.help), viewer: page.viewer ? group : undefined });
    }
  }
  return out;
}

/** The help pages of the switched on plugins as [id, title], after the core pages of the guide. */
export function helpPages(plugins, enabled) {
  return plugins.filter((p) => enabled.has(p.id)).flatMap((p) => (p.help ?? []).map((x) => [helpPage(p.id, x.page), x.title]));
}
