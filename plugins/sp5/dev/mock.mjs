// The plugin's routes for the dev server (frontend/scripts/dev-server.mjs with MOCK_PLUGINS=1 or MOCK_TEST_PLUGINS=1),
// as plugins/sp5/backend answers them (Sp5Resource), with invented data: no folder is read. Ids P0001 and up are
// people of the dev server's dataset (the person links), E1001 and up are not in it. The mapping's patterns are
// evaluated here roughly as the plugin does (fnmatch, "re:" from the start); the front end never evaluates them.

const OPTIONS = { historyMonths: 12, areasFromHistory: null, shiftsFromHistory: null, demand: 'history', mixMinDuties: 10,
  shiftMix: true, hiddenShiftsMonths: 3, recentMonths: 3 };

export const MAPPING = {
  areas: [{ id: 'AN', name: 'Annahme', color: '#D7E8F5' }, { id: 'DR', name: 'Disposition', color: '#F5E1C8' }],
  shifts: [{ match: 'A*', areaId: 'AN' }, { match: 'R*', areaId: 'DR', category: 'DAY' }, { match: 're:^N[0-9]$', areaId: 'DR', unpopular: true }],
  ignoreShifts: ['X*', 'Q9'],
  groups: [{ match: 'Team Annahme', areaIds: ['AN'] }, { match: 'Team Dispo*', areaIds: ['DR'] }],
  ignoreGroups: ['Alle'],
  excludeGroups: ['Extern*'],
  compensatoryRestAbsences: ['ER'],
  ignoreAbsences: ['FB'],
};

// the tables of the folder
const FOLDER = {
  shifts: [['11', 'A1', 'Annahme früh', false], ['12', 'A2', 'Annahme spät', false], ['21', 'R1', 'Disposition Tag', false],
    ['22', 'N1', 'Nacht Reserve', true], ['31', 'X1', 'Schulung', false], ['41', 'T1', 'Sonderdienst', false]]
    .map(([sp5Id, code, name, hidden]) => ({ sp5Id, code, name, hidden })),
  groups: [['4', 'Team Annahme', 12], ['5', 'Team Dispo Nord', 8], ['6', 'Alle', 40], ['7', 'Extern Partner', 3], ['8', 'Projekt', 2]]
    .map(([sp5Id, name, members]) => ({ sp5Id, name, members })),
  absenceTypes: [['1', 'UR', 'Urlaub'], ['3', 'ER', 'Ersatzruhe'], ['5', 'FB', 'Fortbildung']].map(([sp5Id, code, name]) => ({ sp5Id, code, name })),
};

const COLUMNS = ['pruefung', 'person', 'bereich', 'schicht', 'dienste_historie', 'bereich_aus_gruppen', 'einschraenkung', 'tagestyp',
  'spitze_historie', 'spitze_sp5', 'dienste_nicht_uebernommen', 'stunden_historie', 'spitze_historie_max', 'wochenstunden', 'bereiche',
  'mix_kategorien', 'mix_bereiche', 'auf_anfrage', 'grund', 'sp5_schicht', 'calcbase', 'hrsweek', 'wochenstunden_calcbase', 'feld',
  'status', 'wert_export', 'wert_korrektur', 'dienste_nicht_uebernommen_gezaehlt', 'letzter_dienst'];

