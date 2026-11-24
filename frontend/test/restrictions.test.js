import { test } from 'node:test';
import assert from 'node:assert/strict';
import { areaOnRequest, areaRestriction, needsRulesForCategories, onRequestTest, restrictionAppliesOn, setAreaRestriction, shiftCategory } from '../src/lib/restrictions.js';
import { restrictionFromForm } from '../src/lib/masterdata-edit.js';

const shifts = [
  { id: 'X-T', areaId: 'X', start: '07:00', end: '19:00', category: 'DAY' },
  { id: 'X-N', areaId: 'X', start: '19:00', end: '07:00', category: 'NIGHT' },
  { id: 'X-M', areaId: 'X', start: '09:00', end: '17:00', category: null },
];
const rules = { nightStart: '22:00:00', nightEnd: '06:00', nightMinHours: 2 };

const r = (fields) => ({ personId: 'P1', shiftId: null, category: null, weekday: 0, kind: 'ON_REQUEST', validFrom: null, validTo: null, ...fields });

test('a restriction applies on its weekday (Mon=1, 0 every day) inside validFrom..validTo, both inclusive', () => {
  // 2027-01-04 is a Monday
  assert.equal(restrictionAppliesOn(r({ weekday: 0 }), '2027-01-06'), true);
  assert.equal(restrictionAppliesOn(r({ weekday: 1 }), '2027-01-04'), true);
  assert.equal(restrictionAppliesOn(r({ weekday: 1 }), '2027-01-05'), false);
  assert.equal(restrictionAppliesOn(r({ weekday: 7 }), '2027-01-10'), true);
  const valid = r({ validFrom: '2027-01-10', validTo: '2027-01-20' });
  assert.deepEqual(['2027-01-09', '2027-01-10', '2027-01-20', '2027-01-21'].map((d) => restrictionAppliesOn(valid, d)),
    [false, true, true, false]);
  assert.equal(restrictionAppliesOn(r({ validFrom: '2027-01-10' }), '2027-03-01'), true);
  assert.equal(restrictionAppliesOn(r({ validTo: '2027-01-10' }), '2026-12-01'), true);
});

test('on request: by shift or by the shift category, only ON_REQUEST, only the person concerned', () => {
  const onRequest = onRequestTest([
    r({ shiftId: 'X-T', weekday: 1 }),
    r({ personId: 'P2', category: 'NIGHT', validFrom: '2027-01-15' }),
    r({ personId: 'P3', shiftId: 'X-T', kind: 'NEVER' }),
    r({ personId: 'P4', category: 'DAY' }),
  ], shifts);
  assert.equal(onRequest('P1', '2027-01-04', 'X-T'), true);
  assert.equal(onRequest('P1', '2027-01-05', 'X-T'), false); // Tuesday
  assert.equal(onRequest('P1', '2027-01-04', 'X-N'), false); // other shift
  assert.equal(onRequest('P2', '2027-01-14', 'X-N'), false); // before validFrom
  assert.equal(onRequest('P2', '2027-01-15', 'X-N'), true);
  assert.equal(onRequest('P2', '2027-01-15', 'X-T'), false); // other category
  assert.equal(onRequest('P3', '2027-01-04', 'X-T'), false); // NEVER is no on-request mark
  assert.equal(onRequest('P4', '2027-01-04', 'X-T'), true);
  // a shift without a category in the dataset matches only with the derived one (needs the rules)
  assert.equal(onRequest('P4', '2027-01-04', 'X-M'), false);
  const withRules = onRequestTest([r({ personId: 'P4', category: 'DAY' })], shifts, rules);
  assert.equal(withRules('P4', '2027-01-04', 'X-M'), true);
  assert.equal(onRequest('P9', '2027-01-04', 'X-T'), false);
});

test('viewers get no restrictions, so nothing is marked', () => {
  assert.equal(onRequestTest([], shifts)('P1', '2027-01-04', 'X-T'), false);
  assert.equal(onRequestTest(undefined, shifts)('P1', '2027-01-04', 'X-T'), false);
});

test('a shift without a category gets it from the times like in the backend', () => {
  const cat = (start, end, category = null) => shiftCategory({ start, end, category }, rules);
  assert.equal(cat('21:00', '05:00'), 'NIGHT');
  assert.equal(cat('04:00', '12:00'), 'NIGHT'); // two hours in the night window
  assert.equal(cat('05:00', '13:00'), 'EARLY'); // one hour only
  assert.equal(cat('09:00', '17:00'), 'DAY');
  assert.equal(cat('07:00', '19:00'), 'DAY');
  assert.equal(cat('13:00', '21:00'), 'LATE');
  assert.equal(cat('19:00', '23:00'), 'LATE'); // one hour of night
  assert.equal(cat('09:00', '17:00', 'LATE'), 'LATE'); // the dataset wins
  assert.equal(shiftCategory({ start: '09:00', end: '17:00', category: null }, null), null);
});

