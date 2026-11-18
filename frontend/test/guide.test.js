import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { GUIDE_URL, SECTIONS, guideHref, slug } from '../src/lib/guide.js';

const docs = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'docs');
const anchors = new Set([...readFileSync(join(docs, 'anleitung.md'), 'utf8').matchAll(/^#+ (.+)$/gm)].map((m) => slug(m[1])));

test('anchors as on GitHub and the site', () => {
  assert.equal(slug('Von Hand ändern und fixieren'), 'von-hand-ändern-und-fixieren');
  assert.equal(slug('Hinweis „Plan veraltet: neu planen“'), 'hinweis-plan-veraltet-neu-planen');
  assert.equal(slug('Neuer Datensatz, Änderungen aus Dispofold behalten'), 'neuer-datensatz-änderungen-aus-dispofold-behalten');
});

test('every page links to a section the guide has', () => {
  assert.deepEqual(Object.values(SECTIONS).filter((a) => !anchors.has(a)), []);
});

test('the guide links point into the program or to the site', () => {
  assert.equal(guideHref(true), '#/anleitung');
  assert.equal(guideHref(true, 'daten'), '#/anleitung/daten');
  assert.equal(guideHref(false), GUIDE_URL);
  assert.equal(guideHref(false, 'daten'), `${GUIDE_URL}#daten`);
});
