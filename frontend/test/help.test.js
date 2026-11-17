// Help texts (lib/info.js and the page intros): every rule, setting, figure, page and form field has one, derived from
// the sources that define them (catalogue, rules model, views), never from a list kept here.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { CONSTRAINTS, DEFAULT_RULES } from '../dev/mock-data.mjs';
import { METRICS } from '../src/lib/fairness.js';
import { GROUPS } from '../src/lib/rule-groups.js';
import { TEXTS } from '../src/lib/texts.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const repo = join(root, '..');
const source = (file) => readFileSync(join(root, 'src', file), 'utf8');
const missing = (keys) => keys.filter((k) => !Object.hasOwn(TEXTS, k));
const helpKeys = Object.keys(TEXTS).filter((k) => k.startsWith('help.'));

/**
 * The argument text of every call that starts with fn (e.g. "kpi(" or "h('h2'") in a source, found by matching
 * parentheses; the text starts after fn.
 */
function calls(text, fn) {
  const out = [];
  for (let i = text.indexOf(fn); i >= 0; i = text.indexOf(fn, i + 1)) {
    if (/[\w.$]/.test(text[i - 1] ?? '')) continue;
    let depth = 0;
    for (let j = i + fn.indexOf('('); j < text.length; j++) {
      if (text[j] === '(') depth++;
      else if (text[j] === ')' && --depth === 0) {
        out.push(text.slice(i + fn.length, j));
        break;
      }
    }
  }
  return out;
}

/** Top-level arguments of an argument text. */
function args(text) {
  const out = [];
  let depth = 0;
  let start = 0;
  let quote = null;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (quote) {
      if (c === quote && text[i - 1] !== '\\') quote = null;
    } else if (c === '\'' || c === '`' || c === '"') quote = c;
    else if ('([{'.includes(c)) depth++;
    else if (')]}'.includes(c)) depth--;
    else if (c === ',' && depth === 0) {
      out.push(text.slice(start, i));
      start = i + 1;
    }
  }
  out.push(text.slice(start));
  return out.map((a) => a.trim()).filter(Boolean);
}

const catalogue = join(repo, 'backend', 'src', 'main', 'java', 'app', 'dispofold', 'solver', 'ConstraintCatalog.java');
const rulesModel = join(repo, 'backend', 'src', 'main', 'java', 'app', 'dispofold', 'model', 'Rules.java');

test('every rule has a help text, and the mock catalogue matches the backend catalogue', () => {
  const ids = CONSTRAINTS.map((c) => c.id);
  assert.ok(ids.length >= 26, `constraints: ${ids.length}`);
  assert.deepEqual(missing(ids.map((id) => `help.constraint.${id}`)), []);
  if (existsSync(catalogue)) {
    const java = [...readFileSync(catalogue, 'utf8').matchAll(/public static final String [A-Z_]+ = "([a-z][A-Za-z]+)";/g)].map((m) => m[1]);
    assert.deepEqual([...java].sort(), [...ids].sort());
  }
});

test('every weight says what raising and lowering it does; fixed ones say they are fixed', () => {
  for (const c of CONSTRAINTS) {
    const text = TEXTS[`help.constraint.${c.id}`];
    if (c.tunable) assert.match(text, /Höheres Gewicht.*Niedrigeres Gewicht/s, c.id);
    else assert.match(text, /[Kk]ein Gewicht|[Ff]estes Gewicht/, c.id);
  }
});

test('every rules setting has a help text that says what raising or lowering it does', () => {
  const settings = Object.keys(DEFAULT_RULES).filter((k) => k !== 'weights');
  // the fields of the rules page
  const page = [...source('views/rules.js').matchAll(/\['(\w+)', t\('rules\.param\.\1\.label'\)/g)].map((m) => m[1]);
  assert.ok(page.length >= 15, `rules page fields: ${page.length}`);
  if (existsSync(rulesModel)) {
    const record = readFileSync(rulesModel, 'utf8').match(/public record Rules\(([^)]*)\)/)[1].replace(/<[^>]*>/g, '');
    const fields = [...record.matchAll(/\b(\w+)\s*(?:,|$)/g)].map((m) => m[1]);
    assert.ok(fields.length >= 18, `Rules record components: ${fields.length}`);
    // weights are set per rule (help.constraint.*); the time zone is no setting of the interface
    assert.deepEqual(fields.filter((f) => !['weights', 'timeZone'].includes(f) && !settings.includes(f)), []);
  }
  const keys = [...new Set([...settings, ...page])];
  assert.deepEqual(missing(keys.map((k) => `help.param.${k}`)), []);
  for (const k of keys) {
    assert.match(TEXTS[`help.param.${k}`], /\b(Höher|Niedriger|Früher|Später|Mehr|Weniger|Länger)\b|\bEin:|„Gleichmäßig“/, k);
  }
});

test('Wichtigkeit and every planning goal have a help text', () => {
  assert.deepEqual(missing(['help.rules.importance', 'help.rules.importance.fairness', ...Object.keys(GROUPS).map((g) => `help.goal.${g}`)]), []);
});

test('every solver option of the Dienstplan has a help text', () => {
  const options = source('views/roster.js').match(/solveOptions\(\{([^}]*)\}/)[1];
  const keys = [...options.matchAll(/(\w+):/g)].map((m) => m[1]);
  assert.ok(keys.length >= 4, keys.join());
  assert.deepEqual(missing(keys.map((k) => `help.solve.${k}`)), []);
});

