// Playwright check of the Prüfen page and the fairness measure. On the demo data with some duties freed, one duty
// too many and some master data changed, planners see the problem list (grouped and filtered), the open demand by
// day with who could be asked, and the master data checks with what to do and a link; viewers have no Prüfen (its
// address shows the Dienstplan) and no problem list from the API. The fairness page shows planners the loads the
// fairness rules compare, with the measure in one sentence and without the old 40 h toggle; viewers get no loads.
// Demo data of the dev server (mock API).
//   npm run build && node scripts/check-problems.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
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

let failed = false;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);
  if (!ok) failed = true;
};

const browser = await chromium.launch();
try {
  const login = async (user) => {
    const context = await browser.newContext({ viewport: { width: 1400, height: 1000 }, colorScheme: 'light' });
    await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
    const page = await context.newPage();
    page.on('pageerror', (e) => check('no page error', false, e.message));
    await page.goto(`${base}/login.html`);
    await page.fill('#j_username', user);
    await page.fill('#j_password', 'x');
    await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
    return page;
  };
  const page = await login('planung');
  // Master data: one person without areas, one with a usual distribution summing to 80 %, an area without demand.
  const changed = await page.evaluate(async (m) => {
    await fetch('/api/import/demo', { method: 'POST' });
    const dataset = await (await fetch('/api/dataset')).json();
    const [noArea, badMix] = dataset.people.filter((p) => !p.shiftMix);
    noArea.areaIds = [];
    badMix.shiftMix = { categories: { DAY: 0.5, LATE: 0.3 }, areas: {} };
    const area = dataset.areas.at(-1).id;
    dataset.demand = dataset.demand.filter((d) => d.areaId !== area);
    dataset.periods = [await (await fetch(`/api/periods/${m}`)).json()];
    await fetch('/api/import/dataset', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...dataset, version: 2 }) });
    await fetch(`/api/plans/${m}/solve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"seed":1,"timeLimitSeconds":1}' });
    return { noArea: noArea.id, badMix: badMix.id, area };
  }, month);
  await page.waitForTimeout(1500);
  // Plan: two duties of an area with a maximum freed (gaps), one more duty there than allowed (overstaffing).
  const lf = await page.evaluate(async (m) => {
    const put = (body) => fetch(`/api/plans/${m}/cells`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const plan = (await (await fetch(`/api/plans/${m}`)).json()).plan;
    const dataset = await (await fetch('/api/dataset')).json();
    const lfShifts = new Set(dataset.shifts.filter((s) => s.areaId === 'LF').map((s) => s.id));
    const freed = plan.assignments.filter((a) => lfShifts.has(a.shiftId) && !a.pinned).slice(0, 2);
    for (const a of freed) await put({ ...a, shiftId: null, pinned: true });
    const day = `${m}-20`;
    const busy = new Set(plan.assignments.filter((a) => a.date === day && a.shiftId).map((a) => a.personId));
    const extra = dataset.people.find((p) => p.areaIds.includes('LF') && !busy.has(p.id));
    await put({ personId: extra.id, date: day, shiftId: 'LF-T', compRest: false, pinned: true });
    return { freed: freed.length, extra: extra.id };
  }, month);
  check('demo plan has duties to free', lf.freed === 2);

  await page.goto(`${base}/#/pruefen`);
  await page.locator('#check-data').waitFor();
  check('Prüfen has tabs Probleme and Fairness', (await page.locator('.page-tabs a').allTextContents()).join('|') === 'Probleme|Fairness');

  // Problem list
  const problems = page.locator('#check-problems');
  const items = problems.locator('.check-list > li');
  check('problem list has entries', (await items.count()) > 0, String(await items.count()));
  check('problem list shows the overstaffing', (await problems.locator('li[data-rule=overstaffing]').count()) > 0);
  check('problem list shows a quality rule', (await problems.locator('li[data-level=SOFT]').count()) > 0);
  check('entries of a person link to the person page', (await problems.locator('li a[href^="#/person/"]').count()) > 0);
  await page.selectOption('#check-group', 'person');
  const headings = await problems.locator('.check-group > summary').allTextContents();
  check('grouped by person, the overstaffing is under "ohne Person"', headings.some((x) => x.startsWith('ohne Person')), headings.at(-1));
  check('grouped by person, the entries keep their links', (await problems.locator('li a[href^="#/person/"]').count()) > 0
    && (await problems.locator('li[data-rule=overstaffing] a[href="#/zeitachse"]').count()) > 0);
  await page.selectOption('#check-filter-rule', 'overstaffing');
  const rules = await problems.locator('.check-list > li').evaluateAll((li) => li.map((x) => x.dataset.rule));
  check('the rule filter keeps only that rule', rules.length > 0 && rules.every((r) => r === 'overstaffing'), rules.join());
  await page.selectOption('#check-filter-rule', '*');
  await page.selectOption('#check-group', 'day');
  const days = await problems.locator('.check-group > summary').allTextContents();
  check('grouped by day, the whole month first, then the days', days[0].startsWith('ganzer Monat')
    && days.slice(1).length > 0 && days.slice(1).every((x) => /^\S+ \d{2}\.\d{2}\. \(\d+\)$/.test(x)), days.join('|'));

  // Open demand
  const gaps = page.locator('#check-gaps');
  const headline = (await gaps.locator('.check-headline').textContent()) ?? '';
  check('open demand has the headline of the Dienstplan', /^\d+ Lücken? \([\d,  ]+ Personenstunden\)$/.test(headline), headline);
  check('gaps are listed by day', (await gaps.locator('.check-group > summary').count()) > 0 && (await gaps.locator('li.gap-item').count()) > 0);
  check('a gap names the functions of the area', ((await gaps.locator('li.gap-item').first().textContent()) ?? '').includes('Funktionen:'));
  await gaps.locator('li.gap-item button', { hasText: 'Auf Anfrage verfügbar' }).first().click();
  await gaps.locator('.candidates[data-loaded]').first().waitFor();
  check('who could be asked loads on demand', (await gaps.locator('.candidates[data-loaded]').count()) === 1);
  // a closed month: nobody can be asked, a duty cannot be set
  await page.evaluate((m) => fetch(`/api/plans/${m}/finalize`, { method: 'POST' }), month);
  await page.reload();
  await page.locator('#check-gaps li.gap-item').first().waitFor();
  check('closed month: no "Auf Anfrage verfügbar"', (await page.locator('#check-gaps li.gap-item button').count()) === 0);
  await page.evaluate((m) => fetch(`/api/plans/${m}/reopen`, { method: 'POST' }), month);

  // Master data checks
  const data = page.locator('#check-data');
  const kinds = await data.locator('.check-list > li').evaluateAll((li) => li.map((x) => x.dataset.kind));
  for (const kind of ['noArea', 'mixSum', 'areaNoDemand']) check(`master data check ${kind}`, kinds.includes(kind), kinds.join());
  const noArea = data.locator('li[data-kind=noArea]').first();
  check('a finding says what to do', ((await noArea.textContent()) ?? '').includes('Bereich zuordnen'));
  check('a finding links to the person and to Stammdaten', (await noArea.locator(`a[href="#/person/${changed.noArea}"]`).count()) === 1
    && (await noArea.locator('a[href="#/stammdaten"]').count()) === 1);
  await noArea.locator(`a[href="#/person/${changed.noArea}"]`).click();
  await page.waitForURL((u) => u.hash === `#/person/${changed.noArea}`);
  check('the link opens the person page', true);

  // Fairness in the rules' own measure
  await page.goto(`${base}/#/fairness`);
  await page.locator('table.data tbody tr').first().waitFor();
  const measure = (await page.locator('.fairness-measure').textContent()) ?? '';
  check('fairness explains the measure', measure.includes('je 30 verfügbare Tage bei 40 Wochenstunden'), measure);
  const heads = await page.locator('table.data thead th').allTextContents();
  check('fairness shows the loads', ['Belastung Nächte', 'Belastung Wochenenden', 'Belastung Feiertage', 'Belastung unbeliebte Dienste']
    .every((x) => heads.some((hd) => hd.startsWith(x))), heads.slice(0, 6).join('|'));
  check('the 40 h toggle is gone', (await page.locator('main input[type=checkbox]').count()) === 0
    && !((await page.locator('main').textContent()) ?? '').includes('normiert'));

  // Viewers
  const viewer = await login('ansicht');
  await viewer.goto(`${base}/#/pruefen`);
  await viewer.locator('main h1').first().waitFor();
  await viewer.waitForFunction(() => location.hash === '#/plan');
  check('viewer: the address of Prüfen shows the Dienstplan', viewer.url().endsWith('#/plan'), viewer.url());
  check('viewer: no Prüfen in the menu', !(await viewer.locator('#nav').textContent()).includes('Prüfen'));
  const status = await viewer.evaluate(async (m) => (await fetch(`/api/plans/${m}/problems`)).status, month);
  check('viewer: the problem list is planners only', status === 403, String(status));
  await viewer.goto(`${base}/#/fairness`);
  await viewer.locator('table.data tbody tr').first().waitFor();
  const viewerHeads = (await viewer.locator('table.data thead').textContent()) ?? '';
  check('viewer: fairness without loads and without the measure', !viewerHeads.includes('Belastung')
    && (await viewer.locator('.fairness-measure').count()) === 0);
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('Prüfen and fairness measure checked');
