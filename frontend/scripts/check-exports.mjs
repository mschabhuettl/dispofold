// Playwright check: the three exports (XLSX, CSV, JSON) save a file through the blob download path.
// Starts the dev server (mock API) on a free port, logs in, loads demo data, plans and clicks every export button.
//   npm run build && node scripts/check-exports.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
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

const browser = await chromium.launch();
let failed = false;
try {
  const context = await browser.newContext({ acceptDownloads: true });
  await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
  const page = await context.newPage();
  page.on('dialog', (d) => d.accept());
  page.on('pageerror', (e) => { console.error('page error:', e.message); failed = true; });
  await page.goto(`${base}/login.html`);
  await page.fill('#j_username', 'planung');
  await page.fill('#j_password', 'x');
  await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
  await page.evaluate(async (m) => {
    await fetch('/api/import/demo', { method: 'POST' });
    await fetch(`/api/plans/${m}/solve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"seed":1,"timeLimitSeconds":1}' });
  }, month);
  await page.waitForTimeout(1500);
  await page.goto(`${base}/#/import`);
  for (const format of ['xlsx', 'csv', 'json']) {
    const button = page.getByRole('button', { name: format.toUpperCase(), exact: true });
    const [download] = await Promise.all([page.waitForEvent('download', { timeout: 15000 }), button.click()]);
    const path = await download.path();
    const size = readFileSync(path).length;
    const ok = download.suggestedFilename() === `dienstplan-${month}.${format}` && size > 0 && download.url().startsWith('blob:');
    console.log(`${ok ? 'ok  ' : 'FAIL'} ${format}: ${download.suggestedFilename()}, ${size} bytes, from ${download.url().slice(0, 5)}`);
    if (format === 'json') JSON.parse(readFileSync(path, 'utf8'));
    if (!ok) failed = true;
  }
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('all exports saved');
