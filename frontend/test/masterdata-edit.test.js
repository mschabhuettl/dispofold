import { test } from 'node:test';
import assert from 'node:assert/strict';
import { messageText } from '../src/lib/codes.js';
import { conflictDecision, familyText, fromForm, inUseQuestion, itemLabel, keyOf, number, restrictionFromForm, restrictionText, time,
  toForm } from '../src/lib/masterdata-edit.js';

const person = { id: 'P1', firstName: 'Anna', lastName: 'Beispiel', shortName: 'ABE', weeklyHours: 40, areaIds: ['AN'], hoursAccount: 2.5,
  compRestBalance: 1, familyRules: [{ weekdays: [2, 4], latestEnd: '18:00:00', earliestStart: null, noNights: true, note: 'Kind' }],
  externalId: '18', entryDate: '2026-12-14', exitDate: null };

test('numbers and times from inputs', () => {
  assert.equal(number('38,5', 'x'), 38.5);
  assert.equal(number(' 0 ', 'x'), 0);
  assert.throws(() => number('', 'Wochenstunden'), /Wochenstunden/);
  assert.throws(() => number('abc', 'x'));
  assert.equal(time('6:30', 'x'), '06:30');
  assert.equal(time('19:00:00', 'x'), '19:00');
  assert.equal(time('', 'x'), null);
  assert.throws(() => time('25:00', 'Beginn'), /Beginn/);
  assert.throws(() => time('12.30', 'x'));
});

test('a person round-trips through the form; empty inputs become null', () => {
  const values = toForm('people', person);
  assert.equal(values.familyRules[0].latestEnd, '18:00');
  assert.deepEqual(values.familyRules[0].weekdays, ['2', '4']);
  const back = fromForm('people', values, person);
  assert.deepEqual(back, { ...person, familyRules: [{ ...person.familyRules[0], latestEnd: '18:00' }] });
  const changed = fromForm('people', { ...values, shortName: ' ', externalId: '', exitDate: '2027-06-30', weeklyHours: '30' }, person);
  assert.equal(changed.shortName, null);
  assert.equal(changed.externalId, null);
  assert.equal(changed.exitDate, '2027-06-30');
  assert.equal(changed.weeklyHours, 30);
  assert.throws(() => fromForm('people', { ...values, exitDate: '2026-01-01' }, person), /Austritt/);
  assert.throws(() => fromForm('people', { ...values, lastName: '' }, person), /Nachname/);
  assert.throws(() => fromForm('people', { ...values, familyRules: [{ weekdays: [], noNights: true }] }, person), /Wochentag/);
  // the usual distribution names an area by its name
  const mixValues = { ...values, areaIds: ['AN'], shiftMix: { categories: {}, areas: { AN: '120' } } };
  assert.throws(() => fromForm('people', mixValues, person, (id) => ({ AN: 'Notrufannahme' })[id]), (e) => e.message.includes('„Notrufannahme“') && !e.message.includes('„AN“'));
});

test('new people and shifts are sent without id, areas and absence types with their code', () => {
  const p = fromForm('people', { ...toForm('people', null), firstName: 'Neu', lastName: 'Person', weeklyHours: '20' });
  assert.equal('id' in p, false);
  assert.equal(p.hoursAccount, 0);
  assert.deepEqual(p.familyRules, []);
  const s = fromForm('shifts', { ...toForm('shifts', null), areaId: 'AN', code: 'F1', name: 'Früh', start: '06:30', end: '14:30' });
  assert.deepEqual(s, { areaId: 'AN', code: 'F1', name: 'Früh', start: '06:30', end: '14:30', category: null, color: null, unpopular: false });
  assert.deepEqual(fromForm('areas', { id: 'AN', name: 'Notrufannahme', color: '#0F766E' }), { id: 'AN', name: 'Notrufannahme', color: '#0F766E' });
  assert.deepEqual(fromForm('absenceTypes', { code: 'U', name: 'Urlaub', reducesTarget: true, chargesAccount: false, compensatoryRest: false, color: '' }),
    { code: 'U', name: 'Urlaub', reducesTarget: true, chargesAccount: false, compensatoryRest: false, color: null });
  const existing = { id: 'SN1', areaId: 'AN', code: 'F1', name: 'Früh', start: '06:30:00', end: '14:30:00', category: 'EARLY', color: '#fff', unpopular: false };
  assert.equal(fromForm('shifts', toForm('shifts', existing), existing).id, 'SN1');
});

