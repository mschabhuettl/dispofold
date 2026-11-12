import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildGrid, cellOptions } from '../src/lib/grid-model.js';

const dataset = {
  holidays: [{ date: '2026-12-08', name: 'Mariä Empfängnis' }],
  absenceTypes: [{ code: 'U', name: 'Urlaub', color: '#9AB86A' }],
  shifts: [
    { id: 'TV-F', areaId: 'TV', code: 'V1', name: 'Transport früh', start: '07:30', end: '15:30', color: '#F4DCDB' },
    { id: 'DR-N', areaId: 'DR', code: 'R4', name: 'Disposition Nacht', start: '19:00:00', end: '07:00:00', color: '#002D55' },
  ],
  people: [
    { id: 'P2', firstName: 'Bea', lastName: 'Zweite', shortName: 'BZW', weeklyHours: 40, areaIds: ['DR'] },
    { id: 'P1', firstName: 'Anna', lastName: 'Erste', shortName: 'AER', weeklyHours: 30, areaIds: ['TV', 'DR'] },
  ],
};
const period = {
  absences: [{ personId: 'P1', date: '2026-12-03', typeCode: 'U' }],
  fixed: [{ personId: 'P2', date: '2026-12-02', shiftId: 'DR-N' }],
};
const plan = {
  assignments: [
    { personId: 'P1', date: '2026-12-01', shiftId: 'TV-F', compRest: false, pinned: true },
    { personId: 'P1', date: '2026-12-02', shiftId: null, compRest: true, pinned: false },
    { personId: 'P2', date: '2026-12-02', shiftId: 'DR-N', compRest: false, pinned: false },
  ],
};

test('days carry weekend and holiday flags', () => {
  const { days } = buildGrid({ dataset, period, plan, month: '2026-12' });
  assert.equal(days.length, 31);
  assert.equal(days[4].weekend, true); // Sat 5th
  assert.equal(days[7].holiday, 'Mariä Empfängnis');
});

test('cells merge plan, absences and fixed duties', () => {
  const { rows } = buildGrid({ dataset, period, plan, month: '2026-12' });
  const p1 = rows.find((r) => r.person.id === 'P1');
  assert.deepEqual([p1.cells[0].kind, p1.cells[0].code, p1.cells[0].pinned], ['shift', 'V1', true]);
  assert.deepEqual([p1.cells[1].kind, p1.cells[1].code], ['compRest', 'ER']);
  assert.deepEqual([p1.cells[2].kind, p1.cells[2].code, p1.cells[2].color], ['absence', 'U', '#9AB86A']);
  assert.equal(p1.cells[3].kind, 'free');
  assert.equal(p1.hours, 8);
  const p2 = rows.find((r) => r.person.id === 'P2');
  // fixed in the period: pinned even though the plan says otherwise
  assert.deepEqual([p2.cells[1].pinned, p2.cells[1].fixedInPeriod], [true, true]);
  assert.equal(p2.hours, 12);
});

test('rows are filtered by area and search, sorted by first area then name', () => {
  assert.deepEqual(buildGrid({ dataset, period, plan, month: '2026-12', areaId: 'TV' }).rows.map((r) => r.person.id), ['P1']);
  assert.deepEqual(buildGrid({ dataset, period, plan, month: '2026-12', search: 'bzw' }).rows.map((r) => r.person.id), ['P2']);
  assert.deepEqual(buildGrid({ dataset, period: null, plan: null, month: '2026-12' }).rows.map((r) => r.person.id), ['P2', 'P1']);
});

test('cell options are the shifts of the person areas plus free and ER', () => {
  const opts = cellOptions(dataset.people[0], dataset.shifts);
  assert.deepEqual(opts.map((o) => o.code), ['R4', '', 'ER']);
  assert.equal(opts.at(-1).compRest, true);
});

test('a duty on an absence day is shown as a conflict that keeps the duty', () => {
  const conflictPlan = { assignments: [{ personId: 'P1', date: '2026-12-03', shiftId: 'TV-F', compRest: false, pinned: true }] };
  const { rows } = buildGrid({ dataset, period, plan: conflictPlan, month: '2026-12' });
  const cell = rows.find((r) => r.person.id === 'P1').cells[2];
  assert.equal(cell.kind, 'absence');
  assert.equal(cell.conflict, true);
  assert.equal(cell.code, 'U/V1');
  assert.equal(cell.shiftId, 'TV-F');
  assert.equal(cell.pinned, true);
  assert.match(cell.label, /Urlaub, aber Dienst V1/);
  // a plain absence is no conflict
  const plain = buildGrid({ dataset, period, plan, month: '2026-12' }).rows.find((r) => r.person.id === 'P1').cells[2];
  assert.deepEqual([plain.conflict, plain.shiftId], [false, null]);
});

test('grid cells are read-only for viewers, while solving and in a closed month', async () => {
  const { cellsEditable } = await import('../src/lib/grid-model.js');
  assert.equal(cellsEditable({ canEdit: true }), true);
  assert.equal(cellsEditable({ canEdit: false }), false);
  assert.equal(cellsEditable({ canEdit: true, solving: true }), false);
  assert.equal(cellsEditable({ canEdit: true, closed: true }), false);
});

test('solve options: defaults, seed, move limit and cold start', async () => {
  const { solveOptions } = await import('../src/views/roster.js');
  // a default solve sends nothing, so the server's reproducible defaults (seed, move limit, time cap) apply
  assert.deepEqual(solveOptions({}), {});
  assert.deepEqual(solveOptions({ seed: '', timeLimit: '', moveLimit: '', coldStart: false }), {});
  assert.deepEqual(solveOptions({ seed: '7', timeLimit: '60', moveLimit: '50000', coldStart: true }),
    { timeLimitSeconds: 60, seed: 7, moveLimit: 50000, coldStart: true });
  assert.deepEqual(solveOptions({ seed: '0' }), { seed: 0 });
  assert.deepEqual(solveOptions({ moveLimit: '0', timeLimit: '0' }), {});
});

test('viewers see the neutral absence as "abwesend"', async () => {
  const { absenceName } = await import('../src/lib/grid-model.js');
  assert.equal(absenceName('ABW', new Map()), 'abwesend');
  assert.equal(absenceName('U', new Map([['U', { name: 'Urlaub' }]])), 'Urlaub');
  assert.equal(absenceName('X', new Map()), 'X');
});

test('employment period text', async () => {
  const { employmentText } = await import('../src/views/masterdata.js');
  assert.equal(employmentText({ entryDate: '2027-03-01', exitDate: '2027-06-30' }), '01.03.2027 bis 30.06.2027');
  assert.equal(employmentText({ entryDate: '2027-03-01', exitDate: null }), 'ab 01.03.2027');
  assert.equal(employmentText({ exitDate: '2027-06-30' }), 'bis 30.06.2027');
  assert.equal(employmentText({}), '');
});
