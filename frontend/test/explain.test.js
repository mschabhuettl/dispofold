import { test } from 'node:test';
import assert from 'node:assert/strict';
import { groupHitsByDate, hitEffect, hitSummary } from '../src/lib/explain.js';

test('hits are grouped by date with month-level hits first', () => {
  const groups = groupHitsByDate([
    { constraintId: 'wish', level: 'SOFT', date: '2026-12-03', score: 50, text: 'Wunsch erfüllt' },
    { constraintId: 'rest', level: 'HARD', date: '2026-12-03', score: -1, text: 'Ruhezeit' },
    { constraintId: 'hours', level: 'SOFT', date: null, score: -400, text: 'Sollstunden' },
    { constraintId: 'block', level: 'SOFT', date: '2026-12-01', score: -20, text: 'Block' },
  ]);
  assert.deepEqual(groups.map((g) => g.date), [null, '2026-12-01', '2026-12-03']);
  assert.deepEqual(groups[2].hits.map((h) => h.constraintId), ['rest', 'wish']);
});

test('the hits of a day are summed up in words and counts, without scores', () => {
  assert.deepEqual(hitSummary([]), ['verletzt keine Regel']);
  assert.deepEqual(hitSummary([{ constraintId: 'averageWeekHours', level: 'HARD', score: 0 }]), ['verletzt keine Regel']);
  assert.deepEqual(hitSummary([
    { constraintId: 'demand', level: 'MEDIUM', score: 0 },
    { constraintId: 'wishOff', level: 'SOFT', score: 0 },
    { constraintId: 'wishShift', level: 'SOFT', score: 0 },
  ]), ['verletzt keine Regel', 'deckt Bedarf', '2 Wünsche erfüllt']);
  assert.deepEqual(hitSummary([
    { constraintId: 'dailyRest', level: 'HARD', score: -60 },
    { constraintId: 'overstaffing', level: 'MEDIUM', score: -30 },
    { constraintId: 'wishes', level: 'SOFT', score: -120, params: { unmet: 1, wishes: 3 } },
    { constraintId: 'blockLength', level: 'SOFT', score: -15 },
    { constraintId: 'fairNights', level: 'SOFT', score: -4 },
    { constraintId: 'compRestUse', level: 'SOFT', score: 5 },
  ]), ['1 Regelverstoß', '1 Abweichung vom Bedarf', '2 Wünsche erfüllt', '1 Wunsch nicht erfüllt', '2 Nachteile', '1 Vorteil']);
});

test('the effect of a hit is a word, not its score', () => {
  assert.equal(hitEffect({ level: 'HARD', score: -60 }), 'Verstoß');
  assert.equal(hitEffect({ level: 'SOFT', score: -1500 }), 'Nachteil');
  assert.equal(hitEffect({ level: 'SOFT', score: 5 }), 'Vorteil');
  assert.equal(hitEffect({ level: 'MEDIUM', score: 0 }), '');
});
