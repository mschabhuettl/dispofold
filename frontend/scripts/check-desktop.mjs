// Playwright check of the desktop window behaviour against the dev server in desktop mode (MOCK_DESKTOP=1):
// restart answers (503 desktop.restarting) show a status and no error, the download event of the shell shows a toast,
// a bad update file shows its German error, installing an update reloads, and quitting shows the closing state.
// Also: in browser mode (GET /__shell/mode) quitting says the tab can be closed; the Docker build never asks the shell.
// The menu entry Anleitung and the links in the page intros show the guide built into the program (with its images,
// no request leaves the program): every page with its links to the overview and the neighbouring pages, sections by
// address, the intro of every page leading to its section, and a clear message when the built-in guide is missing.
// Updates: the first start asks once whether to check (nothing is checked before the answer, "Nein" keeps it off,
// "Ja" sets the GitHub source), the notice of a new version with "Jetzt installieren"; in the Docker build the notice
// of the server's check for planners, dismissed per version, never for viewers.
//   npm run build && node scripts/check-desktop.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchBrowser } from './browser.mjs';
import { PAGES, SECTIONS, guideHref } from '../src/lib/guide.js';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const servers = [];

/** Starts the dev server with extra environment on a free port; returns its base URL. */
async function startServer(env) {
  const port = await new Promise((resolve) => {
    const s = createServer().listen(0, () => { const p = s.address().port; s.close(() => resolve(p)); });
  });
  const server = spawn(process.execPath, [join(root, 'scripts', 'dev-server.mjs'), String(port)], {
    stdio: ['ignore', 'pipe', 'inherit'], env: { ...process.env, ...env } });
  servers.push(server);
  await new Promise((resolve) => server.stdout.on('data', (d) => { if (String(d).includes('dev server on')) resolve(); }));
  return `http://localhost:${port}`;
}

const base = await startServer({ MOCK_DESKTOP: '1', MOCK_FRONTEND: 'window',
  MOCK_UPDATE_SOURCE: 'https://updates.example.invalid/dispofold' });

let failed = false;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);
  if (!ok) failed = true;
};