const ROWS = [
  { pruefung: 'bedarf', bereich: 'AN', tagestyp: 'MON', dienste_historie: '120', spitze_historie: '4', spitze_historie_max: '5', spitze_sp5: '4',
    dienste_nicht_uebernommen: '2', dienste_nicht_uebernommen_gezaehlt: '1' },
  { pruefung: 'bedarf', bereich: 'AN', tagestyp: 'SAT', dienste_historie: '40', spitze_historie: '2', spitze_historie_max: '3', spitze_sp5: '2' },
  { pruefung: 'bedarf', bereich: 'DR', tagestyp: 'HOL', dienste_historie: '9', spitze_historie: '1', spitze_historie_max: '1' },
  { pruefung: 'bereich', person: 'P0001', bereich: 'AN', dienste_historie: '48', bereich_aus_gruppen: 'ja' },
  { pruefung: 'bereich', person: 'P0002', bereich: 'DR', dienste_historie: '3', bereich_aus_gruppen: 'nein' },
  { pruefung: 'bereich', person: 'E1001', bereich: 'AN', dienste_historie: '12', bereich_aus_gruppen: 'ja' },
  { pruefung: 'schicht', person: 'P0001', bereich: 'AN', schicht: 'A1', dienste_historie: '30' },
  { pruefung: 'schicht', person: 'P0001', bereich: 'AN', schicht: 'A2', dienste_historie: '2', einschraenkung: 'auf_anfrage' },
  { pruefung: 'schicht', person: 'E1001', bereich: 'DR', schicht: 'R1', dienste_historie: '0', einschraenkung: 'zu_kurze_historie' },
  { pruefung: 'schicht', person: 'P0002', bereich: 'DR', schicht: 'N1', dienste_historie: '1', einschraenkung: 'sp5' },
  { pruefung: 'person', person: 'P0001', dienste_historie: '50', wochenstunden: '38.5', bereiche: 'AN', mix_kategorien: 'EARLY:0.6 LATE:0.4',
    mix_bereiche: 'AN:1.0', auf_anfrage: '1' },
  { pruefung: 'person', person: 'P0002', dienste_historie: '9', wochenstunden: '40.0', bereiche: 'DR', auf_anfrage: '1' },
  { pruefung: 'person', person: 'E1001', dienste_historie: '12', wochenstunden: '20.0', bereiche: 'AN DR' },
  { pruefung: 'nicht_zugeordnet', schicht: 'T1', dienste_historie: '14', stunden_historie: '112.0' },
  { pruefung: 'ignoriert', schicht: 'X1', dienste_historie: '6', stunden_historie: '48.0' },
  { pruefung: 'verworfen', schicht: 'A1', dienste_historie: '3', stunden_historie: '24.0', grund: 'Person nicht im Datensatz', sp5_schicht: '11' },
  { pruefung: 'wochenstunden', person: 'E1002', calcbase: '1', hrsweek: '40', wochenstunden_calcbase: '38.5', wochenstunden: '38.5' },
  { pruefung: 'korrektur', person: 'P0001', feld: 'weeklyHours', status: 'angewendet', wert_export: '40.0', wert_korrektur: '38.5' },
  { pruefung: 'korrektur', feld: 'demand:AN', status: 'gleich_quelle', wert_export: '[...]', wert_korrektur: '[...]' },
  { pruefung: 'korrektur', person: 'E1003', feld: 'exclude', status: 'ausgeschlossen', wert_korrektur: 'true' },
  { pruefung: 'korrektur', person: 'E1999', feld: 'weeklyHours', status: 'person_fehlt', wert_korrektur: '30.0' },
  { pruefung: 'korrektur', person: 'P0002', feld: 'areaIds', status: 'ungueltig', grund: 'kein Bereich aus areas: XX', wert_korrektur: '["XX"]' },
  { pruefung: 'ausgeblendet', schicht: 'N1', dienste_historie: '4', stunden_historie: '40.0', sp5_schicht: '22', status: 'uebernommen',
    letzter_dienst: '2026-12-20' },
  { pruefung: 'ausgeblendet', schicht: 'Q9', dienste_historie: '1', stunden_historie: '8.0', sp5_schicht: '23', status: 'verworfen',
    grund: 'keine Dienste in den letzten 3 Monaten', letzter_dienst: '2026-02-01' },
  { pruefung: 'nicht_mehr', person: 'P0002', bereich: 'AN', dienste_historie: '5', einschraenkung: 'auf_anfrage', letzter_dienst: '2026-06-30' },
  { pruefung: 'sonderfall', schicht: 'A1', grund: 'eine Zeilenart, die die Seite nicht kennt' },
];

const WARNINGS = [
  'Schicht ohne Bereich ignoriert: SP5-Id 41, Kürzel \'T1\'',
  'Gruppe ohne Zuordnung: SP5-Id 8, Kürzel \'Projekt\'',
  'Mitarbeiter E1002 hat keine Wochenstunden (CALCBASE 1)',
  'Bereich AN: 12 Mitarbeiter mit Diensten in den 12 Monaten davor',
  'Bedarf im Bereich AN, Spitze je Tagestyp aus der Historie/aus SP5: MON 4/4, SAT 2/2',
  'Stundenkonto und offene Ersatzruhetage werden aus SP5 nicht übernommen (0)',
];

const restriction = (r) => ({ personId: 'P0001', shiftId: null, category: null, weekday: 0, kind: 'NEVER', validFrom: null, validTo: null, ...r });

