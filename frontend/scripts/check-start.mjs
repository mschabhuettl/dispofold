// Playwright check of the first start without data (mock API with MOCK_EMPTY=1 and the shipped plugins, all off):
// planners see on the Dienstplan what to do first ("Demodaten zum Ausprobieren laden", "Datensatz importieren" to the
// Daten page, a link to Erweiterungen, or the first page of a switched on plugin), no empty grid, "Planen" disabled and
// "noch kein Plan" as status; viewers only the note. The demo button loads the demo data and shows their month, where
// "Planen" works and the status still says that there is no plan yet.
//   npm run build && node scripts/check-start.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchBrowser } from './browser.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
// not the month of the demo data: loading them switches the month
const month = '2026-12';
const port = await new Promise((resolve) => {
  const s = createServer().listen(0, () => { const p = s.address().port; s.close(() => resolve(p)); });
});
const server = spawn(process.execPath, [join(root, 'scripts', 'dev-server.mjs'), String(port)],
  { stdio: ['ignore', 'pipe', 'inherit'], env: { ...process.env, MOCK_EMPTY: '1', MOCK_PLUGINS: '1' } });
await new Promise((resolve) => server.stdout.on('data', (d) => { if (String(d).includes('dev server on')) resolve(); }));
const base = `http://localhost:${port}`;

let failed = false;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);
  if (!ok) failed = true;
};

const browser = await launchBrowser();
try {
  const login = async (user) => {
    const context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1360, height: 850 } });
    await context.addInitScript((m) => { if (!sessionStorage.getItem('month')) sessionStorage.setItem('month', m); }, month);
    const page = await context.newPage();
    page.on('pageerror', (e) => check('no page error', false, e.message));
    await page.goto(`${base}/login.html`);
    await page.fill('#j_username', user);
    await page.fill('#j_password', 'x');
    await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
    return page;
  };
  const page = await login('planung');
  await page.goto(`${base}/#/plan`);
  const start = page.locator('#empty-start');
  await start.waitFor();
  check('the data are empty', (await page.evaluate(async () => (await (await fetch('/api/dataset')).json()).people.length)) === 0);
  check('empty start: demo button', await start.getByRole('button', { name: 'Demodaten zum Ausprobieren laden' }).count() === 1);
  check('empty start: import links to Daten', await start.getByRole('link', { name: 'Datensatz importieren' }).getAttribute('href') === '#/uebernehmen');
  check('empty start: no plugin on, a link to Erweiterungen', await start.locator('a[href="#/erweiterungen"]').count() === 1
    && await start.locator('a[data-plugin]').count() === 0);
  check('empty start: no grid and no legend', await page.locator('.roster-wrap').count() === 0 && await page.locator('details.legend-box').count() === 0);
  check('"Planen" disabled without data', await page.getByRole('button', { name: 'Planen', exact: true }).isDisabled());
  const status = await page.locator('.status-details strong').first().innerText();
  check('status "noch kein Plan" instead of Entwurf', status === 'noch kein Plan', status);
  for (const tab of ['funktionen', 'zeitachse']) {
    await page.goto(`${base}/#/${tab}`);
    const back = page.locator('#main p.alert a[href="#/plan"]');
    await back.first().waitFor({ timeout: 5000 }).catch(() => {});
    check(`${tab} without data: a link to the empty start`, await back.count() === 1);
  }
  await page.goto(`${base}/#/plan`);
  await start.waitFor();
  await start.getByRole('link', { name: 'Datensatz importieren' }).click();
  await page.waitForURL((u) => u.hash === '#/uebernehmen');
  await page.locator('#dataset-file').waitFor();
  check('"Datensatz importieren" opens Daten', true);

  // a switched on plugin with pages: its first page instead of the link to Erweiterungen
  const plugin = (await page.evaluate(async () => (await fetch('/api/extensions')).json()))[0];
  check('a shipped plugin to switch on', Boolean(plugin?.id));
  await page.evaluate((id) => fetch(`/api/extensions/${id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: true }) }), plugin.id);
  await page.goto(`${base}/#/plan`);
  await page.reload();
  await start.waitFor();
  const pluginLink = start.locator(`a[data-plugin="${plugin.id}"]`);
  check('plugin on: its import source in the empty start', await pluginLink.count() === 1
    && (await pluginLink.getAttribute('href')) === `#/uebernehmen/${plugin.id}`, await pluginLink.getAttribute('href') ?? '');
  check('plugin on: no link to Erweiterungen', await start.locator('a[href="#/erweiterungen"]').count() === 0);
  await page.evaluate((id) => fetch(`/api/extensions/${id}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ enabled: false }) }), plugin.id);

  // viewers: the note only
  const viewer = await login('ansicht');
  await viewer.goto(`${base}/#/plan`);
  await viewer.locator('.statusbar').first().waitFor();
  check('viewer: no empty start with actions', await viewer.locator('#empty-start').count() === 0
    && (await viewer.locator('main').innerText()).includes('Noch keine Stammdaten.'));
  await viewer.context().close();

  // the demo data: loaded and their month shown
  await page.reload();
  await start.waitFor();
  await start.getByRole('button', { name: 'Demodaten zum Ausprobieren laden' }).click();
  await page.locator('.roster-wrap table').waitFor();
  const shown = await page.locator('#month').inputValue();
  check('demo data loaded, their month shown', shown !== month && (await page.evaluate(async () => (await (await fetch('/api/dataset')).json()).people.length)) > 0, shown);
  check('no empty start with data', await page.locator('#empty-start').count() === 0);
  check('"Planen" enabled with data', await page.getByRole('button', { name: 'Planen', exact: true }).isEnabled());
  check('still "noch kein Plan" before planning', (await page.locator('.status-details strong').first().innerText()) === 'noch kein Plan');
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('empty start checked');
