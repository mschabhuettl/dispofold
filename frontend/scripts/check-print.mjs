// Playwright check of the print layouts of "Dienstplan" (#/plan) and "Plan nach Funktion" (#/funktionen): the
// "Drucken" item of the "Ausgabe" menu exists, and with print media the app chrome is hidden, a header line names month, status and print
// date, and the month is split into days 1-15 and 16 to the end, each starting a new page. Also for the viewer role.
// Demo data of the dev server (mock API). With an output folder as argument, it also saves a PDF of each layout.
//   npm run build && node scripts/check-print.mjs [output folder]     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { mkdirSync, readFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const month = '2027-01';
const out = process.argv[2] ?? null;
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
  for (const user of ['planung', 'ansicht']) {
    const context = await browser.newContext();
    await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
    const page = await context.newPage();
    page.on('pageerror', (e) => check('no page error', false, e.message));
    await page.goto(`${base}/login.html`);
    await page.fill('#j_username', user);
    await page.fill('#j_password', 'x');
    await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
    if (user === 'planung') {
      await page.evaluate(async (m) => {
        await fetch('/api/import/demo', { method: 'POST' });
        await fetch(`/api/plans/${m}/solve`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
          body: '{"seed":1,"timeLimitSeconds":1}' });
      }, month);
      await page.waitForTimeout(1500);
      // a free day pinned by hand: the first person, 2 January
      await page.evaluate(async (m) => {
        const p = (await (await fetch('/api/dataset')).json()).people[0];
        await fetch(`/api/plans/${m}/cells`, { method: 'PUT', headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ personId: p.id, date: `${m}-02`, shiftId: null, compRest: false, pinned: true }) });
      }, month);
    }
    for (const [view, table] of [['plan', 'table.plan-print'], ['funktionen', 'table.functions-print']]) {
      const name = `${user} ${view}`;
      await page.emulateMedia({ media: 'screen' });
      await page.goto(`${base}/#/${view}`);
      await page.locator('.roster-wrap table').waitFor();
      // "Drucken" is in the "Ausgabe" menu
      await page.getByRole('button', { name: 'Ausgabe' }).click();
      check(`${name}: button "Drucken"`, await page.locator('button[data-print]').filter({ hasText: 'Drucken' }).isVisible());
      await page.keyboard.press('Escape');
      check(`${name}: print layout hidden on screen`, await page.locator('.print-only').isHidden());
      await page.emulateMedia({ media: 'print' });
      for (const chrome of ['.sidebar', '.topbar', '.toolbar', '.roster-wrap', '.print-view > h1']) {
        check(`${name}: ${chrome} not printed`, await page.locator(chrome).first().isHidden());
      }
      // the print date is set when printing starts (a view left open over midnight)
      await page.evaluate(() => { document.querySelector('.print-only .print-header').textContent = 'alt'; window.dispatchEvent(new Event('beforeprint')); });
      const header = await page.locator('.print-only .print-header').textContent();
      check(`${name}: header line`, /Jänner 2027 · Status: Entwurf · gedruckt am \d\d\.\d\d\.\d{4}$/.test(header), header);
      check(`${name}: legend printed after the header`, await page.locator('.print-only > .print-header + .legend').isVisible());
      const halves = await page.locator(table).evaluateAll((ts) => ts.map((t) => ({
        caption: t.caption.textContent, breakAfter: getComputedStyle(t).breakAfter,
        days: t.tHead.rows[0].cells.length, rows: t.tBodies[0].rows.length })));
      check(`${name}: two halves`, halves.length === 2 && halves[0].caption.endsWith('Tage 1 bis 15')
        && halves[1].caption.endsWith('Tage 16 bis 31'), halves.map((x) => x.caption).join(' | '));
      check(`${name}: first half ends the page`, halves[0]?.breakAfter === 'page', halves[0]?.breakAfter);
      check(`${name}: rows in both halves`, halves.every((x) => x.rows > 1 && x.rows === halves[0].rows), JSON.stringify(halves));
      if (view === 'plan') {
        // the same people and groups in print as on screen, the name display respected
        if (user === 'planung') {
          const onScreen = await page.locator('.roster-wrap .cell-free.cell-pinned').count();
          const printed = await page.locator(`${table} .cell-code.pinned`).filter({ hasText: /^·$/ }).count();
          check(`${name}: pinned free day framed`, onScreen >= 1 && printed === onScreen, `${printed} / ${onScreen}`);
        }
        const screenRows = await page.locator('.roster-wrap tbody tr').count();
        check(`${name}: rows as on screen`, halves[0].rows === screenRows, `${halves[0].rows} / ${screenRows}`);
        const first = await page.locator(`${table} tbody tr:not(.group) th.person`).first().textContent();
        await page.emulateMedia({ media: 'screen' });
        await page.selectOption('#roster-names', 'short');
        await page.emulateMedia({ media: 'print' });
        const short = await page.locator(`${table} tbody tr:not(.group) th.person`).first().textContent();
        check(`${name}: name display in print`, first !== short && short.length < first.length, `${first} -> ${short}`);
        await page.emulateMedia({ media: 'screen' });
        await page.selectOption('#roster-names', 'full');
        await page.emulateMedia({ media: 'print' });
      }
      if (user === 'ansicht') {
        // viewers print what they see: the same codes as on screen (absences only as "ABW"), no absence type codes
        const printed = new Set(await page.locator('.print-only table .cell-code').allTextContents());
        const screen = new Set(await page.locator('.roster-wrap .cell-code').allTextContents());
        // a cell of the "offen" row summarises many gaps on screen ("+N weitere"); its title names them all
        for (const title of await page.locator('.roster-wrap tr.gap-row .cell-btn[title]').evaluateAll((b) => b.map((x) => x.title))) {
          for (const line of title.match(/\d+ · \d\d:\d\d-\d\d:\d\d/g) ?? []) screen.add(line);
        }
        const codes = await page.evaluate(async () => (await (await fetch('/api/dataset')).json()).absenceTypes
          .filter((a) => !a.compensatoryRest).map((a) => a.code));
        const extra = [...printed].filter((c) => !screen.has(c));
        const leaked = codes.filter((c) => printed.has(c));
        check(`${name}: prints only what the viewer sees`, extra.length === 0 && leaked.length === 0,
          `extra ${extra.join(', ')}; absence types ${leaked.join(', ')}`);
      }
      if (out) {
        mkdirSync(out, { recursive: true });
        const file = join(out, `druck-${view}${user === 'ansicht' ? '-ansicht' : ''}.pdf`);
        await page.pdf({ path: file, preferCSSPageSize: true, printBackground: true });
        const pages = (readFileSync(file, 'latin1').match(/\/Type\s*\/Page[^s]/g) ?? []).length;
        check(`${name}: PDF ${file}`, pages >= 2, `${pages} pages`);
      }
    }
    await context.close();
  }
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('print layouts ok');
