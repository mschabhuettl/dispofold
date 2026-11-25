// Playwright check of the plugin system with the test-only example plugin (plugins/beispiel), on the dev server's
// mock API: Einstellungen → Erweiterungen lists it off, switching it on after a confirmation shows its menu entry,
// page (with intro and help link), settings (saved, refused with the plugin's own error text) and its import source
// on Daten → Übernehmen with the folder picker; readers see its page but neither the switch nor the settings; a build
// without test plugins has none. Desktop: the folder dialog of the window and the plugin's page in the built-in guide.
//   node scripts/build.mjs && node scripts/build.mjs --test-plugins --out dist-plugins && node scripts/check-plugins.mjs
// SHOTS=<folder> also saves screenshots of the Erweiterungen page and the example page there.
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchBrowser } from './browser.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const shots = process.env.SHOTS;
if (shots) mkdirSync(shots, { recursive: true });
let failed = false;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);
  if (!ok) failed = true;
};

async function server(env) {
  const port = await new Promise((resolve) => {
    const s = createServer().listen(0, () => { const p = s.address().port; s.close(() => resolve(p)); });
  });
  const proc = spawn(process.execPath, [join(root, 'scripts', 'dev-server.mjs'), String(port)],
    { stdio: ['ignore', 'pipe', 'inherit'], env: { ...process.env, ...env } });
  await new Promise((resolve) => proc.stdout.on('data', (d) => { if (String(d).includes('dev server on')) resolve(); }));
  return { base: `http://localhost:${port}`, stop: () => proc.kill() };
}

