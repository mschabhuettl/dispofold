import { test } from 'node:test';
import assert from 'node:assert/strict';
import { exceptionLines, expandRestrictions, foldDataset, withEffectiveRestrictions } from '../src/lib/area-states.js';

const shifts = [{ id: 'X-T', areaId: 'X', code: 'X1', name: 'Tag' }, { id: 'X-N', areaId: 'X', code: 'X2', name: 'Nacht' },
  { id: 'Y-T', areaId: 'Y', code: 'Y1', name: 'Tag' }];
const r = (shiftId, kind = 'ON_REQUEST', extra = {}) => ({ personId: 'P1', shiftId, category: null, weekday: 0, kind, validFrom: null, validTo: null, ...extra });

test('whole-area rows fold into a state and expand back to the same rows (as the backend)', () => {
  const rows = [r('X-T'), r('X-N'), r('X-N', 'NEVER', { weekday: 5 }), r('Y-T', 'NEVER', { validFrom: '2027-03-01' })];
  const dataset = { shifts, people: [{ id: 'P1', areaIds: ['X', 'Y'] }], restrictions: rows };
  const folded = foldDataset(dataset);
  assert.deepEqual(folded.people[0].areaStates, { X: 'ON_REQUEST' });
  assert.deepEqual(folded.restrictions, [rows[2], rows[3]]);
  const key = (x) => JSON.stringify(x);
  assert.deepEqual(expandRestrictions(folded).map(key).sort(), rows.map(key).sort());
  assert.deepEqual(withEffectiveRestrictions(folded).exceptions, folded.restrictions);
  // a state of an area the person does not have gives no rows
  assert.deepEqual(expandRestrictions({ shifts, people: [{ id: 'P1', areaIds: ['Y'], areaStates: { X: 'NEVER' } }], restrictions: [] }), []);
});

test('a whole-day row of the other kind does not hide the state; both kinds on every shift: nie (as the backend)', () => {
  const people = [{ id: 'P1', areaIds: ['X'] }];
  const some = foldDataset({ shifts, people, restrictions: [r('X-T'), r('X-N'), r('X-T', 'NEVER')] });
  assert.deepEqual(some.people[0].areaStates, { X: 'ON_REQUEST' });
  assert.deepEqual(some.restrictions, [r('X-T', 'NEVER')]);
  const both = foldDataset({ shifts, people, restrictions: [r('X-T'), r('X-N'), r('X-T', 'NEVER'), r('X-N', 'NEVER')] });
  assert.deepEqual(both.people[0].areaStates, { X: 'NEVER' });
  assert.deepEqual(both.restrictions, [r('X-T'), r('X-N')]);
});

test('the exceptions fold into one line per shift with weekdays and dates', () => {
  const byId = new Map(shifts.map((s) => [s.id, s]));
  const lines = exceptionLines([r('X-N', 'ON_REQUEST', { weekday: 1 }), r('X-N', 'ON_REQUEST', { weekday: 2 }), r('X-N', 'ON_REQUEST', { weekday: 5 }),
    { personId: 'P1', shiftId: null, category: 'NIGHT', weekday: 0, kind: 'NEVER', validFrom: '2027-03-01', validTo: null },
    r('Y-T', 'NEVER')], 'P1', byId, { NIGHT: 'Nacht' }, ['X']);
  assert.deepEqual(lines.map((l) => [l.areaId, l.text]), [['X', 'X2 Nacht: nur von Hand (Mo, Di, Fr)'],
    [null, 'alle Nacht-Schichten: nie ab 1. 3.'], [null, 'Y1 Tag: nie']]);
  assert.equal(lines[0].rows.length, 3);
});
