import { test } from 'node:test';
import assert from 'node:assert/strict';
import { groupHitsByDate } from '../src/lib/explain.js';

test('hits are grouped by date with month-level hits first', () => {
  const groups = groupHitsByDate([
    { constraintId: 'wish', level: 'SOFT', date: '2026-12-03', score: 50, text: 'Wunsch erfüllt' },
    { constraintId: 'rest', level: 'HARD', date: '2026-12-03', score: -1, text: 'Ruhezeit' },
    { constraintId: 'hours', level: 'SOFT', date: null, score: -400, text: 'Sollstunden' },
    { constraintId: 'block', level: 'SOFT', date: '2026-12-01', score: -20, text: 'Block' },
  ]);
  assert.deepEqual(groups.map((g) => g.date), [null, '2026-12-01', '2026-12-03']);
  assert.deepEqual(groups[2].hits.map((h) => h.constraintId), ['rest', 'wish']);
  assert.equal(groups[2].total, 49);
});
