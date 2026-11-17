import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mixFromForm, mixRows, mixText, mixToForm, nightNote, sharePercent } from '../src/lib/shift-mix.js';
import { fromForm, toForm } from '../src/lib/masterdata-edit.js';

const empty = { categories: {}, areas: {} };

test('form values of a mix are percentages as text', () => {
  const form = mixToForm({ categories: { DAY: 0.9, NIGHT: 0.1 }, areas: { AN: 0.675, DR: 0.325 } }, ['AN', 'DR']);
  assert.deepEqual(form.categories, { EARLY: '', DAY: '90', LATE: '', NIGHT: '10' });
  assert.deepEqual(form.areas, { AN: '67,5', DR: '32,5' });
  assert.deepEqual(mixToForm(null, ['AN']), { categories: { EARLY: '', DAY: '', LATE: '', NIGHT: '' }, areas: { AN: '' } });
});

test('a mix is read back as shares with three decimals; nothing filled is null', () => {
  const form = mixToForm({ categories: { DAY: 0.9, NIGHT: 0.1 }, areas: { AN: 0.675, DR: 0.325 } }, ['AN', 'DR']);
  assert.deepEqual(mixFromForm(form, ['AN', 'DR']), { categories: { DAY: 0.9, NIGHT: 0.1 }, areas: { AN: 0.675, DR: 0.325 } });
  assert.equal(mixFromForm(mixToForm(null, ['AN']), ['AN']), null);
  assert.deepEqual(mixFromForm({ categories: { EARLY: '33,3', DAY: '33,3', LATE: '33,4' } }, []), { categories: { EARLY: 0.333, DAY: 0.333, LATE: 0.334 }, areas: {} });
  assert.deepEqual(mixFromForm({ categories: { DAY: '100' } }, []), { categories: { DAY: 1 }, areas: {} });
});

test('invalid mixes throw German messages', () => {
  assert.throws(() => mixFromForm({ categories: { DAY: '80', NIGHT: '10' } }, []), /Schichtarten.*90 %.*100 %/);
  assert.throws(() => mixFromForm({ categories: { DAY: '110' } }, []), /„Tag“.*0 und 100/);
  assert.throws(() => mixFromForm({ categories: { DAY: '-5', NIGHT: '105' } }, []), /0 und 100/);
  assert.throws(() => mixFromForm({ categories: { DAY: 'viel' } }, []), /0 und 100/);
  assert.throws(() => mixFromForm({ areas: { AN: '50' } }, ['AN', 'DR']), /Bereiche/);
});

test('only the ticked areas count', () => {
  assert.deepEqual(mixFromForm({ areas: { AN: '100', DR: '40' } }, ['AN']), { categories: {}, areas: { AN: 1 } });
});

test('the person form carries the mix and clearing sets null', () => {
  const person = { id: 'P1', firstName: 'A', lastName: 'B', weeklyHours: 40, areaIds: ['AN', 'DR'], hoursAccount: 0, compRestBalance: 0,
    familyRules: [], shiftMix: { categories: { DAY: 0.9, NIGHT: 0.1 }, areas: {} } };
  const values = toForm('people', person);
  assert.deepEqual(fromForm('people', values, person).shiftMix, { categories: { DAY: 0.9, NIGHT: 0.1 }, areas: {} });
  assert.equal(fromForm('people', { ...values, shiftMix: mixToForm(null, ['AN']) }, person).shiftMix, null);
  const plain = { ...person };
  delete plain.shiftMix;
  assert.equal('shiftMix' in fromForm('people', toForm('people', plain), plain), false);
});

test('texts and rows', () => {
  assert.equal(mixText({ categories: { DAY: 0.9, NIGHT: 0.1 }, areas: {} }), 'Tag 90 %, Nacht 10 %');
  assert.equal(mixText({ categories: { DAY: 1 }, areas: { AN: 0.675, DR: 0.325 } }, (id) => id), 'Tag 100 %, AN 67,5 %, DR 32,5 %');
  assert.equal(mixText(null), '');
  assert.equal(mixText(empty), '');
  assert.equal(sharePercent(0.1), '10 %');
  assert.equal(sharePercent(null), '');
  assert.deepEqual(mixRows({ categories: { DAY: 0.8, LATE: 0.2 }, areas: { AN: 1 } }, { categories: { DAY: 0.9, NIGHT: 0.1 }, areas: {} }, (id) => `B ${id}`),
    [{ group: 'categories', key: 'DAY', name: 'Tag', planned: 0.8, usual: 0.9 },
      { group: 'categories', key: 'LATE', name: 'Spät', planned: 0.2, usual: null },
      { group: 'categories', key: 'NIGHT', name: 'Nacht', planned: null, usual: 0.1 },
      { group: 'areas', key: 'AN', name: 'B AN', planned: 1, usual: null }]);
  assert.deepEqual(mixRows(null, null), []);
});

test('the note on nights follows the night distribution; rules saved before it mean HABIT', () => {
  assert.match(nightNote('HABIT'), /nicht im Nachtausgleich/);
  assert.equal(nightNote(undefined), nightNote('HABIT'));
  assert.equal(nightNote(null), nightNote('HABIT'));
  assert.match(nightNote('EVEN'), /Nachtanteil wird nicht beachtet/);
});