test('demand: days or a date, max optional', () => {
  const base = { areaId: 'AN', from: '07:00', to: '19:00', min: '2', max: '' };
  assert.deepEqual(fromForm('demand', { ...base, mode: 'days', days: ['SUN', 'MON', 'HOL'], date: '2026-12-24' }),
    { areaId: 'AN', days: ['MON', 'SUN', 'HOL'], date: null, from: '07:00', to: '19:00', min: 2, max: null });
  assert.deepEqual(fromForm('demand', { ...base, mode: 'date', days: ['MON'], date: '2026-12-24', max: '3' }),
    { areaId: 'AN', days: null, date: '2026-12-24', from: '07:00', to: '19:00', min: 2, max: 3 });
  assert.throws(() => fromForm('demand', { ...base, mode: 'days', days: [] }), /Tag/);
  assert.throws(() => fromForm('demand', { ...base, mode: 'date', date: '' }), /Datum/);
  assert.throws(() => fromForm('demand', { ...base, mode: 'days', days: ['MON'], max: '1' }), /Höchst/);
  const rule = { areaId: 'TV', days: null, date: '2026-12-24', from: '07:00', to: '19:00', min: 1, max: null };
  assert.deepEqual(fromForm('demand', toForm('demand', rule), rule), rule);
  assert.equal(toForm('demand', rule).mode, 'date');
});

test('restrictions: exactly one of shift and category', () => {
  assert.deepEqual(restrictionFromForm('P1', { target: 'shift:AN-F', weekday: '0', kind: 'NEVER', validFrom: '', validTo: '' }),
    { personId: 'P1', shiftId: 'AN-F', category: null, weekday: 0, kind: 'NEVER', validFrom: null, validTo: null });
  assert.deepEqual(restrictionFromForm('P2', { target: 'category:NIGHT', weekday: '6', kind: 'ON_REQUEST', validFrom: '2026-12-14', validTo: '2027-03-31' }),
    { personId: 'P2', shiftId: null, category: 'NIGHT', weekday: 6, kind: 'ON_REQUEST', validFrom: '2026-12-14', validTo: '2027-03-31' });
  // an id with a colon stays whole
  assert.equal(restrictionFromForm('P1', { target: 'shift:A:B', weekday: '1' }).shiftId, 'A:B');
  assert.throws(() => restrictionFromForm('P1', { target: '', weekday: '0' }));
  assert.throws(() => restrictionFromForm('P1', { target: 'shift:X', validFrom: '2027-02-01', validTo: '2027-01-01' }), /Gültig/);
});

test('restriction and family rule texts', () => {
  const shifts = new Map([['AN-F', { code: 'F1', name: 'Früh' }]]);
  assert.equal(restrictionText({ shiftId: 'AN-F', category: null, weekday: 0, kind: 'NEVER', validFrom: null, validTo: null }, shifts), 'Nie: F1 Früh, jeden Tag');
  assert.equal(restrictionText({ shiftId: null, category: 'NIGHT', weekday: 1, kind: 'ON_REQUEST', validFrom: '2026-12-14', validTo: null }, shifts),
    'Nur auf Anfrage: alle Nacht-Schichten, Mo, ab 14.12.2026');
  assert.equal(restrictionText({ shiftId: 'GONE', weekday: 7, kind: 'NEVER', validTo: '2027-01-31' }, shifts), 'Nie: GONE, So, bis 31.01.2027');
  assert.equal(familyText(person.familyRules[0]), 'Di/Do: Ende bis 18:00, keine Nacht (Kind)');
});

