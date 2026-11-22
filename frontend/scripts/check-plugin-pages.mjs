// Playwright check of the shipped plugins' own pages: runs plugins/<id>/dev/check.mjs of every plugin without testOnly
// that has one, against the dev server with the plain build (dist/) and MOCK_PLUGINS=1 (its dev/mock.mjs). A plugin's
// check exports a default async function ({ base, browser, check, shots }) and reports with check(name, ok, detail).
//   npm run build && node scripts/check-plugin-pages.mjs     (Chromium via `npx playwright install chromium`)
// SHOTS=<folder> also saves the screenshots the checks take there.
import { spawn } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { launchBrowser } from './browser.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const base = join(root, '..', 'plugins');
const shots = process.env.SHOTS;
if (shots) mkdirSync(shots, { recursive: true });
let failed = false;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);
  if (!ok) failed = true;
};

const plugins = readdirSync(base).sort().filter((id) => existsSync(join(base, id, 'dev', 'check.mjs'))
  && !JSON.parse(readFileSync(join(base, id, 'plugin.json'), 'utf8')).testOnly);
if (!plugins.length) throw new Error('no plugin has a dev/check.mjs');

const browser = await launchBrowser();
try {
  for (const id of plugins) {
    const port = await new Promise((resolve) => {
      const s = createServer().listen(0, () => { const p = s.address().port; s.close(() => resolve(p)); });
    });
    const server = spawn(process.execPath, [join(root, 'scripts', 'dev-server.mjs'), String(port)],
      { stdio: ['ignore', 'pipe', 'inherit'], env: { ...process.env, MOCK_PLUGINS: '1' } });
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('the dev server did not start within 30 s')), 30000);
      server.on('exit', (code) => reject(new Error(`the dev server ended with ${code}`)));
      server.stdout.on('data', (d) => { if (String(d).includes('dev server on')) { clearTimeout(timer); resolve(); } });
    });
    console.log(`plugin ${id}`);
    try {
      const run = (await import(pathToFileURL(join(base, id, 'dev', 'check.mjs')).href)).default;
      await run({ base: `http://localhost:${port}`, browser, check, shots });
    } catch (e) {
      check(`plugin ${id}: the check ran to the end`, false, e.stack ?? String(e));
    } finally {
      server.kill();
    }
  }
} finally {
  await browser.close();
}
if (failed) process.exit(1);
console.log('plugin pages checked');
