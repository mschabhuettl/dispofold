import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { DARK_SURFACE, contrast } from '../src/lib/colors.js';

const css = readFileSync(join(import.meta.dirname, '..', 'public', 'css', 'app.css'), 'utf8');

function tokens(selector) {
  const start = css.indexOf(`${selector} {`);
  assert.ok(start >= 0, selector);
  const block = css.slice(start, css.indexOf('}', start));
  return Object.fromEntries([...block.matchAll(/(--[\w-]+):\s*(#[0-9A-Fa-f]{6})\s*;/g)].map((m) => [m[1], m[2]]));
}

const light = tokens(':root');
const dark = { ...light, ...tokens(':root[data-theme="dark"]') };

// [foreground, background, minimum ratio]: text 4.5:1, focus ring and chart lines 3:1 (WCAG AA)
const PAIRS = [
  ['--text', '--bg', 4.5], ['--text', '--surface', 4.5], ['--text', '--surface-2', 4.5],
  ['--text-muted', '--surface', 4.5], ['--text-muted', '--surface-2', 4.5], ['--text-muted', '--bg', 4.5],
  ['--primary', '--surface', 4.5], ['--primary', '--primary-tint', 4.5], ['--on-primary', '--primary', 4.5],
  ['--on-primary', '--primary-hover', 4.5], ['--danger', '--surface', 4.5], ['--danger', '--danger-tint', 4.5],
  ['--text', '--danger-tint', 4.5], ['--success', '--surface', 4.5], ['--info', '--surface', 4.5],
  ['--accent', '--surface', 4.5], ['--text', '--weekend', 4.5], ['--text', '--holiday', 4.5],
  ['--text-muted', '--weekend', 4.5], ['--text-muted', '--holiday', 4.5], ['--holiday-text', '--holiday', 4.5],
  ['--weekend-mark', '--surface', 3], ['--holiday-mark', '--surface', 3], ['--weekend-mark', '--weekend', 3],
  ['--holiday-mark', '--holiday', 3],
  ['--focus', '--surface', 3], ['--focus', '--bg', 3], ['--border-strong', '--surface', 3],
  ['--chart-planned', '--surface', 3], ['--chart-demand', '--surface', 3], ['--chart-max', '--surface', 3],
  ['--chart-gap', '--surface', 3], ['--chart-mean', '--surface', 3], ['--chart-dot', '--surface', 3],
];

for (const [name, t] of [['light', light], ['dark', dark]]) {
  test(`${name} theme meets WCAG AA contrast`, () => {
    for (const [fg, bg, min] of PAIRS) {
      assert.ok(t[fg] && t[bg], `${fg} or ${bg} missing`);
      const ratio = contrast(t[fg], t[bg]);
      assert.ok(ratio >= min, `${name}: ${fg} on ${bg} is ${ratio.toFixed(2)}, needs ${min}`);
    }
  });
}

// Weekend and holiday columns must stand out from normal days (was 1.09:1 dark, 1.16:1 light).
for (const [name, tk] of [['light', light], ['dark', dark]]) {
  test(`${name} theme: weekend and holiday columns are clearly visible`, () => {
    for (const col of ['--weekend', '--holiday']) {
      const ratio = contrast(tk[col], tk['--surface']);
      assert.ok(ratio >= 1.25, `${name}: ${col} against --surface is ${ratio.toFixed(2)}, needs 1.25`);
    }
    assert.notEqual(tk['--weekend'], tk['--holiday']);
  });
}

test('data colours are dimmed towards the dark surface', () => {
  assert.equal(dark['--surface'], DARK_SURFACE);
});