test('every column of the fairness overview has a help text', () => {
  assert.deepEqual(missing(METRICS.map((m) => `help.figure.${m.id}`)), []);
});

test('every tile of the person page has a help', () => {
  const person = source('views/person.js');
  const tiles = calls(person, 'kpi(').filter((a) => !a.startsWith('label, value'));
  assert.ok(tiles.length >= 20, `kpi calls: ${tiles.length}`);
  assert.deepEqual(tiles.filter((a) => !/t\('help\.figure\.\w+'\)\s*$/.test(a)), []);
  // tiles built without kpi(): the help sits in their label
  const own = calls(person, "h('div.kpi.");
  assert.ok(own.length >= 2, `own tiles: ${own.length}`);
  assert.deepEqual(own.filter((a) => !a.includes('info(')), []);
});

test('every figure of the Dienstplan status line has a help', () => {
  const roster = source('views/roster.js');
  const details = calls(roster, "h('div.status-details'");
  assert.equal(details.length, 1);
  // a figure: a text with a number placeholder or a label ending in ":"
  const figure = (a) => [...a.matchAll(/t\('(roster\.\w+)'/g)].some((m) => /\{\w|:$/.test(TEXTS[m[1]]));
  const parts = args(details[0]).filter(figure);
  assert.ok(parts.length >= 4, `figures: ${parts.length}`);
  assert.deepEqual(parts.filter((a) => !a.includes('info(') && !a.includes("help('help.")), []);
  for (const cls of ['p.plan-headline', 'span.score']) {
    const around = roster.slice(roster.indexOf(cls) - 40, roster.indexOf(cls) + 400);
    assert.match(around, /info\(t\('help\.|help\('help\./, cls);
  }
});

test('every section of the Prüfen page has a help', () => {
  const headings = calls(source('views/check.js'), "h('h2'");
  assert.ok(headings.length >= 3);
  assert.deepEqual(headings.filter((a) => !a.includes("info(t('help.check.")), []);
});

test('every page has an intro', () => {
  const main = source('main.js');
  const paths = [...main.matchAll(/\{ path: '(\w+)'/g)].map((m) => m[1]);
  assert.ok(paths.length >= 11, paths.join());
  assert.deepEqual(missing(paths.map((p) => `help.page.${p}`)), []);
  assert.deepEqual(paths.filter((p) => !main.includes(`${p}: t('help.page.${p}')`)), []);
});

test('every field of the person form, the Stammdaten forms and the Eingaben has a help', () => {
  for (const file of ['views/masterdata.js', 'views/person.js']) {
    const defs = [...source(file).matchAll(/\{ name: '(\w+)', label: t\('[\w.]+'\)(, help: t\('help\.field\.\w+\.(\w+)'\))?/g)];
    assert.ok(defs.length >= 10, `${file}: ${defs.length} fields`);
    assert.deepEqual(defs.filter((m) => m[3] !== m[1]).map((m) => m[1]), [], file);
  }
  const inputs = source('views/inputs.js');
  const sections = calls(inputs, 'section(').filter((a) => a.startsWith("t('inputs.section."));
  assert.equal(sections.length, 3);
  assert.deepEqual(sections.filter((a) => !/^t\('inputs\.section\.\w+'\), '\w+', t\('help\.field\.inputs\.\w+'\)/.test(a)), []);
});

test('every help text belongs to something that exists', () => {
  const literal = new Set();
  const files = (dir) => readdirSync(dir).flatMap((n) => (statSync(join(dir, n)).isDirectory() ? files(join(dir, n)) : [join(dir, n)]));
  for (const f of files(join(root, 'src')).filter((f) => !f.endsWith(join('lib', 'texts.js')))) {
    for (const m of readFileSync(f, 'utf8').matchAll(/'(help\.[\w.]+)'/g)) literal.add(m[1]);
  }
  const derived = new Set([...CONSTRAINTS.map((c) => `help.constraint.${c.id}`), ...METRICS.map((m) => `help.figure.${m.id}`),
    ...Object.keys(DEFAULT_RULES).map((k) => `help.param.${k}`)]);
  // help.import.*: for the Daten page, which is being rewritten separately
  assert.deepEqual(helpKeys.filter((k) => !literal.has(k) && !derived.has(k) && !k.startsWith('help.import.')), []);
});

test('help texts are short, not empty, in plain German without old terms', () => {
  const OLD = /Sollstunden|Monatssoll|Ziel für diesen Plan|\bfixe[nrs]?\b|Fixe Dienste|Eingaben geändert|Vormonat geändert|Schichtmix|\bMix\b|Familienw[uü]nsch|Blocktage|Wer kann gefragt|\bhart(e[nrs]?)?\b|\bweich(e[nrs]?)?\b|\bStufen?\b/i;
  assert.ok(helpKeys.length > 100, `help keys: ${helpKeys.length}`);
  for (const k of helpKeys) {
    const v = TEXTS[k];
    assert.ok(v.trim().length > 0, `${k} is empty`);
    assert.ok(v.length <= 420, `${k} is long: ${v.length}`);
    assert.doesNotMatch(v, OLD, k);
    assert.doesNotMatch(v, /—|\b(score|constraint|solver|weight)\b/i, k);
  }
});
