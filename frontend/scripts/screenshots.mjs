// Takes the PNG screenshots of the guide (docs/anleitung/) and the README with Playwright (dev dependency, Chromium via
// `npx playwright install chromium`), synthetic demo data only:
//   npm run build && SEED_DEMO=1 BASE_URL=http://localhost:8080 APP_USER=planer APP_PASSWORD=... \
//     VIEWER_USER=leser VIEWER_PASSWORD=... node scripts/screenshots.mjs ../docs/screenshots
// 1. Server phase against BASE_URL, a fresh instance of the Docker build (no plan for MONTH yet; SEED_DEMO=1 confirms
//    that its master data may be replaced, so never use it against production): the worked example of the guide
//    (beispiel-*.png) done through the interface as a planner would (demo data, a person, demand, inputs, planning, a
//    sick note after planning, the fixes, planning again, closing the month, the corrections file and the summary of
//    one to load), then the month
//    is opened again and the screens and dialogs of every page are taken, a few with numbered marks (the guide refers
//    to the numbers). A release notice is answered in the browser (GET /api/release), the instance checks nothing.
// 2. Desktop phase: the pages of the program window (password on the first start, the small window "Dispofold läuft")
//    from desktop-shell/pages, and the desktop-only screens of the interface (update question, Updates page, update
//    notice with "Jetzt installieren") from the dev server in desktop mode (MOCK_DESKTOP=1), which this script starts
//    itself on a free port; then Einstellungen → Erweiterungen from the dev server as a server.
//    PHASE=server or PHASE=desktop runs one phase only.
// The window frame pictures fenster*.png come from the real program window and are not made here.
// Every request to another host is blocked and makes the script fail. At the end scripts/optimize-png.py shrinks the
// files (256 colours, the strongest compression that scripts/check-repo.sh accepts).
// German throughout: the full Chromium (see browser.mjs) with the context locale de-DE renders date fields in German.
import { spawn, spawnSync } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchBrowser } from './browser.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const base = process.env.BASE_URL ?? 'http://localhost:8080';
const user = process.env.APP_USER ?? 'planer';
const password = process.env.APP_PASSWORD ?? '';
const viewerUser = process.env.VIEWER_USER ?? 'leser';
const viewerPassword = process.env.VIEWER_PASSWORD ?? '';
const month = process.env.MONTH ?? '2027-01';
const outDir = resolve(process.argv[2] ?? 'screenshots-out');
if (process.env.SEED_DEMO !== '1') throw new Error('SEED_DEMO=1 is required: the script replaces the master data with demo data');
mkdirSync(outDir, { recursive: true });
const VIEWPORT = { width: 1360, height: 850 };
const saved = [];
const external = [];
const work = mkdtempSync(join(process.env.TMPDIR ?? tmpdir(), 'shots-'));

const browser = await launchBrowser();

/** A context that blocks requests to other hosts, accepts every confirm() and starts in the given month. */
async function newPage(origin) {
  const context = await browser.newContext({ viewport: VIEWPORT, locale: 'de-DE', colorScheme: 'light' });
  await context.route('**/*', (route) => {
    const url = route.request().url();
    if (url.startsWith(origin) || url.startsWith('data:') || url.startsWith('blob:')) return route.continue();
    external.push(url);
    return route.abort();
  });
  await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
  const page = await context.newPage();
  page.on('pageerror', (e) => console.error('page error:', e.message));
  page.on('dialog', (d) => d.accept());
  return page;
}

// ---------------------------------------------------------------------------------------------------------------
// helpers on a page

/** Numbered marks for the guide: [selector or locator, number, side ('left', 'right', 'top', 'bottom', 'inside', 'end'), box?]. */
async function mark(page, marks) {
  for (const [target, n, side = 'left', box = false] of marks) {
    const loc = typeof target === 'string' ? page.locator(target).first() : target;
    await loc.waitFor({ state: 'visible', timeout: 15000 });
    await loc.evaluate((el, [num, where, outline]) => {
      const r = el.getBoundingClientRect();
      const x0 = r.left + scrollX;
      const y0 = r.top + scrollY;
      const add = (style, text = '') => {
        const d = document.createElement('div');
        d.className = 'shot-mark';
        d.textContent = text;
        Object.assign(d.style, { position: 'absolute', zIndex: '2147483647', pointerEvents: 'none' }, style);
        document.body.append(d);
      };
      if (outline) {
        add({ left: `${x0 - 4}px`, top: `${y0 - 4}px`, width: `${r.width + 8}px`, height: `${r.height + 8}px`,
          border: '3px solid #c2410c', borderRadius: '7px', boxSizing: 'border-box' });
      }
      const size = 26;
      let x = where === 'right' ? x0 + r.width + 6 : where === 'inside' ? x0 + 4 : where === 'end' ? x0 + r.width - size - 4 : x0 - size - 6;
      let y = where === 'top' ? y0 - size - 4 : where === 'bottom' ? y0 + r.height + 4 : y0 + r.height / 2 - size / 2;
      if (where === 'top') x = x0;
      if (where === 'bottom') x = x0 + r.width / 2 - size / 2;
      x = Math.max(2 + scrollX, x);
      y = Math.max(2 + scrollY, y);
      add({ left: `${x}px`, top: `${y}px`, width: `${size}px`, height: `${size}px`, borderRadius: '50%',
        background: '#c2410c', color: '#fff', font: '700 15px/26px system-ui, sans-serif', textAlign: 'center',
        boxShadow: '0 0 0 2px #fff, 0 2px 6px rgba(0,0,0,.35)' }, String(num));
    }, [n, side, box]);
  }
}
const unmark = (page) => page.evaluate(() => document.querySelectorAll('.shot-mark').forEach((m) => m.remove()));

async function file(name) {
  const path = join(outDir, `${name}.png`);
  saved.push(path);
  console.log('saved', path);
  return path;
}

