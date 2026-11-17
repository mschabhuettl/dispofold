import { test } from 'node:test';
import assert from 'node:assert/strict';
import { anonymizedFiles, conflictText, fieldLabel, noteText, personLabel, shortNames } from '../src/lib/data-files.js';

const names = shortNames({ people: [{ id: 'P0001', shortName: 'ABC' }] }, { people: [{ id: 'P0001', shortName: 'XXX' }, { id: 'P0002', shortName: 'DEF' }] });

test('people by short name and id, the id alone when unknown', () => {
  assert.equal(personLabel('P0001', names), 'ABC (P0001)');
  assert.equal(personLabel('P0002', names), 'DEF (P0002)');
  assert.equal(personLabel('PN7', names), 'PN7');
  assert.equal(personLabel('P0001', shortNames(null)), 'P0001');
});

test('conflicts name the person or the area and the field in German', () => {
  assert.equal(conflictText({ personId: 'P0001', areaId: null, field: 'weeklyHours' }, names), 'ABC (P0001): Wochenstunden');
  assert.equal(conflictText({ personId: 'P0002', areaId: null, field: 'shiftMix' }, names), 'DEF (P0002): Gewohnte Verteilung');
  assert.equal(conflictText({ personId: null, areaId: 'LF', field: 'demand' }, names), 'Bedarf im Bereich LF');
  assert.equal(fieldLabel('unknownField'), 'unknownField');
});

test('notes render from their codes with person, field, kind and change in German', () => {
  assert.equal(noteText({ code: 'corrections.personAdded', params: { personId: 'P0002' } }, names), 'Person DEF (P0002) wurde in Dispofold angelegt.');
  assert.equal(noteText({ code: 'corrections.identity', params: { personId: 'P0001', field: 'shortName' } }, names), 'Person ABC (P0001): Kürzel wurde geändert.');
  assert.equal(noteText({ code: 'corrections.master', params: { kind: 'shift', key: 'AN-F', change: 'changed' } }, names), 'Schicht AN-F wurde geändert.');
  assert.equal(noteText({ code: 'corrections.master', params: { kind: 'holiday', key: '2027-01-06', change: 'added' } }, names), 'Feiertag 06.01.2027 wurde neu angelegt.');
  assert.equal(noteText({ code: 'corrections.idFormat', params: { count: 1 } }, names).startsWith('1 Person hat keine Id'), true);
});

test('the pseudonymised files: dataset and plan to share, the key separately', async () => {
  const files = anonymizedFiles({ dataset: { people: [{ id: 'P001' }] }, plan: { month: '2027-01' }, key: '﻿pseudonym;id\r\n' }, '2027-01');
  assert.deepEqual([files.dataset.name, files.plan.name, files.key.name], ['dispofold-datensatz-anonym.json', 'dienstplan-2027-01-anonym.json', 'schluessel.csv']);
  assert.deepEqual(JSON.parse(await files.dataset.blob.text()), { people: [{ id: 'P001' }] });
  assert.equal(await files.plan.blob.text(), '{\n "month": "2027-01"\n}\n');
  assert.doesNotMatch(await files.dataset.blob.text(), /pseudonym;id/);
  assert.equal(files.key.blob.type, 'text/csv;charset=utf-8');
});
