// The logic of the shipped import plugin's pages (plugins/sp5/frontend/model.js): report groups and filters, the texts
// of the comparison and the editing of the mapping.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { register } from '../src/lib/plugins.js';
import plugin from '../../plugins/sp5/frontend/index.js';
import * as m from '../../plugins/sp5/frontend/model.js';

register(plugin);

test('report rows are grouped by type in page order, each with the columns it fills, unknown ones kept', () => {
  const columns = ['pruefung', 'person', 'bereich', 'schicht', 'dienste_historie', 'grund', 'extra'];
  const rows = [
    { pruefung: 'neu', schicht: 'A1', extra: 'x' },
    { pruefung: 'schicht', person: 'E2', bereich: 'AN', schicht: 'A1', dienste_historie: '3' },
    { pruefung: 'bereich', person: 'E1', bereich: 'AN', dienste_historie: '4', grund: 'unerwartet' },
    { pruefung: 'schicht', person: 'E1', bereich: 'AN', schicht: 'A2', dienste_historie: '' },
  ];
  const groups = m.reportGroups(columns, rows);
  assert.deepEqual(groups.map((g) => [g.type, g.columns, g.rows.length]), [
    ['bereich', ['person', 'bereich', 'dienste_historie', 'grund'], 1],
    ['schicht', ['person', 'bereich', 'schicht', 'dienste_historie'], 2],
    ['neu', ['schicht', 'extra'], 1],
  ]);
  assert.equal(m.typeLabel('korrektur'), 'Korrekturen');
  assert.equal(m.typeLabel('neu'), 'neu');
  assert.match(m.typeHelp('neu'), /nicht kennt/);
  assert.equal(m.columnLabel('spitze_historie_max'), 'Spitze Historie (höchste)');
  assert.equal(m.columnLabel('extra'), 'extra');
});

test('report values in German, filters by type, person and area, a search over raw and German values', () => {
  assert.equal(m.cellText('tagestyp', 'MON'), 'Mo');
  assert.equal(m.cellText('tagestyp', 'HOL'), 'Feiertag');
  assert.equal(m.cellText('einschraenkung', 'zu_kurze_historie'), 'zu kurze Historie');
  assert.equal(m.cellText('status', 'gleich_quelle'), 'gleich wie die Quelle');
  assert.equal(m.cellText('status', 'neu'), 'neu');
  assert.equal(m.cellText('feld', 'demand:AN'), 'Bedarf AN');
  assert.equal(m.cellText('feld', 'weeklyHours'), 'Wochenstunden');
  const row = { pruefung: 'korrektur', person: 'E1', feld: 'demand:DR', status: 'ungueltig' };
  assert.deepEqual(m.rowAreas(row), ['DR']);
  assert.deepEqual(m.rowAreas({ bereiche: 'AN DR', bereich: 'TV' }), ['TV', 'AN', 'DR']);
  assert.ok(m.rowMatches(row, { type: 'korrektur', person: 'E1', area: 'DR' }));
  assert.ok(!m.rowMatches(row, { area: 'AN' }));
  assert.ok(!m.rowMatches(row, { person: 'E2' }));
  assert.ok(m.rowMatches(row, { terms: ['ungültig', 'e1'] }), 'German status');
  assert.ok(m.rowMatches(row, { terms: ['ungueltig'] }), 'raw status');
  assert.ok(m.rowMatches(row, { terms: ['korrekturen'] }), 'the type name');
  assert.ok(!m.rowMatches(row, { terms: ['ungültig', 'e2'] }));
  assert.equal(m.warningKind('Schicht ohne Bereich ignoriert: X'), 'Schicht');
  assert.equal(m.warningKind('  Bedarf: x'), 'Bedarf');
  assert.ok(m.NOTICE_STATUSES.has('person_fehlt') && m.NOTICE_STATUSES.has('ungueltig') && !m.NOTICE_STATUSES.has('angewendet'));
});

