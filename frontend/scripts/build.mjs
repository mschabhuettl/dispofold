// Builds frontend/dist: copies public/, bundles src/ with esbuild and copies the needed vendor files.
import { build } from 'esbuild';
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Marked } from 'marked';
import { PAGES, SITE, guideFile, slug } from '../src/lib/guide.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
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
const pageIds = PAGES.map(([id]) => id);
const images = new Set();
const sizes = new Map(); // width and height of each image (PNG header), so the page keeps its layout while they load
const headings = new Map(); // page -> anchors of its headings
const links = []; // [page, target page, anchor] of every link between the pages, checked once all are parsed
let current = '';
const guide = new Marked({
  walkTokens(token) {
    if (token.type === 'image') {
      const file = relative(docs, join(guideDocs, token.href)).split('\\').join('/');
      const png = readFileSync(join(docs, file));
      if (png.toString('latin1', 1, 4) !== 'PNG') throw new Error(`anleitung/${current}.md shows ${token.href}, which is not a PNG`);
      images.add(file);
      token.href = `${relative(dist, guideDir).split('\\').join('/')}/${file}`;
      sizes.set(token.href, [png.readUInt32BE(16), png.readUInt32BE(20)]);
    } else if (token.type === 'link' && !/^https?:/.test(token.href)) {
      const [file, anchor = ''] = token.href.split('#');
      const page = file ? file.replace(/\.md$/, '') : current;
      if (!file || (/^[a-z-]+\.md$/.test(file) && pageIds.includes(page))) {
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
for (const [id, title] of PAGES) {
  current = id;
  headings.set(id, new Set());
  const markdown = readFileSync(join(guideDocs, `${id}.md`), 'utf8');
  if (markdown.split(/\r?\n/, 1)[0] !== `# ${title}`) throw new Error(`anleitung/${id}.md does not start with "# ${title}" (PAGES in lib/guide.js)`);
  writeFileSync(join(dist, guideFile(id)), guide.parse(markdown));
}
for (const [from, page, anchor] of links) {
  if (anchor && !headings.get(page).has(anchor)) throw new Error(`anleitung/${from}.md links to ${page}.md#${anchor}, which has no such heading`);
}
for (const image of images) {
  mkdirSync(dirname(join(guideDir, image)), { recursive: true });
  cpSync(join(docs, image), join(guideDir, image));
}
console.log('built', dist, names);
