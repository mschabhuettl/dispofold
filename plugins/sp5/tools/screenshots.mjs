// Takes the screenshots of the plugin's help pages (plugins/sp5/docs/img/) with Playwright, from synthetic data only:
// a fresh instance of the Docker build whose allowed folder (DISPOFOLD_PLUGIN_ROOTS) holds, as "ordner", a folder
// written by the generator of the comparison job (python3 -m tools.sp5writer --month 2027-01 --people 40, from the
// pinned commit of sp5-export), and that generator's example mapping:
//   cd frontend && BASE_URL=http://localhost:8080 APP_USER=planer APP_PASSWORD=... \
//     node ../plugins/sp5/tools/screenshots.mjs <mapping.json> ../plugins/sp5/docs/img
// First the thirteen steps of the beginner page (echte-daten-*.png, echte-daten.md) through the interface as a planner
// does them, in the month a fresh instance shows first: switch the plugin on, choose the folder, save, the missing
// mapping, the proposals taken and saved, a run of January 2027 (with the note above the import of a first run without
// any corrections), its report, the import and its month in the plan. Then the generator's mapping is set and January
// imported again, a person and a person's exclusion are changed in Dispofold, the export runs again and the pages are
// taken as a planner sees them, with the proposals for an empty mapping; last the note above the import when January is
// closed. It replaces the master data: never against production. Every request to another host is blocked. At the end
// scripts/optimize-png.py shrinks the files.
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

/** Without the pointer and the focus; the toast too unless it is what the picture shows. */
async function quiet(toast = false) {
  await page.mouse.move(0, 0);
  await page.evaluate((keep) => {
    if (!keep) document.getElementById('toast')?.setAttribute('hidden', '');
    document.activeElement?.blur();
  }, toast);
  await page.waitForTimeout(400);
}

/** The window; outlined: selectors of elements framed in it (the frames are removed afterwards). */
async function shot(name, outlined = [], toast = false) {
  for (const s of outlined) {
    await page.locator(s).first().evaluate((el) => {
      const r = el.getBoundingClientRect();
      const d = document.createElement('div');
      d.className = 'shot-mark';
      Object.assign(d.style, { position: 'absolute', zIndex: '2147483647', pointerEvents: 'none', left: `${r.left + scrollX - 4}px`,
        top: `${r.top + scrollY - 4}px`, width: `${r.width + 8}px`, height: `${r.height + 8}px`, border: '3px solid #c2410c',
        borderRadius: '7px', boxSizing: 'border-box' });
      document.body.append(d);
    });
  }
  await quiet(toast);
  const path = join(outDir, `${name}.png`);
  await page.screenshot({ path });
  await page.evaluate(() => document.querySelectorAll('.shot-mark').forEach((m) => m.remove()));
  saved.push(path);
  console.log('saved', path);
}

