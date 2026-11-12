// Takes PNG screenshots of the main views with Playwright (dev dependency, Chromium via `npx playwright install chromium`).
//   BASE_URL=http://localhost:8080 APP_USER=planung APP_PASSWORD=... MONTH=2027-01 node scripts/screenshots.mjs [outDir]
// Logs in through the form and captures the views. With SEED_DEMO=1 it first loads demo data and solves when the
// month has no plan yet (this replaces the master data, so never use it against production).
// Every request to another host is blocked and makes the script fail, so it doubles as an offline check.
// Each view is captured in the light and the dark theme (files with the suffix "-dunkel").
// DESKTOP=1 (desktop program or `MOCK_DESKTOP=1 npm run dev`): no login, adds the Einstellungen page.
import { mkdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { chromium } from 'playwright';

const base = process.env.BASE_URL ?? 'http://localhost:8080';
const user = process.env.APP_USER ?? 'planung';
const password = process.env.APP_PASSWORD ?? 'planung';
const month = process.env.MONTH ?? '2027-01';
const outDir = resolve(process.argv[2] ?? 'screenshots-out');
const desktop = process.env.DESKTOP === '1';
const SUFFIXES = ['', '-dunkel']; // light, dark
mkdirSync(outDir, { recursive: true });

const origin = new URL(base).origin;
const external = [];
const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1600, height: 1000 }, locale: 'de-AT' });
await context.route('**/*', (route) => {
  const url = route.request().url();
  if (url.startsWith(origin) || url.startsWith('data:') || url.startsWith('blob:')) return route.continue();
  external.push(url);
  return route.abort();
});
await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
const page = await context.newPage();
page.on('pageerror', (e) => console.error('page error:', e.message));

let suffix = '';

async function save(name, fullPage = false) {
  const path = join(outDir, `${name}${suffix}.png`);
  await page.screenshot({ path, fullPage });
  console.log('saved', path);
}

async function shot(name, hash, ready) {
  await page.goto(`${base}/#/${hash}`);
  await page.locator(ready).first().waitFor({ timeout: 30000 });
  await page.waitForTimeout(800);
  await save(name);
}

try {
  if (!desktop) {
    for (suffix of SUFFIXES) {
      await page.emulateMedia({ colorScheme: suffix ? 'dark' : 'light' });
      await page.goto(`${base}/login.html?error=1`);
      await save('login');
    }
    await page.emulateMedia({ colorScheme: 'light' });
    await page.fill('#j_username', user);
    await page.fill('#j_password', password);
    await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
  } else {
    await page.goto(base);
  }

  const api = async (method, path, body) => page.evaluate(async ([m, p, b]) => {
    const r = await fetch(p, { method: m, headers: { 'Content-Type': 'application/json' }, body: b ? JSON.stringify(b) : undefined });
    if (!r.ok) throw new Error(`${m} ${p}: ${r.status}`);
    const t = await r.text();
    return t ? JSON.parse(t) : null;
  }, [method, path, body]);

  let state = await api('GET', `/api/plans/${month}`);
  if (!state.solving && !state.plan.assignments.length) {
    if (process.env.SEED_DEMO !== '1') throw new Error(`no plan for ${month}; plan it first or run with SEED_DEMO=1`);
    console.log('no plan yet: loading demo data and solving');
    await api('POST', '/api/import/demo');
    state = await api('POST', `/api/plans/${month}/solve`, {}); // the server's reproducible defaults
  }
  while (state.solving) {
    await page.waitForTimeout(2000);
    state = await api('GET', `/api/plans/${month}`);
  }

  const analysis = await api('GET', `/api/plans/${month}/analysis`);
  const gapArea = analysis.gaps[0]?.areaId;
  const gapDay = analysis.gaps[0]?.from.slice(0, 10);
  const someone = state.plan.assignments.find((a) => a.shiftId)?.personId;

  for (suffix of SUFFIXES) {
    await page.emulateMedia({ colorScheme: suffix ? 'dark' : 'light' });
    await shot('dienstplan', 'plan', 'table.roster');
    await page.evaluate(([a]) => { if (a) sessionStorage.setItem('timeline-area', a); sessionStorage.setItem('timeline-mode', 'day'); }, [gapArea]);
    await page.goto(`${base}/#/zeitachse`);
    await page.locator('.coverage svg').waitFor({ timeout: 30000 });
    if (gapDay) {
      await page.selectOption('#tl-date', gapDay);
      await page.locator('#tl-date').dispatchEvent('change');
    }
    // Bryntum keeps hidden, recycled event elements: wait for a visible one
    await page.locator('.b-sch-event:visible, .b-grid-empty:visible').first().waitFor({ timeout: 30000 });
    await page.waitForTimeout(1000);
    await save('zeitachse', true);
    await shot('person', `person/${encodeURIComponent(someone)}`, '.hits-day, .alert');
    await shot('fairness', 'fairness', '.spread svg');
    await shot('regeln', 'regeln', '.rule-row');
    if (desktop) await shot('einstellungen', 'einstellungen', '.settings');
  }
} finally {
  await browser.close();
}

if (external.length) {
  console.error('requests to other hosts (blocked):', [...new Set(external)]);
  process.exit(1);
}
console.log('no requests to other hosts');
