import { test } from 'node:test';
import assert from 'node:assert/strict';
import { areaDuties, coverageModel, findGaps, stepLines } from '../src/lib/coverage.js';
import { parseLocalDateTime as t } from '../src/lib/dates.js';

const segs = [
  { areaId: 'AN', from: '2026-12-01T06:30:00', to: '2026-12-01T08:00:00', required: 2, max: 3, planned: 2 },
  { areaId: 'AN', from: '2026-12-01T08:00:00', to: '2026-12-01T12:00:00', required: 4, max: null, planned: 3 },
  { areaId: 'AN', from: '2026-12-01T12:00:00', to: '2026-12-01T20:00:00', required: 4, max: null, planned: 2 },
  { areaId: 'AN', from: '2026-12-01T20:00:00', to: '2026-12-02T06:30:00', required: 1, max: 1, planned: 2 },
];

test('gaps are merged when adjacent and count missing person minutes', () => {
  const m = coverageModel(segs, t('2026-12-01T00:00'), t('2026-12-02T00:00'));
  assert.equal(m.gaps.length, 1);
  assert.equal(m.gaps[0].from, t('2026-12-01T08:00'));
  assert.equal(m.gaps[0].to, t('2026-12-01T20:00'));
  assert.equal(m.gaps[0].missingMinutes, 240 + 2 * 480);
  assert.equal(m.gaps[0].maxShortfall, 2);
  assert.equal(m.over.length, 1);
  assert.equal(m.yMax, 4);
});

test('segments are clipped to the range (night into next day)', () => {
  const m = coverageModel(segs, t('2026-12-01T00:00'), t('2026-12-02T00:00'));
  assert.equal(m.segments.at(-1).to, t('2026-12-02T00:00'));
  const next = coverageModel(segs, t('2026-12-02T00:00'), t('2026-12-03T00:00'));
  assert.equal(next.segments.length, 1);
  assert.equal(next.segments[0].from, t('2026-12-02T00:00'));
});

test('no gaps when demand is covered', () => {
  assert.deepEqual(findGaps([{ from: 0, to: 10, required: 1, planned: 1 }]), []);
});

test('step lines break at holes and at null values', () => {
  const s = [{ from: 0, to: 10, v: 1 }, { from: 10, to: 20, v: 2 }, { from: 30, to: 40, v: 1 }, { from: 40, to: 50, v: null }];
  const lines = stepLines(s, (x) => x.v);
  assert.equal(lines.length, 2);
  assert.deepEqual(lines[0], [{ x: 0, y: 1 }, { x: 10, y: 1 }, { x: 10, y: 2 }, { x: 20, y: 2 }]);
});

test('area duties include previous-month nights reaching into the first morning', () => {
  const shifts = new Map([
    ['DR-N', { id: 'DR-N', areaId: 'DR', start: '19:00', end: '07:00' }],
    ['AN-T', { id: 'AN-T', areaId: 'AN', start: '07:00', end: '19:00' }],
  ]);
  const assignments = [
    { personId: 'P1', date: '2027-01-01', shiftId: 'DR-N', pinned: true },
    { personId: 'P2', date: '2027-01-01', shiftId: 'AN-T', pinned: false },
    { personId: 'P3', date: '2027-01-01', shiftId: null, pinned: false },
  ];
  const previous = [
    { personId: 'P4', date: '2026-12-31', shiftId: 'DR-N' },
    { personId: 'P5', date: '2026-12-30', shiftId: 'DR-N' }, // ends 2026-12-31 07:00, outside the range
  ];
  const day = [t('2027-01-01T00:00'), t('2027-01-02T00:00')];
  const duties = areaDuties(assignments, previous, shifts, 'DR', ...day);
  assert.deepEqual(duties.map((d) => [d.personId, d.previous, d.pinned]), [['P1', false, true], ['P4', true, false]]);
  assert.equal(duties[1].end, t('2027-01-01T07:00'));
  assert.equal(areaDuties(assignments, undefined, shifts, 'DR', ...day).length, 1);
});
