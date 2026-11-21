// Picking a folder (or a file in it) that plugins may read: on the server one of the folders the operator allowed
// and below it, in the desktop program also a folder from the window's folder dialog (GET /api/files/roots,
// /api/files/list, POST /__shell/pick-folder). Nothing outside the allowed folders can be chosen.
import { guarded, h } from './dom.js';
import { t } from './texts.js';

/**
 * @param ctx the page context (ctx.api, ctx.me.desktop)
 * @param files true: a file is chosen, false: a folder
 * @returns { element, value() → { root, path } | null }
 */
export function folderPicker(ctx, { id = 'folder', files = false } = {}) {
  const { api } = ctx;
  let roots = [];
  let root = '';
  let path = ''; // the folder shown, relative to the root
  let file = ''; // files: the chosen file in it
  const select = h('select', { id, onchange: (e) => { root = e.target.value; path = ''; file = ''; draw(); } });
  const where = h('p.muted', { 'aria-live': 'polite' });
  const list = h('ul.folder-list', {});
  const button = (label, fn) => h('button.btn.btn-secondary.btn-small', { type: 'button', onclick: fn }, label);
  const pick = ctx.me?.desktop && button(t('files.pick'), async () => {
    const res = await guarded(() => api.pickFolder());
    if (!res?.id) return;
    root = res.id;
    path = '';
    file = '';
    await load();
  });

  async function load() {
    roots = (await guarded(() => api.fileRoots())) ?? [];
    if (!roots.some((r) => r.id === root)) root = roots[0]?.id ?? '';
    select.replaceChildren(...roots.map((r) => h('option', { value: r.id, selected: r.id === root }, r.id)));
    await draw();
  }

  async function draw() {
    if (!root) {
      where.textContent = ctx.me?.desktop ? t('files.none.desktop') : t('files.none.server');
      list.replaceChildren();
      return;
    }
    const entries = (await guarded(() => api.fileList(root, path))) ?? [];
    const shown = { path: [root, path, file].filter(Boolean).join('/') };
    where.textContent = files ? t('files.chosenFile', shown) : t('files.chosenFolder', shown);
    const join = (name) => (path ? `${path}/${name}` : name);
    list.replaceChildren(
      path && h('li', {}, button(t('files.up'), () => { path = path.split('/').slice(0, -1).join('/'); file = ''; draw(); })),
      ...entries.filter((e) => e.dir || files).map((e) => h('li', {}, e.dir
        ? button(`${e.name}/`, () => { path = join(e.name); file = ''; draw(); })
        : button(e.name, () => { file = e.name; draw(); }))));
  }

  const element = h('div.field.folder-picker', {}, h('label', { for: id }, files ? t('files.file') : t('files.folder')),
    h('div.toolbar', {}, select, pick), where, list);
  load();
  return {
    element,
    value: () => (root && (!files || file) ? { root, path: files ? (path ? `${path}/${file}` : file) : path } : null),
  };
}
