// Playwright check of the header and status line of "Dienstplan" (#/plan) and "Plan nach Funktion" (#/funktionen):
// solver options under a collapsed "Erweitert", Planen, Stopp, Plan fixieren and Fixierungen lösen in one row, the
// "Ausgabe" menu (Drucken, exports, Monat abschließen), the legend collapsed and remembered, the coloured headline
// without the raw score (the score in words only under "Erweitert"), the figures that are no errors said so, the rule
// chips with their list, the sticky gap rows, the checklist before closing the month with its second confirm and what
// follows, and what viewers see (no chips). For the sick note: "Plan fixieren" pins every duty and compensatory rest day
// but no free day, the link to the first conflict or gap scrolls the grid to it, and the list of who could be asked
// says what a click does and the hours with their meaning. Demo data of the dev server (mock API).
//   npm run build && node scripts/check-plan-views.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchBrowser } from './browser.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const month = '2027-01';
const hitsPath = `/api/plans/${month}/hits`;
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

async function login(user) {
  const context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1280, height: 900 } });
  await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
  const page = await context.newPage();
  page.on('pageerror', (e) => check(`${user}: no page error`, false, e.message));
  await page.goto(`${base}/login.html`);
  await page.fill('#j_username', user);
  await page.fill('#j_password', 'x');
  await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
  return page;
}

async function open(page, view) {
  await page.goto(`${base}/#/${view}`);
  await page.locator('.roster-wrap table').waitFor();
}

const api = (page, path, init) => page.evaluate(async ([p, i]) => {
  const res = await fetch(p, i);
  return res.status === 204 ? null : res.json();
}, [path, init]);

/** Texts of the "Ausgabe" menu items; the menu is closed again with Escape. */
async function menuItems(page) {
  await page.getByRole('button', { name: 'Ausgabe' }).click();
  const items = await page.locator('#output-menu button').allTextContents();
  await page.keyboard.press('Escape');
  return items;
}

const legendOpen = (page) => page.locator('details.legend-box').evaluate((d) => d.open);

/** Top of an element relative to the visible area of the grid below the day header. */
const belowHeader = (page, selector) => page.evaluate((sel) => {
  const wrap = document.querySelector('.roster-wrap');
  const el = document.querySelector(sel);
  const r = el.getBoundingClientRect();
  const hit = document.elementFromPoint(r.left + Math.min(20, r.width / 2), r.top + r.height / 2);
  return { offset: r.top - (wrap.getBoundingClientRect().top + wrap.clientTop + wrap.querySelector('thead').getBoundingClientRect().height),
    position: getComputedStyle(el).position, onTop: Boolean(hit && el.closest('tr').contains(hit)), scrolled: wrap.scrollTop };
}, selector);