test('keys, labels and the in-use question', () => {
  assert.equal(keyOf('people', person, 3), 'P1');
  assert.equal(keyOf('absenceTypes', { code: 'U' }, 0), 'U');
  assert.equal(keyOf('holidays', { date: '2026-12-25' }, 4), 4);
  assert.equal(itemLabel('holidays', { date: '2026-12-25', name: 'Christtag' }), 'Feiertag 25.12.2026');
  assert.equal(itemLabel('people', person), 'Person Anna Beispiel');
  assert.equal(itemLabel('demand', { areaId: 'AN', days: ['MON', 'HOL'], date: null, from: '07:00:00', to: '19:00' }), 'Bedarfsregel AN Mo, Feiertag 07:00-19:00');
  assert.equal(inUseQuestion('shifts', { code: 'N1' }, { kind: 'shifts', key: 'SN3', count: 4, months: '2027-01,2027-02' }),
    'Schicht N1 wird noch 4-mal verwendet (in Jänner 2027, Februar 2027). Mit allen Verweisen entfernen?');
  assert.equal(messageText({ code: 'masterdata.inUseClosed', params: { kind: 'people', key: 'P1', months: '2026-11,2026-12' } }),
    'Der Eintrag kommt in abgeschlossenen Monaten vor (November 2026, Dezember 2026) und kann nicht entfernt werden.');
  assert.equal(inUseQuestion('areas', { id: 'AN' }, { count: 1, months: '' }), 'Bereich AN wird noch 1-mal verwendet. Mit allen Verweisen entfernen?');
});

test('conflict: retry when the object is unchanged on the server, else let the user check', () => {
  const area = { id: 'AN', name: 'Notrufannahme', color: '#0F766E' };
  const fresh = { areas: [{ id: 'TV', name: 'Transport', color: null }, area], people: [person], restrictions: [] };
  assert.deepEqual(conflictDecision('areas', 'AN', area, fresh), { action: 'retry', key: 'AN' });
  assert.deepEqual(conflictDecision('areas', 'AN', { ...area, name: 'alt' }, fresh), { action: 'changed', key: 'AN' });
  assert.deepEqual(conflictDecision('areas', 'XX', { id: 'XX' }, fresh), { action: 'gone' });
  assert.deepEqual(conflictDecision('areas', null, null, fresh), { action: 'retry', key: null });
  assert.deepEqual(conflictDecision('people', 'P1', person, fresh), { action: 'retry', key: 'P1' });
  assert.deepEqual(conflictDecision('absenceTypes', 'U', { code: 'U' }, { absenceTypes: [{ code: 'U' }] }), { action: 'retry', key: 'U' });
});

test('conflict: holidays and demand are found again after the positions moved', () => {
  const xmas = { date: '2026-12-25', name: 'Christtag' };
  const stephen = { date: '2026-12-26', name: 'Stefanitag' };
  // read as [xmas, stephen]; someone inserted a holiday in front meanwhile
  const fresh = { holidays: [{ date: '2026-12-08', name: 'Mariä Empfängnis' }, xmas, stephen] };
  assert.deepEqual(conflictDecision('holidays', 1, stephen, fresh), { action: 'retry', key: 2 });
  assert.deepEqual(conflictDecision('holidays', 0, xmas, fresh), { action: 'retry', key: 1 });
  assert.deepEqual(conflictDecision('holidays', 1, xmas, fresh), { action: 'retry', key: 1 });
  // changed or removed: never write to whatever stands at the old position now
  assert.deepEqual(conflictDecision('holidays', 1, { ...stephen, name: 'alt' }, fresh), { action: 'gone' });
  assert.deepEqual(conflictDecision('demand', 0, { areaId: 'AN', min: 1 }, { demand: [] }), { action: 'gone' });
});

test('conflict: restrictions compare the list of the person', () => {
  const r = { personId: 'P1', shiftId: 'N1', category: null, weekday: 0, kind: 'NEVER', validFrom: null, validTo: null };
  const other = { ...r, personId: 'P2' };
  const fresh = { people: [person, { id: 'P2' }], restrictions: [other, r] };
  assert.deepEqual(conflictDecision('restrictions', 'P1', [r], fresh), { action: 'retry', key: 'P1' });
  assert.deepEqual(conflictDecision('restrictions', 'P1', [], fresh), { action: 'changed', key: 'P1' });
  assert.deepEqual(conflictDecision('restrictions', 'P9', [], fresh), { action: 'gone' });
});