const browser = await launchBrowser();
try {
  const page = await browser.newPage({ locale: 'de-DE' });
  page.on('dialog', (d) => d.accept());
  page.on('pageerror', (e) => check('no page error', false, e.message));
  await page.goto(`${base}/#/stammdaten`);
  await page.locator('h1').waitFor();

  // 1. backend restart: status shown, requests repeated, no error toast, view loaded again afterwards
  await page.evaluate(() => fetch('/api/dev/restart?ms=3000', { method: 'POST' }));
  await page.goto(`${base}/#/regeln`);
  await page.locator('#restart-status:not([hidden])').waitFor({ timeout: 5000 });
  check('restart status shown', true, await page.locator('#restart-status').textContent());
  await page.locator('#h-goals').waitFor({ timeout: 20000 });
  check('view loaded after the restart', await page.locator('#restart-status').isHidden());
  check('no error toast during the restart', await page.locator('#toast.error:not([hidden])').count() === 0);

  // 2. the shell saved a download
  await page.evaluate(() => window.dispatchEvent(new CustomEvent('dispofold-download',
    { detail: { name: 'dienstplan-2027-01.json', folder: 'Downloads' } })));
  const toast = await page.locator('#toast:not([hidden])').textContent();
  check('download toast', toast === 'Gespeichert: dienstplan-2027-01.json im Ordner Downloads', toast);

  // 3. update from a file that is not a bundle: German error from the code
  const bad = join(tmpdir(), `dispofold-bad-${process.pid}.zip`);
  writeFileSync(bad, 'not a zip');
  await page.goto(`${base}/#/einstellungen`);
  await page.locator('.settings').waitFor();
  await page.setInputFiles('#update-file', bad);
  await page.getByRole('button', { name: 'Update einspielen' }).click();
  await page.locator('#toast.error:not([hidden])').waitFor();
  check('bad update file', (await page.locator('#toast span').textContent()).includes('kein Update-Paket'));

  // 4. install the offered update: restart page, then reload with the new version
  await page.getByRole('button', { name: 'Jetzt suchen' }).click();
  await page.getByRole('button', { name: 'Update installieren' }).click();
  await page.getByText('Dispofold startet neu').first().waitFor();
  await page.locator('.settings').waitFor({ timeout: 30000 });
  check('reloaded with the new version', (await page.locator('.settings p').first().textContent()).includes('1.3.0'));

  // 5. quit: closing state, no restart page
  await page.click('#quit');
  await page.getByText('Dispofold wird beendet').waitFor();
  check('quit shows the closing state', await page.getByText('startet neu').count() === 0);

  // 6. browser mode (Linux default): the tab stays open after quitting
  const browserBase = await startServer({ MOCK_DESKTOP: '1', MOCK_FRONTEND: 'browser' });
  const tab = await browser.newPage({ locale: 'de-DE' });
  tab.on('dialog', (d) => d.accept());
  await tab.goto(`${browserBase}/#/plan`);
  await tab.locator('#quit:not([hidden])').waitFor();
  await tab.click('#quit');
  await tab.getByText('Dispofold ist beendet.').waitFor();
  check('browser mode: tab can be closed', await tab.getByText('Dieses Fenster kann geschlossen werden.').count() === 1);

  // 7. Docker build: never asks the shell
  const dockerBase = await startServer({});
  const docker = await browser.newPage({ locale: 'de-DE' });
  const asked = [];
  docker.on('request', (r) => { if (r.url().includes('/__shell/')) asked.push(r.url()); });
  await docker.goto(`${dockerBase}/login.html`);
  await docker.fill('#j_username', 'planung');
  await docker.fill('#j_password', 'x');
  await Promise.all([docker.waitForURL((u) => !u.pathname.endsWith('login.html')), docker.click('button[type=submit]')]);
  await docker.locator('#nav a').first().waitFor();
  check('Docker build does not ask /__shell/mode', asked.length === 0, asked.join(', '));

  // 8. the guide built into the program, in the window and in browser mode
  for (const mode of ['window', 'browser']) {
    const guideBase = await startServer({ MOCK_DESKTOP: '1', MOCK_FRONTEND: mode });
    const g = await browser.newPage({ locale: 'de-DE', viewport: { width: 1400, height: 900 } });
    g.on('pageerror', (e) => check('no page error', false, e.message));
    const outside = [];
    g.on('request', (r) => { if (new URL(r.url()).hostname !== 'localhost') outside.push(r.url()); });
    const opened = () => g.waitForFunction(() => document.querySelector('#main h1') && !document.querySelector('#main > p.muted'));
    await g.goto(`${guideBase}/#/regeln`);
    await opened();
    const entry = g.locator('#nav a', { hasText: 'Anleitung' });
    check(`${mode}: menu entry Anleitung to the built-in guide`, await entry.getAttribute('href') === '#/anleitung'
      && await entry.getAttribute('target') === null);
    check(`${mode}: intro links to its page`, await g.locator('#main h1 + p.page-intro a').getAttribute('href') === '#/anleitung/einstellungen');
    await entry.click();
    await g.waitForFunction(() => location.hash === '#/anleitung' && document.querySelector('#main .guide'));
    check(`${mode}: guide title`, await g.locator('#main h1').innerText() === 'Anleitung für die Dienstplanung');
    check(`${mode}: guide menu entry marked`, await entry.getAttribute('aria-current') === 'page');
    const online = g.locator('.guide-online').locator(mode === 'browser' ? 'a' : 'code');
    check(`${mode}: online address ${mode === 'browser' ? 'as a link' : 'to copy'}`,
      (await online.innerText()) === 'https://mschabhuettl.github.io/dispofold/anleitung/');
    // the overview links to every page; each page has its title, the address of the same page on the site and the
    // links to the overview and the neighbouring pages
    const pageLinks = await g.locator('.guide a[href^="#/anleitung/"]').evaluateAll((as) => [...new Set(as.map((a) => a.getAttribute('href')))]);
    check(`${mode}: the overview links to every page`, PAGES.slice(1).every(([id]) => pageLinks.includes(`#/anleitung/${id}`)), pageLinks.join(' '));
    for (const [i, [id, title]] of PAGES.entries()) {
      await g.goto(`${guideBase}/${guideHref(true, id)}`);
      await g.waitForFunction((t) => document.querySelector('#main h1')?.textContent === t && document.querySelector('#main .guide-pages'), title);
      const nav = await g.locator('#main .guide-pages a').evaluateAll((as) => as.map((a) => a.getAttribute('href')));
      const expected = [i > 0 && '#/anleitung', i > 1 && guideHref(true, PAGES[i - 1][0]), i + 1 < PAGES.length && guideHref(true, PAGES[i + 1][0])].filter(Boolean);
      const address = (await online.innerText()) === guideHref(false, id);
      check(`${mode}: page ${id} with title, site address and page links`, address && JSON.stringify(nav) === JSON.stringify(expected), nav.join(' '));
    }
    // a page with many images: all shown
    await g.goto(`${guideBase}/#/anleitung/plan-bearbeiten`);
    await opened();
    const images = await g.locator('.guide img').evaluateAll(async (imgs) => {
      await Promise.all(imgs.map((i) => (i.complete ? null : new Promise((r) => { i.onload = r; i.onerror = r; }))));
      return imgs.map((i) => i.naturalWidth);
    });
    check(`${mode}: guide images shown`, images.length >= 5 && images.every((w) => w > 0), images.join(' '));
    // "next" from the bottom of a long page shows the next page from its top
    await g.locator('#main .guide-pages a').last().click();
    await g.waitForFunction(() => location.hash === '#/anleitung/auswertungen' && document.querySelector('#main .guide'));
    await g.waitForTimeout(300);
    check(`${mode}: the next page starts at its top`, await g.evaluate(() => window.scrollY) === 0);
    // a link to a section of another page
    await g.goto(`${guideBase}/#/anleitung`);
    await opened();
    await g.locator('.guide a[href="#/anleitung/planen/probleme"]').first().click();
    await g.waitForFunction(() => location.hash === '#/anleitung/planen/probleme' && document.getElementById('probleme'));
    await g.waitForTimeout(300);
    const top = await g.locator('#probleme').evaluate((e) => e.getBoundingClientRect().top);
    check(`${mode}: a link in the guide shows its section`, top > 0 && top < 200, String(top));
    await g.goto(`${guideBase}/#/anleitung/personen/nicht-mehr-aus-dem-quellsystem-%C3%BCbernehmen`);
    await opened();
    await g.waitForTimeout(300);
    const section = await g.locator('[id="nicht-mehr-aus-dem-quellsystem-übernehmen"]').evaluate((e) => e.getBoundingClientRect().top);
    check(`${mode}: section with umlaut by address`, section > 0 && section < 200, String(section));
    await g.goto(`${guideBase}/#/anleitung`);
    await opened();
    const external = await g.locator('.guide a[target="_blank"]').count();
    check(`${mode}: links to the other documents ${mode === 'browser' ? 'open the site' : 'as addresses to copy'}`, mode === 'browser'
      ? external >= 1 : external === 0 && await g.locator('.guide code', { hasText: 'https://mschabhuettl.github.io/dispofold/regeln/' }).count() >= 1);
    // every page of the program: its intro links to a page of the guide, which shows the section
    for (const [path, target] of Object.entries(SECTIONS)) {
      await g.goto(`${guideBase}/#/${path}`);
      await opened();
      const intro = g.locator('#main h1 + p.page-intro a');
      const href = await intro.getAttribute('href');
      await intro.click();
      const [page, anchor] = target.split('#');
      const landed = await g.waitForFunction(([p, a]) => document.querySelector('#main .guide')
        && (a ? document.getElementById(a) : document.querySelector('#main h1'))
        && location.hash.startsWith(`#/anleitung/${p}`), [page, anchor], { timeout: 10000 }).then(() => true, () => false);
      check(`${mode}: ${path} intro leads to ${target}`, landed && href === guideHref(true, target), href);
    }
    check(`${mode}: no request leaves the program`, outside.length === 0, outside.join(', '));
    if (mode === 'window') {
      await g.route('**/anleitung/index.html', (r) => r.fulfill({ status: 404, body: 'not found' }));
      await g.goto(`${guideBase}/#/anleitung`);
      const alert = await g.locator('#main .alert-error').innerText();
      check('missing built-in guide: clear message with the online address',
        alert.includes('fehlt') && alert.includes('https://mschabhuettl.github.io/dispofold/anleitung/'), alert);
    }
    await g.close();
    // a section opened from a page intro before the images were ever loaded (new context, empty cache)
    const fresh = await browser.newContext({ locale: 'de-DE', viewport: { width: 1400, height: 900 } });
    const f = await fresh.newPage();
    await f.goto(`${guideBase}/#/pruefen`);
    await f.waitForFunction(() => document.querySelector('#main h1') && !document.querySelector('#main > p.muted'));
    await f.locator('#main h1 + p.page-intro a').click();
    await f.waitForFunction(() => location.hash === '#/anleitung/planen/probleme' && document.getElementById('probleme'));
    await f.locator('.guide img').evaluateAll((imgs) => Promise.all(imgs.map((i) => (i.complete ? null
      : new Promise((r) => { i.onload = r; i.onerror = r; })))));
    await f.waitForTimeout(300);
    const after = await f.locator('#probleme').evaluate((e) => e.getBoundingClientRect().top);
    check(`${mode}: section from the intro stays in view once the images have loaded`, after > 0 && after < 200, String(after));
    await fresh.close();
  }

  // 9. first start without update settings: asked once, nothing checked before the answer; "Nein" keeps it off
  const checks = (p) => {
    const seen = { checks: 0, saved: [] };
    p.on('request', (r) => {
      if (r.url().includes('/api/desktop/update?check=true')) seen.checks++;
      if (r.url().endsWith('/api/desktop/update/settings')) seen.saved.push(r.postDataJSON());
    });
    return seen;
  };
  const noBase = await startServer({ MOCK_DESKTOP: '1', MOCK_FRONTEND: 'window', MOCK_UPDATE_ASK: '1' });
  const no = await browser.newPage({ locale: 'de-DE' });
  const noSeen = checks(no);
  await no.goto(`${noBase}/#/plan`);
  const question = no.locator('#update-consent');
  await question.waitFor();
  check('first start asks whether to check', (await question.locator('h2').innerText())
    === 'Soll Dispofold automatisch nach neuen Versionen suchen?');
  check('"Ja" is preselected', await no.evaluate(() => document.activeElement?.textContent) === 'Ja');
  await no.keyboard.press('Escape');
  await no.keyboard.press('Escape');
  await no.waitForTimeout(500);
  const escaped = await question.isVisible();
  check('Escape is no answer', noSeen.saved.length === 0 && noSeen.checks === 0, escaped ? 'still open' : 'closed, nothing saved');
  if (!escaped) {
    await no.reload();
    await question.waitFor();
    check('closed without an answer: asked again on the next start', true);
  }
  await question.getByRole('button', { name: 'Nein' }).click();
  await question.waitFor({ state: 'detached' });
  check('"Nein" saves no source', JSON.stringify(noSeen.saved) === '[{"source":"","checkOnStart":true}]', JSON.stringify(noSeen.saved));
  await no.reload();
  await no.locator('#nav a').first().waitFor();
  await no.waitForTimeout(800);
  check('asked only once', await no.locator('#update-consent').count() === 0);
  check('"Nein": no check, no notice', noSeen.checks === 0 && await no.locator('#banner').isHidden(), `${noSeen.checks} checks`);

  // 10. "Ja" (Enter on the preselected answer): the GitHub source with the check on start, then the notice
  const yesBase = await startServer({ MOCK_DESKTOP: '1', MOCK_FRONTEND: 'window', MOCK_UPDATE_ASK: '1' });
  const yes = await browser.newPage({ locale: 'de-DE' });
  yes.on('dialog', (d) => d.accept());
  const yesSeen = checks(yes);
  await yes.goto(`${yesBase}/#/plan`);
  await yes.locator('#update-consent').waitFor();
  check('no check before the answer', yesSeen.checks === 0);
  await yes.keyboard.press('Enter');
  const notice = yes.locator('#banner:not([hidden])');
  await notice.waitFor();
  check('"Ja" saves the GitHub source with the check on start', JSON.stringify(yesSeen.saved)
    === '[{"source":"https://api.github.com/repos/mschabhuettl/dispofold/releases/latest","checkOnStart":true}]', JSON.stringify(yesSeen.saved));
  check('desktop notice', (await notice.locator('span').innerText()) === 'Neue Version 1.3.0 verfügbar');
  await notice.getByRole('button', { name: 'Was ist neu?' }).click();
  const release = yes.locator('dialog.release-dialog');
  check('desktop dialog: title, notes and the page as an address', (await release.locator('h2').innerText()) === 'Dispofold 1.3.0'
    && (await release.locator('.release-notes').innerText()).includes('Wochenenden')
    && (await release.locator('code').innerText()) === 'https://github.com/mschabhuettl/dispofold/releases/tag/v1.3.0'
    && await release.locator('ol').count() === 0);
  // a failed install is told inside the dialog (the toast would sit behind the modal dialog)
  await yes.route('**/api/desktop/update/install', (r) => r.fulfill({ status: 400, contentType: 'application/json',
    body: JSON.stringify({ code: 'desktop.update.sourceUnreachable', params: {}, error: 'Die Update-Quelle ist nicht erreichbar.' }) }));
  await release.getByRole('button', { name: 'Jetzt installieren' }).click();
  const failure = release.getByRole('alert');
  await failure.waitFor({ timeout: 5000 }).catch(() => {});
  check('a failed install is shown in the dialog', await release.isVisible() && await failure.isVisible()
    && (await failure.innerText()).length > 0, await failure.isVisible() ? await failure.innerText() : 'no message in the dialog');
  await yes.unroute('**/api/desktop/update/install');
  await release.getByRole('button', { name: 'Jetzt installieren' }).click();
  await yes.getByText('Dispofold startet neu').first().waitFor();
  await yes.locator('#nav a').first().waitFor({ timeout: 30000 });
  await yes.waitForTimeout(800);
  check('installed from the notice; no notice for the running version', await yes.locator('#banner').isHidden());

  // first start on the Updates page: after "Ja" the form shows the saved source, so "Speichern" keeps the check on
  const formBase = await startServer({ MOCK_DESKTOP: '1', MOCK_FRONTEND: 'window', MOCK_UPDATE_ASK: '1' });
  const form = await browser.newPage({ locale: 'de-DE' });
  const formSeen = checks(form);
  await form.goto(`${formBase}/#/einstellungen`);
  await form.locator('#update-consent').waitFor();
  await form.locator('#update-source').waitFor();
  await form.keyboard.press('Enter');
  await form.locator('#update-consent').waitFor({ state: 'detached' });
  await form.waitForFunction(() => document.getElementById('update-source')?.value.startsWith('https://api.github.com/'), null,
    { timeout: 5000 }).catch(() => {});
  await form.getByRole('button', { name: 'Speichern' }).click();
  await form.waitForTimeout(500);
  check('Updates page behind the question: "Speichern" after "Ja" keeps the GitHub source', formSeen.saved.length === 2
    && formSeen.saved[1].source === 'https://api.github.com/repos/mschabhuettl/dispofold/releases/latest'
    && formSeen.saved[1].checkOnStart === true, JSON.stringify(formSeen.saved));

  // 11. Docker build: the notice of the server's check for planners, dismissed per version; viewers never see it
  const serverBase = await startServer({ MOCK_RELEASE: '1.3.0' });
  const login = async (p, user) => {
    await p.goto(`${serverBase}/login.html`);
    await p.fill('#j_username', user);
    await p.fill('#j_password', 'x');
    await Promise.all([p.waitForURL((u) => !u.pathname.endsWith('login.html')), p.click('button[type=submit]')]);
    await p.locator('#nav a').first().waitFor();
  };
  const planner = await (await browser.newContext({ locale: 'de-DE' })).newPage();
  planner.on('pageerror', (e) => check('no page error', false, e.message));
  await login(planner, 'planung');
  const serverNotice = planner.locator('#banner:not([hidden])');
  await serverNotice.waitFor();
  check('server notice for planners', (await serverNotice.locator('span').innerText()) === 'Neue Version 1.3.0 verfügbar');
  await serverNotice.getByRole('button', { name: 'Was ist neu?' }).click();
  const serverDialog = planner.locator('dialog.release-dialog');
  const link = serverDialog.getByRole('link', { name: 'Seite der Version auf GitHub öffnen' });
  check('server dialog: release page link, update steps with the tag, no install button',
    await link.getAttribute('href') === 'https://github.com/mschabhuettl/dispofold/releases/tag/v1.3.0'
    && await link.getAttribute('target') === '_blank'
    && (await serverDialog.locator('ol').innerText()).includes('TAG=1.3.0')
    && await serverDialog.getByRole('button', { name: 'Jetzt installieren' }).count() === 0);
  await serverDialog.getByRole('button', { name: 'Schließen' }).click();
  await planner.getByRole('button', { name: 'Hinweis für diese Version ausblenden' }).click();
  check('dismissed: hidden', await planner.locator('#banner').isHidden());
  await planner.reload();
  await planner.locator('#nav a').first().waitFor();
  await planner.waitForTimeout(800);
  check('dismissed: stays hidden after a reload', await planner.locator('#banner').isHidden());
  // a newer release comes back; its notes are shown as text, never as HTML
  await planner.route('**/api/release', (r) => r.fulfill({ contentType: 'application/json', body: JSON.stringify({ current: '1.2.0',
    available: { version: '1.4.0', title: null, notes: '<img src=x onerror="window.injected=1"> **fett**', url: 'javascript:alert(1)' } }) }));
  await planner.reload();
  await serverNotice.waitFor();
  check('a newer version shows again', (await serverNotice.locator('span').innerText()) === 'Neue Version 1.4.0 verfügbar');
  await serverNotice.getByRole('button', { name: 'Was ist neu?' }).click();
  check('notes as text, no link that is not https', await serverDialog.locator('.release-notes img').count() === 0
    && (await serverDialog.locator('.release-notes').innerText()).startsWith('<img')
    && await serverDialog.locator('a').count() === 0 && (await serverDialog.locator('h2').innerText()) === 'Version 1.4.0'
    && await planner.evaluate(() => window.injected) === undefined);
  // a release the server finds after the page was opened shows on a later page change
  const late = await (await browser.newContext({ locale: 'de-DE' })).newPage();
  await late.clock.install();
  let found = false;
  await late.route('**/api/release', (r) => (found ? r.continue() : r.fulfill({ contentType: 'application/json',
    body: JSON.stringify({ current: '1.2.0', available: null }) })));
  await login(late, 'planung');
  await late.waitForTimeout(500);
  const hiddenFirst = await late.locator('#banner').isHidden();
  found = true;
  await late.clock.fastForward('11:00');
  await late.locator('#nav a[data-group="settings"]').click();
  await late.locator('#banner:not([hidden])').waitFor({ timeout: 5000 }).catch(() => {});
  check('a release found later shows on a later page change', hiddenFirst && await late.locator('#banner').isVisible());
  const viewer = await (await browser.newContext({ locale: 'de-DE' })).newPage();
  const viewerAsked = [];
  viewer.on('request', (r) => { if (r.url().includes('/api/release')) viewerAsked.push(r.url()); });
  await login(viewer, 'ansicht');
  await viewer.waitForTimeout(800);
  check('viewers: no notice, not even asked', await viewer.locator('#banner').isHidden() && viewerAsked.length === 0);
} finally {
  await browser.close();
  for (const s of servers) s.kill();
}
if (failed) process.exit(1);
console.log('desktop window behaviour ok');
