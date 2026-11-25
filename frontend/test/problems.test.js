import { test } from 'node:test';
import assert from 'node:assert/strict';
import { filterProblems, groupProblems, problemEntries } from '../src/lib/problems.js';

const people = [{ id: 'P1', firstName: 'Anna', lastName: 'Berg' }, { id: 'P2', firstName: 'Bert', lastName: 'Adler' }];
const hit = (personId, constraintId, level, date, score = -1) => ({ personId, constraintId, level, date, score, code: `hit.${constraintId}`, params: {} });
const problems = [
  hit('P1', 'dailyRest', 'HARD', '2030-03-04'),
  hit('P2', 'dailyRest', 'HARD', '2030-03-02'),
  hit(null, 'overstaffing', 'MEDIUM', '2030-03-03'),
  hit('P2', 'uselessDuty', 'MEDIUM', '2030-03-03'),
  hit('P1', 'blockLength', 'SOFT', '2030-03-05', -6000),
  hit('P2', 'wishes', 'SOFT', null, -300), // a soft rule with a small share: left out
  hit('GONE', 'dailyRest', 'HARD', '2030-03-01'), // not in the dataset
];
const constraints = [
  { id: 'dailyRest', level: 'HARD', score: -2, matches: 2 },
  { id: 'overstaffing', level: 'MEDIUM', score: -60, matches: 1 },
  { id: 'blockLength', level: 'SOFT', score: -6000, matches: 1 },
  { id: 'wishes', level: 'SOFT', score: -300, matches: 1 },
];

test('every hard and medium penalty and the soft rules the Dienstplan points at', () => {
  const entries = problemEntries(problems, constraints, people);
  assert.deepEqual(entries.map((e) => [e.hit.constraintId, e.hit.personId]), [
    ['dailyRest', 'P2'], ['dailyRest', 'P1'], ['overstaffing', null], ['uselessDuty', 'P2'], ['blockLength', 'P1']]);
  assert.equal(entries[2].person, null);
});

test('grouped by rule (level first), by person (no person last) and by day (whole month first)', () => {
  const entries = problemEntries([...problems, hit('P1', 'blockLength', 'SOFT', null, -6000)], constraints, people);
  assert.deepEqual(groupProblems(entries, 'rule').map((g) => [g.key, g.entries.length]),
    [['dailyRest', 2], ['overstaffing', 1], ['uselessDuty', 1], ['blockLength', 2]]);
  assert.deepEqual(groupProblems(entries, 'person').map((g) => g.key), ['P2', 'P1', '']);
  assert.deepEqual(groupProblems(entries, 'day').map((g) => g.key), ['', '2030-03-02', '2030-03-03', '2030-03-04', '2030-03-05']);
});

test('filters by rule, person and day combine', () => {
  const entries = problemEntries(problems, constraints, people);
  assert.equal(filterProblems(entries, {}).length, 5);
  assert.deepEqual(filterProblems(entries, { rule: 'dailyRest' }).map((e) => e.hit.personId), ['P2', 'P1']);
  assert.deepEqual(filterProblems(entries, { person: 'P2' }).map((e) => e.hit.constraintId), ['dailyRest', 'uselessDuty']);
  assert.deepEqual(filterProblems(entries, { person: '' }).map((e) => e.hit.constraintId), ['overstaffing']);
  assert.deepEqual(filterProblems(entries, { day: '2030-03-03', person: 'P2' }).map((e) => e.hit.constraintId), ['uselessDuty']);
});

test('staffing above the minimum belongs to no person: no chip, but always in the list', () => {
  const constraints = [{ id: 'overMinimum', level: 'SOFT', score: -900000, matches: 3 },
    { id: 'blockLength', level: 'SOFT', score: -1000, matches: 1 }];
  const entries = problemEntries([hit(null, 'overMinimum', 'SOFT', '2030-03-06', -4000)], constraints, people);
  assert.equal(entries.length, 1);
  assert.equal(entries[0].person, null);
});
