import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildFunctionRows, missingByShiftDay, personLabel, printParts, windowOverlapsShift } from '../src/lib/functions-model.js';
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
  { areaId: 'A', from: '2027-01-04T07:00', to: '2027-01-04T19:00', required: 3, planned: 2 },
  // a shorter gap the same day misses more: the larger count applies
  { areaId: 'A', from: '2027-01-04T10:00', to: '2027-01-04T12:00', required: 4, planned: 2 },
  // after midnight: belongs to the night that started the day before
  { areaId: 'A', from: '2027-01-05T00:00', to: '2027-01-05T07:00', required: 2, planned: 1 },
];
const D4 = 3; // column of 2027-01-04

function model() {
  return buildFunctionRows({ dataset, period, plan, month: '2027-01', gaps, onRequest: onRequestTest(dataset.restrictions, dataset.shifts) });
}
const positions = (rows, shiftId) => rows.filter((r) => r.type === 'position' && r.shift.id === shiftId);

test('rows are grouped by area in dataset order, shifts in dataset order, unused shifts without demand left out', () => {
  const { rows } = model();
  assert.deepEqual(rows.map((r) => (r.type === 'group' ? `[${r.area.id}]` : `${r.shift.id}#${r.index}`)),
    ['[B]', 'B-F#0', '[A]', 'A-T#0', 'A-T#1', 'A-T#2', 'A-T#3', 'A-N#0', 'A-N#1']);
});

test('demand windows overlap a shift by time of day, also across midnight', () => {
  const night = { start: '21:30', end: '06:30' };
  assert.equal(windowOverlapsShift({ from: '19:00', to: '00:00' }, night), true);
  assert.equal(windowOverlapsShift({ from: '00:00', to: '07:00' }, night), true);
  assert.equal(windowOverlapsShift({ from: '07:00', to: '19:00' }, { start: '19:00', end: '07:00' }), false);
  assert.equal(windowOverlapsShift({ from: '07:00', to: '19:00' }, { start: '06:00', end: '14:00' }), true);
});

test('several people on one shift and day are separate positions sorted by name, then the missing ones', () => {
  const day = positions(model().rows, 'A-T').map((r) => r.cells[D4]);
  assert.deepEqual(day.map((c) => (c.kind === 'person' ? c.name : c.kind)), ['Berg Otto', 'Zett Anna', 'gap', 'gap']);
  assert.equal(day[2].gaps.length, 2);
  // the next day nobody works the shift and nothing is missing
  assert.deepEqual(positions(model().rows, 'A-T').map((r) => r.cells[D4 + 1].kind), ['empty', 'empty', 'empty', 'empty']);
});

test('a gap after midnight marks the night shift of the day before', () => {
  const night = positions(model().rows, 'A-N');
  assert.equal(night[0].cells[D4].kind, 'person');
  assert.equal(night[1].cells[D4].kind, 'gap');
  assert.deepEqual(night[1].cells[D4].gaps, [gaps[2]]);
  assert.equal(night[1].cells[D4 + 1].kind, 'empty');
});

test('missing per shift and day: overlap of the shift time with the gap, the largest count of several gaps', () => {
  const dates = buildDays('2027-01').map((d) => d.date);
  const missing = missingByShiftDay(gaps, dataset.shifts, dates);
  assert.equal(missing.get('A-T|2027-01-04').missing, 2);
  assert.equal(missing.get('A-N|2027-01-04').missing, 1);
  assert.equal(missing.has('A-N|2027-01-05'), false);
  assert.equal(missing.has('A-T|2027-01-05'), false); // touches 07:00 only
  assert.equal(missing.has('B-F|2027-01-04'), false); // other area
  // a gap over more than a day hits every shift and day it overlaps, the 11th up to 19:30 only
  const long = missingByShiftDay([{ areaId: 'A', from: '2027-01-10T20:00', to: '2027-01-11T19:30', required: 1, planned: 0 }],
    dataset.shifts, dates);
  assert.deepEqual([...long.keys()].sort(), ['A-N|2027-01-10', 'A-N|2027-01-11', 'A-T|2027-01-11', 'A-X|2027-01-10']);
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
