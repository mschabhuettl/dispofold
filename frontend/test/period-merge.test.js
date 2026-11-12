import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mergePeriod } from '../src/lib/period-merge.js';

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