/** The window as it is. */
/** No toast, no hover and no focus ring in the picture (unless an explanation is open, which needs its button). */
async function quiet(page) {
  await page.mouse.move(0, 0);
  await page.evaluate(() => {
    document.getElementById('toast')?.setAttribute('hidden', '');
    if (!document.querySelector('.info-text')) document.activeElement?.blur();
  });
}

async function shot(page, name, fullPage = false) {
  await page.waitForTimeout(400);
  await quiet(page);
  await page.screenshot({ path: await file(name), fullPage });
  await unmark(page);
}

/** A part of the page: the box around the given elements (and their marks), with some room. */
async function clip(page, name, targets, pad = 14) {
  await page.waitForTimeout(400);
  await quiet(page);
  const boxes = [];
  for (const target of [...targets, '.shot-mark']) {
    const all = typeof target === 'string' ? page.locator(target) : target;
    for (const b of await all.evaluateAll((els) => els.map((e) => {
      const r = e.getBoundingClientRect();
      return r.width && r.height ? [r.left + scrollX, r.top + scrollY, r.right + scrollX, r.bottom + scrollY] : null;
    }))) if (b) boxes.push(b);
  }
  if (!boxes.length) throw new Error(`${name}: nothing to take`);
  const size = await page.evaluate(() => [document.documentElement.scrollWidth, document.documentElement.scrollHeight]);
  const x = Math.max(0, Math.min(...boxes.map((b) => b[0])) - pad);
  const y = Math.max(0, Math.min(...boxes.map((b) => b[1])) - pad);
  const right = Math.min(size[0], Math.max(...boxes.map((b) => b[2])) + pad);
  const bottom = Math.min(size[1], Math.max(...boxes.map((b) => b[3])) + pad);
  await page.screenshot({ path: await file(name), fullPage: true, clip: { x, y, width: right - x, height: bottom - y } });
  await unmark(page);
}

async function open(page, origin, hash, ready) {
  const target = `${origin}/#/${hash}`;
  // the same address again would not draw the page anew
  if (page.url() === target) await page.reload();
  else await page.goto(target);
  await page.locator(ready).first().waitFor({ timeout: 30000 });
  await page.waitForTimeout(600);
}

const api = (page, method, path, body) => page.evaluate(async ([m, p, b]) => {
  const r = await fetch(p, { method: m, headers: { 'Content-Type': 'application/json' }, body: b ? JSON.stringify(b) : undefined });
  if (!r.ok) throw new Error(`${m} ${p}: ${r.status} ${await r.text()}`);
  const text = await r.text();
  return text ? JSON.parse(text) : null;
}, [method, path, body]);

async function login(page, origin, name, pw) {
  await page.goto(`${origin}/login.html`);
  await page.fill('#j_username', name);
  await page.fill('#j_password', pw);
  await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
  await page.locator('#nav a').first().waitFor();
}

/** Waits until the plan of the month is no longer being solved. */
async function solved(page) {
  for (;;) {
    const state = await api(page, 'GET', `/api/plans/${month}`);
    if (!state.solving) return state;
    await page.waitForTimeout(2000);
  }
}

const select = async (page, selector, value) => {
  await page.selectOption(selector, value);
  await page.locator(selector).dispatchEvent('change');
};

// ---------------------------------------------------------------------------------------------------------------
// 1. server phase

