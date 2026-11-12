// Playwright check of the desktop window behaviour against the dev server in desktop mode (MOCK_DESKTOP=1):
// restart answers (503 desktop.restarting) show a status and no error, the download event of the shell shows a toast,
// a bad update file shows its German error, installing an update reloads, and quitting shows the closing state.
// Also: in browser mode (GET /__shell/mode) quitting says the tab can be closed; the Docker build never asks the shell.
//   npm run build && node scripts/check-desktop.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const servers = [];

/** Starts the dev server with extra environment on a free port; returns its base URL. */
async function startServer(env) {
  const port = await new Promise((resolve) => {
    const s = createServer().listen(0, () => { const p = s.address().port; s.close(() => resolve(p)); });
  });
  const server = spawn(process.execPath, [join(root, 'scripts', 'dev-server.mjs'), String(port)], {
    stdio: ['ignore', 'pipe', 'inherit'], env: { ...process.env, ...env } });
  servers.push(server);
  await new Promise((resolve) => server.stdout.on('data', (d) => { if (String(d).includes('dev server on')) resolve(); }));
  return `http://localhost:${port}`;
}

const base = await startServer({ MOCK_DESKTOP: '1', MOCK_FRONTEND: 'window',
  MOCK_UPDATE_SOURCE: 'https://updates.example.invalid/dispofold' });

let failed = false;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);
  if (!ok) failed = true;
};

const browser = await chromium.launch();
try {
  const page = await browser.newPage();
  page.on('dialog', (d) => d.accept());
  page.on('pageerror', (e) => check('no page error', false, e.message));
  await page.goto(`${base}/#/stammdaten`);
  await page.locator('h1').waitFor();

  // 1. backend restart: status shown, requests repeated, no error toast, view loaded again afterwards
  await page.evaluate(() => fetch('/api/dev/restart?ms=3000', { method: 'POST' }));
  await page.goto(`${base}/#/regeln`);
  await page.locator('#restart-status:not([hidden])').waitFor({ timeout: 5000 });
  check('restart status shown', true, await page.locator('#restart-status').textContent());
  await page.locator('.rule-row').first().waitFor({ timeout: 20000 });
  check('view loaded after the restart', await page.locator('#restart-status').isHidden());
  check('no error toast during the restart', await page.locator('#toast.error:not([hidden])').count() === 0);

  // 2. the shell saved a download
  await page.evaluate(() => window.dispatchEvent(new CustomEvent('dispofold-download',
    { detail: { name: 'dienstplan-2027-01.json', folder: 'Downloads' } })));
  const toast = await page.locator('#toast:not([hidden])').textContent();
  check('download toast', toast === 'Gespeichert: dienstplan-2027-01.json im Ordner Downloads', toast);

  // 3. update from a file that is not a bundle: German error from the code
  const bad = join(tmpdir(), `dispofold-bad-${process.pid}.zip`);
  writeFileSync(bad, 'not a zip');
  await page.goto(`${base}/#/einstellungen`);
  await page.locator('.settings').waitFor();
  await page.setInputFiles('#update-file', bad);
  await page.getByRole('button', { name: 'Update einspielen' }).click();
  await page.locator('#toast.error:not([hidden])').waitFor();
  check('bad update file', (await page.locator('#toast span').textContent()).includes('kein Update-Paket'));

  // 4. install the offered update: restart page, then reload with the new version
  await page.getByRole('button', { name: 'Update installieren' }).first().click();
  await page.getByText('Dispofold startet neu').first().waitFor();
  await page.locator('.settings').waitFor({ timeout: 30000 });
  check('reloaded with the new version', (await page.locator('.settings p').first().textContent()).includes('1.3.0'));

  // 5. quit: closing state, no restart page
  await page.click('#quit');
  await page.getByText('Dispofold wird beendet').waitFor();
  check('quit shows the closing state', await page.getByText('startet neu').count() === 0);

  // 6. browser mode (Linux default): the tab stays open after quitting
  const browserBase = await startServer({ MOCK_DESKTOP: '1', MOCK_FRONTEND: 'browser' });
  const tab = await browser.newPage();
  tab.on('dialog', (d) => d.accept());
  await tab.goto(`${browserBase}/#/plan`);
  await tab.locator('#quit:not([hidden])').waitFor();
  await tab.click('#quit');
  await tab.getByText('Dispofold ist beendet.').waitFor();
  check('browser mode: tab can be closed', await tab.getByText('Dieses Fenster kann geschlossen werden.').count() === 1);

  // 7. Docker build: never asks the shell
  const dockerBase = await startServer({});
  const docker = await browser.newPage();
  const asked = [];
  docker.on('request', (r) => { if (r.url().includes('/__shell/')) asked.push(r.url()); });
  await docker.goto(`${dockerBase}/login.html`);
  await docker.fill('#j_username', 'planung');
  await docker.fill('#j_password', 'x');
  await Promise.all([docker.waitForURL((u) => !u.pathname.endsWith('login.html')), docker.click('button[type=submit]')]);
  await docker.locator('#nav a').first().waitFor();
  check('Docker build does not ask /__shell/mode', asked.length === 0, asked.join(', '));
} finally {
  await browser.close();
  for (const s of servers) s.kill();
}
if (failed) process.exit(1);
console.log('desktop window behaviour ok');
