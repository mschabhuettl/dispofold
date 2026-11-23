// Playwright check at phone width (390 x 844): the menu is folded into a bar at the top and opens with its button,
// a choice folds it again; no page scrolls sideways, for planners and viewers, on every page and tab of the menu, a
// person's page and the guide; wide tables and the plan scroll inside their own box. Demo data of the dev server
// (mock API), month 2027-01 planned.
//   npm run build && node scripts/check-phone.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchBrowser } from './browser.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const month = '2027-01';
const WIDTH = 390;
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
    const context = await browser.newContext({ locale: 'de-DE', viewport: { width: WIDTH, height: 844 }, hasTouch: true, isMobile: true });
    await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
    const page = await context.newPage();
    page.on('pageerror', (e) => check(`${user}: no page error`, false, e.message));
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
    await page.waitForTimeout(300); // charts and the timeline are drawn after the page
  };
  /** Width of the page and the elements that reach past the window, unless a box of their own scrolls them. */
  const overflow = (page) => page.evaluate((width) => {
    const out = [];
    for (const el of document.querySelectorAll('body *')) {
      const r = el.getBoundingClientRect();
      if (r.width === 0 || r.right <= width + 1) continue;
      let clipped = false;
      for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) {
        const o = getComputedStyle(p).overflowX;
        if ((o === 'auto' || o === 'scroll' || o === 'hidden') && p.getBoundingClientRect().right <= width + 1) { clipped = true; break; }
      }
      if (!clipped) out.push(`${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}${el.className && typeof el.className === 'string' ? `.${el.className.trim().split(/\s+/).join('.')}` : ''} ${Math.round(r.right)}`);
    }
    return { scroll: document.scrollingElement.scrollWidth, main: document.getElementById('main').getBoundingClientRect().width, out: out.slice(0, 12),
      error: document.querySelector('#main .alert-error')?.textContent ?? '' };
  }, WIDTH);

  const page = await login('planung');
  const personId = await page.evaluate(async (m) => {
    await fetch('/api/import/demo', { method: 'POST' });
    await fetch(`/api/plans/${m}/solve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"seed":1,"timeLimitSeconds":1}' });
    return (await (await fetch('/api/dataset')).json()).people[0].id;
  }, month);
  const solving = () => page.evaluate(async (m) => (await (await fetch(`/api/plans/${m}`)).json()).solving, month);
  for (let i = 0; i < 120 && await solving(); i++) await page.waitForTimeout(500);
  check('the plan is finished', !(await solving()));

  // the menu: folded at first, the button opens it, a choice folds it again
  await open(page, 'plan');
  const toggle = page.locator('#nav-toggle');
  check('menu folded, its button shown', await page.locator('#nav').isHidden() && await toggle.isVisible()
    && await toggle.getAttribute('aria-expanded') === 'false');
  await toggle.tap();
  check('the button opens the menu', await page.locator('#nav').isVisible() && await toggle.getAttribute('aria-expanded') === 'true');
  await page.locator('#nav a[data-group="people"]').tap();
  await page.waitForFunction(() => location.hash === '#/person' && document.querySelector('#main h1'));
  check('a choice folds the menu again', await page.locator('#nav').isHidden() && await toggle.getAttribute('aria-expanded') === 'false');

  const viewer = await login('ansicht');
  for (const [who, p] of [['planner', page], ['viewer', viewer]]) {
    await open(p, 'plan');
    const groups = await p.locator('#nav a[href^="#/"]').evaluateAll((as) => as.map((a) => a.getAttribute('href').slice(2)));
    const paths = [];
    for (const g of groups) {
      await open(p, g);
      const tabs = await p.locator('.page-tabs a').evaluateAll((as) => as.map((a) => a.getAttribute('href').slice(2)));
      paths.push(...(tabs.length ? tabs : [g]));
    }
    paths.push(`person/${encodeURIComponent(personId)}`, 'anleitung/plan-bearbeiten');
    for (const path of [...new Set(paths)]) {
      await open(p, path);
      const o = await overflow(p);
      check(`${who} ${path}: no sideways scrolling of the page`, o.scroll <= WIDTH && o.main >= WIDTH - 2 && !o.error, JSON.stringify(o));
    }
  }
  // the plan itself scrolls in its own box
  await open(page, 'plan');
  const wrap = await page.locator('.roster-wrap').evaluate((w) => ({ scroll: w.scrollWidth, width: w.clientWidth }));
  check('the plan scrolls inside its box', wrap.scroll > wrap.width && wrap.width <= WIDTH, JSON.stringify(wrap));
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('phone width checked');
