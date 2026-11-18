// Takes PNG screenshots of the main views with Playwright (dev dependency, Chromium via `npx playwright install chromium`).
//   BASE_URL=http://localhost:8080 APP_USER=planung APP_PASSWORD=... MONTH=2027-01 node scripts/screenshots.mjs [outDir]
// Logs in through the form and captures the views. With SEED_DEMO=1 it first loads demo data and solves when the
// month has no plan yet (this replaces the master data, so never use it against production).
// Every request to another host is blocked and makes the script fail, so it doubles as an offline check.
// Each view is captured in the light and the dark theme (files with the suffix "-dunkel").
// DESKTOP=1 (desktop program or `MOCK_DESKTOP=1 npm run dev`): no login, adds the Einstellungen page.
// German throughout: the full Chromium (channel 'chromium', not the headless shell, which has only English UI
// strings) with --lang and LANG/LANGUAGE renders date fields and file pickers in German; the context locale sets
// navigator.language and Intl.
import { mkdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { launchBrowser } from './browser.mjs';

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
const browser = await launchBrowser();
const context = await browser.newContext({ viewport: { width: 1600, height: 1000 }, locale: 'de-DE' });
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

  let analysis = await api('GET', `/api/plans/${month}/analysis`);
  // the demo plan has no gaps: with SEED_DEMO=1, free a few duties of one day (pinned free) for the gap views
  if (process.env.SEED_DEMO === '1' && !analysis.gaps.length) {
    const freed = state.plan.assignments.filter((a) => a.shiftId && a.date === `${month}-12`).slice(0, 3);
    for (const a of freed) await api('PUT', `/api/plans/${month}/cells`, { personId: a.personId, date: a.date, shiftId: null, compRest: false, pinned: true });
    analysis = await api('GET', `/api/plans/${month}/analysis`);
  }
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
    // Plan nach Funktion, then the list of who could be asked at the first gap cell of a row "offen" (planners only)
    await shot('funktionen', 'funktionen', 'table.functions');
    // the first gap cell whose list names somebody (else the first one, showing that nobody fits)
    const markers = page.locator('table.functions tr.gap-row .gap-marker');
    const count = Math.min(await markers.count(), 60);
    for (let i = 0; i < count; i++) {
      await markers.nth(i).click();
      await page.locator('.popover .candidates[data-loaded]').first().waitFor({ timeout: 30000 });
      if (await page.locator('.popover .candidates li').count() || i === count - 1) break;
      await page.keyboard.press('Escape');
    }
    if (count) {
      await page.waitForTimeout(500);
      await save('anfrage');
    }
    // Prüfen with the list of who could be asked opened at the first gap
    await shot('pruefen', 'pruefen', '#check-data');
    const ask = page.locator('#check-gaps .gap-item button').first();
    if (await ask.count()) {
      await ask.click();
      await page.locator('#check-gaps .candidates[data-loaded]').first().waitFor({ timeout: 30000 });
      await page.locator('#check-gaps').evaluate((el) => { el.scrollIntoView(); window.scrollBy(0, -120); });
      await page.waitForTimeout(500);
      await save('pruefen-bedarf');
    }
    await shot('eingaben', 'eingaben', '#inputs-filter');
    await shot('person', `person/${encodeURIComponent(someone)}`, '.hits-day, .alert');
    await shot('fairness', 'fairness', '.spread svg');
    await shot('regeln', 'regeln', '#h-goals');
    // Stammdaten (areas, shifts, demand, ...), the list of people and the form on the person page (planners edit a person there)
    await shot('stammdaten', 'stammdaten', '[data-table="shifts"]');
    await shot('personen', 'person', '[data-table="people"]');
    await page.goto(`${base}/#/person/${encodeURIComponent(someone)}`);
    await page.locator('#person-form').waitFor({ timeout: 30000 });
    await page.locator('#h-edit').evaluate((el) => el.scrollIntoView());
    await page.waitForTimeout(500);
    await save('person-bearbeiten');
    await shot('daten', 'import', '#h-import');
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
