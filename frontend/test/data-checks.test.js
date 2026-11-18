import { test } from 'node:test';
import assert from 'node:assert/strict';
import { dataChecks, neverNightShifts, plannableShifts } from '../src/lib/data-checks.js';

const month = '2030-03';
const person = (id, areaIds, extra = {}) => ({ id, firstName: id, lastName: 'Test', areaIds, familyRules: [], shiftMix: null, ...extra });
const base = () => ({
  areas: [{ id: 'X', name: 'Bereich X' }, { id: 'Y', name: 'Bereich Y' }, { id: 'Z', name: 'Bereich Z' }],
  shifts: [
    { id: 'X-D', areaId: 'X', code: 'X1', category: 'DAY' },
    { id: 'X-N', areaId: 'X', code: 'X2', category: 'NIGHT' },
    { id: 'Y-D', areaId: 'Y', code: 'Y1', category: 'DAY' },
  ],
  demand: [{ areaId: 'X', min: 1 }, { areaId: 'Y', min: 1 }, { areaId: 'Z', min: 0 }],
  people: [person('OK', ['X'])],
  restrictions: [],
});
const kinds = (findings, id) => findings.filter((f) => f.personId === id).map((f) => f.kind);

test('a clean dataset has no finding but the area without demand', () => {
  const findings = dataChecks(base(), { month });
  assert.deepEqual(findings.map((f) => [f.kind, f.areaId]), [['areaNoDemand', 'Z']]);
  assert.match(findings[0].text, /Bereich Z/);
  assert.ok(findings[0].todo.length > 0);
  assert.equal(findings[0].link, 'masterdata');
});

test('people without areas, without shifts in their areas, or with every shift excluded', () => {
  const d = base();
  d.people.push(person('NONE', []), person('EMPTY', ['Z']), person('NEVER', ['X']), person('PART', ['X']), person('WEEK', ['Y']));
  d.restrictions.push(
    { personId: 'NEVER', shiftId: 'X-D', weekday: 0, kind: 'NEVER' },
    { personId: 'NEVER', category: 'NIGHT', weekday: 0, kind: 'NEVER' },
    // only from the middle of the month: the shift is plannable before
    { personId: 'PART', shiftId: 'X-D', weekday: 0, kind: 'NEVER', validFrom: '2030-03-15' },
    { personId: 'PART', shiftId: 'X-N', weekday: 0, kind: 'NEVER' },
    // all seven weekdays one by one count as every day; on request is no exclusion
    ...[1, 2, 3, 4, 5, 6, 7].map((weekday) => ({ personId: 'WEEK', shiftId: 'Y-D', weekday, kind: 'NEVER' })),
    { personId: 'OK', shiftId: 'X-D', weekday: 0, kind: 'ON_REQUEST' });
  const findings = dataChecks(d, { month });
  assert.deepEqual(kinds(findings, 'NONE'), ['noArea']);
  assert.deepEqual(kinds(findings, 'EMPTY'), ['noShift']);
  assert.deepEqual(kinds(findings, 'NEVER'), ['allNever']);
  assert.deepEqual(kinds(findings, 'PART'), []);
  assert.deepEqual(kinds(findings, 'WEEK'), ['allNever']);
  assert.deepEqual(kinds(findings, 'OK'), []);
  assert.deepEqual(plannableShifts(d, d.people.find((p) => p.id === 'PART'), month).map((s) => s.id), ['X-D']);
});

test('people not employed in the month are not checked', () => {
  const d = base();
  d.people.push(person('GONE', [], { exitDate: '2030-02-28' }), person('LATER', [], { entryDate: '2030-04-01' }),
    person('EXCLUDED', [], { excludedFrom: '2030-03-01' }));
  assert.deepEqual(dataChecks(d, { month }).filter((f) => f.personId), []);
  // excluded only from the next month on: checked in this one
  d.people.push(person('NEXT', [], { excludedFrom: '2030-04-01' }));
  assert.deepEqual(dataChecks(d, { month }).filter((f) => f.personId).map((f) => f.personId), ['NEXT']);
});

test('usual distributions that do not fit', () => {
  const d = base();
  d.people.push(
    person('SUM', ['X'], { shiftMix: { categories: { DAY: 0.5, NIGHT: 0.3 }, areas: {} } }),
    person('AREA', ['X'], { shiftMix: { categories: {}, areas: { X: 0.5, Y: 0.5 } } }),
    person('CAT', ['Y'], { shiftMix: { categories: { DAY: 0.8, LATE: 0.2 }, areas: {} } }),
    person('FINE', ['X'], { shiftMix: { categories: { DAY: 0.9, NIGHT: 0.1 }, areas: { X: 1 } } }));
  const findings = dataChecks(d, { month });
  assert.deepEqual(kinds(findings, 'SUM'), ['mixSum']);
  assert.match(findings.find((f) => f.personId === 'SUM').text, /80 % statt 100 %/);
  assert.deepEqual(kinds(findings, 'AREA'), ['mixArea']);
  assert.match(findings.find((f) => f.personId === 'AREA').text, /Bereich Y/);
  assert.deepEqual(kinds(findings, 'CAT'), ['mixCategory']);
  assert.deepEqual(kinds(findings, 'FINE'), []);
});

