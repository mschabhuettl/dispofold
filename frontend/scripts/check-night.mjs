// Playwright check of the night setting (nightDistribution) on the person page and the fairness page. In HABIT a
// person with a usual night share "folgt gewohnter Verteilung (10 % Nacht)", in EVEN the same person is "im
// Nachtausgleich: Belastung x, Mittel y"; a person with "keine Nacht" on every day is "nicht im Nachtausgleich (nie
// Nacht)" in both modes; with a usual night share too, the tile and the mix note say that share does not count. The
// mix table and the person form name the shares that apply (without the night share, in EVEN and for never nights), and
// the form has its own night note for never nights. The fairness page marks people outside the night comparison with the reason; viewers see
// neither. Demo data of the dev server (mock API).
//   npm run build && node scripts/check-night.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const month = '2027-01';
const port = await new Promise((resolve) => {
  const s = createServer().listen(0, () => { const p = s.address().port; s.close(() => resolve(p)); });
});
const server = spawn(process.execPath, [join(root, 'scripts', 'dev-server.mjs'), String(port)], { stdio: ['ignore', 'pipe', 'inherit'] });
await new Promise((resolve) => server.stdout.on('data', (d) => { if (String(d).includes('dev server on')) resolve(); }));
const base = `http://localhost:${port}`;

let failed = false;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);
  if (!ok) failed = true;
};

