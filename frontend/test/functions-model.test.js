import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildFunctionRows, gapLine, personLabel, printParts, windowOverlapsShift } from '../src/lib/functions-model.js';
import { buildDays } from '../src/lib/grid-model.js';
import { onRequestTest } from '../src/lib/restrictions.js';

const ALL = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
const dataset = {
  // dataset order, not alphabetical
  areas: [{ id: 'B', name: 'Bereich B' }, { id: 'A', name: 'Bereich A' }],
  shifts: [
    { id: 'A-T', areaId: 'A', code: 'T', name: 'Tag', start: '07:00', end: '19:00', category: 'DAY' },
    { id: 'A-X', areaId: 'A', code: 'X', name: 'Abend', start: '20:00', end: '23:00', category: 'LATE' },
    { id: 'A-N', areaId: 'A', code: 'N', name: 'Nacht', start: '19:00', end: '07:00', category: 'NIGHT' },
    { id: 'B-F', areaId: 'B', code: 'F', name: 'Früh', start: '06:00', end: '14:00', category: 'EARLY' },
  ],
  // the window touches the night shift but does not overlap it; the evening shift lies outside
  demand: [{ areaId: 'A', days: ALL, date: null, from: '07:00', to: '19:00', min: 2, max: null }],
  holidays: [],
  absenceTypes: [{ code: 'U', name: 'Urlaub', color: '#9AB86A' }],
  people: [
    { id: 'P1', firstName: 'Anna', lastName: 'Zett', shortName: 'AZE', weeklyHours: 40, areaIds: ['A'] },
    { id: 'P2', firstName: 'Otto', lastName: 'Berg', shortName: 'OBE', weeklyHours: 40, areaIds: ['A'] },
    { id: 'P3', firstName: 'Eva', lastName: 'Mayer', shortName: null, weeklyHours: 40, areaIds: ['A', 'B'] },
  ],
  restrictions: [{ personId: 'P3', shiftId: null, category: 'NIGHT', weekday: 0, kind: 'ON_REQUEST', validFrom: null, validTo: null }],
};
const duty = (personId, date, shiftId, pinned = false) => ({ personId, date, shiftId, compRest: false, pinned });
const plan = {
  assignments: [
    duty('P1', '2027-01-04', 'A-T'), duty('P2', '2027-01-04', 'A-T'), duty('P3', '2027-01-04', 'A-N', true),
    duty('P3', '2027-01-07', 'B-F'), duty('P2', '2027-01-06', 'A-T'),
  ],
};
const period = { absences: [{ personId: 'P2', date: '2027-01-06', typeCode: 'U' }], fixed: [] };
const gaps = [
  // two gaps the same day, given out of order
  { areaId: 'A', from: '2027-01-04T10:00', to: '2027-01-04T12:00', required: 4, planned: 2 },
  { areaId: 'A', from: '2027-01-04T07:00', to: '2027-01-04T19:00', required: 3, planned: 2 },
  // starts after midnight: belongs to the day it starts
  { areaId: 'A', from: '2027-01-05T00:00', to: '2027-01-05T07:00', required: 2, planned: 1 },
  // a night gap past midnight belongs to the day it starts
  { areaId: 'A', from: '2027-01-08T19:00', to: '2027-01-09T07:00', required: 1, planned: 0 },
  // nothing missing: not shown
  { areaId: 'B', from: '2027-01-04T06:00', to: '2027-01-04T14:00', required: 1, planned: 1 },
];
const D4 = 3; // column of 2027-01-04

function model() {
  return buildFunctionRows({ dataset, period, plan, month: '2027-01', gaps, onRequest: onRequestTest(dataset.restrictions, dataset.shifts) });
}
const positions = (rows, shiftId) => rows.filter((r) => r.type === 'position' && r.shift.id === shiftId);
const gapRow = (rows, areaId) => rows.filter((r) => r.type === 'gaps' && r.area.id === areaId);
const label = (r) => (r.type === 'group' ? `[${r.area.id}]` : r.type === 'gaps' ? `${r.area.id}:offen` : `${r.shift.id}#${r.index}`);

test('rows are grouped by area in dataset order, shifts in dataset order, unused shifts without demand left out', () => {
  // one gap row per area with gaps, after its shifts; position rows count planned people only
  assert.deepEqual(model().rows.map(label), ['[B]', 'B-F#0', '[A]', 'A-T#0', 'A-T#1', 'A-N#0', 'A:offen']);
});

test('demand windows overlap a shift by time of day, also across midnight', () => {
  const night = { start: '21:30', end: '06:30' };
  assert.equal(windowOverlapsShift({ from: '19:00', to: '00:00' }, night), true);
  assert.equal(windowOverlapsShift({ from: '00:00', to: '07:00' }, night), true);
  assert.equal(windowOverlapsShift({ from: '07:00', to: '19:00' }, { start: '19:00', end: '07:00' }), false);
  assert.equal(windowOverlapsShift({ from: '07:00', to: '19:00' }, { start: '06:00', end: '14:00' }), true);
});