async function serverPhase() {
  const origin = new URL(base).origin;
  const page = await newPage(origin);

  // Erste Schritte: the login page, then with the message after a wrong password
  await page.goto(`${base}/login.html`);
  await clip(page, 'anmelden', ['main.center-box']);
  await page.goto(`${base}/login.html?error=1`);
  await clip(page, 'anmelden-fehler', ['main.center-box']);
  await login(page, base, user, password);

  const first = await api(page, 'GET', `/api/plans/${month}`).catch(() => null);
  if (first?.plan?.assignments?.length) throw new Error(`${month} has a plan already: the script needs a fresh instance`);

  // ---- the worked example ----
  // 1. demo data
  await open(page, base, 'import', '#h-import');
  await page.locator('section[aria-labelledby="h-import"] details.advanced summary').click();
  await page.getByRole('button', { name: 'Demodaten laden (Jänner 2027)', exact: true }).click();
  await page.locator('#main div[aria-live] .alert').first().waitFor({ timeout: 60000 });
  await mark(page, [[page.getByRole('button', { name: 'Demodaten laden (Jänner 2027)', exact: true }), 1], ['#main div[aria-live] .alert', 2]]);
  await clip(page, 'beispiel-demodaten', ['#main h1', '#main div[aria-live] .alert', 'section[aria-labelledby="h-import"]']);

  const dataset = await api(page, 'GET', '/api/dataset');
  const areaName = (id) => dataset.areas.find((a) => a.id === id)?.name ?? id;
  // the area with the fewest people (there a sick note leaves a gap) and the one with the most (more demand there is
  // covered without gaps)
  const bySize = [...dataset.areas].sort((a, b) => dataset.people.filter((p) => p.areaIds.includes(a.id)).length
    - dataset.people.filter((p) => p.areaIds.includes(b.id)).length);
  const small = bySize[0].id;
  const large = bySize.at(-1).id;
  const smallPeople = dataset.people.filter((p) => p.areaIds.includes(small));

  // 2. Prüfen without a plan: only the check of the master data
  await open(page, base, 'pruefen', '#check-data');
  await mark(page, [['#check-data h2', 1]]);
  await clip(page, 'beispiel-stammdaten-pruefen', ['#main h1', '#check-data']);

  // 3. a person works fewer hours from this month on
  const parttime = smallPeople.find((p) => p.weeklyHours >= 30) ?? smallPeople[0];
  await open(page, base, `person/${encodeURIComponent(parttime.id)}`, '#person-form');
  await page.locator('#h-edit').evaluate((e) => { e.scrollIntoView(); scrollBy(0, -20); });
  await page.locator('#person-form #f-weeklyHours').fill(String(parttime.weeklyHours - 4));
  await mark(page, [['#person-form #f-weeklyHours', 1, 'left', true],
    [page.locator('#person-form button[type=submit], #person-form .btn-primary').first(), 2]]);
  await clip(page, 'beispiel-person', ['#h-edit', '#person-form #f-firstName', '#person-form #f-weeklyHours',
    page.locator('#person-form button[type=submit], #person-form .btn-primary').first()]);
  await page.locator('#person-form button[type=submit], #person-form .btn-primary').first().click();
  await page.waitForTimeout(1500);

  // 4. demand: one more person on working days in the late afternoon in the large area
  await open(page, base, 'stammdaten', '[data-table="demand"]');
  await page.fill('#filter-demand', areaName(large));
  const rows = page.locator('[data-table="demand"] tbody tr:visible', { hasText: 'Mo' });
  const late = rows.filter({ hasText: /14:30\s*19:00/ });
  const demandRow = (await late.count()) ? late.first() : rows.first();
  await demandRow.locator('button', { hasText: 'Bearbeiten' }).click();
  const form = page.locator('tr.edit-row');
  const min = form.locator("#f-min");
  await min.fill(String(Number(await min.inputValue()) + 1));
  await mark(page, [[min, 1, 'left', true], [form.getByRole('button', { name: 'Speichern', exact: true }), 2, 'right']]);
  await clip(page, 'beispiel-bedarf', ['section:has([data-table="demand"]) h2', '[data-table="demand"] thead', 'tr:has(+ tr.edit-row)', 'tr.edit-row']);
  await form.getByRole('button', { name: 'Speichern', exact: true }).click();
  await page.waitForTimeout(1500);

  // 5. inputs: a holiday and a wish
  const holidayPerson = dataset.people.find((p) => p.areaIds.includes(small) && p.id !== parttime.id) ?? dataset.people[1];
  const wishPerson = dataset.people.find((p) => p.areaIds.length === 1 && p.id !== holidayPerson.id) ?? dataset.people[2];
  await open(page, base, 'eingaben', '#inputs-filter');
  await select(page, '#abs-person', holidayPerson.id);
  await select(page, '#abs-from', `${month}-25`);
  await select(page, '#abs-to', `${month}-29`);
  await select(page, '#abs-type', dataset.absenceTypes.find((a) => /urlaub/i.test(a.name))?.code ?? dataset.absenceTypes[0].code);
  await page.locator('section[aria-labelledby="h-absences"] .toolbar button', { hasText: 'Hinzufügen' }).click();
  await select(page, '#wish-person', wishPerson.id);
  await select(page, '#wish-date', `${month}-15`);
  await select(page, '#wish-kind', 'OFF');
  await page.locator('section[aria-labelledby="h-wishes"] .toolbar button', { hasText: 'Hinzufügen' }).click();
  await page.evaluate(() => scrollTo(0, 0));
  await mark(page, [[page.getByRole('button', { name: 'Speichern', exact: true }), 1], ['#dirty-note', 2, 'right'],
    ['section[aria-labelledby="h-absences"] .toolbar', 3]]);
  await clip(page, 'beispiel-eingaben', ['#main h1', 'section[aria-labelledby="h-absences"] .toolbar']);
  await page.getByRole('button', { name: 'Speichern', exact: true }).click();
  await page.waitForTimeout(1500);

  // 6. plan
  await open(page, base, 'plan', 'table.roster, #main .btn-primary');
  await page.getByRole('button', { name: 'Planen', exact: true }).click();
  await page.locator('.statusbar', { hasText: 'Planung läuft' }).waitFor({ timeout: 30000 });
  await page.waitForTimeout(3000);
  await mark(page, [[page.getByRole('button', { name: 'Stopp', exact: true }), 1, 'top'], ['.statusbar .plan-headline', 2]]);
  await clip(page, 'beispiel-planen', ['.plan-actions', '.statusbar']);
  await solved(page);
  await open(page, base, 'plan', 'table.roster');
  await mark(page, [['.statusbar .plan-headline', 1], ['.statusbar .chips', 2], ['.statusbar .status-details', 3]]);
  await clip(page, 'beispiel-plan', ['.plan-actions', '.statusbar', 'details.legend-box']);

  // 7. Prüfen
  await open(page, base, 'pruefen', '#check-problems');
  await page.locator('#check-problems details.check-group[open] > summary').evaluateAll((s) => s.forEach((x) => x.click()));
  await mark(page, [['#check-problems h2', 1], ['#check-problems .toolbar', 2], ['#check-problems details.check-group > summary', 3],
    ['#check-gaps h2', 4], ['#check-data h2', 5]]);
  await shot(page, 'beispiel-pruefen');

  // 8. a sick note after planning: someone of the small area with duties there on two working days in a row
  let state = await api(page, 'GET', `/api/plans/${month}`);
  const shiftArea = new Map(dataset.shifts.map((s) => [s.id, s.areaId]));
  const duty = (pid, date) => state.plan.assignments.find((a) => a.personId === pid && a.date === date)?.shiftId;
  const dates = [...new Set(state.plan.assignments.map((a) => a.date))].sort().filter((d) => d >= `${month}-18`);
  const working = (d) => ![0, 6].includes(new Date(`${d}T12:00:00Z`).getUTCDay());
  // preferably on days where nobody else works there, so that the sick note leaves a gap
  const there = (d) => smallPeople.filter((x) => shiftArea.get(duty(x.id, d)) === small);
  let sick = null;
  for (const alone of [true, false]) {
    for (let i = 0; i + 1 < dates.length && !sick; i++) {
      const [d1, d2] = [dates[i], dates[i + 1]];
      if (!working(d1) || !working(d2)) continue;
      const p = there(d1).find((x) => there(d2).includes(x) && (!alone || (there(d1).length === 1 && there(d2).length === 1)));
      if (p) sick = { person: p, from: d1, to: d2 };
    }
  }
  if (!sick) throw new Error(`nobody of ${small} works there on two working days in a row`);
  await open(page, base, 'eingaben', '#inputs-filter');
  await select(page, '#abs-person', sick.person.id);
  await select(page, '#abs-from', sick.from);
  await select(page, '#abs-to', sick.to);
  await select(page, '#abs-type', dataset.absenceTypes.find((a) => /krank/i.test(a.name))?.code ?? dataset.absenceTypes[0].code);
  await page.locator('section[aria-labelledby="h-absences"] .toolbar button', { hasText: 'Hinzufügen' }).click();
  await page.getByRole('button', { name: 'Speichern', exact: true }).click();
  await page.waitForTimeout(1500);

  await page.evaluate((a) => sessionStorage.setItem('roster-area', a), small);
  await open(page, base, 'plan', 'table.roster');
  const cell = (d) => page.locator(`button.cell-btn[data-cell="${sick.person.id}|${d}"]`);
  await mark(page, [['.statusbar .plan-headline', 1], ['.statusbar .badge-warn', 2, 'right'], [cell(sick.from), 3, 'top', true],
    [cell(sick.to), 3, 'right', true]]);
  await shot(page, 'beispiel-konflikt');
  await cell(sick.from).click();
  await page.locator('.popover').waitFor();
  await mark(page, [[page.locator('.popover .btn-primary'), 1]]);
  await clip(page, 'beispiel-zelle', ['.popover', cell(sick.from)]);
  await page.locator('.popover .btn-primary').click();
  await page.waitForTimeout(1200);
  await cell(sick.to).click();
  await page.locator('.popover .btn-primary').click();
  await page.waitForTimeout(1500);
  await open(page, base, 'plan', 'table.roster');
  const gap = page.locator(`button.gap-btn[data-cell="gaps|${sick.to}"]`);
  await mark(page, [['.statusbar .plan-headline', 1], [gap, 2, 'top', true]]);
  await clip(page, 'beispiel-luecke', ['.statusbar', 'tr.sticky-gaps', gap]);
  await gap.click();
  await page.locator('.popover .candidates[data-loaded]').first().waitFor({ timeout: 30000 });
  await clip(page, 'beispiel-anfrage', ['.popover', gap]);
  const candidate = page.locator('.popover .candidates li button').first();
  if (await candidate.count()) {
    await candidate.click();
    await page.waitForTimeout(1500);
  } else await page.keyboard.press('Escape');

  // 9. plan again: the cells set by hand stay
  await open(page, base, 'plan', 'table.roster');
  await page.getByRole('button', { name: 'Planen', exact: true }).click();
  await page.locator('.statusbar', { hasText: 'Planung läuft' }).waitFor({ timeout: 30000 });
  await solved(page);
  await open(page, base, 'plan', 'table.roster');
  await mark(page, [['.statusbar .plan-headline', 1], [cell(sick.from), 2, 'top', true]]);
  await clip(page, 'beispiel-neu-geplant', ['.statusbar', cell(sick.from), cell(sick.to)]);

  // 10. close the month
  await page.locator('[aria-controls="output-menu"]').click();
  await page.locator('#output-menu').getByRole('button', { name: 'Monat abschließen', exact: true }).click();
  await page.locator('dialog.dialog').waitFor();
  await clip(page, 'beispiel-abschluss', ['dialog.dialog']);
  await page.locator('dialog.dialog .btn-primary').click();
  await page.waitForTimeout(1500);
  await open(page, base, 'plan', 'table.roster');
  await mark(page, [['.statusbar .status-details', 1]]);
  await clip(page, 'beispiel-abgeschlossen', ['.statusbar']);

  // 11. the corrections file
  await open(page, base, 'import', '#h-corrections');
  await page.getByRole('button', { name: 'Änderungen als Korrekturdatei exportieren', exact: true }).click();
  await page.locator('section[aria-labelledby="h-corrections"] .alert').waitFor();
  await page.locator('#h-corrections').evaluate((e) => e.scrollIntoView());
  await clip(page, 'beispiel-korrekturdatei', ['#h-corrections', 'section[aria-labelledby="h-corrections"] > p.muted',
    'section[aria-labelledby="h-corrections"] > .toolbar', 'section[aria-labelledby="h-corrections"] > div[aria-live] .alert']);
  // loading a corrections file: the summary before it is saved (cancelled, nothing loaded)
  const named = (await api(page, 'GET', '/api/dataset')).people.slice(0, 2).map((p) => p.id);
  await page.setInputFiles('#corrections-file', { name: 'korrekturen.json', mimeType: 'application/json',
    buffer: Buffer.from(JSON.stringify({ format: 'dispofold/corrections', version: 2, people: {
      [named[0]]: { weeklyHours: 30, hoursAccount: 12.5 }, [named[1]]: { weeklyHours: 'viel' }, E999999: { exclude: true } }, demand: {} })) });
  await page.getByRole('button', { name: 'Korrekturdatei laden', exact: true }).click();
  await page.locator('#corrections-confirm').waitFor();
  await clip(page, 'korrekturdatei-laden', ['#corrections-confirm']);
  await page.locator('#corrections-confirm').getByRole('button', { name: 'Abbrechen' }).click();

  // open the month again for the screens below
  await open(page, base, 'import', '#h-final');
  await page.locator('#h-final').evaluate((e) => e.scrollIntoView());
  await clip(page, 'monat-oeffnen', ['section[aria-labelledby="h-final"]']);
  await page.locator('section[aria-labelledby="h-final"] button.btn').click();
  await page.waitForTimeout(2000);

  // ---- the screens of every page ----
  // a few gaps on the 12th (duties pinned free) for the views of open demand
  state = await solved(page);
  const freed = state.plan.assignments.filter((a) => a.shiftId && a.date === `${month}-12`).slice(0, 3);
  for (const a of freed) await api(page, 'PUT', `/api/plans/${month}/cells`, { personId: a.personId, date: a.date, shiftId: null, compRest: false, pinned: true });
  const analysis = await api(page, 'GET', `/api/plans/${month}/analysis`);
  const gapArea = analysis.gaps[0]?.areaId;
  const gapDay = analysis.gaps[0]?.from.slice(0, 10);
  const someone = parttime.id;
  await page.evaluate(() => sessionStorage.removeItem('roster-area'));

  // Überblick: the parts of every page
  await open(page, base, 'plan', 'table.roster');
  await mark(page, [['#nav a[data-group="check"]', 1, 'end'], ['.month-picker', 2, 'left'], ['.page-tabs', 3], ['#main h1 + p.page-intro a', 4, 'right'],
    ['.statusbar .info-btn', 5, 'right'], ['#user-role', 6, 'bottom'], ['.theme-toggle', 7, 'bottom']]);
  await shot(page, 'uebersicht');
  await page.locator('.statusbar .with-info .info-btn').first().click();
  await clip(page, 'hilfe', ['.statusbar .plan-headline', '.info-text']);
  await page.keyboard.press('Escape');

  // Dienstplan
  for (const dark of [false, true]) {
    await page.emulateMedia({ colorScheme: dark ? 'dark' : 'light' });
    await open(page, base, 'plan', 'table.roster');
    await shot(page, dark ? 'dienstplan-dunkel' : 'dienstplan');
  }
  await page.emulateMedia({ colorScheme: 'light' });
  await open(page, base, 'plan', 'table.roster');
  await mark(page, [['#area', 1], ['#search', 2], ['#roster-names', 3], ['[aria-controls="output-menu"]', 4], ['details.legend-box summary', 5],
    ['tr.sticky-gaps th', 6]]);
  await shot(page, 'dienstplan-teile');
  await page.locator('details.legend-box summary').click();
  await clip(page, 'legende', ['details.legend-box']);
  await page.locator('details.legend-box summary').click();
  const anyCell = page.locator(`button.cell-btn[data-cell="${someone}|${month}-05"]`);
  await anyCell.click();
  await page.locator('.popover').waitFor();
  await clip(page, 'zelle', ['.popover', anyCell]);
  await page.keyboard.press('Escape');
  await page.locator('[aria-controls="output-menu"]').click();
  await clip(page, 'ausgabe', ['[aria-controls="output-menu"]', '#output-menu']);
  // the close dialog with open points (the gaps of the 12th): closing only after "Trotzdem abschließen"
  await page.locator('#output-menu').getByRole('button', { name: 'Monat abschließen', exact: true }).click();
  await page.locator('dialog.dialog #close-anyway').waitFor();
  await clip(page, 'abschluss-offen', ['dialog.dialog']);
  await page.locator('dialog.dialog').getByRole('button', { name: 'Abbrechen', exact: true }).click();
  await open(page, base, 'plan', 'table.roster');
  await page.locator('.plan-actions details.advanced summary, details.advanced summary').first().click();
  await mark(page, [['#seed', 1, 'bottom'], ['#limit', 2, 'bottom'], ['#move-limit', 3, 'bottom'], ['#cold-start', 4, 'bottom']]);
  await clip(page, 'planen-erweitert', ['.plan-actions', 'details.advanced']);
  await open(page, base, 'plan', 'table.roster');
  await mark(page, [['.statusbar .plan-headline', 1], ['.statusbar .chips', 2], ['.statusbar .status-details', 3]]);
  await clip(page, 'statuszeile', ['.statusbar']);
  await page.locator('.statusbar button.chip').first().click();
  await page.locator('.popover .hit-list').waitFor({ timeout: 30000 });
  await clip(page, 'treffer', ['.popover', '.statusbar button.chip']);
  await page.keyboard.press('Escape');

  // Funktionen with the list of who could be asked at the first gap
  await open(page, base, 'funktionen', 'table.functions');
  await shot(page, 'funktionen');
  const markers = page.locator('table.functions tr.gap-row .gap-marker');
  const count = Math.min(await markers.count(), 60);
  for (let i = 0; i < count; i++) {
    await markers.nth(i).click();
    await page.locator('.popover .candidates[data-loaded]').first().waitFor({ timeout: 30000 });
    if (await page.locator('.popover .candidates li').count() || i === count - 1) break;
    await page.keyboard.press('Escape');
  }
  if (count) await shot(page, 'anfrage');

  // Zeitachse at the first gap
  for (const dark of [false, true]) {
    await page.emulateMedia({ colorScheme: dark ? 'dark' : 'light' });
    await page.evaluate(([a]) => { if (a) sessionStorage.setItem('timeline-area', a); sessionStorage.setItem('timeline-mode', 'day'); }, [gapArea]);
    await page.goto(`${base}/#/zeitachse`);
    await page.locator('.coverage svg').waitFor({ timeout: 30000 });
    if (gapDay) await select(page, '#tl-date', gapDay);
    await page.locator('.b-sch-event:visible, .b-grid-empty:visible').first().waitFor({ timeout: 30000 });
    await page.waitForTimeout(1000);
    await shot(page, dark ? 'zeitachse-dunkel' : 'zeitachse', true);
    if (!dark) {
      await mark(page, [['#tl-area', 1], ['#tl-mode', 2], ['#tl-date', 3], ['.coverage', 4, 'inside'], ['.gap-list', 5, 'inside']]);
      await shot(page, 'zeitachse-teile', true);
    }
  }
  await page.emulateMedia({ colorScheme: 'light' });

  // Prüfen
  await open(page, base, 'pruefen', '#check-data');
  await page.locator('#check-problems details.check-group[open] > summary').evaluateAll((s) => s.forEach((x) => x.click()));
  await mark(page, [['#check-group', 1], ['#check-filter-rule', 2, 'top'], ['#check-problems details.check-group > summary', 3],
    ['#check-gaps h2', 4]]);
  await shot(page, 'pruefen');
  const ask = page.locator('#check-gaps .gap-item button').first();
  if (await ask.count()) {
    await ask.click();
    await page.locator('#check-gaps .candidates[data-loaded]').first().waitFor({ timeout: 30000 });
    await page.locator('#check-gaps').evaluate((el) => { el.scrollIntoView(); window.scrollBy(0, -20); });
    await shot(page, 'pruefen-bedarf');
  }
  // a finding of the master data check: an area without demand, for the picture only (removed again)
  const master = (method, path, body) => page.evaluate(async ([m, p, b]) => {
    const { revision } = await (await fetch('/api/dataset')).json();
    const r = await fetch(p, { method: m, headers: { 'Content-Type': 'application/json', 'If-Match': String(revision) },
      body: b ? JSON.stringify(b) : undefined });
    if (!r.ok) throw new Error(`${m} ${p}: ${r.status} ${await r.text()}`);
  }, [method, path, body]);
  await master('POST', '/api/dataset/areas', { id: 'BX', name: 'Beispielbereich', color: null });
  await open(page, base, 'pruefen', '#check-data');
  await page.locator('#check-data').evaluate((el) => el.scrollIntoView());
  await clip(page, 'pruefen-stammdaten', ['#check-data']);
  await master('DELETE', '/api/dataset/areas/BX?confirm=true');

  await open(page, base, 'fairness', '.spread svg');
  await shot(page, 'fairness');

  // Eingaben
  await open(page, base, 'eingaben', '#inputs-filter');
  // one person with entries in all three lists, so that everything fits on the screen
  const period = await api(page, 'GET', `/api/periods/${month}`);
  const has = (list, id) => period[list].some((x) => x.personId === id);
  const filled = dataset.people.find((p) => has('absences', p.id) && has('wishes', p.id) && has('fixed', p.id))
    ?? dataset.people.find((p) => has('fixed', p.id)) ?? dataset.people[0];
  await select(page, '#inputs-filter', filled.id);
  await mark(page, [['#inputs-filter', 1], ['#h-absences', 2], ['#h-wishes', 3], ['#h-fixed', 4], [page.getByRole('button', { name: 'Speichern', exact: true }), 5]]);
  await shot(page, 'eingaben');

  // Personen: the person page, its parts and the form
  await open(page, base, `person/${encodeURIComponent(someone)}`, '.hits-day, .alert');
  await shot(page, 'person');
  await page.locator('#h-why').evaluate((e) => e.scrollIntoView());
  await clip(page, 'person-warum', ['#h-why', page.locator('section[aria-labelledby="h-why"] .hits-day').nth(2)]);
  // the form of someone with restrictions and family rules
  const rich = dataset.people.find((p) => p.familyRules?.length && dataset.restrictions.some((r) => r.personId === p.id)) ?? parttime;
  await open(page, base, `person/${encodeURIComponent(rich.id)}`, '#person-form');
  await clip(page, 'person-bearbeiten', ['#h-edit', '#person-form']);

  // Stammdaten
  await open(page, base, 'stammdaten', '[data-table="shifts"]');
  await shot(page, 'stammdaten');
  await mark(page, [['[data-new="areas"]', 1, 'right'], ['#filter-areas', 2], ['[data-table="areas"] tbody tr:first-child button', 3, 'left']]);
  await shot(page, 'stammdaten-teile');
  await page.locator('[data-table="shifts"] tbody tr').first().locator('button', { hasText: 'Bearbeiten' }).click();
  await page.locator('tr.edit-row').waitFor();
  await clip(page, 'stammdaten-schicht', ['section:has([data-table="shifts"]) h2', 'tr.edit-row']);
  await page.locator('tr.edit-row').getByRole('button', { name: 'Abbrechen', exact: true }).click();
  await page.fill('#filter-demand', areaName(large));
  await page.locator('[data-table="demand"]').evaluate((e) => e.scrollIntoView());
  await clip(page, 'stammdaten-bedarf', ['section:has([data-table="demand"]) h2', '[data-table="demand"]']);

  // Regeln
  await open(page, base, 'regeln', '#h-goals');
  await shot(page, 'regeln');
  await mark(page, [['fieldset.night', 1], ['.goal[data-group="blocks"] fieldset.importance', 2], ['.save-bar', 3]]);
  await shot(page, 'regeln-teile');
  await page.locator('#h-law').evaluate((e) => e.scrollIntoView());
  await page.locator('section[aria-labelledby="h-law"]').getByRole('button', { name: 'Ändern', exact: true }).click();
  // at the end of the page the sticky save bar stands below the section instead of over its last fields
  await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight));
  await clip(page, 'regeln-gesetz', ['section[aria-labelledby="h-law"]']);
  await open(page, base, 'regeln', '#h-goals');
  await page.locator('details.rules-advanced > summary').click();
  await page.locator('.rule-row').first().evaluate((e) => e.scrollIntoView({ block: 'center' }));
  await shot(page, 'regeln-erweitert');

  // Daten: the page, the preview of an import keeping the changes, the question before replacing them, an error list
  await open(page, base, 'import', '#h-import');
  await mark(page, [['#dataset-file', 1], ['#dataset-keep', 2, 'top'], ['#h-export', 3], ['#h-corrections', 4]]);
  await shot(page, 'daten');
  const exported = await page.evaluate(async () => (await fetch('/api/dataset/export')).text());
  const good = join(work, 'datensatz.json');
  writeFileSync(good, exported);
  const broken = JSON.parse(exported);
  broken.people[0].weeklyHours = 0;
  broken.demand[0].areaId = 'XX';
  const bad = join(work, 'datensatz-fehler.json');
  writeFileSync(bad, JSON.stringify(broken));
  await page.setInputFiles('#dataset-file', good);
  await page.locator('#dataset-keep').check();
  await page.getByRole('button', { name: 'Datensatz importieren', exact: true }).click();
  await page.locator('#import-preview').waitFor({ timeout: 30000 });
  await clip(page, 'daten-vorschau', ['#import-preview']);
  await page.locator('#import-preview').getByRole('button', { name: 'Abbrechen', exact: true }).click();
  await page.locator('#dataset-keep').uncheck();
  await page.getByRole('button', { name: 'Datensatz importieren', exact: true }).click();
  await page.locator('#main div[aria-live] .alert[role=alert]').first().waitFor({ timeout: 30000 });
  await clip(page, 'daten-rueckfrage', ['#main div[aria-live] .alert[role=alert]']);
  await page.locator('#main div[aria-live] .alert[role=alert]').getByRole('button', { name: 'Abbrechen', exact: true }).click();
  await page.setInputFiles('#dataset-file', bad);
  await page.getByRole('button', { name: 'Datensatz importieren', exact: true }).click();
  await page.locator('#main .alert-error').first().waitFor({ timeout: 30000 });
  await clip(page, 'daten-fehler', ['#main .alert-error']);
  await open(page, base, 'import', '#h-anonymize');
  await page.locator('section[aria-labelledby="h-anonymize"] button.btn').first().click();
  await page.locator('section[aria-labelledby="h-anonymize"] .alert').waitFor({ timeout: 30000 });
  await page.locator('#h-anonymize').evaluate((e) => e.scrollIntoView());
  await clip(page, 'daten-pseudonym', ['section[aria-labelledby="h-anonymize"]']);

  // print layouts: the PDF of the browser, first page as an image
  for (const [view, ready] of [['plan', 'table.roster'], ['funktionen', 'table.functions']]) {
    await open(page, base, view, ready);
    await page.emulateMedia({ media: 'print' });
    const pdf = join(work, `${view}.pdf`);
    await page.pdf({ path: pdf, preferCSSPageSize: true, printBackground: true });
    await page.emulateMedia({ media: 'screen' });
    const target = await file(view === 'plan' ? 'druck-dienstplan' : 'druck-funktionen');
    const r = spawnSync('pdftoppm', ['-png', '-r', '110', '-f', '1', '-l', '1', '-singlefile', pdf, target.slice(0, -4)]);
    if (r.status !== 0) throw new Error(`pdftoppm: ${r.stderr}`);
  }

  // the person who is no longer taken over from the source: switch, note and the list of people (switched back after)
  await open(page, base, `person/${encodeURIComponent(holidayPerson.id)}`, '#f-excluded');
  await page.locator('#f-excluded').check();
  await page.waitForTimeout(1500);
  await open(page, base, `person/${encodeURIComponent(holidayPerson.id)}`, '#f-excluded');
  await page.locator('#h-source').evaluate((e) => { e.scrollIntoView(); scrollBy(0, -20); });
  await mark(page, [['#f-excluded', 1, 'left']]);
  await clip(page, 'person-ausschluss', ['section[aria-labelledby="h-source"]']);
  await open(page, base, 'person', '[data-table="people"]');
  await page.fill('#person-search', holidayPerson.lastName);
  await page.waitForTimeout(600);
  await page.fill('#person-search', '');
  await page.waitForTimeout(600);
  const row = page.locator('[data-table="people"] tbody tr', { hasText: holidayPerson.lastName }).first();
  await row.scrollIntoViewIfNeeded();
  await page.evaluate(() => scrollTo(0, 0));
  await mark(page, [['#person-search', 1], [page.getByRole('link', { name: 'Person anlegen', exact: true }).or(page.getByRole('button', { name: 'Person anlegen', exact: true })), 2, 'right'],
    [page.getByText(/^\d+ Personen/), 3], ['[data-table="people"] .badge', 4, 'right']]);
  await shot(page, 'personen');
  await open(page, base, `person/${encodeURIComponent(holidayPerson.id)}`, '#f-excluded');
  await page.locator('#f-excluded').uncheck();
  await page.waitForTimeout(1500);

  // Updates: the notice of a new version and its dialog (the answer of the release check given here)
  await page.route('**/api/release', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
    current: '0.16.1', available: { version: '0.17.0', title: 'Dispofold 0.17.0',
      notes: 'Neu\n- Beispieltext der Release-Notizen\n- Kleinere Korrekturen', url: 'https://github.com/mschabhuettl/dispofold/releases' } }) }));
  await page.reload();
  await page.locator('#banner :is(button, a)').first().waitFor({ timeout: 30000 });
  await clip(page, 'version-hinweis', ['#banner']);
  await page.locator('#banner button', { hasText: 'Was ist neu?' }).click();
  await page.locator('dialog.release-dialog').waitFor();
  await clip(page, 'version-dialog', ['dialog.release-dialog']);
  await page.keyboard.press('Escape');
  await page.unroute('**/api/release');
  await page.context().close();

  // the role "nur lesen"
  const viewer = await newPage(origin);
  await login(viewer, base, viewerUser, viewerPassword);
  await open(viewer, base, 'plan', 'table.roster');
  await mark(viewer, [['#nav a[data-group="fairness"]', 1, 'end'], ['#user-role', 2, 'bottom'], ['[aria-controls="output-menu"]', 3]]);
  await shot(viewer, 'nur-lesen');
  await viewer.context().close();
}

