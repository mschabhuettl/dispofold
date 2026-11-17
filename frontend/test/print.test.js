import { test } from 'node:test';
import assert from 'node:assert/strict';
import { printHeader, printStatus } from '../src/lib/print.js';

test('header line of a printout: title, plan status and print date', () => {
  const now = new Date(2027, 0, 5);
  assert.equal(printHeader('Dienstplan Jänner 2027', 'SOLVED', now), 'Dienstplan Jänner 2027 · Status: Entwurf · gedruckt am 05.01.2027');
  assert.equal(printHeader('Plan nach Funktion Jänner 2027', 'CLOSED', now),
    'Plan nach Funktion Jänner 2027 · Status: abgeschlossen · gedruckt am 05.01.2027');
  for (const s of ['EMPTY', 'SOLVING', 'STOPPED', 'FAILED']) assert.equal(printStatus(s), 'Entwurf');
});