test('several people on one shift and day are separate positions sorted by name, no gap markers', () => {
  const day = positions(model().rows, 'A-T').map((r) => r.cells[D4]);
  assert.deepEqual(day.map((c) => (c.kind === 'person' ? c.name : c.kind)), ['Berg Otto', 'Zett Anna']);
  assert.deepEqual(positions(model().rows, 'A-T').map((r) => r.cells[D4 + 1].kind), ['empty', 'empty']);
  assert.ok(model().rows.filter((r) => r.type === 'position').every((r) => r.cells.every((c) => c.kind === 'person' || c.kind === 'empty')));
});

test('gap row: several gaps of a day in one cell, sorted by start', () => {
  const [row] = gapRow(model().rows, 'A');
  assert.equal(row.cells[D4].kind, 'gaps');
  assert.deepEqual(row.cells[D4].gaps, [gaps[1], gaps[0]]);
  assert.deepEqual(row.cells[D4].gaps.map(gapLine), [{ count: 1, from: '07:00', to: '19:00' }, { count: 2, from: '10:00', to: '12:00' }]);
  // every other day without a gap is empty
  const withGaps = row.cells.flatMap((c, i) => (c.kind === 'gaps' ? [i + 1] : []));
  assert.deepEqual(withGaps, [4, 5, 8]);
});

test('a gap belongs to the day of its start: after midnight the next day, a night past midnight the day it starts', () => {
  const [row] = gapRow(model().rows, 'A');
  assert.deepEqual(row.cells[D4 + 1].gaps, [gaps[2]]);
  assert.deepEqual(row.cells[7].gaps, [gaps[3]]); // 2027-01-08
  assert.equal(row.cells[8].kind, 'empty');
  assert.deepEqual(gapLine(gaps[3]), { count: 1, from: '19:00', to: '07:00' });
  // a gap the analysis merged over several days keeps its end date
  assert.deepEqual(gapLine({ areaId: 'AN', from: '2027-01-10T07:00:00', to: '2027-01-13T19:00:00', required: 2, planned: 1 }),
    { count: 1, from: '07:00', to: 'Mi 13.01. 19:00' });
});

test('each gap shows once; no gap row without missing people; an area with gaps but no shown shift gets one', () => {
  const { rows } = model();
  assert.equal(gapRow(rows, 'A').length, 1);
  assert.equal(gapRow(rows, 'B').length, 0);
  const shown = gapRow(rows, 'A')[0].cells.flatMap((c) => c.gaps ?? []);
  assert.equal(shown.length, 4);
  assert.equal(new Set(shown).size, 4);
  const withC = { ...dataset, areas: [...dataset.areas, { id: 'C', name: 'Bereich C' }] };
  const gapC = { areaId: 'C', from: '2027-01-02T08:00', to: '2027-01-02T09:00', required: 1, planned: 0 };
  const res = buildFunctionRows({ dataset: withC, period, plan, month: '2027-01', gaps: [gapC] });
  assert.deepEqual(res.rows.map(label), ['[B]', 'B-F#0', '[A]', 'A-T#0', 'A-T#1', 'A-N#0', '[C]', 'C:offen']);
  // gaps outside the month's days are left out
  const outside = buildFunctionRows({ dataset, period, plan, month: '2027-01',
    gaps: [{ areaId: 'A', from: '2026-12-31T19:00', to: '2027-01-01T07:00', required: 1, planned: 0 }] });
  assert.equal(gapRow(outside.rows, 'A').length, 0);
});

test('cells carry the roster marks: fixed, conflict with an absence, on request', () => {
  const { rows } = model();
  const night = positions(rows, 'A-N')[0].cells[D4];
  assert.equal(night.person.id, 'P3');
  assert.equal(night.cell.pinned, true);
  assert.equal(night.cell.onRequest, true);
  const conflict = positions(rows, 'A-T')[0].cells[5]; // 2027-01-06: duty despite holiday leave
  assert.equal(conflict.kind, 'person');
  assert.equal(conflict.cell.conflict, true);
  assert.equal(positions(rows, 'B-F')[0].cells[6].cell.onRequest, false);
});

test('print: days 1-15 and 16 to the end', () => {
  assert.deepEqual(printParts(buildDays('2027-01')).map((p) => [p[0].day, p.at(-1).day]), [[1, 15], [16, 31]]);
  assert.deepEqual(printParts(buildDays('2027-02')).map((p) => [p[0].day, p.at(-1).day]), [[1, 15], [16, 28]]);
});

test('cells show the short name or the full name; without a short name always the full name', () => {
  const p = { shortName: 'MUS' };
  assert.equal(personLabel(p, 'Muster Anna', 'short'), 'MUS');
  assert.equal(personLabel(p, 'Muster Anna', 'full'), 'Muster Anna');
  assert.equal(personLabel({ shortName: '' }, 'Muster Anna', 'short'), 'Muster Anna');
});
