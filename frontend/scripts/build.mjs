// Builds frontend/dist: copies public/, bundles src/ with esbuild and copies the needed vendor files.
// Plugins (docs/plugins.md): the front end of each plugin in ../plugins/<id>/ is bundled as the module
// 'dispofold:plugins', its help pages (docs/*.md) join the built-in guide. Plugins marked testOnly in their plugin.json
// only with --test-plugins, which needs --out <folder> so that they never reach dist/ (and so no image or bundle).
import { build } from 'esbuild';
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, readdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Marked } from 'marked';
import { PAGES, SITE, guideFile, slug } from '../src/lib/guide.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
const testPlugins = args.includes('--test-plugins');
const out = args.includes('--out') ? resolve(args[args.indexOf('--out') + 1]) : null;
if (testPlugins && (!out || out === join(root, 'dist'))) throw new Error('--test-plugins needs --out <folder> other than dist');
const dist = out ?? join(root, 'dist');

/** The plugins to build in: [{ id, dir, manifest }]; a plugin has no styles of its own (lib/plugin-api.js). */
function pluginList() {
  const base = join(root, '..', 'plugins');
  if (!existsSync(base)) return [];
  const list = [];
  for (const id of readdirSync(base).sort()) {
    const dir = join(base, id);
    const manifest = JSON.parse(readFileSync(join(dir, 'plugin.json'), 'utf8'));
    if (manifest.id !== id) throw new Error(`plugins/${id}/plugin.json has the id ${manifest.id}`);
    if (manifest.testOnly && !testPlugins) continue;
    const front = join(dir, 'frontend');
    for (const f of existsSync(front) ? readdirSync(front, { recursive: true }).map(String) : []) {
      if (/\.(css|scss|less)$/i.test(f)) throw new Error(`plugins/${id}/frontend/${f}: plugins bring no styles`);
      if (/\.m?js$/.test(f) && /createElement\(\s*['"]style|<style|<link|adoptedStyleSheets|insertRule|CSSStyleSheet/.test(readFileSync(join(front, f), 'utf8'))) {
        throw new Error(`plugins/${id}/frontend/${f}: plugins bring no styles`);
      }
    }
    list.push({ id, dir, manifest, front: existsSync(join(front, 'index.js')) });
  }
  return list;
}
const plugins = pluginList();
const pluginModule = {
  name: 'dispofold-plugins',
  setup(b) {
    b.onResolve({ filter: /^dispofold:plugins$/ }, () => ({ path: 'plugins', namespace: 'dispofold' }));
    b.onLoad({ filter: /.*/, namespace: 'dispofold' }, () => {
      const front = plugins.filter((p) => p.front);
      const imports = front.map((p, i) => `import p${i} from ${JSON.stringify(join(p.dir, 'frontend', 'index.js').split('\\').join('/'))};`);
      return { contents: `${imports.join('\n')}\nexport default [${front.map((_, i) => `p${i}`).join(', ')}];\n`, resolveDir: root };
    });
  },
};
const nm = join(root, 'node_modules', '@bryntum');

rmSync(dist, { recursive: true, force: true });
cpSync(join(root, 'public'), dist, { recursive: true });

// Only named files, never whole package directories.
const vendor = [
  ['scheduler-community/scheduler.css', 'bryntum/scheduler.css'],
  ['scheduler-community/svalbard-light.css', 'bryntum/svalbard-light.css'],
  ['scheduler-community/svalbard-dark.css', 'bryntum/svalbard-dark.css'],
  ['scheduler-community/LICENSE', 'bryntum/LICENSE.txt'],
  ['core-community/fontawesome/css/fontawesome.css', 'fontawesome/css/fontawesome.css'],
  ['core-community/fontawesome/css/solid.css', 'fontawesome/css/solid.css'],
  ['core-community/fontawesome/webfonts/fa-solid-900.woff2', 'fontawesome/webfonts/fa-solid-900.woff2'],
];
for (const [from, to] of vendor) {
  const target = join(dist, 'vendor', to);
  mkdirSync(dirname(target), { recursive: true });
  cpSync(join(nm, from), target);
}

// All scripts get a content hash in the name (name-XXXXXXXX.js), so the server can cache them as immutable;
// the HTML pages are rewritten to the hashed names below and are themselves never cached.
const hashedEntry = (result) => relative(dist, Object.entries(result.metafile.outputs).find(([, o]) => o.entryPoint)[0])
  .split('\\').join('/');

const main = await build({
  entryPoints: [join(root, 'src/main.js')],
  outdir: join(dist, 'js'),
  entryNames: '[name]-[hash]',
  metafile: true,
  bundle: true,
  splitting: true,
  format: 'esm',
  minify: true,
  sourcemap: false,
  target: 'es2022',
  chunkNames: 'chunks/[name]-[hash]',
  logLevel: 'warning',
  plugins: [pluginModule],
});
// The login page is public: one file without shared chunks, so the server only has to expose js/login-*.js.
const login = await build({
  entryPoints: [join(root, 'src/login.js')],
  outdir: join(dist, 'js'),
  entryNames: '[name]-[hash]',
  metafile: true,
  bundle: true,
  format: 'esm',
  minify: true,
  target: 'es2022',
  logLevel: 'warning',
});
// theme.js is a plain script copied from public/: give it the same kind of name (8 characters A-Z, 2-7)
const BASE32 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
const digest = createHash('sha256').update(readFileSync(join(dist, 'theme.js'))).digest();
const themeHash = Array.from(digest.subarray(0, 8), (b) => BASE32[b & 31]).join('');
const theme = `theme-${themeHash}.js`;
renameSync(join(dist, 'theme.js'), join(dist, theme));

const names = { 'theme.js': theme, 'js/main.js': hashedEntry(main), 'js/login.js': hashedEntry(login) };
for (const page of ['index.html', 'login.html']) {
  const file = join(dist, page);
  let html = readFileSync(file, 'utf8');
  for (const [plain, hashed] of Object.entries(names)) html = html.replaceAll(`src="${plain}"`, `src="${hashed}"`);
  // every script a page loads must exist, so a renamed entry cannot slip through
  for (const [, src] of html.matchAll(/<script[^>]*\ssrc="([^"]+)"/g)) {
    if (!existsSync(join(dist, src))) throw new Error(`${page} loads ${src}, which was not built`);
  }
  writeFileSync(file, html);
}

// The guide built into the program (lib/guide.js): each page of docs/anleitung/ as an HTML fragment, its images next to
// them. Links between the pages become routes of the program (#/anleitung/<page>/<anchor>) and must land on a heading;
// links to the other documents go to the documentation site (whose strict build checks them; the Docker build has
// only docs/anleitung/ and the images).
const docs = join(root, '..', 'docs');
const guideDocs = join(docs, 'anleitung');
const guideDir = dirname(join(dist, guideFile('index')));
// the plugins' help pages (plugins/<id>/docs/<page>.md, written as if they were in docs/anleitung/) after the core
// pages, as plugin-<id>-<page>; their title is their first line
const pluginImages = new Map(); // guide page -> where its plugin's images come from and go to
const pluginPages = plugins.flatMap((p) => {
  const dir = join(p.dir, 'docs');
  return existsSync(dir) ? readdirSync(dir).filter((f) => /^[a-z0-9-]+\.md$/.test(f)).sort().map((f) => {
    const file = join(dir, f);
    const title = readFileSync(file, 'utf8').split(/\r?\n/, 1)[0].replace(/^# /, '');
    pluginImages.set(`plugin-${p.id}-${f.slice(0, -3)}`, { dir, target: `plugin-${p.id}-img` });
    return [`plugin-${p.id}-${f.slice(0, -3)}`, title, file];
  }) : [];
});
const allPages = [...PAGES.map(([id, title]) => [id, title, join(guideDocs, `${id}.md`)]), ...pluginPages];
const pageIds = allPages.map(([id]) => id);
const images = new Map(); // file next to the guide -> its source
const sizes = new Map(); // width and height of each image (PNG header), so the page keeps its layout while they load
const headings = new Map(); // page -> anchors of its headings
const links = []; // [page, target page, anchor] of every link between the pages, checked once all are parsed
let current = '';
const guide = new Marked({
  walkTokens(token) {
    if (token.type === 'image') {
      // a plugin's images: plugins/<id>/docs/img/<name>.png, next to the guide as plugin-<id>-img/<name>.png; a core page
      // shows one as plugin-<id>-img/<name>.png
      const own = pluginImages.get(current);
      if (own && !/^img\/[a-z0-9-]+\.png$/.test(token.href)) throw new Error(`${current}: plugin help pages show only img/<name>.png, not ${token.href}`);
      const plugin = own ?? [...pluginImages.values()].find((p) => token.href.startsWith(`${p.target}/`));
      const name = own ? token.href.slice(4) : plugin ? token.href.slice(plugin.target.length + 1) : null;
      const file = plugin ? `${plugin.target}/${name}` : relative(docs, join(guideDocs, token.href)).split('\\').join('/');
      const source = plugin ? join(plugin.dir, 'img', name) : join(docs, file);
      const png = readFileSync(source);
      if (png.toString('latin1', 1, 4) !== 'PNG') throw new Error(`anleitung/${current}.md shows ${token.href}, which is not a PNG`);
      images.set(file, source);
      token.href = `${relative(dist, guideDir).split('\\').join('/')}/${file}`;
      sizes.set(token.href, [png.readUInt32BE(16), png.readUInt32BE(20)]);
    } else if (token.type === 'link' && !/^https?:/.test(token.href)) {
      const [file, anchor = ''] = token.href.split('#');
      const page = file ? file.replace(/\.md$/, '') : current;
      if (!file || (/^[a-z0-9-]+\.md$/.test(file) && pageIds.includes(page))) {
        links.push([current, page, anchor]);
        token.href = `#/anleitung${page === 'index' && !anchor ? '' : `/${page}`}${anchor ? `/${anchor}` : ''}`;
      } else if (/^\.\.\/[a-z-]+\.md$/.test(file)) token.href = `${SITE}${file.slice(3, -3)}/${anchor ? `#${anchor}` : ''}`;
      else throw new Error(`anleitung/${current}.md links to ${token.href}, which the built-in guide cannot show`);
    }
  },
  renderer: {
    heading({ tokens, depth, text }) {
      const id = slug(text);
      if (headings.get(current).has(id)) throw new Error(`anleitung/${current}.md has the heading anchor ${id} twice`);
      headings.get(current).add(id);
      return `<h${depth} id="${id}">${this.parser.parseInline(tokens)}</h${depth}>\n`;
    },
    image({ href, text }) {
      const [width, height] = sizes.get(href);
      return `<img src="${href}" alt="${text.replace(/[&<>"]/g, (c) => `&#${c.charCodeAt(0)};`)}" width="${width}" height="${height}">`;
    },
    link({ href, tokens }) {
      return /^https?:/.test(href) ? `<a href="${href}" target="_blank" rel="noopener">${this.parser.parseInline(tokens)}</a>` : false;
    },
  },
});
mkdirSync(guideDir, { recursive: true });
for (const [id, title, file] of allPages) {
  current = id;
  headings.set(id, new Set());
  const markdown = readFileSync(file, 'utf8');
  if (markdown.split(/\r?\n/, 1)[0] !== `# ${title}`) throw new Error(`${file} does not start with "# ${title}" (PAGES in lib/guide.js)`);
  writeFileSync(join(dist, guideFile(id)), guide.parse(markdown));
}
for (const [from, page, anchor] of links) {
  if (anchor && !headings.get(page).has(anchor)) throw new Error(`anleitung/${from}.md links to ${page}.md#${anchor}, which has no such heading`);
}
for (const [image, source] of images) {
  mkdirSync(dirname(join(guideDir, image)), { recursive: true });
  cpSync(source, join(guideDir, image));
}
console.log('built', dist, names, 'plugins', plugins.map((p) => p.id));
