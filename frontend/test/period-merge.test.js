import { test } from 'node:test';
import assert from 'node:assert/strict';
import { absenceRanges, addAbsenceRange, mergePeriod } from '../src/lib/period-merge.js';

const abs = (personId, date, typeCode = 'U') => ({ personId, date, typeCode });

test('local additions and removals are applied onto the newer server version', () => {
  const base = { revision: 3, absences: [abs('P1', '2027-01-04'), abs('P2', '2027-01-05')], wishes: [], fixed: [] };
  const local = { ...base, absences: [abs('P2', '2027-01-05'), abs('P3', '2027-01-06')] }; // removed P1, added P3
  const server = { revision: 4, absences: [abs('P1', '2027-01-04'), abs('P2', '2027-01-05'), abs('P4', '2027-01-07')], wishes: [], fixed: [] };
  const { period, conflicts } = mergePeriod(base, local, server);
  assert.equal(period.revision, 4);
  assert.deepEqual(period.absences.map((a) => a.personId).sort(), ['P2', 'P3', 'P4']);
  assert.deepEqual(conflicts, []);
});

test('the same entry changed on both sides is reported, the local change wins', () => {
  const base = { revision: 1, absences: [abs('P1', '2027-01-04', 'U')], wishes: [], fixed: [] };
  const local = { ...base, absences: [abs('P1', '2027-01-04', 'K')] };
  const server = { revision: 2, absences: [abs('P1', '2027-01-04', 'FB')], wishes: [], fixed: [] };
  const { period, conflicts } = mergePeriod(base, local, server);
  assert.deepEqual(period.absences, [abs('P1', '2027-01-04', 'K')]);
  assert.deepEqual(conflicts, [{ list: 'absences', key: 'P1|2027-01-04' }]);
});

test('changes only on the server stay untouched', () => {
  const base = { revision: 1, absences: [], wishes: [{ personId: 'P1', date: '2027-01-02', kind: 'OFF' }], fixed: [] };
  const server = { revision: 2, absences: [], wishes: [], fixed: [{ personId: 'P2', date: '2027-01-03', shiftId: 'S1' }] };
  const { period, conflicts } = mergePeriod(base, structuredClone(base), server);
  assert.deepEqual(period.wishes, []);
  assert.deepEqual(period.fixed, server.fixed);
  assert.deepEqual(conflicts, []);
});

test('absences as ranges: consecutive days of one person and type, across a month end', () => {
  const list = [abs('P2', '2027-01-03'), abs('P1', '2027-01-05'), abs('P1', '2027-01-04'), abs('P1', '2027-01-06', 'K'),
    abs('P1', '2027-01-08'), abs('P2', '2027-01-31'), abs('P2', '2027-02-01')];
  assert.deepEqual(absenceRanges(list).map((r) => [r.personId, r.typeCode, r.from, r.to, r.entries.length]), [
    ['P1', 'U', '2027-01-04', '2027-01-05', 2], ['P1', 'K', '2027-01-06', '2027-01-06', 1], ['P1', 'U', '2027-01-08', '2027-01-08', 1],
    ['P2', 'U', '2027-01-03', '2027-01-03', 1], ['P2', 'U', '2027-01-31', '2027-02-01', 2]]);
  assert.deepEqual(absenceRanges([]), []);
});

test('a range is stored as one record per day and replaces the absences of the person on these days', () => {
  assert.deepEqual(addAbsenceRange([], { personId: 'P1', from: '2027-01-30', to: '2027-02-02', typeCode: 'U' }),
    ['2027-01-30', '2027-01-31', '2027-02-01', '2027-02-02'].map((d) => abs('P1', d)));
  assert.deepEqual(addAbsenceRange([], { personId: 'P1', from: '2027-01-05', to: '2027-01-05', typeCode: 'K' }), [abs('P1', '2027-01-05', 'K')]);
  // a range inside another one splits it; other people stay
  let list = addAbsenceRange([abs('P2', '2027-01-06')], { personId: 'P1', from: '2027-01-04', to: '2027-01-10', typeCode: 'U' });
  list = addAbsenceRange(list, { personId: 'P1', from: '2027-01-06', to: '2027-01-07', typeCode: 'K' });
  assert.equal(list.filter((a) => a.personId === 'P1').length, 7);
  assert.deepEqual(absenceRanges(list).map((r) => [r.personId, r.typeCode, r.from, r.to]), [['P1', 'U', '2027-01-04', '2027-01-05'],
    ['P1', 'K', '2027-01-06', '2027-01-07'], ['P1', 'U', '2027-01-08', '2027-01-10'], ['P2', 'U', '2027-01-06', '2027-01-06']]);
  // an end before the start enters nothing
  assert.deepEqual(addAbsenceRange([abs('P1', '2027-01-04')], { personId: 'P1', from: '2027-01-05', to: '2027-01-04', typeCode: 'U' }), [abs('P1', '2027-01-04')]);
});
