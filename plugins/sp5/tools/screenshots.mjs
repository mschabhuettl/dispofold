// Takes the screenshots of the plugin's help pages (plugins/sp5/docs/img/) with Playwright, from synthetic data only:
// a fresh instance of the Docker build whose allowed folder (DISPOFOLD_PLUGIN_ROOTS) holds, as "ordner", a folder
// written by the generator of the comparison job (python3 -m tools.sp5writer --month 2027-01 --people 40, from the
// pinned commit of sp5-export), and that generator's example mapping:
//   cd frontend && BASE_URL=http://localhost:8080 APP_USER=planer APP_PASSWORD=... \
//     node ../plugins/sp5/tools/screenshots.mjs <mapping.json> ../plugins/sp5/docs/img
// It switches the plugin on, imports January 2027 once, changes a person and a person's exclusion in Dispofold, then
// runs the export again and takes the pages as a planner sees them. It replaces the master data: never against
// production. Every request to another host is blocked. At the end scripts/optimize-png.py shrinks the files.
import { spawnSync } from 'node:child_process';
import { mkdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchBrowser } from '../../../frontend/scripts/browser.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const base = process.env.BASE_URL ?? 'http://localhost:8080';
const mapping = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const outDir = resolve(process.argv[3] ?? 'sp5-shots');
mkdirSync(outDir, { recursive: true });
const month = '2027-01';
const saved = [];
const external = [];

const browser = await launchBrowser();
const context = await browser.newContext({ viewport: { width: 1360, height: 850 }, locale: 'de-DE', colorScheme: 'light' });
await context.route('**/*', (route) => {
  const url = route.request().url();
  if (url.startsWith(base) || url.startsWith('data:') || url.startsWith('blob:')) return route.continue();
  external.push(url);
  return route.abort();
});
await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
const page = await context.newPage();
page.on('pageerror', (e) => console.error('page error:', e.message));
page.on('dialog', (d) => d.accept());

/** The API as the page calls it (same origin, the session's cookie). */
const api = (method, path, body, headers = {}) => page.evaluate(async ([m, p, b, x]) => {
  const res = await fetch(p, { method: m, credentials: 'same-origin',
    headers: { Accept: 'application/json', ...(b === undefined ? {} : { 'Content-Type': 'application/json' }), ...x },
    body: b === undefined ? undefined : JSON.stringify(b) });
  const text = await res.text();
  if (!res.ok) throw new Error(`${m} ${p}: ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}, [method, path, body, headers]);

async function open(path) {
  await page.goto(`${base}/#/${path}`);
  await page.locator('#main h1, #main .page-intro').first().waitFor();
}

async function quiet() {
  await page.mouse.move(0, 0);
  await page.evaluate(() => {
    document.getElementById('toast')?.setAttribute('hidden', '');
    document.activeElement?.blur();
  });
  await page.waitForTimeout(400);
}

async function shot(name) {
  await quiet();
  const path = join(outDir, `${name}.png`);
  await page.screenshot({ path });
  saved.push(path);
  console.log('saved', path);
}

/** The box around the given elements, with some room, as its own picture (page coordinates, the whole page). */
async function clip(name, selectors, pad = 14) {
  await quiet();
  const boxes = await page.evaluate((list) => list.map((s) => {
    const r = document.querySelector(s).getBoundingClientRect();
    return { x: r.left + window.scrollX, y: r.top + window.scrollY, width: r.width, height: r.height };
  }), selectors);
  const x = Math.max(0, Math.min(...boxes.map((b) => b.x)) - pad);
  const y = Math.max(0, Math.min(...boxes.map((b) => b.y)) - pad);
  const right = Math.max(...boxes.map((b) => b.x + b.width)) + pad;
  const bottom = Math.max(...boxes.map((b) => b.y + b.height)) + pad;
  const path = join(outDir, `${name}.png`);
  await page.screenshot({ path, clip: { x, y, width: right - x, height: Math.min(bottom - y, 1400) }, fullPage: true });
  saved.push(path);
  console.log('saved', path);
}

try {
  await page.goto(`${base}/login.html`);
  await page.fill('#j_username', process.env.APP_USER ?? 'planer');
  await page.fill('#j_password', process.env.APP_PASSWORD ?? '');
  await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
  await page.locator('#nav a').first().waitFor();

  // switched on, the folder and the mapping set, January imported once
  await api('PUT', '/api/extensions/sp5', { enabled: true });
  const roots = await api('GET', '/api/files/roots');
  const settings = await api('GET', '/api/plugins/sp5/einstellungen');
  await api('PUT', '/api/plugins/sp5/einstellungen', { ...settings, root: roots[0].id, path: 'ordner', mapping });
  await api('POST', '/api/import/plugin/sp5?replace=true', { month, to: null });

  // in Dispofold: weekly hours of one person changed, another excluded (both become corrections)
  let d = await api('GET', '/api/dataset');
  const [first, second] = d.people.slice().sort((a, b) => Number(a.id.slice(1)) - Number(b.id.slice(1)));
  await api('PUT', `/api/dataset/people/${first.id}`, { ...first, weeklyHours: first.weeklyHours === 30 ? 32 : 30 }, { 'If-Match': String(d.revision) });
  d = await api('GET', '/api/dataset');
  await api('PUT', `/api/dataset/people/${second.id}/excluded`, true, { 'If-Match': String(d.revision) });

  // Übersicht: a run of January
  await page.reload();
  await open('plugin/sp5/uebersicht');
  await page.fill('#sp5-run-month', month);
  await page.click('#sp5-run');
  await page.locator('#sp5-run-summary').waitFor();
  await shot('uebersicht');

  // the import of this run, keeping the changes made in Dispofold: its preview
  await page.check('#plugin-keep-sp5');
  await page.click('section:has(#h-sp5-import) .toolbar button.btn-primary');
  await page.locator('#import-preview').waitFor();
  await clip('import', ['#h-sp5-import', '#import-preview']);

  // Bericht: the sections, then the corrections
  await open('plugin/sp5/bericht');
  await page.locator('#sp5-report-count').waitFor();
  await shot('bericht');

  // Zuordnung: the editor, then what it does with the folder's codes
  await open('plugin/sp5/zuordnung');
  await page.locator('#sp5-codes table').first().waitFor();
  await shot('zuordnung');
  await clip('zuordnung-ordner', ['#sp5-codes']);

  // Vergleich: the run against the last import
  await open('plugin/sp5/lauf-vergleich');
  await page.locator('#sp5-cmp-against').waitFor();
  await shot('vergleich');
} finally {
  await browser.close();
}
if (external.length) throw new Error(`requests to other hosts: ${external.join(', ')}`);
const optimize = spawnSync('python3', [join(here, '..', '..', '..', 'scripts', 'optimize-png.py'), ...saved], { stdio: 'inherit' });
if (optimize.status !== 0) process.exit(1);