try {
  const page = await login('planung');
  await page.evaluate(async (m) => {
    await fetch('/api/import/demo', { method: 'POST' });
    await fetch(`/api/plans/${m}/solve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"seed":1,"timeLimitSeconds":1}' });
  }, month);
  await page.waitForTimeout(1500);
  await open(page, 'plan');

  // header: solver options under "Erweitert", collapsed; the three plan buttons in one row
  const advanced = page.locator('details.advanced');
  check('Erweitert collapsed', !(await advanced.evaluate((d) => d.open)));
  check('Erweitert holds the solver options', await advanced.locator('#seed, #limit, #move-limit, #cold-start').count() === 4);
  check('solver options hidden', await page.locator('#seed').isHidden());
  const tops = await Promise.all(['Planen', 'Stopp', 'Plan fixieren', 'Fixierungen lösen']
    .map(async (name) => (await page.getByRole('button', { name, exact: true }).boundingBox()).y));
  check('Planen, Stopp, Plan fixieren, Fixierungen lösen in one row', tops.every((y) => y === tops[0]), tops.join(', '));

  // the "Ausgabe" menu: aria-expanded, items, arrow keys, Escape back to the button
  const toggle = page.getByRole('button', { name: 'Ausgabe' });
  check('menu closed', await toggle.getAttribute('aria-expanded') === 'false');
  await toggle.click();
  check('menu open', await toggle.getAttribute('aria-expanded') === 'true' && await page.locator('#output-menu').isVisible());
  const items = await page.locator('#output-menu button').allTextContents();
  check('menu items (planner)', ['Drucken', 'Export XLSX', 'Export CSV', 'Export JSON', 'Monat abschließen'].every((x) => items.includes(x)), items.join(', '));
  check('Drucken keeps data-print', await page.locator('#output-menu button[data-print]').textContent() === 'Drucken');
  check('focus on the first item', await page.evaluate(() => document.activeElement.textContent) === 'Drucken');
  await page.keyboard.press('ArrowDown');
  check('arrow down moves', await page.evaluate(() => document.activeElement.textContent) === 'Export XLSX');
  await page.keyboard.press('Escape');
  check('Escape closes the menu', await page.locator('#output-menu').isHidden() && await toggle.getAttribute('aria-expanded') === 'false');
  check('focus back on the menu button', await page.evaluate(() => document.activeElement.getAttribute('aria-controls')) === 'output-menu');
  await toggle.click();
  await page.keyboard.press('Tab');
  check('Tab closes the menu', await page.locator('#output-menu').isHidden() && await toggle.getAttribute('aria-expanded') === 'false');

  // legend: collapsed by default, remembered after a reload
  check('legend collapsed', !(await legendOpen(page)));
  await page.locator('details.legend-box > summary').click();
  await page.reload();
  await page.locator('.roster-wrap table').waitFor();
  check('legend open after reload', await legendOpen(page));
  check('planner legend: on request and absence types', await page.locator('.legend-box .cell-code.on-request').count() === 1
    && await page.locator('.legend-box .cell-code.absence.tinted').count() > 0);
  await page.locator('details.legend-box > summary').click();
  await page.reload();
  await page.locator('.roster-wrap table').waitFor();
  check('legend closed after reload', !(await legendOpen(page)));

  // headline: coloured, no score in the visible status line (only under "Erweitert", in words, the raw score as its tooltip)
  const analysis = await api(page, `/api/plans/${month}/analysis`);
  const state = await api(page, `/api/plans/${month}`);
  const headline = page.locator('.plan-headline');
  const text = await headline.textContent();
  const cls = await headline.getAttribute('class');
  const gaps = analysis.gaps.length;
  if (analysis.hardViolations === 0 && gaps === 0) {
    check('headline usable', text === 'Plan verwendbar: keine Regelverstöße, keine Lücken' && cls.includes('headline-good'), `${text} (${cls})`);
  } else {
    check('headline with gaps', analysis.hardViolations === 0 && new RegExp(`^${gaps} Lücken? \\([\\d,\\u202f\\u00a0]+ Personenstunden\\)$`).test(text)
      && cls.includes('headline-warn'), `${text} (${cls})`);
  }
  const visible = await page.locator('.statusbar').innerText();
  check('no raw score in the status line', !visible.includes(state.plan.score) && !/\d+hard\//.test(visible), visible.replace(/\n/g, ' | '));
  check('no score tooltip on the headline', await headline.getAttribute('title') === null);
  const words = (await advanced.locator('.score').textContent()) ?? '';
  check('score under Erweitert in words', words.startsWith(`Bewertung: Regelverstöße ${analysis.hardViolations}, fehlende Besetzung `)
    && words.includes('Qualität: Abzüge ') && !words.includes(state.plan.score), words);
  check('the raw score as tooltip under Erweitert', await advanced.locator('.score span[title]').getAttribute('title') === state.plan.score);
  // figures that are no errors say so: overstaffing and the quality chips
  check('quality chips labelled as no errors', await page.locator('.chips .chip:not(.chip-hard)').count() > 0
    && (await page.locator('.chips .chips-label').innerText()) === 'Qualität, kein Fehler:');
  check('gaps shown once', (visible.match(/Lücke/g) ?? []).length <= 1, visible.replace(/\n/g, ' | '));
  // overstaffing (injected into the analysis) is no error: said next to it
  await page.route(`**/api/plans/${month}/analysis`, async (route) => {
    const a = await (await route.fetch()).json();
    await route.fulfill({ json: { ...a, overstaffedHours: 12.5 } });
  });
  await page.reload();
  await page.locator('.roster-wrap table').waitFor();
  const overText = await page.locator('.status-details').innerText();
  check('overstaffing "(kein Fehler)"', /Überbesetzung: 12,5 Personenstunden \(kein Fehler\)/.test(overText), overText);
  await page.unroute(`**/api/plans/${month}/analysis`);
  // "Einzelne freie Tage" and the block days once: as chip when the rule has one (it lists who and when), else as figure
  const singleDays = async (withChip) => {
    await page.route(`**/api/plans/${month}/analysis`, async (route) => {
      const a = await (await route.fetch()).json();
      const others = a.constraints.filter((c) => c.id !== 'isolatedDayOff' && c.id !== 'blockLength');
      a.constraints = withChip ? [...others, { id: 'isolatedDayOff', level: 'SOFT', weight: 1, score: -1000000, matches: 7 },
        { id: 'blockLength', level: 'SOFT', weight: 1, score: -1000000, matches: 9 }] : others;
      a.singleDaysOff = 7;
      a.blockDays = 9;
      await route.fulfill({ json: a });
    });
    await page.reload();
    await page.locator('.roster-wrap table').waitFor();
    await page.unroute(`**/api/plans/${month}/analysis`);
    const line = await page.locator('.statusbar').innerText();
    return { count: (line.match(/Einzelne freie Tage/g) ?? []).length, chip: await page.locator('.chips .chip').filter({ hasText: 'Einzelne freie Tage' }).count(),
      blockFigure: (line.match(/Tage außerhalb der Blocklänge/g) ?? []).length, blockChip: await page.locator('.chips .chip').filter({ hasText: 'Dienstblöcke' }).count() };
  };
  const withChip = await singleDays(true);
  check('"Einzelne freie Tage" once with its chip', withChip.count === 1 && withChip.chip === 1, JSON.stringify(withChip));
  check('block days once with their chip', withChip.blockFigure === 0 && withChip.blockChip === 1, JSON.stringify(withChip));
  const withoutChip = await singleDays(false);
  check('"Einzelne freie Tage" once without a chip', withoutChip.count === 1 && withoutChip.chip === 0, JSON.stringify(withoutChip));
  check('block days once without a chip', withoutChip.blockFigure === 1 && withoutChip.blockChip === 0, JSON.stringify(withoutChip));
  await page.reload();
  await page.locator('.roster-wrap table').waitFor();

  // chips: a click lists person and day
  const chips = page.locator('.chips .chip');
  check('rule chips', await chips.count() > 0, String(await chips.count()));
  const listRequests = [];
  const countRequest = (r) => {
    const path = new URL(r.url()).pathname;
    if (path === hitsPath || path.startsWith(`/api/plans/${month}/people/`)) listRequests.push(path);
  };
  page.on('request', countRequest);
  await chips.first().click();
  await page.locator('.popover .hit-list li').first().waitFor({ timeout: 10000 }).catch(() => {});
  page.off('request', countRequest);
  const entries = await page.locator('.popover .hit-list li').allInnerTexts();
  check('chip list with person and day', entries.length > 0 && /\S+ \S+/.test(entries[0]), entries.slice(0, 2).join(' | '));
  check('chip list: one request for all hits, none per person', listRequests.length === 1 && listRequests[0] === hitsPath, listRequests.join(' '));
  await page.keyboard.press('Escape');
  check('chip list closes', await page.locator('.popover').count() === 0);
  // a fairness rule is one comparison of everybody: its chip has no count
  const fairness = page.locator('.chip').filter({ hasText: 'Fairness' });
  check('fairness chip without count', await fairness.count() === 0 || await fairness.first().locator('.chip-count').count() === 0);

  // a rule violation (injected into the analysis): red headline, hard chip
  await page.route(`**/api/plans/${month}/analysis`, async (route) => {
    const a = await (await route.fetch()).json();
    a.hardViolations = 2;
    a.constraints = [{ id: 'dailyRest', level: 'HARD', weight: 1, score: -2, matches: 2 }, ...a.constraints];
    await route.fulfill({ json: a });
  });
  await page.reload();
  await page.locator('.roster-wrap table').waitFor();
  const bad = await headline.textContent();
  check('headline with violations', /^2 Regelverstöße(, \d+ Lücken? \([\d,  ]+ Personenstunden\))?$/.test(bad)
    && (await headline.getAttribute('class')).includes('headline-bad'), bad);
  check('hard chip', (await page.locator('.chip.chip-hard').textContent()).replace(/\s+/g, ' ') === 'Tägliche Ruhezeit 2');
  // the hits fail: a note, and the next click loads them again
  const hitsUrl = (url) => url.pathname === hitsPath;
  await page.route(hitsUrl, (route) => route.fulfill({ status: 500, body: '' }));
  const softChip = page.locator('.chips .chip:not(.chip-hard)').first();
  await softChip.click();
  await page.locator('.popover .alert-error').waitFor({ timeout: 10000 }).catch(() => {});
  check('failed list: note', await page.locator('.popover .alert-error').count() === 1);
  await page.keyboard.press('Escape');
  await page.unroute(hitsUrl);
  await softChip.click();
  await page.locator('.popover .hit-list li').first().waitFor({ timeout: 10000 }).catch(() => {});
  check('failed list: loaded again', await page.locator('.popover .alert-error').count() === 0
    && await page.locator('.popover .hit-list li').count() > 0);
  await page.keyboard.press('Escape');
  await page.unroute(`**/api/plans/${month}/analysis`);
  // a plan without violations and gaps (injected): green
  await page.route(`**/api/plans/${month}/analysis`, async (route) => {
    const a = await (await route.fetch()).json();
    await route.fulfill({ json: { ...a, hardViolations: 0, gaps: [], uncoveredHours: 0 } });
  });
  await page.reload();
  await page.locator('.roster-wrap table').waitFor();
  check('headline usable', await headline.textContent() === 'Plan verwendbar: keine Regelverstöße, keine Lücken'
    && (await headline.getAttribute('class')).includes('headline-good'), await headline.textContent());
  await page.unroute(`**/api/plans/${month}/analysis`);
  await page.reload();
  await page.locator('.roster-wrap table').waitFor();

  // "Plan fixieren": every duty and compensatory rest day pinned, free days stay open; "Fixierungen lösen" undoes it
  const fixed = new Set((await api(page, `/api/periods/${month}`)).fixed.map((f) => `${f.personId}|${f.date}`));
  await page.getByRole('button', { name: 'Plan fixieren', exact: true }).click();
  await page.locator('#toast').filter({ hasText: 'fixiert' }).waitFor({ timeout: 10000 }).catch(() => {});
  let cellsNow = (await api(page, `/api/plans/${month}`)).plan.assignments;
  check('Plan fixieren pins every duty and compensatory rest day', cellsNow.filter((a) => a.shiftId || a.compRest).every((a) => a.pinned)
    && cellsNow.some((a) => !a.shiftId && !a.compRest && !a.pinned), String(cellsNow.filter((a) => a.pinned).length));
  check('Plan fixieren: every duty in the grid framed', await page.locator('.roster-wrap .cell-shift').count() > 0
    && await page.locator('.roster-wrap .cell-shift:not(.cell-pinned)').count() === 0);
  await page.getByRole('button', { name: 'Fixierungen lösen', exact: true }).click();
  await page.locator('#toast').filter({ hasText: 'gelöst' }).waitFor({ timeout: 10000 }).catch(() => {});
  cellsNow = (await api(page, `/api/plans/${month}`)).plan.assignments;
  check('Fixierungen lösen undoes it', cellsNow.every((a) => !a.pinned || fixed.has(`${a.personId}|${a.date}`)));

  // the link to the first gap and to the first conflict: the grid scrolls sideways to that cell, the focus on it
  const ds = await api(page, '/api/dataset');
  const firstArea = ds.areas[0].id;
  const askShift = ds.shifts.find((x) => x.areaId === firstArea).id;
  await page.route(`**/api/plans/${month}/analysis`, async (route) => {
    const a = await (await route.fetch()).json();
    await route.fulfill({ json: { ...a, gaps: [{ areaId: firstArea, from: `${month}-30T08:00`, to: `${month}-30T16:00`, required: 2, planned: 1 }] } });
  });
  // one person who could be asked for that gap
  const asked = cellsNow.find((a) => !a.shiftId && !a.compRest);
  await page.route((u) => u.pathname === `/api/plans/${month}/candidates`, (route) => route.fulfill({ json: [
    { personId: asked.personId, date: `${month}-30`, shiftId: askShift, plannedHours: 108, targetHours: 117.3 }] }));
  await page.reload();
  await page.locator('.roster-wrap table').waitFor();
  const jump = page.locator('.jump-first button');
  check('link to the first gap', await jump.textContent() === 'Zur ersten Lücke springen');
  await jump.click();
  const toGap = await page.evaluate(() => ({ cell: document.activeElement.dataset.cell, left: document.querySelector('.roster-wrap').scrollLeft }));
  check('the link shows the gap', toGap.cell === `gaps|${month}-30` && toGap.left > 0, JSON.stringify(toGap));
  // the list of who could be asked: what a click does, the hours with their meaning
  await page.keyboard.press('Enter');
  await page.locator('.popover .candidates[data-loaded]').waitFor({ timeout: 10000 });
  const hint = await page.locator('.popover .candidates-hint').textContent();
  const hours = await page.locator('.popover .candidates li span.muted').last().textContent();
  check('candidates: what a click does', hint === 'Klick auf einen Namen setzt den Dienst und fixiert ihn.', hint);
  check('candidates: hours with their meaning', hours === 'geplant 108,0 h von Ziel 117,3 h', hours);
  await page.keyboard.press('Escape');
  // a conflict (an absence entered on a planned duty late in the month) comes before the gap
  const late = cellsNow.filter((a) => a.shiftId && a.date >= `${month}-28`).sort((a, b) => a.date.localeCompare(b.date))[0];
  await page.route(`**/api/periods/${month}`, async (route) => {
    const p = await (await route.fetch()).json();
    await route.fulfill({ json: { ...p, absences: [...p.absences.filter((a) => !(a.personId === late.personId && a.date === late.date)),
      { personId: late.personId, date: late.date, typeCode: 'K' }] } });
  });
  await page.reload();
  await page.locator('.roster-wrap table').waitFor();
  check('link to the first conflict', await jump.textContent() === 'Zum ersten Konflikt springen');
  await page.evaluate(() => { document.querySelector('.roster-wrap').scrollLeft = 0; });
  await jump.click();
  const toConflict = await page.evaluate(() => ({ cell: document.activeElement.dataset.cell, left: document.querySelector('.roster-wrap').scrollLeft,
    hard: document.activeElement.classList.contains('cell-hard') }));
  check('the link shows the conflict', toConflict.cell === `${late.personId}|${late.date}` && toConflict.hard && toConflict.left > 0, JSON.stringify(toConflict));
  await page.unroute(`**/api/periods/${month}`);
  await page.unroute(`**/api/plans/${month}/analysis`);
  await page.reload();
  await page.locator('.roster-wrap table').waitFor();

  // the gap row is row 0 of the arrow key grid
  const days = await page.locator('.roster-wrap thead th').count() - 2;
  check('gap row in the arrow key grid', await page.locator('tr.sticky-gaps [data-cell]').count() === days, String(days));
  await page.locator('tr.sticky-gaps [data-cell]').first().focus();
  await page.keyboard.press('ArrowDown');
  check('arrow down from the gap row', await page.evaluate(() => document.activeElement.dataset.r) === '1');

  // gap row: sticky below the day header, still on top after scrolling the grid
  const wrapScroll = (sel, to) => page.evaluate(([s, y]) => { document.querySelector(s).scrollTop = y; }, [sel, to]);
  await wrapScroll('.roster-wrap', 100000);
  await page.waitForTimeout(100);
  const gapRow = await belowHeader(page, 'tr.sticky-gaps th.person');
  check('gap row sticky', gapRow.position === 'sticky' && gapRow.scrolled > 0 && Math.abs(gapRow.offset) < 2 && gapRow.onTop, JSON.stringify(gapRow));

  // a day with many gaps in the first area: twelve short windows of demand on that date only
  const manyGapsDay = `${month}-15`;
  await page.evaluate(async ([m, day]) => {
    const dataset = await (await fetch('/api/dataset')).json();
    const area = dataset.areas[0].id;
    const windows = Array.from({ length: 12 }, (_, i) => ({ areaId: area, date: day, from: `${String(2 * i).padStart(2, '0')}:00`,
      to: `${String(2 * i).padStart(2, '0')}:30`, min: 9 }));
    dataset.demand = [...dataset.demand, ...windows];
    dataset.periods = [await (await fetch(`/api/periods/${m}`)).json()];
    await fetch('/api/import/dataset?replace=true', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...dataset, version: 2 }) });
  }, [month, manyGapsDay]);

  // Plan nach Funktion: menu without closing, legend state shared, "offen" row of the area at the top sticks
  await open(page, 'funktionen');
  // the "offen" row stays low on a day with many gaps: a few lines and "+N weitere", every gap in the title
  const manyGaps = await page.evaluate((day) => {
    const row = document.querySelector('.roster-wrap tbody tr.gap-row');
    const cell = row.querySelector(`[data-cell$="|${day}"]`);
    return { height: row.getBoundingClientRect().height, lines: cell.querySelectorAll('.cell-code').length,
      text: cell.textContent, title: cell.title };
  }, manyGapsDay);
  check('"offen" row capped on a day with many gaps', manyGaps.height < 80 && /\+\d+ weitere/.test(manyGaps.text)
    && (manyGaps.title.match(/\d+ · \d\d:\d\d-\d\d:\d\d/g) ?? []).length >= 12, JSON.stringify(manyGaps));
  const fnItems = await menuItems(page);
  check('function view menu', ['Drucken', 'Export XLSX', 'Export CSV', 'Export JSON'].every((x) => fnItems.includes(x))
    && !fnItems.includes('Monat abschließen'), fnItems.join(', '));
  check('function view legend collapsed', !(await legendOpen(page)));
  // the last area after the first with an "offen" row whose middle row can be scrolled to the top of the grid
  const target = await page.evaluate(() => {
    const wrap = document.querySelector('.roster-wrap');
    wrap.style.maxHeight = '250px'; // a small window: the demo data has few areas
    const scrollTo = (body) => wrap.scrollTop + body.rows[Math.floor(body.rows.length / 2)].getBoundingClientRect().top
      - wrap.getBoundingClientRect().top - wrap.clientTop - wrap.querySelector('thead').getBoundingClientRect().height;
    const bodies = [...wrap.querySelectorAll('tbody')];
    const i = bodies.findLastIndex((b, k) => k > 0 && b.querySelector('tr.gap-row') && b.rows.length > 3
      && scrollTo(b) <= wrap.scrollHeight - wrap.clientHeight);
    if (i > 0) wrap.scrollTop = scrollTo(bodies[i]);
    return i;
  });
  check('an area after the first with an "offen" row', target > 0, String(target));
  if (target > 0) {
    await page.waitForTimeout(150);
    const stuck = await page.evaluate(() => [...document.querySelectorAll('.roster-wrap tbody')].map((b, i) => (b.classList.contains('stuck') ? i : -1)).filter((i) => i >= 0));
    check('only the area at the top sticks', stuck.length === 1 && stuck[0] === target, JSON.stringify(stuck));
    const group = await belowHeader(page, '.roster-wrap tbody.stuck tr.group th');
    check('area name below the day header', Math.abs(group.offset) < 2 && group.onTop, JSON.stringify(group));
    const offen = await belowHeader(page, '.roster-wrap tbody.stuck tr.gap-row th.person');
    const groupHeight = await page.locator('.roster-wrap tbody.stuck tr.group').evaluate((r) => r.getBoundingClientRect().height);
    check('"offen" row sticky below the area name', offen.position === 'sticky' && Math.abs(offen.offset - groupHeight) < 2 && offen.onTop,
      JSON.stringify(offen));
  }

  // viewers: no solver options, no exports, no closing; the legend without planner-only items
  const viewer = await login('ansicht');
  await open(viewer, 'plan');
  check('viewer: no Erweitert, no Planen', await viewer.locator('details.advanced').count() === 0
    && await viewer.getByRole('button', { name: 'Planen', exact: true }).count() === 0);
  check('viewer: no rule chips (there is no list behind them)', await viewer.locator('.statusbar .chips, .statusbar .chip').count() === 0);
  const viewerItems = await menuItems(viewer);
  check('viewer: menu only Drucken', viewerItems.length === 1 && viewerItems[0] === 'Drucken', viewerItems.join(', '));
  await viewer.locator('details.legend-box > summary').click();
  check('viewer legend: no on-request marker, no absence types, ABW',
    await viewer.locator('.legend-box .cell-code.on-request').count() === 0
    && await viewer.locator('.legend-box .cell-code.absence.tinted').count() === 0
    && await viewer.locator('.legend-box .cell-code.absence').filter({ hasText: 'ABW' }).count() === 1);
  check('viewer print legend: no absence types', await viewer.locator('.print-only .legend .cell-code.absence.tinted').count() === 0);
  await open(viewer, 'funktionen');
  check('viewer function legend: no on-request marker', await viewer.locator('.legend .cell-code.on-request').count() === 0);
  check('viewer function menu only Drucken', (await menuItems(viewer)).join() === 'Drucken');
  await viewer.context().close();

  // closing the month: checklist; with an open item (plan out of date) only after "Trotzdem abschließen"
  const period = await api(page, `/api/periods/${month}`);
  await api(page, `/api/periods/${month}`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(period) });
  await open(page, 'plan');
  const closeDialog = async () => {
    await page.getByRole('button', { name: 'Ausgabe' }).click();
    await page.locator('#output-menu').getByRole('button', { name: 'Monat abschließen' }).click();
    await page.locator('dialog.dialog[open]').waitFor();
  };
  await closeDialog();
  const list = await page.locator('dialog .checklist li').allInnerTexts();
  check('checklist of three items', list.length === 3, list.join(' | '));
  const after = await page.locator('#close-month-after').innerText();
  check('the dialog says what follows: read only, where to reopen', after.includes('nur noch lesbar') && after.includes('„Daten > Monate“'), after);
  check('plan out of date is open', await page.locator('dialog .checklist li.open').filter({ hasText: 'Plan veraltet' }).count() === 1);
  const confirmButton = page.locator('dialog').getByRole('button', { name: 'Monat abschließen' });
  check('closing needs the second confirm', await confirmButton.isDisabled());
  await page.locator('#close-anyway').check();
  check('"Trotzdem abschließen" enables closing', await confirmButton.isEnabled());
  await page.keyboard.press('Escape');
  check('Escape cancels', await page.locator('dialog').count() === 0
    && (await api(page, `/api/plans/${month}`)).plan.status !== 'CLOSED');
  await closeDialog();
  await page.locator('#close-anyway').check();
  await confirmButton.click();
  await page.locator('.status-details').filter({ hasText: 'abgeschlossen' }).waitFor({ timeout: 10000 }).catch(() => {});
  check('month closed', (await api(page, `/api/plans/${month}`)).plan.status === 'CLOSED'
    && (await page.locator('.status-details').innerText()).includes('Status: abgeschlossen'));
  check('closed month: no closing in the menu', !(await menuItems(page)).includes('Monat abschließen'));
  await page.context().close();
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('plan views ok');