test('comparison: natural id order, differences, field values, restrictions', () => {
  assert.deepEqual(m.byId([{ id: 'E10' }, { id: 'E9' }, { id: 'E100' }, { id: 'E9', name: 'A' }]).map((x) => x.id + (x.name ?? '')),
    ['E9A', 'E9', 'E10', 'E100']);
  assert.equal(m.difference(3, 5), '+2');
  assert.equal(m.difference(12, 5), '−7');
  assert.equal(m.difference(4, 4), '0');
  assert.equal(m.fieldValue('exitDate', null), 'offen');
  assert.equal(m.fieldValue('exitDate', '2027-06-30'), '30.06.2027');
  assert.equal(m.fieldValue('weeklyHours', 38.5), '38,5');
  assert.equal(m.fieldValue('areaIds', ['AN', 'DR'], (id) => ({ AN: 'Annahme' })[id] ?? id), 'Annahme, DR');
  assert.equal(m.fieldValue('areaIds', []), 'offen');
  assert.equal(m.fieldValue('shiftMix', { categories: { EARLY: 0.6, LATE: 0.4 }, areas: {} }), 'Früh 60 %, Spät 40 %');
  assert.equal(m.fieldValue('shiftMix', { categories: {}, areas: {} }), 'offen');
  assert.equal(m.fieldValue('x', true), 'ja');
  assert.equal(m.fieldName('weeklyHours'), 'Wochenstunden');
  assert.equal(m.fieldName('excludedFrom'), 'Nicht mehr übernommen ab');
  assert.equal(m.fieldName('neuesFeld'), 'neuesFeld');
  assert.equal(m.sectionName('previousDuties'), 'Dienste davor');
  assert.equal(m.sectionName('neu'), 'neu');
  const r = { personId: 'E1', shiftId: 'S12', category: null, weekday: 1, kind: 'ON_REQUEST', validFrom: '2027-01-01', validTo: '2027-03-31' };
  const shifts = new Map([['S12', { id: 'S12', code: 'A1', name: 'Annahme früh' }]]);
  assert.deepEqual(m.restrictionLines({ field: 'restrictions', added: [r], removed: [{ ...r, shiftId: null, category: 'NIGHT', weekday: 0, kind: 'NEVER', validFrom: null, validTo: null }] }, shifts),
    ['+ Nur auf Anfrage: A1 Annahme früh, Mo, 01.01.2027 bis 31.03.2027', '− Nie: alle Nacht-Schichten, jeden Tag']);
  assert.deepEqual(m.restrictionLines({ field: 'restrictions', added: [{ ...r, validFrom: null, validTo: null }], removed: [] }), ['+ Nur auf Anfrage: S12, Mo']);
});

test('demand labels and staffing, an empty max being the minimum', () => {
  assert.equal(m.daysLabel(['MON', 'TUE', 'WED', 'THU', 'FRI']), 'Mo–Fr');
  assert.equal(m.daysLabel(['SAT', 'SUN', 'HOL']), 'Sa, So, Feiertag');
  assert.equal(m.daysLabel(['MON', 'WED', 'THU', 'FRI', 'SUN']), 'Mo, Mi–Fr, So');
  assert.equal(m.demandLabel({ areaId: 'AN', days: ['MON', 'TUE', 'WED', 'THU', 'FRI'], date: null, from: '07:00', to: '19:00' }), 'AN Mo–Fr 07:00–19:00');
  assert.equal(m.demandLabel({ areaId: 'DR', days: null, date: '2027-01-06', from: '00:00:00', to: '06:00' }), 'DR 06.01.2027 00:00–06:00');
  assert.equal(m.demandValues([{ min: 1, max: 2 }]), '1–2');
  assert.equal(m.demandValues([{ min: 2, max: null }]), '2');
  assert.equal(m.demandValues([{ min: 2, max: 2 }, { min: 0, max: 1 }]), '2, 0–1');
  assert.equal(m.demandValues([]), '–');
});