/** The comparison of the run with the import or a file; without the corrections the hours of P0001 do not change. */
function comparison(against, corrections) {
  return {
    against, corrections,
    counts: [['people', 150, 151], ['areas', 2, 2], ['shifts', 5, 6], ['demand', 17, 18], ['holidays', 13, 13], ['absenceTypes', 3, 3],
      ['restrictions', 40, 41], ['history', 900, 912], ['duties', 812, corrections ? 810 : 809], ['previousDuties', 40, 40],
      ['absences', 300, 300], ['wishes', 0, 0]].map(([section, old, now]) => ({ section, old, new: now })),
    people: {
      added: [{ id: 'E10', name: 'Neumann Nora' }, { id: 'E9', name: 'Auer Anton' }, { id: 'E100', name: 'Berger Berta' }],
      removed: [{ id: 'P0150', name: 'Weg Walter' }],
      changed: [
        { id: 'P0001', name: 'Erlenhuber Waltraud', fields: [
          ...(corrections ? [{ field: 'weeklyHours', old: 40.0, new: 38.5 }] : []),
          { field: 'shiftMix', old: { categories: { EARLY: 0.5, LATE: 0.5 }, areas: {} }, new: { categories: { EARLY: 0.6, LATE: 0.4 }, areas: { AN: 1 } } },
          { field: 'exitDate', old: null, new: '2027-06-30' },
          { field: 'restrictions', added: [restriction({ shiftId: 'AN-F', weekday: 1, kind: 'ON_REQUEST', validFrom: '2027-01-01', validTo: '2027-03-31' })],
            removed: [restriction({ category: 'NIGHT' })] }] },
        { id: 'E1001', name: 'Beispiel Berta', fields: [{ field: 'areaIds', old: ['AN'], new: ['AN', 'DR'] }] },
      ],
    },
    duties: [{ month: '2027-01', areaId: 'AN', old: 120, new: corrections ? 118 : 117 }, { month: '2027-01', areaId: null, old: 3, new: 0 }],
    demand: [
      { areaId: 'AN', days: ['MON', 'TUE', 'WED', 'THU', 'FRI'], date: null, from: '07:00', to: '19:00', old: [{ min: 1, max: 2 }], new: [{ min: 2, max: null }] },
      { areaId: 'DR', days: null, date: '2027-01-06', from: '00:00', to: '00:00', old: [], new: [{ min: 1, max: 1 }] },
    ],
  };
}

// ---- the mapping: checked and its patterns matched roughly as the plugin does ----

const LISTS = ['ignoreShifts', 'ignoreGroups', 'excludeGroups', 'compensatoryRestAbsences', 'ignoreAbsences'];

function regex(pattern) {
  if (pattern.startsWith('re:')) return new RegExp(`^(?:${pattern.slice(3)})`);
  return new RegExp(`^${pattern.replace(/[.+^${}()|\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\?/g, '.')}$`);
}
function matches(pattern, value) {
  try { return typeof pattern === 'string' && pattern !== '' && regex(pattern).test(value); } catch { return false; }
}
const object = (x) => x && typeof x === 'object' && !Array.isArray(x);

export function mappingErrors(m) {
  if (!object(m)) return ['die Zuordnung muss ein JSON-Objekt sein'];
  const errors = [];
  const known = ['areas', 'shifts', 'groups', 'ignoreShiftsAsAbsence', ...LISTS];
  for (const k of Object.keys(m).filter((x) => !known.includes(x)).sort()) errors.push(`unbekannter Schlüssel '${k}'`);
  for (const k of ['areas', 'shifts', 'groups']) if (!(k in m)) errors.push(`Pflichtschlüssel '${k}' fehlt`);
  for (const k of ['areas', 'shifts', 'groups', ...LISTS]) if (k in m && !Array.isArray(m[k])) errors.push(`'${k}' muss eine Liste sein`);
  if ('ignoreShiftsAsAbsence' in m && typeof m.ignoreShiftsAsAbsence !== 'boolean') errors.push("'ignoreShiftsAsAbsence' muss true oder false sein");
  const list = (k) => (Array.isArray(m[k]) ? m[k] : []);
  if (Array.isArray(m.areas) && !m.areas.length) errors.push("'areas' ist leer");
  const ids = new Set();
  list('areas').forEach((a, i) => {
    const where = `areas, Eintrag ${i + 1}`;
    if (!object(a)) return errors.push(`${where}: muss ein Objekt sein`);
    if (typeof a.id !== 'string' || !a.id.trim()) errors.push(`${where}: 'id' fehlt oder ist kein Text`);
    else if (ids.has(a.id)) errors.push(`${where}: Bereich '${a.id}' doppelt`);
    else ids.add(a.id);
    for (const k of ['name', 'color']) if (typeof a[k] !== 'string') errors.push(`${where}: '${k}' fehlt oder ist kein Text`);
    return undefined;
  });
  const pattern = (where, p) => {
    if (typeof p !== 'string' || !p) return errors.push(`${where} ist kein Text`);
    if (p.startsWith('re:')) {
      try { regex(p); } catch (e) { errors.push(`${where} ist kein gültiger regulärer Ausdruck (${e.message.split(': ').at(-1)})`); }
    }
    return undefined;
  };
  for (const k of ['shifts', 'groups']) {
    list(k).forEach((r, i) => {
      const where = `${k}, Eintrag ${i + 1}`;
      if (!object(r)) return errors.push(`${where}: muss ein Objekt sein`);
      pattern(`${where}: 'match'`, r.match);
      if (k === 'shifts') {
        if (!ids.has(r.areaId)) errors.push(`${where}: 'areaId' '${r.areaId}' ist kein Bereich aus 'areas'`);
        if ('category' in r && !['EARLY', 'DAY', 'LATE', 'NIGHT'].includes(r.category)) errors.push(`${where}: 'category' muss EARLY, DAY, LATE, NIGHT sein`);
        if ('unpopular' in r && typeof r.unpopular !== 'boolean') errors.push(`${where}: 'unpopular' muss true oder false sein`);
      } else if (!Array.isArray(r.areaIds) || !r.areaIds.length) errors.push(`${where}: 'areaIds' muss eine nicht leere Liste sein`);
      else for (const a of r.areaIds) if (!ids.has(a)) errors.push(`${where}: 'areaIds' enthält '${a}', keinen Bereich aus 'areas'`);
      return undefined;
    });
  }
  for (const k of LISTS) list(k).forEach((p, i) => pattern(`${k}, Eintrag ${i + 1}`, p));
  return errors;
}

