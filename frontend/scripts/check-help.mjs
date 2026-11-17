// Playwright check of the help (lib/info.js): on the rules page, the person page and the Dienstplan an "i" button
// opens its text by click and by keyboard (Enter, Space), closes it by Escape (the focus stays on the button) and by a
// click elsewhere; a tap opens and closes it on a touch screen, where the text stays inside the window; one help is
// open at a time; the text is not in the page while closed; an open help survives the redraws of the status line while
// solving; every page has its intro under the title, also after a view draws itself anew.
// Demo data of the dev server (mock API), month 2027-01 planned.
//   npm run build && node scripts/check-help.mjs     (Chromium via `npx playwright install chromium`)
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
  const login = async (user, options = { viewport: { width: 1600, height: 1000 } }) => {
    const context = await browser.newContext({ locale: 'de-DE', ...options });
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
  const open = async (page, hash) => {
    await page.goto(`${base}/#/${hash}`);
    await page.waitForFunction(() => document.querySelector('#main h1') && !document.querySelector('#main > p.muted'));
  };
  /** The state of a help button: expanded, its text box in the page and visible, the text, the focus on it. */
  const state = (button) => button.evaluate((b) => {
    const box = document.getElementById(b.getAttribute('aria-controls'));
    return { expanded: b.getAttribute('aria-expanded'), shown: Boolean(box?.isConnected && box.offsetParent !== null),
      text: box?.isConnected ? box.textContent : '', focused: document.activeElement === b, label: b.getAttribute('aria-label') };
  });

  const page = await login('planung');
  const personId = await page.evaluate(async (m) => {
    await fetch('/api/import/demo', { method: 'POST' });
    await fetch(`/api/plans/${m}/solve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"seed":1,"timeLimitSeconds":1}' });
    return (await (await fetch('/api/dataset')).json()).people[0].id;
  }, month);
  // the plan must be finished: while solving, the status line is drawn again every few seconds
  const solving = () => page.evaluate(async (m) => (await (await fetch(`/api/plans/${m}`)).json()).solving, month);
  for (let i = 0; i < 120 && await solving(); i++) await page.waitForTimeout(500);
  check('the plan is finished', !(await solving()));

  const pages = [
    ['rules page', 'regeln', '.goal[data-group="blocks"] h3 .info-btn'],
    ['person page', `person/${encodeURIComponent(personId)}`, '.kpi-hours .info-btn'],
    ['Dienstplan', 'plan', '.statusbar .with-info .info-btn'],
  ];
  for (const [name, hash, selector] of pages) {
    await open(page, hash);
    check(`${name}: intro under the title`, (await page.locator('#main h1 + p.page-intro').innerText()).length > 20);
    const button = page.locator(selector).first();
    await button.waitFor();
    let s = await state(button);
    check(`${name}: closed at first, text not in the page`, s.expanded === 'false' && !s.shown && s.text === '' && s.label.startsWith('Hilfe'), JSON.stringify(s));

    await button.click();
    s = await state(button);
    check(`${name}: a click opens`, s.expanded === 'true' && s.shown && s.text.length > 20, JSON.stringify(s));
    await page.locator(`#${await button.getAttribute('aria-controls')}`).click();
    check(`${name}: a click on the text keeps it open`, (await state(button)).expanded === 'true');
    await page.locator('#main h1').click();
    s = await state(button);
    check(`${name}: a click elsewhere closes`, s.expanded === 'false' && !s.shown, JSON.stringify(s));

    await button.focus();
    await page.keyboard.press('Enter');
    s = await state(button);
    check(`${name}: Enter opens`, s.expanded === 'true' && s.shown, JSON.stringify(s));
    await page.keyboard.press('Escape');
    s = await state(button);
    check(`${name}: Escape closes, the focus stays on the button`, s.expanded === 'false' && !s.shown && s.focused, JSON.stringify(s));
    await page.keyboard.press('Space');
    check(`${name}: Space opens`, (await state(button)).expanded === 'true');
    await page.keyboard.press('Space');
    check(`${name}: Space closes again`, (await state(button)).expanded === 'false');
  }

  // Rules page: one help open at a time, and opening help changes nothing in the form
  await open(page, 'regeln');
  const first = page.locator('.goal[data-group="blocks"] h3 .info-btn');
  const second = page.locator('.goal[data-group="wishes"] h3 .info-btn');
  await first.click();
  await second.click();
  check('one help open at a time', (await state(first)).expanded === 'false' && (await state(second)).expanded === 'true');
  check('help does not mark the rules unsaved', !(await page.locator('.save-bar').innerText()).includes('Nicht gespeichert'));
  await page.keyboard.press('Escape');
  check('every rule has its help in Erweitert', await page.locator('.rule-row').count() > 20
    && await page.locator('.rule-row').count() === await page.locator('.rule-row .info-btn').count());

  // An intro under the title of every page, for planners and viewers, and again after a view draws itself anew
  const intro = (p) => p.locator('#main h1 + p.page-intro').count();
  const viewer = await login('ansicht');
  for (const [who, p] of [['planner', page], ['viewer', viewer]]) {
    const groups = await p.locator('#nav a').evaluateAll((as) => as.map((a) => a.getAttribute('href').slice(2)));
    for (const g of groups) {
      await open(p, g);
      const tabs = await p.locator('.page-tabs a').evaluateAll((as) => as.map((a) => a.getAttribute('href').slice(2)));
      for (const path of tabs.length ? tabs : [g]) {
        await open(p, path);
        check(`${who} ${path}: intro under the title`, await intro(p) === 1);
      }
    }
  }
  await open(page, 'stammdaten');
  await page.locator('[data-new="areas"]').click();
  await page.locator('tr.edit-row').getByRole('button', { name: 'Abbrechen' }).click();
  check('Stammdaten: the intro stays after Abbrechen', await intro(page) === 1);

  // While solving, the status line is drawn again every few seconds: an open help stays open with the focus on it
  await page.evaluate(async (m) => {
    await fetch(`/api/plans/${m}/solve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"seed":1,"timeLimitSeconds":8}' });
  }, month);
  await open(page, 'plan');
  const headline = page.locator('.statusbar .with-info .info-btn');
  await headline.focus();
  await page.keyboard.press('Enter');
  await page.waitForTimeout(4500);
  const during = await state(headline);
  check('solving: the help stays open and focused across redraws', during.expanded === 'true' && during.shown && during.focused, JSON.stringify(during));
  await page.keyboard.press('Escape');
  check('solving: Escape still closes it', (await state(headline)).expanded === 'false'
    && await page.locator('.info-text').count() === 0);
  for (let i = 0; i < 40 && await solving(); i++) await page.waitForTimeout(500);

  // Touch: a tap opens, the text stays inside a narrow window, a tap elsewhere closes
  const phone = await login('planung', { viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });
  await open(phone, `person/${encodeURIComponent(personId)}`);
  const tile = phone.locator('.kpi .info-btn').last();
  await tile.scrollIntoViewIfNeeded();
  await tile.tap();
  const s = await state(tile);
  const box = await phone.locator(`#${await tile.getAttribute('aria-controls')}`).boundingBox();
  check('touch: a tap opens', s.expanded === 'true' && s.shown, JSON.stringify(s));
  check('touch: the text stays inside the window', box && box.x >= 0 && box.x + box.width <= 390
    && box.y >= 0 && box.y + box.height <= 844, JSON.stringify(box));
  await phone.locator('#main h1').tap();
  check('touch: a tap elsewhere closes', (await state(tile)).expanded === 'false');
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('help checked');
