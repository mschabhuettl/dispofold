import { test } from 'node:test';
import assert from 'node:assert/strict';
import { hitText } from '../src/lib/codes.js';

test('hit texts name the area when the names are given', () => {
  const hit = { code: 'overstaffing', params: { areaId: 'X', from: '2030-03-01T08:00', to: '2030-03-01T10:00', planned: 3, upper: 2 } };
  assert.match(hitText(hit), /^Bereich X,/);
  assert.match(hitText(hit, new Map([['X', 'Nord']])), /^Bereich Nord,/);
  assert.match(hitText(hit, new Map([['Y', 'Süd']])), /^Bereich X,/);
});
