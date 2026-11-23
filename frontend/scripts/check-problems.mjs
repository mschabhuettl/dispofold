// Playwright check of the Prüfen page and the fairness measure. On the demo data with some duties freed, one duty
// too many and some master data changed, planners see a verdict first (what to do first, or nothing to do), the rule
// violations, the open demand right after them, then the notes on quality and overstaffing (no errors, collapsed,
// grouped and filtered), every group collapsed; the open demand by day with who could be asked, links to the Zeitachse on the area and day of an entry, and the master data checks
// with what to do and a link (demand nobody can be planned for to its marked row in Stammdaten); viewers have no
// Prüfen (its address shows the Dienstplan) and no problem list from the API. The fairness page shows planners the
// loads the fairness rules compare, with the measure in one sentence and without the old 40 h toggle; viewers get no
// loads, and "Fällt auf" above the charts.
// Demo data of the dev server (mock API).
//   npm run build && node scripts/check-problems.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchBrowser } from './browser.mjs';

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

const browser = await launchBrowser();
try {
  const login = async (user) => {
    const context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1400, height: 1000 }, colorScheme: 'light' });
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
    // an area nobody has, with demand on the last day: nobody can be planned for it
    dataset.areas.push({ id: 'NIEMAND', name: 'Niemand' });
    dataset.demand.push({ areaId: 'NIEMAND', days: [], date: `${m}-31`, from: '07:00', to: '13:00', min: 1, max: null });
    dataset.periods = [await (await fetch(`/api/periods/${m}`)).json()];
    await fetch('/api/import/dataset', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...dataset, version: 2 }) });
    await fetch(`/api/plans/${m}/solve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"seed":1,"timeLimitSeconds":1}' });
    return { noArea: noArea.id, badMix: badMix.id, area, demandIndex: dataset.demand.length - 1 };
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

  // Verdict and order: the rule violations, the open demand right after them, the notes on quality collapsed
  const sums = await page.evaluate(async (m) => {
    const a = await (await fetch(`/api/plans/${m}/analysis`)).json();
    return { hard: a.hardViolations, gaps: a.gaps.length };
  }, month);
  const verdict = await page.locator('#check-verdict .plan-headline').innerText();
  check('the verdict says what to do first', sums.hard === 0 && sums.gaps === 0 ? verdict === 'Nichts zu tun: 0 Regelverstöße, 0 Lücken'
    : verdict.startsWith('Zuerst: ') && verdict.includes(`${sums.gaps} Lücke`) && (sums.hard === 0 || verdict.includes('Regelverstö')), verdict);
  const order = await page.locator('#main section.check-section, #main #check-verdict').evaluateAll((els) => els.map((e) => e.id));
  check('order: verdict, rule violations, open demand, quality notes, ..., master data', order[0] === 'check-verdict'
    && order.indexOf('check-problems') === 1 && order.indexOf('check-gaps') === 2 && order.indexOf('check-quality') === 3 && order.at(-1) === 'check-data',
  order.join());
  check('every group collapsed', await page.locator('#main details[open]').count() === 0 && await page.locator('#main details.check-group').count() > 0);
  check('the notes on quality are no errors', (await page.locator('#check-quality h2').innerText()).startsWith('Hinweise zur Qualität (keine Fehler)'));
  check('no "Strafen" on the page', !/Strafe/.test(await page.locator('#main').innerText()));

  // Problem list (the notes on quality)
  const problems = page.locator('#check-quality');
  await problems.locator('details.check-quality > summary').click();
  const items = problems.locator('.check-list > li');
  check('problem list has entries', (await items.count()) > 0, String(await items.count()));
  check('problem list shows the overstaffing', (await problems.locator('li[data-rule=overstaffing]').count()) > 0);
  check('problem list shows a quality rule', (await problems.locator('li[data-level=SOFT]').count()) > 0);
  check('entries of a person link to the person page', (await problems.locator('li a[href^="#/person/"]').count()) > 0);
  await page.selectOption('#check-group', 'person');
  const headings = await problems.locator('.check-group > summary').allTextContents();
  check('grouped by person, the overstaffing is under "ohne Person"', headings.some((x) => x.startsWith('ohne Person')), headings.at(-1));
  check('grouped by person, the entries keep their links', (await problems.locator('li a[href^="#/person/"]').count()) > 0
    && (await problems.locator('li[data-rule=overstaffing] a[href^="#/zeitachse/"]').count()) > 0);
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
  await gaps.locator('.check-group > summary').first().click();
  check('a gap names the functions of the area', ((await gaps.locator('li.gap-item').first().textContent()) ?? '').includes('Funktionen:'));
  await gaps.locator('li.gap-item button', { hasText: 'Auf Anfrage verfügbar' }).first().click();
  await gaps.locator('.candidates[data-loaded]').first().waitFor();
  check('who could be asked loads on demand', (await gaps.locator('.candidates[data-loaded]').count()) === 1);
  // a closed month: nobody can be asked, a duty cannot be set
  await page.evaluate((m) => fetch(`/api/plans/${m}/finalize`, { method: 'POST' }), month);
  await page.reload();
  await page.locator('#check-gaps li.gap-item').first().waitFor({ state: 'attached' });
  check('closed month: no "Auf Anfrage verfügbar"', (await page.locator('#check-gaps li.gap-item button').count()) === 0);
  await page.evaluate((m) => fetch(`/api/plans/${m}/reopen`, { method: 'POST' }), month);

  // Zeitachse links: an overstaffing and a gap open the Zeitachse on their area and day
  const timelineAt = async (link) => {
    const href = await link.getAttribute('href');
    await link.click();
    await page.locator('#tl-date').waitFor();
    return { href, area: await page.locator('#tl-area').inputValue(), date: await page.locator('#tl-date').inputValue(),
      mode: await page.locator('#tl-mode').inputValue() };
  };
  await page.goto(`${base}/#/pruefen`);
  await page.locator('#check-quality details.check-quality > summary').click();
  await page.selectOption('#check-filter-rule', 'overstaffing');
  await page.locator('#check-quality .check-group > summary').first().click();
  const over = await timelineAt(page.locator('#check-quality li[data-rule=overstaffing] a', { hasText: 'Zeitachse' }).first());
  check('Zeitachse link of the overstaffing opens its area and day', over.area === 'LF' && over.date === `${month}-20` && over.mode === 'day',
    JSON.stringify(over));
  await page.goto(`${base}/#/pruefen`);
  await page.locator('#check-gaps .check-group > summary').first().click();
  const gapLink = page.locator('#check-gaps li.gap-item a', { hasText: 'Zeitachse' }).first();
  await gapLink.waitFor();
  const [, gapArea, gapDate] = (await gapLink.getAttribute('href')).match(/^#\/zeitachse\/([^/]+)\/(\d{4}-\d{2}-\d{2})$/) ?? [];
  const gapTl = await timelineAt(gapLink);
  check('Zeitachse link of a gap opens its area and day', gapArea && gapTl.area === gapArea && gapTl.date === gapDate, JSON.stringify(gapTl));

  // Master data checks
  await page.goto(`${base}/#/pruefen`);
  await page.locator('#check-data').waitFor();
  const data = page.locator('#check-data');
  const kinds = await data.locator('.check-list > li').evaluateAll((li) => li.map((x) => x.dataset.kind));
  for (const kind of ['noArea', 'mixSum', 'areaNoDemand', 'demandNoPeople']) check(`master data check ${kind}`, kinds.includes(kind), kinds.join());
  const noArea = data.locator('li[data-kind=noArea]').first();
  check('a finding says what to do', ((await noArea.textContent()) ?? '').includes('Bereich zuordnen'));
  check('a finding links to the person and to Stammdaten', (await noArea.locator(`a[href="#/person/${changed.noArea}"]`).count()) === 1
    && (await noArea.locator('a[href="#/stammdaten"]').count()) === 1);
  await noArea.locator(`a[href="#/person/${changed.noArea}"]`).click();
  await page.waitForURL((u) => u.hash === `#/person/${changed.noArea}`);
  check('the link opens the person page', true);
  // demand nobody can be planned for links to its row in Stammdaten, which is marked
  await page.goto(`${base}/#/pruefen`);
  const demand = page.locator('#check-data li[data-kind=demandNoPeople]');
  await demand.waitFor();
  const demandText = (await demand.textContent()) ?? '';
  check('demand nobody can be planned for names area, day and time', demandText.includes('Bereich Niemand, 31.1. 07:00-13:00: an 1 Tag kann niemand eingeplant werden'), demandText);
  const demandLink = demand.locator(`a[href="#/stammdaten/bedarf/${changed.demandIndex}"]`);
  check('the finding links to its demand row', (await demandLink.count()) === 1);
  await demandLink.click();
  await page.waitForURL((u) => u.hash === `#/stammdaten/bedarf/${changed.demandIndex}`);
  const row = page.locator('[data-table="demand"] tr.row-target');
  await row.waitFor();
  const rowText = (await row.textContent()) ?? '';
  check('the demand row is marked and in view', (await row.getAttribute('data-row')) === String(changed.demandIndex)
    && rowText.includes('Niemand') && rowText.includes('07:00') && await row.evaluate((tr) => {
      const r = tr.getBoundingClientRect();
      return r.top >= 0 && r.bottom <= innerHeight;
    }), rowText);

  // The verdict without gaps (injected into the analysis): nothing to do; with rule violations (injected into the
  // analysis and the problem list): those first, listed by rule under "Regelverstöße"
  const analysisUrl = `**/api/plans/${month}/analysis`;
  const problemsUrl = `**/api/plans/${month}/problems`;
  await page.route(analysisUrl, async (route) => {
    const a = await (await route.fetch()).json();
    await route.fulfill({ json: { ...a, hardViolations: 0, gaps: [], uncoveredHours: 0 } });
  });
  await page.goto(`${base}/#/pruefen`);
  await page.locator('#check-data').waitFor();
  const good = await page.locator('#check-verdict .plan-headline').innerText();
  check('verdict without violations and gaps', good === 'Nichts zu tun: 0 Regelverstöße, 0 Lücken'
    && (await page.locator('#check-verdict').getAttribute('class')).includes('status-good'), good);
  // out of date (injected into the plan state): plan again first
  const stateUrl = (url) => url.pathname === `/api/plans/${month}`;
  await page.route(stateUrl, async (route) => {
    const s = await (await route.fetch()).json();
    await route.fulfill({ json: { ...s, stale: true } });
  });
  await page.reload();
  await page.locator('#check-data').waitFor();
  const stale = await page.locator('#check-verdict .plan-headline').innerText();
  check('verdict on a plan out of date', stale === 'Zuerst: neu planen (der Plan ist veraltet)', stale);
  await page.unroute(stateUrl);
  await page.unroute(analysisUrl);
  await page.route(analysisUrl, async (route) => {
    const a = await (await route.fetch()).json();
    await route.fulfill({ json: { ...a, hardViolations: 2 } });
  });
  await page.route(problemsUrl, async (route) => {
    const list = await (await route.fetch()).json();
    const hit = list.find((x) => x.personId);
    await route.fulfill({ json: [...list, { ...hit, constraintId: 'dailyRest', level: 'HARD' }, { ...hit, constraintId: 'dailyRest', level: 'HARD', date: `${month}-02` }] });
  });
  await page.reload();
  await page.locator('#check-data').waitFor();
  const bad = await page.locator('#check-verdict .plan-headline').innerText();
  check('verdict with violations: those first, then the gaps', /^Zuerst: 2 Regelverstöße beheben, dann \d+ Lücken? schließen \(/.test(bad)
    && (await page.locator('#check-verdict').getAttribute('class')).includes('status-bad'), bad);
  const hardGroups = await page.locator('#check-problems details.check-group > summary').allInnerTexts();
  check('the rule violations listed by rule, collapsed', hardGroups.length === 1 && hardGroups[0].startsWith('Tägliche Ruhezeit · Regelverstoß (2)')
    && await page.locator('#check-problems details[open]').count() === 0, hardGroups.join(' | '));
  check('rule violations: only rule violations', (await page.locator('#check-problems li[data-level]').evaluateAll((li) => li.map((x) => x.dataset.level)))
    .join() === 'HARD,HARD');
  check('the notes on quality keep the rest', await page.locator('#check-quality li[data-level=HARD]').count() === 0
    && await page.locator('#check-quality li[data-level]').count() > 0);
  await page.unroute(analysisUrl);
  await page.unroute(problemsUrl);

  // Fairness in the rules' own measure
  await page.goto(`${base}/#/fairness`);
  await page.locator('table.data tbody tr').first().waitFor();
  const measure = (await page.locator('.fairness-measure').textContent()) ?? '';
  check('fairness explains the measure', measure.includes('je 30 verfügbare Tage bei 40 Wochenstunden'), measure);
  const heads = await page.locator('table.data thead th').allTextContents();
  check('fairness shows the loads', ['Belastung Nächte', 'Belastung Wochenenden', 'Belastung Feiertage', 'Belastung fair verteilte Dienste']
    .every((x) => heads.some((hd) => hd.startsWith(x))), heads.slice(0, 6).join('|'));
  const standout = page.locator('#fairness-standouts');
  const standoutText = (await standout.innerText()) ?? '';
  check('"Fällt auf" above the charts', await standout.evaluate((el) => {
    const spread = [...document.querySelectorAll('#main h2')].find((x) => x.textContent === 'Streuung je Kennzahl');
    return Boolean(spread && el.compareDocumentPosition(spread) & Node.DOCUMENT_POSITION_FOLLOWING);
  }) && standoutText.startsWith('Fällt auf') && (await standout.locator('li a[href^="#/person/"]').count() > 0
    || standoutText.includes('Niemand liegt weit vom Mittel entfernt.')), standoutText.replace(/\n/g, ' | '));
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
  check('viewer: "Fällt auf" without loads, account or wishes', !/Belastung|Stundenkonto|Wunsch/.test(await viewer.locator('#fairness-standouts').innerText()));
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('Prüfen and fairness measure checked');
