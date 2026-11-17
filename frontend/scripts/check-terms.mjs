// Playwright check of the terms: every page, for planners and viewers, shows none of the old names (Sollstunden,
// Fixe Dienste, Eingaben geändert, Schichtmix, Familienwunsch, hart/weich, ...) in its text, tooltips or labels; the
// plan status reads Entwurf or abgeschlossen. Also: the menu has the groups Dienstplan, Prüfen, Eingaben, Personen and Einstellungen, viewers get Dienstplan, Personen and Fairness only, the address of Daten
// shows viewers the Dienstplan, the month picker shows on month pages only, the people list names the areas, the mix fields have labels, the note on the
// night fairness is shown in the person form and next to the mix on the person page, the import page shows no paths.
// Demo data of the dev server (mock API), month 2027-01 planned.
//   npm run build && node scripts/check-terms.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const month = '2027-01';
const OLD = /Sollstunden|Monatssoll|Ziel für diesen Plan|\bfixe[nrs]?\b|Eingaben geändert|Vormonat geändert|Schichtmix|\bMix\b|Familienw[uü]nsch|Blocktage|Wer kann gefragt|\bhart(e[nrs]?)?\b|\bweich(e[nrs]?)?\b|\bStufen?\b/i;
const PATHS = /docs\/|dispofold\/dataset|Version 2\b/;
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
    const context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1600, height: 1000 } });
    await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
    const page = await context.newPage();
    page.on('pageerror', (e) => check('no page error', false, e.message));
    page.on('dialog', (d) => d.dismiss());
    await page.goto(`${base}/login.html`);
    await page.fill('#j_username', user);
    await page.fill('#j_password', 'x');
    await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
    return page;
  };

  /** Opens a page and waits until the view is drawn. */
  const open = async (page, hash) => {
    await page.goto(`${base}/#/${hash}`);
    await page.waitForFunction(() => document.querySelector('#main h1') && !document.querySelector('#main > p.muted'));
    await page.waitForTimeout(400);
  };
  /** Every page of the navigation: each menu entry and the tabs of its group. */
  const pages = async (page) => {
    const groups = await page.locator('#nav a').evaluateAll((as) => as.map((a) => a.getAttribute('href').slice(2)));
    const paths = [];
    for (const first of groups) {
      await open(page, first);
      const tabs = await page.locator('.page-tabs a').evaluateAll((as) => as.map((a) => a.getAttribute('href').slice(2)));
      paths.push(...(tabs.length ? tabs : [first]));
    }
    return paths;
  };
  /** Visible text plus tooltips and accessible names, with every collapsed section opened. */
  const allText = (page) => page.evaluate(() => {
    for (const d of document.querySelectorAll('details')) d.open = true;
    const attrs = [...document.querySelectorAll('[title], [aria-label]')].flatMap((el) => [el.title, el.getAttribute('aria-label')]);
    return [document.body.innerText, ...attrs].filter(Boolean).join('\n');
  });
  const scan = async (page, name) => {
    const text = await allText(page);
    const hit = text.split('\n').find((line) => OLD.test(line));
    check(`${name}: no old term`, !hit, hit);
    // a condition that rendered as text (e.g. `x && h(...)` appended to a DOM node)
    const stray = text.split('\n').find((line) => /^(false|true|null|undefined)$/.test(line.trim()));
    check(`${name}: no stray value`, !stray, stray);
  };

  const page = await login('planung');
  const { personId, areas } = await page.evaluate(async (m) => {
    await fetch('/api/import/demo', { method: 'POST' });
    await fetch(`/api/plans/${m}/solve`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: '{"seed":1,"timeLimitSeconds":1}' });
    const dataset = await (await fetch('/api/dataset')).json();
    // one person with shares per shift type, for the note on the night fairness on the person page
    const p = dataset.people[0];
    await fetch(`/api/dataset/people/${encodeURIComponent(p.id)}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...p, shiftMix: { categories: { DAY: 0.9, NIGHT: 0.1 }, areas: {} } }) });
    return { personId: p.id, areas: dataset.areas };
  }, month);
  // waitForFunction does not await an async predicate: poll from here until the solve has ended
  for (const end = Date.now() + 60000; await page.evaluate(async (m) => (await (await fetch(`/api/plans/${m}`)).json()).solving, month);) {
    if (Date.now() > end) throw new Error('solve did not end within 60 s');
    await page.waitForTimeout(500);
  }
  const routes = await pages(page);
  check('planner navigation has Daten', routes.includes('import'), routes.join(' '));
  check('planner menu', (await page.locator('#nav').innerText()).split('\n').join(' | ') === 'Dienstplan | Prüfen | Eingaben | Personen | Einstellungen',
    await page.locator('#nav').innerText());
  // the month picker shows on month pages only
  for (const [path, shown] of [['plan', true], ['eingaben', true], ['regeln', false], ['stammdaten', false], ['import', true]]) {
    await open(page, path);
    check(`month picker on ${path}: ${shown}`, await page.locator('.month-picker').isVisible() === shown);
  }

  for (const path of routes) {
    await open(page, path === 'person' ? `person/${encodeURIComponent(personId)}` : path);
    await scan(page, `planner ${path}`);
  }

  await open(page, `person/${encodeURIComponent(personId)}`);
  check('night note on the person page', (await page.locator('section[aria-labelledby="h-mix"]').innerText()).includes('Nachtausgleich'));

  // the plan status on screen: Entwurf or abgeschlossen, never "geplant"
  await open(page, 'plan');
  const status = await page.locator('.status-details strong').first().innerText();
  check('plan status reads Entwurf', status === 'Entwurf', status);

  // a gap list (Auf Anfrage verfügbar) in Plan nach Funktion
  await open(page, 'funktionen');
  const marker = page.locator('table.functions tr.gap-row .gap-marker').first();
  if (await marker.count()) {
    await marker.click();
    await page.locator('.popover .candidates[data-loaded]').first().waitFor({ timeout: 30000 });
    check('gap list titled Auf Anfrage verfügbar', (await page.locator('.popover').innerText()).includes('Auf Anfrage verfügbar'));
    await scan(page, 'gap list');
    await page.keyboard.press('Escape');
  }

  // Personen: area names in the people list, the person form with labelled mix fields and the night note (the note
  // next to the mix of the person page is checked above; Stammdaten has no people section any more)
  await open(page, 'person');
  await scan(page, 'planner people list');
  const areaCells = await page.locator('[data-table="people"] tbody tr').evaluateAll((rows) => rows.map((r) => {
    const col = [...r.closest('table').querySelectorAll('thead th')].findIndex((th) => th.textContent === 'Bereiche');
    return r.children[col]?.textContent ?? '';
  }));
  const names = new Set(areas.map((a) => a.name));
  check('people table shows area names', areaCells.length > 0 && areaCells.every((c) => c.split(', ').every((n) => names.has(n))),
    areaCells.slice(0, 3).join(' | '));
  await open(page, `person/${encodeURIComponent(personId)}`);
  const form = page.locator('#person-form');
  await form.waitFor();
  const unlabelled = await form.evaluate((f) => [...f.querySelectorAll('input[data-mix-cat], input[data-mix-area]')]
    .filter((i) => !i.id || !f.querySelector(`label[for="${i.id}"]`)).length);
  const mixFields = await form.locator('input[data-mix-cat], input[data-mix-area]').count();
  check('mix fields have labels', mixFields > 0 && unlabelled === 0, `${unlabelled} of ${mixFields} without label`);
  const areaLabels = await form.locator('input[name="areaIds"]').evaluateAll((cs) => cs.map((c) => c.parentElement.textContent.trim()));
  check('area checkboxes named', areaLabels.length > 0 && areaLabels.every((l) => areas.some((a) => l === `${a.name} (${a.id})`)), areaLabels.slice(0, 3).join(' | '));
  check('night note in the person form', (await form.innerText()).includes('Nachtausgleich'));
  await scan(page, 'person form');

  // Import page: no repository paths or format strings, demo data under Erweitert
  await open(page, 'import');
  const importText = await page.locator('#main').innerText();
  check('import page without paths and format strings', !PATHS.test(importText), importText.match(PATHS)?.[0]);
  check('demo data under Erweitert', await page.locator('details.advanced button', { hasText: 'Demodaten laden' }).count() === 1);

  // Viewers: no Import/Export, its address shows the Dienstplan; every other page without old terms
  const viewer = await login('ansicht');
  const viewerRoutes = await pages(viewer);
  check('viewer navigation: Dienstplan, Personen, Fairness', !viewerRoutes.includes('import')
    && (await viewer.locator('#nav').innerText()).split('\n').join(' | ') === 'Dienstplan | Personen | Fairness', viewerRoutes.join(' '));
  await open(viewer, 'import');
  check('viewer address of Import/Export shows the Dienstplan', viewer.url().endsWith('#/plan')
    && (await viewer.locator('#main h1').innerText()).startsWith('Dienstplan'), viewer.url());
  for (const path of viewerRoutes) {
    await open(viewer, path === 'person' ? `person/${encodeURIComponent(personId)}` : path);
    await scan(viewer, `viewer ${path}`);
  }
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('terms checked on every page');
