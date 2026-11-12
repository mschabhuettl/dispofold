import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { CODE_PREFIXES, TEXTS, codeText, formatParams, t } from '../src/lib/texts.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');

function files(dir, ext) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    if (statSync(path).isDirectory()) return files(path, ext);
    return path.endsWith(ext) ? [path] : [];
  });
}

/** Keys used by t('...') calls in src/ (texts.js itself excluded) and by data-t* attributes in public/*.html. */
function usedKeys() {
  const used = new Set();
  // a non-identifier character must precede t( so that split( or set( do not match
  const call = /(?:^|[^A-Za-z0-9_$.])t\(\s*(['"])([^'"]+)\1/g;
  for (const file of files(join(root, 'src'), '.js')) {
    if (file.endsWith(join('lib', 'texts.js'))) continue;
    for (const m of readFileSync(file, 'utf8').matchAll(call)) used.add(m[2]);
  }
  for (const file of files(join(root, 'public'), '.html')) {
    for (const m of readFileSync(file, 'utf8').matchAll(/\bdata-t(?:-[a-z-]+)?="([^"]+)"/g)) used.add(m[1]);
  }
  return used;
}

test('every used text key exists', () => {
  const missing = [...usedKeys()].filter((k) => !Object.hasOwn(TEXTS, k));
  assert.deepEqual(missing, []);
});

test('every text key is used', () => {
  const used = usedKeys();
  // keys for server codes are looked up dynamically (codeText) and are not literal at call sites
  const dead = Object.keys(TEXTS).filter((k) => !used.has(k) && !CODE_PREFIXES.some((p) => k.startsWith(p)));
  assert.deepEqual(dead, []);
});

test('t replaces placeholders', () => {
  assert.equal(t('roster.title', { month: 'Jänner 2027' }), 'Dienstplan Jänner 2027');
  assert.equal(t('common.outOf', { value: 3, total: 5 }), '3 von 5');
  assert.equal(t('roster.solve'), 'Planen');
  assert.equal(t('roster.title', {}), 'Dienstplan {month}');
});

test('t throws for unknown keys', () => {
  assert.throws(() => t('no.such.key'), /Unknown text key/);
});

test('server codes render from texts.js with formatted params, unknown codes fall back', () => {
  assert.deepEqual(formatParams({ date: '2027-01-31', hours: 2.5, ids: ['P1', 'P2'], month: '2027-01' }),
    { date: '31.01.2027', hours: '2,5', ids: 'P1, P2', month: 'Jänner 2027' });
  assert.equal(codeText('error.no.such.code', {}, 'Deutscher Ersatztext'), 'Deutscher Ersatztext');
  assert.equal(codeText('error.no.such.code', {}), 'error.no.such.code');
  const key = Object.keys(TEXTS).find((k) => k.startsWith('error.'));
  assert.ok(key, 'at least one error code text');
  assert.equal(codeText(key, {}), TEXTS[key].replace(/\{(\w+)\}/g, '{$1}'));
});

test('placeholders are {name} or {name|singular|plural}', () => {
  for (const [k, v] of Object.entries(TEXTS)) {
    for (const m of v.matchAll(/\{([^}]*)\}/g)) assert.match(m[1], /^\w+(\|[^|]+\|[^|]+)?$/, `${k}: {${m[1]}}`);
  }
});

test('counts take the singular for 1 and the plural otherwise', () => {
  assert.equal(t('roster.gapCount', { count: 1 }), '1 Lücke');
  assert.equal(t('roster.gapCount', { count: 3 }), '3 Lücken');
  assert.equal(t('roster.gapCount', { count: 0 }), '0 Lücken');
  assert.equal(t('import.summary', { people: 1, areas: 2, shifts: 1, months: '2027-01' }),
    '1 Person, 2 Bereiche, 1 Schicht, Monate 2027-01.');
  // no number with a noun in parentheses like "Lücke(n)" is left
  for (const [k, v] of Object.entries(TEXTS)) assert.doesNotMatch(v, /\w\((n|en|e)\)/, k);
});

test('errors, constraints and hits are rendered from codes', async () => {
  const { constraintDescription, constraintLabel, hitText, messageText } = await import('../src/lib/codes.js');
  const { errorMessage } = await import('../src/lib/api.js');
  assert.equal(constraintLabel({ id: 'dailyRest', label: 'old' }), TEXTS['constraint.dailyRest.label']);
  assert.equal(constraintDescription({ id: 'dailyRest' }), TEXTS['constraint.dailyRest.description']);
  assert.equal(constraintLabel({ id: 'unknownRule', label: 'Server label' }), 'Server label');
  assert.equal(constraintLabel({ id: 'unknownRule' }), 'unknownRule');
  assert.equal(messageText({ code: 'plan.closed', params: { month: '2027-01' } }), 'Der Monat Jänner 2027 ist abgeschlossen und kann nicht geändert werden.');
  assert.equal(messageText({ code: 'plan.previousOpen', params: { month: '2027-02', previous: '2027-01' } }),
    'Februar 2027 kann erst abgeschlossen werden, wenn Jänner 2027 abgeschlossen ist. Monate werden der Reihe nach abgeschlossen.');
  assert.equal(messageText({ code: 'not.listed', params: {}, error: 'Ersatz' }), 'Ersatz');
  assert.equal(messageText('alter Text'), 'alter Text');
  assert.equal(errorMessage(409, { code: 'plan.solving', params: {}, error: 'x' }), TEXTS['error.plan.solving']);
  assert.equal(hitText({ text: 'nur Text' }), 'nur Text');
  assert.equal(hitText({ code: 'hit.unknown', params: {}, text: 'Ersatz' }), 'Ersatz');
});

const repo = join(root, '..');

test('every error code in docs/api.md "Fehlercodes" has a text', (ctx) => {
  const doc = join(repo, 'docs', 'api.md');
  if (!existsSync(doc)) return ctx.skip('docs not checked out');
  const section = readFileSync(doc, 'utf8').split(/^## Fehlercodes$/m)[1]?.split(/^## /m)[0] ?? '';
  // the codes stand in backticks in the first column of the tables
  const codes = [...new Set(section.split('\n').filter((l) => l.startsWith('| `'))
    .flatMap((l) => [...l.split('|')[1].matchAll(/`([^`]+)`/g)].map((m) => m[1])))];
  assert.ok(codes.length > 60, `codes found in docs/api.md: ${codes.length}`);
  assert.deepEqual(codes.filter((c) => !Object.hasOwn(TEXTS, `error.${c}`)), []);
});

test('every import code in docs/datenformat.md has a text', (ctx) => {
  const doc = join(repo, 'docs', 'datenformat.md');
  if (!existsSync(doc)) return ctx.skip('docs not checked out');
  const section = readFileSync(doc, 'utf8').split('## Fehlercodes beim Import')[1] ?? '';
  const codes = [...new Set([...section.matchAll(/`(import\.[A-Za-z]+)`/g)].map((m) => m[1]))];
  assert.ok(codes.length > 20, 'import codes found in the docs');
  assert.deepEqual(codes.filter((c) => !Object.hasOwn(TEXTS, `error.${c}`)), []);
});

test('every constraint of the backend catalogue has a label, a description and a hit text', (ctx) => {
  const java = join(repo, 'backend', 'src', 'main', 'java', 'app', 'dispofold', 'solver', 'ConstraintCatalog.java');
  if (!existsSync(java)) return ctx.skip('backend not checked out');
  const ids = [...readFileSync(java, 'utf8').matchAll(/public static final String [A-Z_]+ = "([a-z][A-Za-z]+)";/g)].map((m) => m[1]);
  assert.ok(ids.length >= 26, 'constraint ids found');
  const missing = ids.flatMap((id) => [`constraint.${id}.label`, `constraint.${id}.description`, `hit.${id}`])
    .filter((k) => !Object.hasOwn(TEXTS, k));
  assert.deepEqual(missing, []);
});

test('hit params are shown readably', async () => {
  const { displayParams, hitText, messageText } = await import('../src/lib/codes.js');
  assert.deepEqual(displayParams({ restMinutes: 630, minutes: -30, load: 1250, index: 0, kind: 'demand', category: 'NIGHT' }),
    { restMinutes: 630, restHours: '10,5', minutes: -30, hours: '-0,5', load: 1.25, index: 0, number: 1, kind: 'Bedarfsregel', category: 'Nacht' });
  assert.equal(hitText({ code: 'hit.dailyRest', params: { restMinutes: 120, previousShiftId: 'N1', previousDate: '2027-01-31' } }),
    'Nur 2,0 h Ruhe nach Dienst N1 am 31.01.2027.');
  // durations as decimal hours like the cards, counts with singular and plural
  assert.equal(hitText({ code: 'hit.targetHours', params: { minutes: 7842, targetMinutes: 7947 } }),
    'Geplant und angerechnet 130,7 h, Ziel 132,5 h (Soll mit Ausgleich des Stundenkontos).');
  assert.equal(hitText({ code: 'hit.fairNights', params: { count: 1, history: 14, load: 4470, mean: 4280 } }),
    '1 Nachtdienst (Vormonate 14), Belastung 4,47 gegenüber Mittel 4,28.');
  assert.equal(hitText({ code: 'hit.fairUnpopular', params: { count: 1, history: 14, load: 4470, mean: 4280 } }),
    '1 unbeliebter Dienst (Vormonate 14), Belastung 4,47 gegenüber Mittel 4,28.');
  assert.equal(hitText({ code: 'fairNights', params: { count: 5, history: 12, load: 1500, mean: 1200 } }),
    '5 Nachtdienste (Vormonate 12), Belastung 1,5 gegenüber Mittel 1,2.');
  assert.equal(messageText({ code: 'import.shiftTooLong', params: { shiftId: 'X1', minutes: 810, maxMinutes: 720 } }),
    'Schicht X1 dauert 13,5 h, erlaubt sind höchstens 12,0 h.');
  assert.equal(messageText({ code: 'import.duplicateEntry', params: { kind: 'fixed', personId: 'P1', date: '2027-01-04' } }),
    'fixer Dienst: doppelter Eintrag für Person P1 (04.01.2027).');
  assert.equal(messageText({ code: 'import.missingField', params: { kind: 'shift', field: 'start', index: 2 } }),
    'Schicht Nr. 3: Pflichtfeld „start“ fehlt.');
});
