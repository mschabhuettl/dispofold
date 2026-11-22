import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compareValues, matchesTerms, searchTerms, sortRows } from '../src/lib/table.js';

test('cells compare as numbers when both are numbers, else in German order with digit runs as numbers', () => {
  assert.ok(compareValues('9', '10') < 0);
  assert.ok(compareValues('38.5', '4') > 0);
  assert.ok(compareValues('-1', '0') < 0);
  assert.ok(compareValues(2, '10') < 0);
  assert.ok(compareValues('E9', 'E10') < 0);
  assert.ok(compareValues('Österle', 'Pichler') < 0);
  assert.ok(compareValues('äpfel', 'Birnen') < 0);
  assert.equal(compareValues('', null), 0);
  assert.ok(compareValues('', 'A') > 0);
  assert.ok(compareValues('A', undefined) < 0);
});

test('rows sort stably both ways with empty values last', () => {
  const rows = [{ id: 'E10', n: '' }, { id: 'E9', n: '3' }, { id: 'E100', n: '12' }, { id: 'E2', n: '3' }];
  assert.deepEqual(sortRows(rows, (r) => r.id).map((r) => r.id), ['E2', 'E9', 'E10', 'E100']);
  assert.deepEqual(sortRows(rows, (r) => r.id, false).map((r) => r.id), ['E100', 'E10', 'E9', 'E2']);
  assert.deepEqual(sortRows(rows, (r) => r.n).map((r) => r.id), ['E9', 'E2', 'E100', 'E10']);
  assert.deepEqual(sortRows(rows, (r) => r.n, false).map((r) => r.id), ['E100', 'E9', 'E2', 'E10']);
  assert.deepEqual(rows.map((r) => r.id), ['E10', 'E9', 'E100', 'E2'], 'the input stays as it was');
});

test('a search needs every word, in any order and case', () => {
  assert.deepEqual(searchTerms('  Früh   AN '), ['früh', 'an']);
  assert.deepEqual(searchTerms(''), []);
  assert.ok(matchesTerms('Schicht A1 Annahme früh', searchTerms('früh a1')));
  assert.ok(!matchesTerms('Schicht A1 Annahme früh', searchTerms('früh R1')));
  assert.ok(matchesTerms('irgendwas', []));
});