function codes(m) {
  const list = (k) => (object(m) && Array.isArray(m[k]) ? m[k] : []);
  const rule = (k, value) => list(k).findIndex((r) => object(r) && matches(r.match, value));
  const pattern = (k, value) => list(k).findIndex((p) => matches(p, value));
  return {
    errors: mappingErrors(m),
    shifts: FOLDER.shifts.map((s) => {
      const ignored = pattern('ignoreShifts', s.code);
      const r = rule('shifts', s.code);
      const result = ignored >= 0 ? { kind: 'ignored', index: ignored }
        : r >= 0 ? { kind: 'rule', index: r, areaId: list('shifts')[r].areaId ?? null, category: list('shifts')[r].category ?? null } : { kind: 'none' };
      return { ...s, result };
    }),
    groups: FOLDER.groups.map((g) => {
      const excluded = pattern('excludeGroups', g.name);
      const r = rule('groups', g.name);
      const ignored = pattern('ignoreGroups', g.name);
      const result = excluded >= 0 ? { kind: 'excluded', index: excluded } : r >= 0 ? { kind: 'rule', index: r, areaIds: list('groups')[r].areaIds ?? [] }
        : ignored >= 0 ? { kind: 'ignored', index: ignored } : { kind: 'none' };
      return { ...g, result };
    }),
    absenceTypes: FOLDER.absenceTypes.map((a) => {
      const rest = pattern('compensatoryRestAbsences', a.code);
      const ignored = pattern('ignoreAbsences', a.code);
      return { ...a, result: rest >= 0 ? { kind: 'compensatoryRest', index: rest } : ignored >= 0 ? { kind: 'ignored', index: ignored } : { kind: 'none' } };
    }),
  };
}

// ---- answers ----

const ok = (body) => ({ status: 200, body });
const error = (status, code, params, text) => ({ status, body: { code, params, error: text } });
const integer = (v, min) => Number.isInteger(v) && v >= min;

/** The options of a request merged with the settings and checked as the settings are; an error answer or the options. */
function options(saved, given) {
  if (given != null && !object(given)) return { refused: error(400, 'plugin.sp5.setting', { field: 'options' }, 'Einstellung ungültig: options') };
  const o = Object.fromEntries(Object.keys(OPTIONS).map((k) => [k, given && k in given ? given[k] : saved[k]]));
  const bad = !integer(o.historyMonths, 0) || o.historyMonths > 24 ? 'historyMonths'
    : ['areasFromHistory', 'shiftsFromHistory'].find((k) => o[k] !== null && !integer(o[k], 1))
      ?? (!['history', 'sp5'].includes(o.demand) ? 'demand' : !integer(o.mixMinDuties, 1) ? 'mixMinDuties'
        : ['hiddenShiftsMonths', 'recentMonths'].find((k) => !integer(o[k], 0)) ?? (typeof o.shiftMix !== 'boolean' ? 'shiftMix' : null));
  return bad ? { refused: error(400, 'plugin.sp5.setting', { field: bad }, `Einstellung ungültig: ${bad}`) } : { options: o };
}