const browser = await launchBrowser();
const servers = [];
try {
  const session = async (base, user) => {
    const context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1400, height: 900 } });
    await context.addInitScript(() => sessionStorage.setItem('month', '2027-01'));
    const page = await context.newPage();
    page.on('pageerror', (e) => check('no page error', false, e.message));
    page.on('dialog', (d) => d.accept());
    if (user) {
      await page.goto(`${base}/login.html`);
      await page.fill('#j_username', user);
      await page.fill('#j_password', 'x');
      await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
    }
    return page;
  };
  const open = async (page, base, hash) => {
    await page.goto(`${base}/#/${hash}`);
    await page.waitForFunction(() => document.querySelector('#main h1') && !document.querySelector('#main > p.muted'));
  };
  const menu = (page) => page.$$eval('#nav a', (as) => as.map((a) => a.textContent));
  const tabs = (page) => page.$$eval('.page-tabs a', (as) => as.map((a) => a.textContent));
  const text = (page, sel) => page.$eval(sel, (e) => e.textContent).catch(() => null);

  // the server build with the example plugin
  const s = await server({ DIST: join(root, 'dist-plugins'), MOCK_TEST_PLUGINS: '1' });
  servers.push(s);
  const planner = await session(s.base, 'planung');
  await open(planner, s.base, 'erweiterungen');
  check('Erweiterungen is a tab of Einstellungen', (await tabs(planner)).includes('Erweiterungen'), (await tabs(planner)).join(', '));
  check('the plugin is listed with version and off', (await text(planner, '#ext-beispiel-name'))?.includes('Version 1.0.0')
    && !(await planner.isChecked('#ext-beispiel')));
  check('no menu entry while off', !(await menu(planner)).includes('Beispiel'));
  await planner.goto(`${s.base}/#/plugin/beispiel/start`);
  await planner.waitForFunction(() => document.querySelector('#main h1'));
  check('its page is not reachable while off', !planner.url().includes('plugin/beispiel'), planner.url());
  check('no import source while off', !(await planner.$('#h-plugin-beispiel')));
  await open(planner, s.base, 'erweiterungen');
  await Promise.all([planner.waitForEvent('load'), planner.check('#ext-beispiel')]);
  await planner.waitForSelector('#beispiel-greeting');
  check('switched on: menu entry', (await menu(planner)).includes('Beispiel'), (await menu(planner)).join(', '));
  await planner.waitForFunction(() => document.querySelector('#beispiel-greeting').value === 'Hallo');
  check('its settings show under its entry', true);
  await planner.fill('#beispiel-greeting', '');
  await planner.click('#beispiel-save');
  await planner.waitForSelector('#toast:not([hidden])');
  check('a refused setting shows the plugin\'s error text', (await text(planner, '#toast'))?.includes('Der Gruß braucht 1 bis 40 Zeichen'),
    await text(planner, '#toast'));
  await planner.fill('#beispiel-greeting', 'Servus');
  await planner.click('#beispiel-save');
  await planner.waitForFunction(() => document.querySelector('#toast')?.textContent.includes('Gruß gespeichert'));
  if (shots) await planner.screenshot({ path: join(shots, 'erweiterungen.png'), fullPage: true });
  await open(planner, s.base, 'plugin/beispiel/start');
  check('its page shows the setting', (await text(planner, '#beispiel-gruss')) === 'Servus');
  check('its page has its intro', (await text(planner, '.page-intro'))?.includes('Beispiel-Erweiterung zeigt den Gruß'));
  const help = await planner.$eval('.page-intro a', (a) => a.getAttribute('href')).catch(() => '');
  check('its help link opens its page of the built-in guide', help === '#/anleitung/plugin-beispiel-beispiel', help);
  if (shots) await planner.screenshot({ path: join(shots, 'beispiel-seite.png'), fullPage: true });
  // the import source: a source on Daten → Übernehmen; folder picker below the allowed folder, then the core's step
  await open(planner, s.base, 'uebernehmen/beispiel');
  check('Daten → Übernehmen has its import source, chosen', (await text(planner, 'label[for="source-beispiel"]'))?.trim() === 'Aus der Beispiel-Erweiterung'
    && await planner.isChecked('#source-beispiel'), await text(planner, '#take-over-sources'));
  await planner.waitForFunction(() => document.querySelector('#beispiel-file option')?.value === '/srv/dispofold/quellen');
  // a double click opens the folder once (the second click acts on the folder its button was shown in)
  await planner.dblclick('.folder-picker button:text("2027/")');
  await planner.waitForSelector('.folder-picker button:text("daten.json")');
  await planner.waitForTimeout(300);
  check('a double click opens the folder once', !(await text(planner, '.folder-picker p'))?.includes('2027/2027')
    && !(await text(planner, '#toast'))?.includes('nicht gefunden'), await text(planner, '.folder-picker p'));
  await planner.click('.folder-picker button:text("daten.json")');
  await planner.waitForFunction(() => document.querySelector('.folder-picker p')?.textContent.includes('2027/daten.json'));
  await planner.click('[data-import-run="beispiel"]');
  await planner.waitForSelector('#main .alert strong');
  check('the import runs and shows its summary', (await text(planner, '#main .alert strong'))?.includes('Import'), await text(planner, '#main .alert'));
  if (shots) await planner.screenshot({ path: join(shots, 'beispiel-import.png'), fullPage: true });

  const reader = await session(s.base, 'ansicht');
  await open(reader, s.base, 'plugin/beispiel/start');
  check('readers see the page marked for them', (await text(reader, '#beispiel-gruss')) === 'Servus');
  await reader.goto(`${s.base}/#/erweiterungen`);
  await reader.waitForFunction(() => document.querySelector('#main h1'));
  check('readers have no Erweiterungen page', !reader.url().includes('erweiterungen') && !(await reader.$('#ext-beispiel')));

  // a build without test plugins: nothing listed
  const plain = await server({});
  servers.push(plain);
  const p2 = await session(plain.base, 'planung');
  await open(p2, plain.base, 'erweiterungen');
  check('without plugins the page says so', (await text(p2, '#extensions-none')) === 'Dieses Programm enthält keine Erweiterungen.');

  // desktop program: the window's folder dialog, the built-in guide with the plugin's help page
  const d = await server({ DIST: join(root, 'dist-plugins'), MOCK_TEST_PLUGINS: '1', MOCK_DESKTOP: '1', MOCK_FRONTEND: 'window' });
  servers.push(d);
  const desk = await session(d.base, null);
  await open(desk, d.base, 'erweiterungen');
  const deskTabs = await tabs(desk);
  check('desktop: Updates and Erweiterungen are tabs', deskTabs.includes('Updates') && deskTabs.includes('Erweiterungen'), deskTabs.join(', '));
  await Promise.all([desk.waitForEvent('load'), desk.check('#ext-beispiel')]);
  await desk.waitForSelector('#beispiel-greeting');
  await open(desk, d.base, 'uebernehmen/beispiel');
  check('desktop: no folder before one is picked', (await text(desk, '.folder-picker p')) === 'Noch kein Ordner gewählt.', await text(desk, '.folder-picker p'));
  await desk.click('.folder-picker button:text("Ordner wählen …")');
  await desk.waitForFunction(() => document.querySelector('#beispiel-file option')?.value === '/home/lokal/Quellen');
  check('desktop: the picked folder is chosen', true);
  await open(desk, d.base, 'plugin/beispiel/start');
  const deskHelp = await desk.$eval('.page-intro a', (a) => a.getAttribute('href')).catch(() => '');
  check('desktop: its help link opens the built-in guide', deskHelp === '#/anleitung/plugin-beispiel-beispiel', deskHelp);
  await open(desk, d.base, 'anleitung/plugin-beispiel-beispiel');
  check('desktop: the built-in guide shows its help page', (await text(desk, '#main h1')) === 'Beispiel-Erweiterung');
  const image = await desk.waitForFunction(() => { const i = document.querySelector('#main img'); return i?.complete && i.naturalWidth > 0 && i.getAttribute('src'); }, null, { timeout: 5000 }).then((x) => x.jsonValue()).catch(() => '');
  check('desktop: the built-in guide shows its image', String(image).endsWith('plugin-beispiel-img/gruss.png'), image);
} finally {
  await browser.close();
  for (const x of servers) x.stop();
}
if (failed) process.exit(1);
console.log('plugin check passed');
