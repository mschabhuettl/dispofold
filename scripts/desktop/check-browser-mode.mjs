// Playwright part of check-browser-mode.sh. Argument: the work folder (launch-path appears there).
import { createRequire } from 'node:module';
import { existsSync, readFileSync, statSync } from 'node:fs';
import http from 'node:http';
import { randomBytes } from 'node:crypto';

const require = createRequire(new URL('../../frontend/package.json', import.meta.url));
const { chromium } = require('playwright');
const work = process.argv[2];
const MONTH = '2027-01';
let failed = 0;
const check = (ok, what) => { console.log(`${ok ? 'PASS' : 'FAIL'}  ${what}`); if (!ok) failed++; };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Raw loopback request with an explicit Host header (no browser, no cookie unless given). */
function raw(port, host, path, headers = {}, address = '127.0.0.1') {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: address, port, path, headers: { Host: host, ...headers } }, (res) => {
      let body = '';
      res.on('data', (d) => { body += d; });
      res.on('end', () => resolve({ status: res.statusCode, body }));
    });
    req.on('error', reject);
    req.end();
  });
}

let launchPath = null;
for (let i = 0; i < 240 && !launchPath; i++) {
  if (existsSync(`${work}/launch-path`)) launchPath = readFileSync(`${work}/launch-path`, 'utf8').trim();
  else await sleep(250);
}
if (!launchPath) { console.log('FAIL  no launch file within 60 s'); process.exit(1); }
const launchHtml = readFileSync(launchPath, 'utf8');
const m = /http:\/\/([a-z]{16}\.localhost):(\d+)\/__shell\/launch#([A-Za-z0-9_-]+)/.exec(launchHtml);
check(!!m, 'launch file points to <name>.localhost with a code in the fragment');
if (!m) process.exit(1);
const [, hostName, portText] = m;
const port = Number(portText);
const host = `${hostName}:${port}`;
check((statSync(launchPath).mode & 0o777) === 0o600, 'launch file readable by the user only (600); the browser gets only its path');

const browser = await chromium.launch();
try {
  const ctx = await browser.newContext({ acceptDownloads: true });
  const page = await ctx.newPage();
  page.on('dialog', (d) => d.accept());
  await page.goto(`file://${launchPath}`);
  await page.waitForURL(/\/__shell\/unlock$/, { timeout: 120000 });
  check(true, 'launch file opens the unlock page through the launch code');
  const password = randomBytes(18).toString('base64');
  await page.fill('#password', password);
  if (await page.isVisible('#repeat')) await page.fill('#repeat', password);
  await page.click('#submit');
  await page.waitForFunction(() => document.getElementById('user-name')?.textContent, null, { timeout: 180000 });
  check(true, 'unlocked, interface loaded');
  const cookies = await ctx.cookies();
  const s = cookies.find((c) => c.name === 'dispofold_s');
  check(s && s.httpOnly && s.sameSite === 'Strict' && s.domain === hostName, 'session cookie host-only, HttpOnly, SameSite=Strict');

  const evalJson = (fn, arg) => page.evaluate(fn, arg);
  const mode = await evalJson(() => fetch('/__shell/mode').then((r) => r.json()));
  check(mode.frontEnd === 'browser', `front end reported as ${mode.frontEnd}`);
  const demo = await evalJson(() => fetch('/api/import/demo', { method: 'POST' }).then((r) => r.status));
  check(demo === 200, `demo data loaded (${demo})`);
  const solve = await evalJson((month) => fetch(`/api/plans/${month}/solve`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ moveLimit: 20000 }),
  }).then((r) => r.status), MONTH);
  check(solve === 202 || solve === 200, `solve started (${solve})`);
  let state = null;
  for (let i = 0; i < 600; i++) {
    await sleep(1000);
    state = await evalJson((month) => fetch(`/api/plans/${month}`).then((r) => r.json()), MONTH);
    if (!state.solving && state.plan) break;
  }
  check(state && !state.solving && /hard/.test(state.plan?.score ?? ''), `plan finished: ${state?.plan?.score}`);
  const [download] = await Promise.all([
    page.waitForEvent('download', { timeout: 60000 }),
    evalJson(async (month) => {
      const blob = await (await fetch(`/api/plans/${month}/export?format=json`)).blob();
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = `dienstplan-${month}.json`;
      document.body.append(a);
      a.click();
    }, MONTH),
  ]);
  const exported = JSON.parse(readFileSync(await download.path(), 'utf8'));
  check(typeof exported === 'object' && exported !== null, `export downloaded as ${download.suggestedFilename()}`);

  // refusals
  const other = await browser.newContext();
  const again = await other.newPage();
  await again.goto(`file://${launchPath}`);
  await again.waitForSelector('#expired:not([hidden])', { timeout: 30000 });
  check(true, 'used launch code refused');
  const noCookie = await raw(port, host, '/api/me');
  check(noCookie.status === 401 && noCookie.body.includes('security.desktopLogin'), `no cookie: ${noCookie.status}`);
  // browsers try ::1 first: the program must hold it itself (else another user could listen there)
  const v6 = await raw(port, host, '/api/me', {}, '::1').catch((e) => ({ status: e.code }));
  check(v6.status === 401 || v6.status === 'EADDRNOTAVAIL', `[::1] on the same port held by the program: ${v6.status}`);
  const page401 = await raw(port, host, '/');
  check(page401.status === 401 && page401.body.includes('Bitte Dispofold neu starten'), `no cookie, page: ${page401.status}`);
  const wrongHost = await raw(port, `127.0.0.1:${port}`, '/api/me', { Cookie: `dispofold_s=${s?.value}` });
  check(wrongHost.status === 421, `foreign host name: ${wrongHost.status}`);
  const evil = await raw(port, `evil.localhost:${port}`, '/api/me', { Cookie: `dispofold_s=${s?.value}` });
  check(evil.status === 421, `other localhost name: ${evil.status}`);
  const quitViaListener = await new Promise((resolve) => {
    const req = http.request({ host: '127.0.0.1', port, path: '/__shell/quit', method: 'POST',
      headers: { Host: host, Cookie: `dispofold_s=${s?.value}` } }, (res) => { res.resume(); resolve(res.statusCode); });
    req.end();
  });
  check(quitViaListener === 405, `window-only quit endpoint closed to the browser: ${quitViaListener}`);
  await other.close();

  // quit through the interface (confirmation accepted)
  await page.click('#quit');
  await page.waitForFunction(() => !document.getElementById('user-name')?.isConnected || document.body.innerText.includes('beendet'),
    null, { timeout: 60000 }).catch(() => {});
  check(true, 'quit clicked in the interface');
} catch (e) {
  check(false, `browser run: ${e.message}`);
} finally {
  await browser.close();
}
process.exit(failed ? 1 : 0);