export function mock() {
  let settings = { root: '/srv/dispofold/quellen', path: '2027', mapping: structuredClone(MAPPING), options: { ...OPTIONS } };
  let run = null;
  let imported = false;

  /** What the export of a request needs: an error answer, or the request with its options. */
  function inputs(body) {
    if (!object(settings.mapping)) return { refused: error(400, 'plugin.sp5.noMapping', {}, 'Die Zuordnung fehlt') };
    if (!settings.root) return { refused: error(400, 'plugin.sp5.noFolder', {}, 'Der Ordner fehlt') };
    if (!/^\d{4}-\d{2}$/.test(body?.month ?? '')) return { refused: error(400, 'plugin.sp5.setting', { field: 'month' }, 'Einstellung ungültig: month') };
    const o = options(settings.options, body?.options);
    return o.refused ? o : { request: { month: body.month, to: body.to || null, options: o.options } };
  }

  const route = (method, path, body, role, query) => {
    if (role !== 'planner') return error(403, 'auth.forbidden', {}, 'Nur für Planung');
    const is = (m, p) => method === m && path === p;
    if (is('GET', 'einstellungen')) return ok(settings);
    if (is('PUT', 'einstellungen')) {
      if (!object(body)) return error(400, 'plugin.sp5.setting', { field: 'options' }, 'Einstellung ungültig: options');
      const o = options(OPTIONS, body.options);
      if (o.refused) return o.refused;
      const mapping = body.mapping ?? null;
      if (mapping !== null && !object(mapping)) return error(400, 'plugin.sp5.setting', { field: 'mapping' }, 'Einstellung ungültig: mapping');
      const problems = mapping ? mappingErrors(mapping) : [];
      if (problems.length) return error(400, 'plugin.sp5.mapping', {}, problems.join('; '));
      settings = { root: String(body.root ?? ''), path: String(body.path ?? ''), mapping, options: o.options };
      return ok(settings);
    }
    if (is('POST', 'ergebnis')) {
      const x = inputs(body);
      if (x.refused) return x.refused;
      return { status: 200, raw: Buffer.from('PK\u0003\u0004 Beispiel', 'latin1'), type: 'application/zip', download: `dispofold-sp5-ergebnis-${x.request.month}.zip` };
    }
    if (is('POST', 'lauf')) {
      const x = inputs(body);
      if (x.refused) return x.refused;
      const months = [x.request.month, ...(x.request.to && x.request.to > x.request.month ? [x.request.to] : [])];
      run = { request: x.request, months, ranAt: new Date().toISOString().replace(/\.\d+Z$/, 'Z'),
        counts: { people: 150, areas: 2, shifts: 5, demandRules: 17, duties: 812, previousDuties: 40, absences: 300,
          warnings: WARNINGS.length, reportRows: ROWS.length, corrections: ROWS.filter((r) => r.pruefung === 'korrektur').length },
        corrections: true };
      return ok(run);
    }
    const noRun = () => error(404, 'plugin.sp5.noRun', {}, 'Noch kein Lauf seit dem Start des Servers');
    if (is('GET', 'lauf')) return run ? ok(run) : noRun();
    if (is('GET', 'lauf/bericht')) return run ? ok({ columns: COLUMNS, rows: ROWS, warnings: WARNINGS }) : noRun();
    if (is('GET', 'lauf/bericht.csv')) {
      if (!run) return noRun();
      const csv = [COLUMNS, ...ROWS.map((r) => COLUMNS.map((c) => r[c] ?? ''))].map((r) => r.join(';')).join('\r\n');
      return { status: 200, raw: Buffer.from(`${csv}\r\n`, 'utf8'), type: 'text/csv; charset=utf-8', download: `dispofold-sp5-bericht-${run.request.month}.csv` };
    }
    if (is('POST', 'zuordnung/pruefen')) return ok({ errors: mappingErrors(body) });
    if (is('POST', 'zuordnung/codes')) return ok(codes(body));
    if (path === 'vergleich' && (method === 'GET' || method === 'POST')) {
      if (!run) return noRun();
      const corrections = query?.get('korrekturen') !== 'false';
      if (method === 'POST') {
        if (body?.format !== 'dispofold/dataset') return error(400, 'plugin.sp5.dataset', {}, 'Die Datei ist kein Datensatz');
        return ok(comparison('datei', corrections));
      }
      return imported ? ok(comparison('import', corrections)) : error(409, 'plugin.sp5.noBaseline', {}, 'Noch nichts importiert');
    }
    return null;
  };
  // the import source (POST /api/import/plugin/sp5): the request of a run
  route.load = (body, preview) => {
    const x = inputs(body);
    if (x.refused) return x.refused;
    if (!preview) imported = true;
    return null;
  };
  return route;
}