// ---------------------------------------------------------------------------------------------------------------
// 2. desktop phase: the dev server in desktop mode

async function startDevServer(env) {
  const port = await new Promise((done) => {
    const s = createServer().listen(0, () => { const p = s.address().port; s.close(() => done(p)); });
  });
  const server = spawn(process.execPath, [join(root, 'scripts', 'dev-server.mjs'), String(port)], {
    stdio: ['ignore', 'pipe', 'inherit'], env: { ...process.env, ...env } });
  await new Promise((done) => server.stdout.on('data', (d) => { if (String(d).includes('dev server on')) done(); }));
  return { origin: `http://localhost:${port}`, server };
}

/**
 * A page of the program window itself (desktop-shell/pages, served by the window process under /__shell/): the files
 * from the repository, /__shell/state answered with the given state, nothing else reachable.
 */
async function shellPage(name, state, viewport) {
  const pages = join(root, '..', 'desktop-shell', 'pages');
  const assets = { 'icon.svg': join(root, '..', 'branding', 'icon.svg'),
    'fonts/plex-400.woff2': join(root, 'public', 'fonts', 'ibm-plex-sans-latin-400-normal.woff2'),
    'fonts/plex-600.woff2': join(root, 'public', 'fonts', 'ibm-plex-sans-latin-600-normal.woff2') };
  const types = { html: 'text/html; charset=utf-8', js: 'text/javascript', css: 'text/css', svg: 'image/svg+xml', woff2: 'font/woff2' };
  const context = await browser.newContext({ viewport, locale: 'de-DE', colorScheme: 'light' });
  await context.route('**/*', (route) => {
    const url = new URL(route.request().url());
    const asset = url.pathname.replace(/^\/__shell\//, '');
    if (url.origin !== 'http://dispofold.localhost' || asset === url.pathname) {
      external.push(url.href);
      return route.abort();
    }
    if (asset === 'state') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(state) });
    const path = assets[asset] ?? join(pages, /\.\w+$/.test(asset) ? asset : `${asset}.html`);
    return route.fulfill({ status: 200, contentType: types[path.split('.').pop()], body: readFileSync(path) });
  });
  const page = await context.newPage();
  await page.goto(`http://dispofold.localhost/__shell/${name}`);
  await page.waitForTimeout(800);
  return page;
}