test('mapping editor: new entries, moving, optional keys, areas of a group in area order, the file', () => {
  const mapping = { areas: [{ id: 'AN', name: 'A', color: '#E8E8E8' }], extra: 1 };
  m.sectionList(mapping, 'shifts').push(m.newEntry('shifts'));
  m.sectionList(mapping, 'ignoreShifts').push(m.newEntry('ignoreShifts'));
  assert.deepEqual(Object.keys(mapping), ['areas', 'extra', 'shifts', 'ignoreShifts']);
  assert.deepEqual(mapping.shifts, [{ match: '', areaId: '' }]);
  assert.deepEqual(m.newEntry('areas'), { id: '', name: '', color: '#E8E8E8' });
  assert.deepEqual(mapping.ignoreShifts, ['']);
  const list = ['a', 'b', 'c'];
  assert.equal(m.moveEntry(list, 2, -1), 1);
  assert.deepEqual(list, ['a', 'c', 'b']);
  assert.equal(m.moveEntry(list, 0, -1), 0);
  assert.equal(m.moveEntry(list, 2, 1), 2);
  assert.deepEqual(list, ['a', 'c', 'b']);
  const rule = { match: 'A*', areaId: 'AN', category: 'EARLY', note: 1 };
  m.setOptional(rule, 'category', '');
  m.setOptional(rule, 'unpopular', false);
  assert.deepEqual(rule, { match: 'A*', areaId: 'AN', note: 1, unpopular: false });
  const group = { match: 'G', areaIds: ['XX', 'DR'] };
  m.toggleArea(group, 'AN', true, ['AN', 'DR']);
  assert.deepEqual(group.areaIds, ['AN', 'DR', 'XX']);
  m.toggleArea(group, 'DR', false, ['AN', 'DR']);
  assert.deepEqual(group.areaIds, ['AN', 'XX']);
  assert.equal(m.mappingJson({ b: 1, a: [2] }), '{\n  "b": 1,\n  "a": [\n    2\n  ]\n}\n');
});

test('codes of the folder: result texts, the hits of each rule and pattern, codes without a rule', () => {
  const codes = {
    shifts: [{ code: 'A1', result: { kind: 'rule', index: 0, areaId: 'AN', category: 'EARLY' } }, { code: 'A2', result: { kind: 'rule', index: 0, areaId: 'AN', category: null } },
      { code: 'X1', result: { kind: 'ignored', index: 1 } }, { code: 'T1', result: { kind: 'none' } }],
    groups: [{ name: 'G1', result: { kind: 'rule', index: 1, areaIds: ['AN', 'DR'] } }, { name: 'G2', result: { kind: 'excluded', index: 0 } },
      { name: 'G3', result: { kind: 'ignored', index: 0 } }, { name: 'G4', result: { kind: 'none' } }],
    absenceTypes: [{ code: 'ER', result: { kind: 'compensatoryRest', index: 0 } }, { code: 'UR', result: { kind: 'none' } }],
  };
  assert.equal(m.resultText('shifts', codes.shifts[0].result), 'Regel 1 → AN, Früh');
  assert.equal(m.resultText('shifts', codes.shifts[1].result), 'Regel 1 → AN');
  assert.equal(m.resultText('shifts', codes.shifts[2].result), 'ignoriert (Ignorierte Schichten, Muster 2)');
  assert.equal(m.resultText('shifts', codes.shifts[3].result), 'keine Regel');
  assert.equal(m.resultText('groups', codes.groups[0].result), 'Regel 2 → AN, DR');
  assert.equal(m.resultText('groups', codes.groups[1].result), 'ausgeschlossen (Ausgeschlossene Gruppen, Muster 1)');
  assert.equal(m.resultText('groups', codes.groups[2].result), 'ignoriert (Ignorierte Gruppen, Muster 1)');
  assert.equal(m.resultText('absenceTypes', codes.absenceTypes[0].result), 'Ersatzruhe (Muster 1)');
  assert.equal(m.resultText('absenceTypes', codes.absenceTypes[1].result), 'übernommen');
  const hits = m.hitsBySection(codes);
  assert.deepEqual(hits.shifts.get(0), ['A1', 'A2']);
  assert.deepEqual(hits.ignoreShifts.get(1), ['X1']);
  assert.equal(hits.ignoreShifts.get(0), undefined);
  assert.deepEqual(hits.groups.get(1), ['G1']);
  assert.deepEqual(hits.excludeGroups.get(0), ['G2']);
  assert.deepEqual(hits.ignoreGroups.get(0), ['G3']);
  assert.deepEqual(hits.compensatoryRestAbsences.get(0), ['ER']);
  assert.equal(hits.ignoreAbsences.size, 0);
  assert.deepEqual(m.unmatched(codes), { shifts: ['T1'], groups: ['G4'] });
  assert.deepEqual(m.unmatched(null), { shifts: [], groups: [] });
});
