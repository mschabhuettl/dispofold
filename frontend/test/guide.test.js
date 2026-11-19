import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { GUIDE_URL, PAGES, SECTIONS, guideHref, slug } from '../src/lib/guide.js';

const repo = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const guideDocs = join(repo, 'docs', 'anleitung');
const markdown = (page) => readFileSync(join(guideDocs, `${page}.md`), 'utf8');
const anchors = (page) => new Set([...markdown(page).matchAll(/^#+ (.+)$/gm)].map((m) => slug(m[1])));

test('anchors as on GitHub and the site', () => {
  assert.equal(slug('Von Hand ändern und fixieren'), 'von-hand-ändern-und-fixieren');
  assert.equal(slug('Hinweis „Plan veraltet: neu planen“'), 'hinweis-plan-veraltet-neu-planen');
  assert.equal(slug('Neuer Datensatz, Änderungen aus Dispofold behalten'), 'neuer-datensatz-änderungen-aus-dispofold-behalten');
});

test('the pages: every file of docs/anleitung, with its title, in the order of the site navigation', () => {
  const files = readdirSync(guideDocs).filter((f) => f.endsWith('.md')).map((f) => f.slice(0, -3)).sort();
  assert.deepEqual(PAGES.map(([id]) => id).sort(), files);
  for (const [id, title] of PAGES) assert.equal(markdown(id).split(/\r?\n/, 1)[0], `# ${title}`, id);
  const nav = [...readFileSync(join(repo, 'mkdocs.yml'), 'utf8').matchAll(/^\s+- [^:]+: anleitung\/([a-z-]+)\.md$/gm)].map((m) => m[1]);
  assert.deepEqual(nav, PAGES.map(([id]) => id));
});

test('every page of the program links to a page and a heading the guide has', () => {
  for (const [path, section] of Object.entries(SECTIONS)) {
    const [page, anchor] = section.split('#');
    assert.ok(PAGES.some(([id]) => id === page), `${path}: page ${page}`);
    if (anchor) assert.ok(anchors(page).has(anchor), `${path}: ${page}#${anchor}`);
  }
});

test('the guide links point into the program or to the site', () => {
  assert.equal(guideHref(true), '#/anleitung');
  assert.equal(guideHref(true, 'daten'), '#/anleitung/daten');
  assert.equal(guideHref(true, 'planen#probleme'), '#/anleitung/planen/probleme');
  assert.equal(guideHref(true, 'index#begriffe'), '#/anleitung/index/begriffe');
  assert.equal(guideHref(false), GUIDE_URL);
  assert.equal(guideHref(false, 'daten'), `${GUIDE_URL}daten/`);
  assert.equal(guideHref(false, 'planen#probleme'), `${GUIDE_URL}planen/#probleme`);
});
