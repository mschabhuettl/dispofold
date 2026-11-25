// Takes the screenshots of the plugin's help pages (plugins/sp5/docs/img/) with Playwright, from synthetic data only:
// a fresh instance of the Docker build whose allowed folder (DISPOFOLD_PLUGIN_ROOTS) holds, as "ordner", a folder
// written by the generator of the comparison job (python3 -m tools.sp5writer --month 2027-01 --people 40, from the
// pinned commit of sp5-export), and that generator's example mapping:
//   cd frontend && BASE_URL=http://localhost:8080 APP_USER=planer APP_PASSWORD=... \
//     node ../plugins/sp5/tools/screenshots.mjs <mapping.json> ../plugins/sp5/docs/img
// First the ten steps of the beginner page (echte-daten-*.png, echte-daten.md) through the interface as a planner does
// them, in the month a fresh instance shows first: switch the plugin on, Einrichten, choose and save the folder, the
// mapping by the assistant in one click, Einlesen of January 2027 (with the note above Übernehmen of a first run
// without any corrections), its report, Übernehmen and its month in the plan. Then the generator's mapping is set and
// January imported again, a person and a person's exclusion are changed in Dispofold, January is read again and the
// pages are taken as a planner sees them, with the proposals for an empty mapping; last the note above Übernehmen when
// January is closed. It replaces the master data: never against production. Every request to another host is blocked. At the end
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
  const tab = (name) => page.locator('.page-tabs a', { hasText: name }).click();
  // 1. Einstellungen → Erweiterungen: switched on (the confirmation is accepted), the program shows itself again
  await open('erweiterungen');
  await shot('erweiterungen', ['.page-tabs a[aria-current="page"]', 'section.extension label.check']);
  await page.check('#ext-sp5');
  await page.locator('#sp5-set-up').waitFor();
  await shot('echte-daten-01-einschalten', ['#sp5-set-up']);
  // 2. Einrichten: Daten → Übernehmen with this source; the allowed place, then its subfolder
  await page.click('#sp5-set-up');
  await page.locator('#sp5-folder option').first().waitFor({ state: 'attached' });
  await page.locator('.folder-picker .folder-list button', { hasText: 'ordner/' }).click();
  await page.locator('.folder-picker p.muted', { hasText: 'ordner' }).waitFor();
  await scrollTo('.folder-picker');
  await shot('echte-daten-02-ordner', ['.folder-picker p.muted', '#sp5-folder-save']);
  // 3. saved: the people found, the mapping still missing
  await page.click('#sp5-folder-save');
  await toastSays('Ordner gespeichert');
  await page.locator('#sp5-source-line', { hasText: 'gefunden' }).waitFor();
  await page.evaluate(() => scrollTo(0, 0));
  await shot('echte-daten-03-quelle', ['#sp5-source-line', '#sp5-mapping-line a']);
  // 4. Zuordnung einrichten: the assistant with the proposals and the areas they bring
  await page.click('#sp5-mapping-line a');
  await page.locator('#sp5-assistant-take').waitFor();
  await shot('echte-daten-04-assistent', ['#sp5-assistant-count', '#sp5-assistant-take']);
  await clip('assistent', ['#sp5-assistant']);
  // 5. taken and saved with one click; a choice left is taken with its first area and saved too
  await page.click('#sp5-assistant-take');
  await page.locator('#sp5-assistant-done').waitFor();
  await shot('echte-daten-05-gespeichert', ['#sp5-assistant-done'], true);
  if (await page.locator('#sp5-assistant-done', { hasText: 'Wahl' }).count()) {
    await page.click('#sp5-propose');
    await page.locator('#sp5-proposals-summary, #sp5-proposals-none').first().waitFor();
    const choice = page.locator('#sp5-proposals button', { hasText: /^Übernehmen: / });
    while (await choice.count()) await choice.first().click();
    await page.click('#sp5-map-save');
    await toastSays('Zuordnung gespeichert');
  }
  // 6. Übernehmen: the month, Einlesen (nothing changes yet)
  await tab('Übernehmen');
  await page.locator('#sp5-run-month').waitFor();
  await page.selectOption('#sp5-run-month', month);
  await shot('echte-daten-06-einlesen', ['#sp5-run-month', '#sp5-run', '#sp5-run-note']);
  // 7. the result; before the first import, with no corrections at all: the note above Übernehmen
  await page.click('#sp5-run');
  await page.locator('#sp5-run-summary').waitFor();
  await page.locator('#take-over-source .corrections-hint .alert').waitFor();
  await scrollTo('#sp5-run-summary');
  await shot('echte-daten-07-ergebnis', ['#sp5-report-verdict', '#sp5-run-summary .kpis', '#sp5-take-what']);
  await clip('hinweis', ['#h-sp5-import', '#take-over-source .corrections-hint']);
  // 8. the report, in the result
  await page.click('#sp5-details-report summary');
  await page.locator('#sp5-report-count').waitFor();
  await scrollTo('#sp5-details-report');
  await shot('echte-daten-08-bericht', ['#sp5-details-report summary', '#sp5-report-count']);
  // 9. Übernehmen, without keeping anything: the month of the run is chosen
  await page.click('#sp5-details-report summary');
  await page.uncheck('#plugin-keep-sp5');
  await page.click('[data-import-run="sp5"]');
  const summary = page.locator('#take-over-source .alert', { hasText: 'Import aus' });
  await summary.waitFor({ timeout: 60000 });
  console.log('import result:', (await summary.innerText()).replace(/\s+/g, ' '));
  await scrollTo('#import-check-areas');
  await shot('echte-daten-09-uebernommen', ['#import-month', '#import-check-areas']);
  // 10. the plan of that month
  await page.locator('#import-month a').click();
  await page.locator('.roster-wrap table.roster').waitFor({ timeout: 60000 });
  await shot('echte-daten-10-dienstplan', ['.month-picker']);

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

  // Übernehmen: January read, the page as a whole; the source with its options
  const read = async () => {
    await page.selectOption('#sp5-run-month', month);
    await Promise.all([page.waitForResponse((r) => r.url().endsWith('/api/plugins/sp5/lauf') && r.request().method() === 'POST'), page.click('#sp5-run')]);
    await page.locator('#sp5-run-summary').waitFor();
  };
  await page.reload();
  await open('uebernehmen/sp5');
  await read();
  await shot('uebersicht');
  await page.click('#sp5-source summary');
  await page.click('#sp5-advanced summary');
  await clip('optionen', ['#sp5-source']);
  await page.click('#sp5-source summary');

  // Übernehmen keeping the changes made in Dispofold: its preview
  await page.check('#plugin-keep-sp5');
  await page.click('[data-import-run="sp5"]');
  await page.locator('#import-preview').waitFor();
  await clip('import', ['#h-sp5-import', '#import-preview']);

  // Bericht and Vergleich: the fold-outs of the result
  await open('uebernehmen/sp5');
  await page.locator('#sp5-run-summary').waitFor();
  await page.click('#sp5-details-report summary');
  await page.locator('#sp5-report-count').waitFor();
  await scrollTo('#sp5-details-report');
  await shot('bericht');
  await page.click('#sp5-details-report summary');
  await page.click('#sp5-details-compare summary');
  await page.locator('#sp5-cmp-against').waitFor();
  await scrollTo('#sp5-details-compare');
  await shot('vergleich');

  // Zuordnung: the editor, then what it does with the folder's codes
  await open('plugin/sp5/zuordnung');
  await page.locator('#sp5-codes table').first().waitFor();
  await shot('zuordnung');
  await clip('zuordnung-ordner', ['#sp5-codes']);
  // the proposals for an empty mapping in the editor (not saved)
  await page.click('#sp5-map-experts summary');
  await page.fill('#sp5-map-json', JSON.stringify({ areas: [], shifts: [], groups: [] }));
  await page.click('#sp5-map-apply');
  await page.click('#sp5-propose');
  await page.locator('#sp5-proposals-summary').waitFor();
  await clip('zuordnung-vorschlaege', ['#h-sp5-propose', '#sp5-proposals']);

  // January closed (a plan of one duty): the note above Übernehmen of a run of it, with the next step
  d = await api('GET', '/api/dataset');
  const day = await api('GET', `/api/plans/${month}`);
  if (!day.plan?.assignments?.length) {
    await api('POST', `/api/plans/${month}/import`, { format: 'dispofold/plan', version: 1, month,
      assignments: [{ personId: first.id, date: `${month}-04`, shiftId: d.shifts[0].id, compRest: false, pinned: false }] });
  }
  await api('POST', `/api/plans/${month}/finalize`);
  page.removeAllListeners('dialog');
  page.on('dialog', (x) => x.accept()); // leaving the mapping not saved
  await open('uebernehmen/sp5');
  await read();
  await page.locator('#sp5-import-closed .alert').waitFor();
  await clip('import-abgeschlossen', ['#h-sp5-import', '#sp5-import-closed']);
} finally {
  await browser.close();
}
if (external.length) throw new Error(`requests to other hosts: ${external.join(', ')}`);
const optimize = spawnSync('python3', [join(here, '..', '..', '..', 'scripts', 'optimize-png.py'), ...saved], { stdio: 'inherit' });
if (optimize.status !== 0) process.exit(1);