async function desktopPhase() {
  // the window process: the password on the first start and the small window of the browser front end (Linux)
  let page = await shellPage('unlock', { phase: 'needPassword', firstStart: true }, { width: 1000, height: 640 });
  await clip(page, 'desktop-passwort', ['main.card']);
  await page.context().close();
  page = await shellPage('running', {}, { width: 420, height: 260 });
  await shot(page, 'desktop-laeuft');
  await page.context().close();
  // first start: the question whether to look for new versions
  const ask = await startDevServer({ MOCK_DESKTOP: '1', MOCK_FRONTEND: 'window', MOCK_UPDATE_ASK: '1' });
  try {
    const page = await newPage(ask.origin);
    page.removeAllListeners('dialog');
    await page.goto(`${ask.origin}/#/plan`);
    await page.locator('dialog#update-consent').waitFor({ timeout: 30000 });
    await clip(page, 'desktop-frage', ['dialog#update-consent']);
    await page.context().close();
  } finally { ask.server.kill(); }
  // an update source that offers a new version: the notice with "Jetzt installieren" and the Updates page
  const offer = await startDevServer({ MOCK_DESKTOP: '1', MOCK_FRONTEND: 'window', MOCK_UPDATE_SOURCE: 'https://updates.example.invalid/dispofold' });
  try {
    const page = await newPage(offer.origin);
    await page.goto(`${offer.origin}/#/plan`);
    await page.locator('#banner button', { hasText: 'Was ist neu?' }).waitFor({ timeout: 30000 });
    await page.locator('#banner button', { hasText: 'Was ist neu?' }).click();
    await page.locator('dialog.release-dialog').waitFor();
    await clip(page, 'desktop-version', ['dialog.release-dialog']);
    await page.keyboard.press('Escape');
    await open(page, offer.origin, 'einstellungen', '.settings');
    await page.getByRole('button', { name: 'Jetzt suchen', exact: true }).click();
    await page.locator('.settings .alert').first().waitFor({ timeout: 30000 });
    await mark(page, [['#update-source', 1], ['#update-on-start', 2, 'left'], [page.getByRole('button', { name: 'Jetzt suchen', exact: true }), 3, 'right'],
      ['.settings .alert', 4], ['#update-file', 5]]);
    await shot(page, 'updates', true);
    await page.context().close();
  } finally { offer.server.kill(); }
  // Einstellungen → Erweiterungen as a planner on the server sees it in this version: no plugin built in yet
  const plain = await startDevServer({});
  try {
    const page = await newPage(plain.origin);
    await login(page, plain.origin, 'planung', 'x');
    await open(page, plain.origin, 'erweiterungen', '#extensions-none');
    await mark(page, [['.page-tabs a[aria-current="page"]', 1, 'bottom'], ['#extensions-none', 2, 'left']]);
    await shot(page, 'erweiterungen');
    await page.context().close();
  } finally { plain.server.kill(); }
}

try {
  if (process.env.PHASE !== 'desktop') await serverPhase();
  if (process.env.PHASE !== 'server') await desktopPhase();
} finally {
  await browser.close();
  rmSync(work, { recursive: true, force: true });
}
if (external.length) {
  console.error('requests to other hosts (blocked):', [...new Set(external)]);
  process.exit(1);
}
console.log('no requests to other hosts');
const optimize = spawnSync('python3', [join(root, '..', 'scripts', 'optimize-png.py'), ...saved], { stdio: 'inherit' });
if (optimize.status !== 0) process.exit(1);