test('the rules are loaded only for category restrictions and shifts without a category', () => {
  const restriction = r({ category: 'NIGHT' });
  assert.equal(needsRulesForCategories({ restrictions: [restriction], shifts }), true);
  assert.equal(needsRulesForCategories({ restrictions: [restriction], shifts: shifts.slice(0, 2) }), false);
  assert.equal(needsRulesForCategories({ restrictions: [r({ shiftId: 'X-M' })], shifts }), false);
  assert.equal(needsRulesForCategories({ restrictions: [], shifts }), false);
});

test('the area switch gives the same records as entering each shift by hand', () => {
  const ids = ['X-T', 'X-N', 'X-M'];
  for (const kind of ['NEVER', 'ON_REQUEST']) {
    const byHand = ids.map((id) => restrictionFromForm('P1', { target: `shift:${id}`, weekday: '0', kind, validFrom: '', validTo: '' }));
    assert.deepEqual(setAreaRestriction([], 'P1', ids, kind), byHand);
    assert.equal(areaRestriction(byHand, 'P1', ids), kind);
  }
  assert.deepEqual(setAreaRestriction([], 'P1', ids, 'NONE'), []);
});

test('the area switch replaces the undated shift restrictions of the area and keeps the others', () => {
  const other = r({ shiftId: 'Y-T', kind: 'NEVER' });
  const dated = r({ shiftId: 'X-T', kind: 'NEVER', validFrom: '2027-01-01' });
  const category = r({ category: 'NIGHT', kind: 'NEVER' });
  const otherPerson = r({ personId: 'P2', shiftId: 'X-T', kind: 'NEVER' });
  const list = [other, dated, category, otherPerson, r({ shiftId: 'X-T', weekday: 3 }), r({ shiftId: 'X-N', kind: 'NEVER' })];
  const never = setAreaRestriction(list, 'P1', ['X-T', 'X-N'], 'NEVER');
  assert.deepEqual(never.slice(0, 4), [other, dated, category, otherPerson]);
  assert.deepEqual(never.slice(4).map((x) => [x.shiftId, x.weekday, x.kind]), [['X-T', 0, 'NEVER'], ['X-N', 0, 'NEVER']]);
  assert.deepEqual(setAreaRestriction(list, 'P1', ['X-T', 'X-N'], 'NONE'), [other, dated, category, otherPerson]);
});

test('the state of an area: one kind, none, or mixed', () => {
  const ids = ['X-T', 'X-N'];
  assert.equal(areaRestriction([], 'P1', ids), 'NONE');
  assert.equal(areaRestriction([r({ shiftId: 'X-T' })], 'P1', ids), 'MIXED'); // one shift only
  assert.equal(areaRestriction([r({ shiftId: 'X-T' }), r({ shiftId: 'X-N', kind: 'NEVER' })], 'P1', ids), 'MIXED');
  assert.equal(areaRestriction([r({ shiftId: 'X-T', weekday: 2 }), r({ shiftId: 'X-N', weekday: 2 })], 'P1', ids), 'MIXED'); // single weekdays
  assert.equal(areaRestriction([r({ shiftId: 'X-T' }), r({ shiftId: 'X-N' })], 'P1', ids), 'ON_REQUEST');
  // dated, category and other people's restrictions do not count; records without the optional keys (mock data) do
  assert.equal(areaRestriction([r({ shiftId: 'X-T', validTo: '2027-01-31' }), r({ category: 'DAY' }), r({ personId: 'P2', shiftId: 'X-N' })], 'P1', ids), 'NONE');
  assert.equal(areaRestriction([{ personId: 'P1', shiftId: 'X-T', weekday: 0, kind: 'NEVER' }, { personId: 'P1', shiftId: 'X-N', weekday: 0, kind: 'NEVER' }], 'P1', ids), 'NEVER');
  assert.equal(areaRestriction([], 'P1', []), 'NONE');
});

test('an area is all, partly or not on request for the note next to it', () => {
  const ids = ['S1', 'S2'];
  const on = (shiftId, weekday = 0, extra = {}) => ({ personId: 'P1', shiftId, weekday, kind: 'ON_REQUEST', ...extra });
  assert.equal(areaOnRequest([], 'P1', ids), 'NONE');
  assert.equal(areaOnRequest([on('S1'), on('S2')], 'P1', ids), 'ALL');
  assert.equal(areaOnRequest([on('S1')], 'P1', ids), 'SOME');
  assert.equal(areaOnRequest([on('S1'), on('S2', 3)], 'P1', ids), 'SOME');
  // dated, another person's or "never" do not count
  assert.equal(areaOnRequest([on('S1', 0, { validFrom: '2027-01-01' }), on('S2', 0, { personId: 'P2' }), { ...on('S2'), kind: 'NEVER' }], 'P1', ids), 'NONE');
  assert.equal(areaOnRequest([on('S1')], 'P1', []), 'NONE');
});
