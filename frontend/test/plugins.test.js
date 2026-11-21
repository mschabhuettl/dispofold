import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { TEXTS, t } from '../src/lib/texts.js';
import { helpPages, register, routes } from '../src/lib/plugins.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const base = join(root, '..', 'plugins');
const ids = readdirSync(base).filter((id) => existsSync(join(base, id, 'plugin.json'))).sort();
const front = (id) => import(pathToFileURL(join(base, id, 'frontend', 'index.js')).href).then((m) => m.default);
const sources = (dir) => (existsSync(dir) ? readdirSync(dir, { recursive: true }).map(String).filter((f) => /\.m?js$/.test(f))
  .map((f) => [f, readFileSync(join(dir, f), 'utf8')]) : []);

test('every plugin front end matches its plugin.json and registers without touching other texts', async () => {
  assert.ok(ids.includes('beispiel'));
  const texts = { ...TEXTS };
  for (const id of ids) {
    const manifest = JSON.parse(readFileSync(join(base, id, 'plugin.json'), 'utf8'));
    assert.equal(manifest.id, id);
    if (!existsSync(join(base, id, 'frontend', 'index.js'))) continue;
    const p = await front(id);
    assert.equal(p.id, id);
    register(p, texts);
    // every text key it uses exists, its help pages are its docs with their titles
    for (const page of p.pages ?? []) for (const key of [page.tab, page.intro]) assert.ok(Object.hasOwn(texts, key), key);
    if (p.importSource) assert.ok(Object.hasOwn(texts, p.importSource.label));
    const docs = existsSync(join(base, id, 'docs')) ? readdirSync(join(base, id, 'docs')).filter((f) => f.endsWith('.md')).sort() : [];
    assert.deepEqual((p.help ?? []).map((x) => `${x.page}.md`).sort(), docs);
    for (const x of p.help ?? []) {
      assert.equal(readFileSync(join(base, id, 'docs', `${x.page}.md`), 'utf8').split('\n', 1)[0], `# ${x.title}`);
    }
  }
});

test('a plugin front end uses only lib/plugin-api.js and its own files, the core never a plugin', () => {
  for (const id of ids) {
    for (const [file, src] of sources(join(base, id, 'frontend'))) {
      for (const [, spec] of src.matchAll(/\bfrom\s+['"]([^'"]+)['"]|\bimport\(\s*['"]([^'"]+)['"]/g)) {
        assert.ok(spec === '../../../frontend/src/lib/plugin-api.js' || spec.startsWith('./'), `plugins/${id}/frontend/${file} imports ${spec}`);
      }
    }
  }
  for (const [file, src] of sources(join(root, 'src'))) {
    assert.doesNotMatch(src, /plugins\/[a-z0-9]+\//, `src/${file} uses a plugin`);
  }
});

test('register refuses texts outside the plugin\'s own keys and replacing a core text', () => {
  const texts = { 'nav.plan': 'Plan' };
  assert.throws(() => register({ id: 'x1', texts: { 'nav.plan': 'Weg' } }, texts), /must start with/);
  assert.throws(() => register({ id: 'x1', texts: { 'plugin.x2.a': 'b' } }, texts), /must start with/);
  register({ id: 'x1', texts: { 'plugin.x1.a': 'b' } }, texts);
  assert.throws(() => register({ id: 'x1', texts: { 'plugin.x1.a': 'c' } }, texts), /exists already/);
  assert.throws(() => register({ id: 'X', texts: {} }, texts), /lower-case/);
  assert.throws(() => register({ id: 'x3', pages: [{ path: 'a' }] }, texts), /no render/);
});

test('only switched on plugins get routes and help pages, in their group, for readers only when marked', () => {
  const texts = { ...TEXTS };
  const render = () => {};
  const own = register({ id: 'eigen', texts: { 'plugin.eigen.g': 'Eigen', 'plugin.eigen.a': 'A', 'plugin.eigen.b': 'B' },
    group: { label: 'plugin.eigen.g' }, pages: [{ path: 'a', tab: 'plugin.eigen.a', viewer: true, help: 'hilfe#teil', render },
      { path: 'b', tab: 'plugin.eigen.b', render }], help: [{ page: 'hilfe', title: 'Hilfe' }] }, texts);
  const core = register({ id: 'kern', texts: { 'plugin.kern.a': 'K' }, group: 'settings', pages: [{ path: 'a', tab: 'plugin.kern.a', render }] }, texts);
  const tt = (k) => texts[k];
  const groups = { settings: 'Einstellungen' };
  assert.deepEqual(routes([own, core], new Set(), groups, tt), []);
  const r = routes([own, core], new Set(['eigen', 'kern']), groups, tt);
  assert.deepEqual(r.map((x) => [x.path, x.group, x.viewer, x.section]), [
    ['plugin/eigen/a', 'plugin-eigen', 'plugin-eigen', 'plugin-eigen-hilfe#teil'],
    ['plugin/eigen/b', 'plugin-eigen', undefined, null],
    ['plugin/kern/a', 'settings', undefined, null]]);
  assert.equal(groups['plugin-eigen'], 'Eigen');
  assert.throws(() => routes([{ id: 'zz', group: 'nirgends', pages: [] }], new Set(['zz']), groups, tt), /unknown menu group/);
  assert.deepEqual(helpPages([own, core], new Set(['eigen'])), [['plugin-eigen-hilfe', 'Hilfe']]);
  assert.deepEqual(helpPages([own, core], new Set()), []);
  assert.ok(t('extensions.none'));
});
