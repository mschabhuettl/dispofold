import { test } from 'node:test';
import assert from 'node:assert/strict';
import { areasWithoutDemand, chipCount, closeChecklist, planHeadline, problemConstraints, scoreWords } from '../src/lib/plan-status.js';

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
  // a fairness rule is one comparison of everybody: no count
  assert.equal(chipCount(c('fairNights', 'SOFT', -150, 1)), null);
  assert.equal(chipCount(c('wishes', 'SOFT', -500, 5)), 5);
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

test('score in words: violations counted, staff in person hours, the quality deductions without sign', () => {
  assert.equal(scoreWords({ score: '0hard/-5430medium/-6185375soft', hard: 0, uncovered: 0, overstaffed: 90.5 }),
    `Regelverstöße 0, fehlende Besetzung 0 Personenstunden, Überbesetzung 90,5 Personenstunden, Qualität: Abzüge ${(6185375).toLocaleString('de-AT')}`);
  assert.equal(scoreWords({ score: '-120hard/-2000000060medium/-5soft', hard: 2, uncovered: 2 / 60, overstaffed: 1 }),
    'Regelverstöße 2, fehlende Besetzung 0 Personenstunden, Überbesetzung 1 Personenstunden, Qualität: Abzüge 5');
  assert.equal(scoreWords({ score: null }), '–');
});

test('headline: never green for an empty plan or where no area asks for anyone', () => {
  // no minimum demand in any area: nobody was planned
  assert.deepEqual(planHeadline({ hard: 0, gaps: 0, uncoveredHours: 0, duties: 0, areas: 4, areasWithoutDemand: 4 }),
    { level: 'warn', text: 'Kein Bereich hat Mindestbedarf: es wurde niemand eingeteilt.' });
  // some areas without demand: named, not green
  assert.deepEqual(planHeadline({ hard: 0, gaps: 0, uncoveredHours: 0, duties: 120, areas: 4, areasWithoutDemand: 1 }),
    { level: 'warn', text: '1 Bereich ohne Mindestbedarf: dort wird niemand eingeteilt' });
  assert.equal(planHeadline({ hard: 0, gaps: 0, duties: 0, areas: 4, areasWithoutDemand: 0 }).level, 'warn');
  // normal: green
  assert.equal(planHeadline({ hard: 0, gaps: 0, uncoveredHours: 0, duties: 120, areas: 4, areasWithoutDemand: 0 }).level, 'good');
  assert.equal(areasWithoutDemand({ areas: [{ id: 'A' }, { id: 'B' }], demand: [{ areaId: 'A', min: 1 }, { areaId: 'B', min: 0 }] }), 1);
});