const browser = await chromium.launch();
try {
  const login = async (user) => {
    const context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1600, height: 1000 }, colorScheme: 'light' });
    await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
    const page = await context.newPage();
    page.on('pageerror', (e) => check('no page error', false, e.message));
    await page.goto(`${base}/login.html`);
    await page.fill('#j_username', user);
    await page.fill('#j_password', 'x');
    await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
    return page;
  };
  const page = await login('planung');
  // Demo data; one person with a usual night share, one without nights on every day (and one more with a night share),
  // one plain person with nights.
  const people = await page.evaluate(async (m) => {
    const put = (path, body) => fetch(path, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    await fetch('/api/import/demo', { method: 'POST' });
    const dataset = await (await fetch('/api/dataset')).json();
    const nightAreas = new Set(dataset.shifts.filter((s) => s.category === 'NIGHT').map((s) => s.areaId));
    const plain = dataset.people.filter((p) => !p.shiftMix && !p.familyRules.length && p.areaIds.some((a) => nightAreas.has(a))
      && !dataset.restrictions.some((r) => r.personId === p.id));
    const [never, mix] = plain;
    await put(`/api/dataset/people/${never.id}`, { ...never, familyRules: [{ weekdays: [1, 2, 3, 4, 5, 6, 7], latestEnd: null,
      earliestStart: null, noNights: true, note: '' }] });
    await put(`/api/dataset/people/${mix.id}`, { ...mix, shiftMix: { categories: { DAY: 0.9, NIGHT: 0.1 }, areas: {} } });
    const neverMix = plain[3];
    await put(`/api/dataset/people/${neverMix.id}`, { ...neverMix, shiftMix: { categories: { DAY: 0.9, NIGHT: 0.1 }, areas: {} },
      familyRules: [{ weekdays: [1, 2, 3, 4, 5, 6, 7], latestEnd: null, earliestStart: null, noNights: true, note: '' }] });
    const rules = await (await fetch('/api/rules')).json();
    await put('/api/rules', { ...rules, nightDistribution: 'HABIT' });
    await fetch(`/api/plans/${m}/solve`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: '{"seed":1,"timeLimitSeconds":1}' });
    return { mix: mix.id, never: never.id, plain: plain[2].id, neverMix: neverMix.id };
  }, month);
  await page.waitForTimeout(1500);
  const setMode = (mode) => page.evaluate(async (m) => {
    const rules = await (await fetch('/api/rules')).json();
    await fetch('/api/rules', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...rules, nightDistribution: m }) });
  }, mode);

  const tile = async (p, id) => {
    await p.goto(`${base}/#/person/${encodeURIComponent(id)}`);
    await p.locator('.kpi-nights').waitFor();
    const help = p.locator('.kpi-nights .kpi-help');
    return (await help.count()) ? (await help.textContent()).trim() : null;
  };
  const marks = async (p) => {
    await p.goto(`${base}/#/fairness`);
    await p.locator('table.data tbody tr').first().waitFor();
    return p.evaluate(() => Object.fromEntries([...document.querySelectorAll('table.data tbody tr')]
      .map((tr) => [decodeURIComponent(tr.querySelector('a').getAttribute('href').split('/').pop()), tr.querySelector('.night-outside')?.dataset.reason ?? null])));
  };
  const pool = /^im Nachtausgleich: Belastung \d+,\d, Mittel \d+,\d$/;
  // never nights with a usual night share, the same in both modes
  const neverMixTile = 'nicht im Nachtausgleich (nie Nacht); der Nachtanteil der gewohnten Verteilung (10 %) zählt nicht';
  const neverMixNote = async (p) => (await p.locator('#h-mix').locator('..').textContent()).includes('Die Person macht keine Nachtdienste: Der Nachtanteil der gewohnten Verteilung zählt nicht.');
  // the shares that apply: column of the mix table per row name, and the line under the form inputs
  const effective = (p) => p.evaluate(() => {
    const table = document.querySelector('section[aria-labelledby="h-mix"] table');
    const col = [...table.querySelectorAll('th')].findIndex((th) => th.textContent === 'Gilt (ohne Nächte)');
    // rows with a share that applies or "zählt nicht" (planned-only rows are empty there)
    return col < 0 ? null : Object.fromEntries([...table.querySelectorAll('tbody tr')].filter((tr) => tr.cells[col].textContent)
      .map((tr) => [tr.cells[0].textContent, tr.cells[col].textContent]));
  });
  const formLine = async (p) => {
    const line = p.locator('#mix-effective');
    return (await line.isVisible()) ? (await line.textContent()).trim() : null;
  };
  const formNote = async (p) => (await p.locator('#person-form').textContent());
  const neverForm = 'Die Person macht keine Nachtdienste (Familienregel „keine Nacht“ an allen Tagen';
  const neverMixTitle = (p, id) => p.locator(`tr:has(a[href="#/person/${encodeURIComponent(id)}"]) .night-outside`).getAttribute('title');

  // HABIT (today): the usual night share decides, the person is outside the comparison.
  const habitMix = await tile(page, people.mix);
  check('HABIT: person with a night share follows the mix', habitMix === 'folgt gewohnter Verteilung (10 % Nacht)', habitMix);
  check('HABIT: the mix applies as entered, no column of shares that apply', await effective(page) === null && await formLine(page) === null);
  check('HABIT: the form has the mode note', (await formNote(page)).includes('die Nächte folgen dann dem Nachtanteil') && !(await formNote(page)).includes(neverForm));
  const habitNever = await tile(page, people.never);
  check('HABIT: no nights on every day is outside', habitNever === 'nicht im Nachtausgleich (nie Nacht)', habitNever);
  const habitNeverMix = await tile(page, people.neverMix);
  check('HABIT: never nights beats the night share', habitNeverMix === neverMixTile, habitNeverMix);
  check('HABIT: the mix note says the night share does not count', await neverMixNote(page));
  const habitNeverEffective = await effective(page);
  check('HABIT never nights: the mix table shows the shares that apply', JSON.stringify(habitNeverEffective) === '{"Tag":"100 %","Nacht":"zählt nicht"}',
    JSON.stringify(habitNeverEffective));
  check('HABIT never nights: the form has the never-nights note, not the mode note', (await formNote(page)).includes(neverForm)
    && !(await formNote(page)).includes('die Nächte folgen dann dem Nachtanteil'));
  check('HABIT never nights: the form names the shares that apply', await formLine(page) === 'Es gelten für die Dienste außer Nächten: Tag 100 %.', await formLine(page));
  await page.locator('#person-form input[data-mix-cat="EARLY"]').fill('30');
  await page.locator('#person-form input[data-mix-cat="DAY"]').fill('60');
  check('the shares that apply follow the inputs', await formLine(page) === 'Es gelten für die Dienste außer Nächten: Früh 33,3 %, Tag 66,7 %.', await formLine(page));
  await page.locator('#person-form input[data-mix-cat="DAY"]').fill('10');
  check('no line while the shares do not sum to 100 %', await formLine(page) === null, await formLine(page));
  await tile(page, people.never);
  check('HABIT never nights without a mix: the form has the never-nights note', (await formNote(page)).includes(neverForm) && await formLine(page) === null);
  const habitPlain = await tile(page, people.plain);
  check('HABIT: person without a mix is in the comparison', pool.test(habitPlain ?? ''), habitPlain);
  let marked = await marks(page);
  check('HABIT: fairness marks the mix', marked[people.mix] === 'MIX', marked[people.mix]);
  check('HABIT: fairness marks never nights', marked[people.never] === 'NEVER', marked[people.never]);
  check('HABIT: fairness marks never nights over the night share', marked[people.neverMix] === 'NEVER', marked[people.neverMix]);
  check('HABIT: the mark says the night share does not count', (await neverMixTitle(page, people.neverMix) ?? '').includes('Nachtanteil der gewohnten Verteilung zählt nicht'));
  check('fairness explains the night share of never nights', (await page.locator('main').textContent()).includes('Wer nie Nacht macht, dem zählt der Nachtanteil'));
  check('HABIT: fairness does not mark the others', marked[people.plain] === null, marked[people.plain]);
  check('fairness explains the marks', (await page.locator('main').textContent()).includes('nicht im Nachtausgleich, weil'));

  // EVEN: the night share is ignored, the person is in the comparison; never nights stays outside.
  await setMode('EVEN');
  const evenMix = await tile(page, people.mix);
  check('EVEN: person with a night share is in the comparison', pool.test(evenMix ?? ''), evenMix);
  check('EVEN: the mix note says the night share is ignored', (await page.locator('#h-mix').locator('..').textContent()).includes('Nachtanteil wird nicht beachtet'));
  const evenEffective = await effective(page);
  check('EVEN: the mix table shows the shares that apply', JSON.stringify(evenEffective) === '{"Tag":"100 %","Nacht":"zählt nicht"}', JSON.stringify(evenEffective));
  check('EVEN: the form names the shares that apply and has the mode note', await formLine(page) === 'Es gelten für die Dienste außer Nächten: Tag 100 %.'
    && (await formNote(page)).includes('Nachtanteil wird nicht beachtet') && !(await formNote(page)).includes(neverForm), await formLine(page));
  const evenNever = await tile(page, people.never);
  check('EVEN: no nights on every day stays outside', evenNever === 'nicht im Nachtausgleich (nie Nacht)', evenNever);
  const evenNeverMix = await tile(page, people.neverMix);
  check('EVEN: never nights beats the night share', evenNeverMix === neverMixTile, evenNeverMix);
  check('EVEN: the mix note says the night share does not count', await neverMixNote(page));
  marked = await marks(page);
  check('EVEN: fairness no longer marks the mix', marked[people.mix] === null, marked[people.mix]);
  check('EVEN: fairness marks never nights', marked[people.never] === 'NEVER', marked[people.never]);
  // the master data of a person are edited on the person page
  await page.goto(`${base}/#/person/${encodeURIComponent(people.plain)}`);
  await page.locator('#person-form').waitFor();
  check('EVEN: master data note follows the mode', (await page.locator('#person-form').textContent()).includes('Nachtanteil wird nicht beachtet'));
  await setMode('HABIT');

  // Viewers learn neither the mix nor the reason.
  const viewer = await login('ansicht');
  check('viewer: no night status in the tile', await tile(viewer, people.mix) === null && await tile(viewer, people.never) === null);
  marked = await marks(viewer);
  check('viewer: no marks on the fairness page', Object.values(marked).every((r) => r === null));
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('night setting checked');
