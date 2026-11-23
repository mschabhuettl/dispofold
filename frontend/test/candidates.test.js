import { test } from 'node:test';
import assert from 'node:assert/strict';
import { candidateRequest, candidateRows, gapLabel } from '../src/lib/candidates.js';
import { cellRequest, cellsEditable } from '../src/lib/grid-model.js';

const people = new Map([['P1', { id: 'P1', firstName: 'Anna', lastName: 'Erste', shortName: 'AER' }]]);
const shifts = new Map([['X-T', { id: 'X-T', areaId: 'X', code: 'T1', name: 'Tag', start: '07:00', end: '19:00', color: '#AABBCC' }]]);

test('candidate rows keep the server order and show name, shift code, date and hours', () => {
  const rows = candidateRows([
    { personId: 'P1', date: '2027-01-04', shiftId: 'X-T', plannedHours: 96, targetHours: 160.5 },
    { personId: 'P7', date: '2027-01-05', shiftId: 'X-Q', plannedHours: 120.25, targetHours: 120 },
  ], people, shifts);
  assert.equal(rows.length, 2);
  assert.deepEqual([rows[0].name, rows[0].code, rows[0].day, rows[0].hours, rows[0].color], ['Erste Anna', 'T1', 'Mo 04.01.', 'geplant 96,0 h von Ziel 160,5 h', '#AABBCC']);
  assert.equal(rows[0].label, 'Erste Anna, T1 am Mo 04.01., geplant 96,0 h von Ziel 160,5 h: Dienst setzen und fixieren');
  // unknown person or shift: the ids
  assert.deepEqual([rows[1].name, rows[1].code, rows[1].color], ['P7', 'X-Q', null]);
});

test('no candidates: no rows (the list then says that nobody fits)', () => {
  assert.deepEqual(candidateRows([], people, shifts), []);
});

test('a click on a name sets the duty by hand and fixed, like a choice in the roster popover', () => {
  const c = { personId: 'P1', date: '2027-01-04', shiftId: 'X-T', plannedHours: 96, targetHours: 160 };
  const body = { personId: 'P1', date: '2027-01-04', shiftId: 'X-T', compRest: false, pinned: true };
  assert.deepEqual(candidateRequest(c), body);
  assert.deepEqual(candidateRows([c], people, shifts)[0].request, body);
  assert.deepEqual(cellRequest('P1', '2027-01-04', { shiftId: 'X-T', compRest: false }, true), body);
  assert.deepEqual(cellRequest('P1', '2027-01-04', { shiftId: null, compRest: true }, false),
    { personId: 'P1', date: '2027-01-04', shiftId: null, compRest: true, pinned: false });
});

test('gap line: area, time (with the end day when it crosses into a later day) and missing count', () => {
  assert.equal(gapLabel({ areaId: 'X', from: '2027-01-04T07:00', to: '2027-01-04T19:00', required: 3, planned: 1 }, 'Bereich X'),
    'Bereich X, 07:00-19:00: fehlt 2');
  assert.equal(gapLabel({ areaId: 'X', from: '2027-01-04T19:00', to: '2027-01-05T00:00', required: 1, planned: 0 }, 'Bereich X'),
    'Bereich X, 19:00-00:00: fehlt 1');
  assert.equal(gapLabel({ areaId: 'X', from: '2027-01-04T19:00', to: '2027-01-05T07:00', required: 1, planned: 0 }),
    'X, 19:00-Di 05.01. 07:00: fehlt 1');
});

test('only planners may change cells and ask for candidates, not while solving, not in a closed month', () => {
  assert.equal(cellsEditable({ canEdit: true }), true);
  assert.equal(cellsEditable({ canEdit: false }), false);
  assert.equal(cellsEditable({ canEdit: true, solving: true }), false);
  assert.equal(cellsEditable({ canEdit: true, closed: true }), false);
});
