import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { contrast, textOn, tint, tintStyle } from '../src/lib/colors.js';

test('text colour picks the higher contrast', () => {
  assert.equal(textOn('#F3E3D8'), '#000000');
  assert.equal(textOn('#34478C'), '#FFFFFF');
  assert.equal(textOn('#16303D'), '#FFFFFF');
  assert.equal(textOn('#EBBD00'), '#000000');
  assert.equal(textOn(null), '#000000');
});

test('dark mode dims light tints', () => {
  const light = tint('#F3E3D8', false);
  const dark = tint('#F3E3D8', true);
  assert.equal(light.bg, '#F3E3D8');
  assert.ok(contrast(dark.bg, '#000000') < contrast(light.bg, '#000000'));
  assert.equal(tint('nonsense', true), null);
  assert.match(tintStyle('#2563EB'), /^--c-bg:#2563EB;--c-fg:#FFFFFF;--c-bg-d:#[0-9A-F]{6};--c-fg-d:#[0-9A-F]{6}$/);
});

/** Every colour in the demo data, the synthetic fixtures and the mock server. */
function dataColours() {
  const root = join(import.meta.dirname, '..', '..');
  const files = [join(root, 'frontend', 'dev', 'mock-data.mjs')];
  const fixtures = join(root, 'tests', 'fixtures', 'synthetic');
  // the fixtures live outside frontend/ and are missing when only the frontend is checked out (Docker build context)
  if (existsSync(fixtures)) for (const f of readdirSync(fixtures)) if (f.endsWith('.json')) files.push(join(fixtures, f));
  const colours = new Set();
  for (const f of files) for (const m of readFileSync(f, 'utf8').matchAll(/#[0-9A-Fa-f]{6}\b/g)) colours.add(m[0]);
  return [...colours];
}

test('every data colour is readable (WCAG AA 4.5:1) in both themes', () => {
  const colours = dataColours();
  assert.ok(colours.length > 10);
  for (const hex of colours) {
    for (const dark of [false, true]) {
      const { bg, fg } = tint(hex, dark);
      assert.ok(contrast(bg, fg) >= 4.5, `${hex} ${dark ? 'dark' : 'light'}: ${contrast(bg, fg).toFixed(2)}`);
    }
  }
});
