// Playwright check of small things for beginners: the month picker labelled "Planungsmonat", file fields shown whole,
// a person's page with its own intro (not the list's), "= Mindestens" where the demand has no maximum, each filter
// of the master data named after its table, and on the Zeitachse the coverage above the duties. Demo data of the dev
// server (mock API), month 2027-01 planned.
//   npm run build && node scripts/check-beginner.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchBrowser } from './browser.mjs';

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

const browser = await launchBrowser();
try {
  const context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1360, height: 850 } });
  await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
  const page = await context.newPage();
  page.on('pageerror', (e) => check('no page error', false, e.message));
  await page.goto(`${base}/login.html`);
  await page.fill('#j_username', 'planung');
  await page.fill('#j_password', 'x');
  await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
  const dataset = await page.evaluate(async (m) => {
    await fetch('/api/import/demo', { method: 'POST' });
    await fetch(`/api/plans/${m}/solve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"seed":1,"timeLimitSeconds":1}' });
    return (await fetch('/api/dataset')).json();
  }, month);
  const open = async (hash) => {
    await page.goto(`${base}/#/${hash}`);
    await page.waitForFunction(() => document.querySelector('#main h1') && !document.querySelector('#main > p.muted'));
  };
  const intro = () => page.locator('#main h1 + p.page-intro').innerText();

  await open('plan');
  const label = page.locator('label[for="month"]');
  check('month picker labelled Planungsmonat', await label.isVisible() && await label.textContent() === 'Planungsmonat');

  // file fields: as wide as their text, not cut off
  await open('import');
  const files = await page.locator('#main input[type=file]').evaluateAll((inputs) => inputs.map((i) => ({ id: i.id, width: i.getBoundingClientRect().width })));
  check('file fields shown whole', files.length >= 2 && files.every((f) => f.width >= 180), JSON.stringify(files));

  // the person's page has its own intro, the list keeps the general one
  await open('person');
  const listIntro = await intro();
  await open(`person/${encodeURIComponent(dataset.people[0].id)}`);
  const personIntro = await intro();
  check('person list: its intro', listIntro.startsWith('Alle Personen zum Suchen und Öffnen'), listIntro);
  check('person page: its own intro', personIntro.startsWith('Die Zahlen dieser Person im gewählten Monat') && !personIntro.includes('Alle Personen'), personIntro);

  // master data: "= Mindestens" without a maximum, the filters named after their tables
  await open('stammdaten');
  const maxCells = await page.locator('table[data-table="demand"] tbody tr').evaluateAll((trs) => trs.map((tr) => tr.children[5].textContent));
  const withoutMax = dataset.demand.filter((d) => d.max == null).length;
  check('demand without a maximum: "= Mindestens"', withoutMax > 0 && maxCells.filter((c) => c === '= Mindestens').length === withoutMax
    && !maxCells.includes('–'), `${withoutMax} ${maxCells.slice(0, 3).join(' | ')}`);
  const filters = await page.locator('label[for^="filter-"]').allTextContents();
  check('each filter named after its table', filters.length >= 4 && new Set(filters).size === filters.length
    && filters.every((f) => / filtern$/.test(f)) && filters.includes('Bedarf filtern'), filters.join(' | '));

  // Zeitachse: the coverage against the demand above the duties
  for (let i = 0; i < 60 && await page.evaluate(async (m) => (await (await fetch(`/api/plans/${m}`)).json()).solving, month); i++) {
    await page.waitForTimeout(500);
  }
  await open('zeitachse');
  await page.locator('.coverage svg').waitFor();
  const order = await page.evaluate(() => ({ chart: document.querySelector('.coverage').getBoundingClientRect().top,
    timeline: document.querySelector('.timeline-host').getBoundingClientRect().top }));
  check('Zeitachse: coverage above the duties', order.chart < order.timeline, JSON.stringify(order));
  check('Zeitachse: the intro says so', (await intro()).includes('darüber die Besetzung gegen den Bedarf'));
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('beginner details checked');
