// Builds frontend/dist: copies public/, bundles src/ with esbuild and copies the needed vendor files.
import { build } from 'esbuild';
import { createHash } from 'node:crypto';
import { cpSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

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
console.log('built', dist, names);
