import { test } from 'node:test';
import assert from 'node:assert/strict';
import { closeChecklist, planHeadline, problemConstraints } from '../src/lib/plan-status.js';

test('headline: usable plan, gaps, rule violations, both', () => {
  assert.deepEqual(planHeadline({ hard: 0, gaps: 0, uncoveredHours: 0 }),
    { level: 'good', text: 'Plan verwendbar: keine Regelverstöße, keine Lücken' });
  assert.deepEqual(planHeadline({ hard: 0, gaps: 3, uncoveredHours: 16 }), { level: 'warn', text: '3 Lücken (16 Personenstunden)' });
  assert.deepEqual(planHeadline({ hard: 2, gaps: 0, uncoveredHours: 0 }), { level: 'bad', text: '2 Regelverstöße' });
  assert.deepEqual(planHeadline({ hard: 1, gaps: 1, uncoveredHours: 7.5 }), { level: 'bad', text: '1 Regelverstoß, 1 Lücke (7,5 Personenstunden)' });
});

test('headline: never "usable" without a plan, while solving or without an analysis', () => {
  assert.equal(planHeadline({ planned: false, gaps: null }).level, 'none');
  assert.equal(planHeadline({ gaps: null }).level, 'none');
  assert.deepEqual(planHeadline({ solving: true, seconds: 12, hard: 3, uncoveredHours: 4 }),
    { level: 'solving', text: 'Planung läuft (12 s), 3 Regelverstöße, 4 Personenstunden ungedeckt' });
  assert.deepEqual(planHeadline({ gaps: null, uncoveredHours: 2 }), { level: 'warn', text: '2 Personenstunden ungedeckt' });
});

test('chips: violated hard rules, then soft rules with a high share of the soft penalty', () => {
  const c = (id, level, score, matches) => ({ id, level, score, matches });
  const list = problemConstraints([
    c('dailyRest', 'HARD', -2, 2), c('qualification', 'HARD', 0, 0), c('absence', 'HARD', -5, 5),
    c('demand', 'MEDIUM', -9000, 4),
    c('wishes', 'SOFT', -500, 5), c('blockLength', 'SOFT', -300, 30), c('fairNights', 'SOFT', -150, 3),
    c('forwardRotation', 'SOFT', -50, 50), c('shiftVariety', 'SOFT', 0, 0),
  ]);
  // soft total -1000: fairNights 15 % and up are in, forwardRotation (5 %) is not
  assert.deepEqual(list.map((x) => x.id), ['absence', 'dailyRest', 'wishes', 'blockLength', 'fairNights']);
  assert.deepEqual(problemConstraints(undefined), []);
});

test('close checklist: violations, gaps, plan out of date; no analysis counts as open', () => {
  const ok = closeChecklist({ hardViolations: 0 }, { hardViolations: 0, gaps: [], uncoveredHours: 0 });
  assert.deepEqual(ok.map((i) => i.ok), [true, true, true]);
  assert.deepEqual(ok.map((i) => i.text), ['Keine Regelverstöße', 'Keine offenen Lücken', 'Plan aktuell']);
  const open = closeChecklist({ hardViolations: 0, stale: true }, { hardViolations: 2, gaps: [{}, {}, {}], uncoveredHours: 16 });
  assert.deepEqual(open.map((i) => i.ok), [false, false, false]);
  assert.deepEqual(open.map((i) => i.text), ['2 Regelverstöße', '3 Lücken (16 Personenstunden)', 'Plan veraltet: neu planen']);
  assert.deepEqual(closeChecklist({ hardViolations: 0, previousMonthChanged: true }, null).map((i) => i.ok), [true, false, false]);
});