const scrollTo = (selector) => page.locator(selector).first().evaluate((e) => e.scrollIntoView({ block: 'center' }));
const toastSays = (text) => page.locator('#toast', { hasText: text }).waitFor();

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

  // the beginner page, step by step in the month a fresh instance shows first
  const nav = () => page.locator('#nav a', { hasText: 'Schichtplaner 5' });
  const tab = (name) => page.locator('.page-tabs a', { hasText: name }).click();
  // 1. Einstellungen → Erweiterungen: switched on (the confirmation is accepted), the program shows itself again
  await open('erweiterungen');
  await shot('erweiterungen', ['.page-tabs a[aria-current="page"]', 'section.extension label.check']);
  await page.check('#ext-sp5');
  await page.locator('#sp5-folder option').first().waitFor({ state: 'attached' });
  await nav().waitFor();
  await shot('echte-daten-01-einschalten', ['#nav a:has-text("Schichtplaner 5")', 'section.extension h3']);
  // 2. the folder: the allowed place, then its subfolder
  await page.locator('.folder-picker .folder-list button', { hasText: 'ordner/' }).click();
  await page.locator('.folder-picker p.muted', { hasText: 'ordner' }).waitFor();
  await scrollTo('.folder-picker');
  await shot('echte-daten-02-ordner', ['.folder-picker p.muted']);
  // 3. saved, the mapping still empty
  await page.click('#sp5-save');
  await toastSays('Einstellungen gespeichert');
  await shot('echte-daten-03-gespeichert', ['#sp5-save'], true);
  // 4. the pages of the plugin: the missing mapping
  await nav().click();
  await page.locator('#main .alert-error a', { hasText: 'Zur Zuordnung' }).waitFor();
  await shot('echte-daten-04-zuordnung-fehlt', ['#main .alert-error']);
  // 5. Zur Zuordnung: nothing saved yet, the check finds no area
  await page.locator('#main .alert-error a', { hasText: 'Zur Zuordnung' }).click();
  await page.locator('#sp5-map-problems', { hasText: 'Es ist noch kein Bereich eingetragen.' }).waitFor();
  await shot('echte-daten-05-zuordnung-leer', ['#sp5-map-problems']);
  // 6. the proposals with their reasons
  await page.click('#sp5-propose');
  await page.locator('#sp5-proposals-summary').waitFor();
  await page.locator('#h-sp5-propose').evaluate((e) => e.scrollIntoView());
  await shot('echte-daten-06-vorschlaege', ['#sp5-proposals-summary']);
  // 7. every section taken, then each choice with its first area
  while (await page.locator('[data-take-all]').count()) await page.locator('[data-take-all]').first().click();
  const choice = page.locator('#sp5-proposals button', { hasText: /^Übernehmen: / });
  while (await choice.count()) await choice.first().click();
  await page.locator('#sp5-proposals-none').waitFor();
  await page.locator('#sp5-map-problems', { hasText: 'Keine Probleme.' }).waitFor();
  await page.evaluate(() => scrollTo(0, 0));
  await shot('echte-daten-07-uebernommen', ['#sp5-map-dirty', '#sp5-map-problems', '#sp5-proposals-none']);
  // 8. saved
  await page.click('#sp5-map-save');
  await toastSays('Zuordnung gespeichert');
  await page.locator('#sp5-map-dirty').waitFor({ state: 'hidden' });
  await page.evaluate(() => scrollTo(0, 0));
  await shot('echte-daten-08-gespeichert', ['#sp5-map-save'], true);
  // 9. Lauf und Import: the first month
  await tab('Lauf und Import');
  await page.locator('#sp5-run-month').waitFor();
  await page.fill('#sp5-run-month', month);
  await shot('echte-daten-09-monat', ['#sp5-run-month']);
  // 10. the run with its figures; before the first import, with no corrections at all: the note above the import
  await page.click('#sp5-run');
  await page.locator('#sp5-run-summary').waitFor();
  await page.locator('#h-sp5-import ~ div .corrections-hint .alert').waitFor();
  await scrollTo('#sp5-run-summary');
  await shot('echte-daten-10-lauf', ['#sp5-run-summary .kpis']);
  await clip('echte-daten-10-lauf-zahlen', ['#sp5-run-summary']);
  await clip('hinweis', ['#h-sp5-import', '#h-sp5-import ~ div .corrections-hint']);
  // 11. the report with its warnings
  await page.locator('#sp5-run-summary a', { hasText: 'Bericht ansehen' }).click();
  await page.locator('#sp5-report-count').waitFor();
  await shot('echte-daten-11-bericht', ['#sp5-report-count']);
  await scrollTo('#h-sp5-warnings');
  await shot('echte-daten-11-bericht-warnungen', ['#h-sp5-warnings']);
  // 12. the import of this run, without keeping anything (the question is accepted)
  await tab('Lauf und Import');
  await page.locator('#sp5-import-request').waitFor();
  await page.uncheck('#plugin-keep-sp5');
  await page.click('section:has(#h-sp5-import) .toolbar button.btn-primary');
  const summary = page.locator('section:has(#h-sp5-import) .alert', { hasText: 'Import aus' });
  await summary.waitFor({ timeout: 60000 });
  await scrollTo('section:has(#h-sp5-import) .alert:has-text("Import aus")');
  await shot('echte-daten-12-import', ['section:has(#h-sp5-import) .alert:has-text("Import aus")']);
  // 13. to the imported month, then the plan
  const toMonth = summary.locator('button', { hasText: /^Zu .* wechseln$/ });
  console.log('import result:', (await summary.innerText()).replace(/\s+/g, ' '));
  await toMonth.click();
  await page.locator('#nav a[data-group="plan"]').click();
  await page.locator('.roster-wrap table.roster').waitFor({ timeout: 60000 });
  await shot('echte-daten-13-dienstplan', ['.month-picker']);

  // the pages as a planner sees them later: the generator's mapping, January imported again, in January
  await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
  const settings = await api('GET', '/api/plugins/sp5/einstellungen');
  await api('PUT', '/api/plugins/sp5/einstellungen', { ...settings, mapping });
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
  // the proposals for an empty mapping in the editor (not saved)
  await page.click('summary:text("JSON anzeigen und bearbeiten")');
  await page.fill('#sp5-map-json', JSON.stringify({ areas: [], shifts: [], groups: [] }));
  await page.click('#sp5-map-apply');
  await page.click('#sp5-propose');
  await page.locator('#sp5-proposals-summary').waitFor();
  await clip('zuordnung-vorschlaege', ['#h-sp5-propose', '#sp5-proposals']);

  // Vergleich: the run against the last import
  await open('plugin/sp5/lauf-vergleich');
  await page.locator('#sp5-cmp-against').waitFor();
  await shot('vergleich');

  // January closed (a plan of one duty): the note above the import of a run of it, with the next step
  d = await api('GET', '/api/dataset');
  const day = await api('GET', `/api/plans/${month}`);
  if (!day.plan?.assignments?.length) {
    await api('POST', `/api/plans/${month}/import`, { format: 'dispofold/plan', version: 1, month,
      assignments: [{ personId: first.id, date: `${month}-04`, shiftId: d.shifts[0].id, compRest: false, pinned: false }] });
  }
  await api('POST', `/api/plans/${month}/finalize`);
  await open('plugin/sp5/uebersicht');
  await page.fill('#sp5-run-month', month);
  await page.click('#sp5-run');
  await page.locator('#sp5-import-closed .alert').waitFor();
  await clip('import-abgeschlossen', ['#h-sp5-import', '#sp5-import-closed']);
} finally {
  await browser.close();
}
if (external.length) throw new Error(`requests to other hosts: ${external.join(', ')}`);
const optimize = spawnSync('python3', [join(here, '..', '..', '..', 'scripts', 'optimize-png.py'), ...saved], { stdio: 'inherit' });
if (optimize.status !== 0) process.exit(1);