test('the night note: in HABIT shares without nights mean no nights, in EVEN a night share does not count', () => {
  const d = base();
  d.people.push(
    person('DAYS', ['X'], { shiftMix: { categories: { DAY: 1 }, areas: {} } }),
    person('NIGHTS', ['X'], { shiftMix: { categories: { DAY: 0.8, NIGHT: 0.2 }, areas: {} } }),
    // never nights anyway: no note
    person('FAMILY', ['X'], { shiftMix: { categories: { DAY: 1 }, areas: {} },
      familyRules: [{ weekdays: [1, 2, 3, 4, 5, 6, 7], noNights: true }] }),
    // no night shift in the area: no note
    person('DAYAREA', ['Y'], { shiftMix: { categories: { DAY: 1 }, areas: {} } }),
    // never nights with a night share: the share does not count in either mode
    person('NEVERFAM', ['X'], { shiftMix: { categories: { DAY: 0.9, NIGHT: 0.1 }, areas: {} },
      familyRules: [{ weekdays: [1, 2, 3, 4, 5, 6, 7], noNights: true }] }));
  const habit = dataChecks(d, { month, rules: { nightDistribution: 'HABIT' } });
  assert.deepEqual(kinds(habit, 'DAYS'), ['noNights']);
  assert.equal(habit.find((f) => f.personId === 'DAYS').severity, 'info');
  assert.deepEqual(kinds(habit, 'NIGHTS'), []);
  assert.deepEqual(kinds(habit, 'FAMILY'), []);
  assert.deepEqual(kinds(habit, 'DAYAREA'), []);
  assert.deepEqual(kinds(habit, 'NEVERFAM'), ['nightNever']);
  const even = dataChecks(d, { month, rules: { nightDistribution: 'EVEN' } });
  assert.deepEqual(kinds(even, 'DAYS'), []);
  assert.deepEqual(kinds(even, 'NIGHTS'), ['nightIgnored']);
  assert.equal(even.find((f) => f.personId === 'NIGHTS').link, 'rules');
  assert.deepEqual(kinds(even, 'NEVERFAM'), ['nightNever']);
});

test('shifts without a category count with the category derived from their times', () => {
  const rules = { nightStart: '22:00', nightEnd: '06:00', nightMinHours: 2, nightDistribution: 'HABIT' };
  const d = base();
  d.shifts = [{ id: 'X-D', areaId: 'X', code: 'X1', category: null, start: '08:00', end: '16:00' },
    { id: 'X-N', areaId: 'X', code: 'X2', category: null, start: '20:00', end: '08:00' }];
  d.people = [person('DAY', ['X'], { shiftMix: { categories: { DAY: 1 }, areas: {} } }),
    person('NONIGHT', ['X'], { shiftMix: { categories: { DAY: 1 }, areas: {} } }),
    person('NEVERSHARE', ['X'], { shiftMix: { categories: { DAY: 0.7, NIGHT: 0.3 }, areas: {} } })];
  d.restrictions = [{ personId: 'NONIGHT', shiftId: null, category: 'NIGHT', weekday: 0, kind: 'NEVER' },
    { personId: 'NEVERSHARE', shiftId: null, category: 'NIGHT', weekday: 0, kind: 'NEVER' }];
  const findings = dataChecks(d, { month, rules });
  assert.deepEqual(kinds(findings, 'DAY'), ['noNights']);
  assert.deepEqual(kinds(findings, 'NONIGHT'), []);
  assert.deepEqual(kinds(findings, 'NEVERSHARE'), ['nightNever']);
  assert.deepEqual(kinds(dataChecks(d, { month, rules: { ...rules, nightDistribution: 'EVEN' } }), 'NEVERSHARE'), ['nightNever']);
  assert.deepEqual(plannableShifts(d, d.people[1], month, rules).map((s) => s.id), ['X-D']);
  // seven weekday restrictions: no nights either, but the solver keeps the night share (only weekday 0 counts there)
  d.restrictions = [1, 2, 3, 4, 5, 6, 7].map((weekday) => ({ personId: 'NEVERSHARE', shiftId: null, category: 'NIGHT', weekday, kind: 'NEVER' }));
  assert.deepEqual(kinds(dataChecks(d, { month, rules }), 'NEVERSHARE'), []);
});

test('never nights as the solver: a family rule on every day or NEVER for every day of the week on each night shift', () => {
  const d = base();
  const allDays = [1, 2, 3, 4, 5, 6, 7];
  d.people.push(person('FAM', ['X'], { familyRules: [{ weekdays: allDays, noNights: true }] }),
    person('FAMPART', ['X'], { familyRules: [{ weekdays: [1, 2, 3, 4, 5], noNights: true }] }),
    person('CAT', ['X']), person('SHIFT', ['X']), person('WEEK', ['X']), person('DAYONLY', ['Y']));
  d.restrictions.push({ personId: 'CAT', category: 'NIGHT', weekday: 0, kind: 'NEVER' },
    { personId: 'SHIFT', shiftId: 'X-N', weekday: 0, kind: 'NEVER' },
    // seven weekdays one by one are not "every day" for the solver
    ...allDays.map((weekday) => ({ personId: 'WEEK', shiftId: 'X-N', weekday, kind: 'NEVER' })));
  const never = Object.fromEntries(d.people.map((p) => [p.id, neverNightShifts(d, p, month)]));
  assert.deepEqual(never, { OK: false, FAM: true, FAMPART: false, CAT: true, SHIFT: true, WEEK: false, DAYONLY: true });
});
