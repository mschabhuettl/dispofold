// Playwright check of the menu Daten (mock API): the menus of planners and viewers, the tabs, old addresses, Einstellungen
// opening on its tab used last; Übernehmen with a dataset file in two steps (Einlesen changes nothing) and the month of
// the data chosen after it; Monate closing and opening a month; Sichern: sections with intros, corrections file of the corrections in effect
// (earlier ones and the changes made in Dispofold), loading a corrections file with its summary, import keeping those changes (preview with conflicts, also from
// the "master data changed" warning), pseudonymised export with a separate key, and viewers refused.
//   npm run build && node scripts/check-data.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchBrowser } from './browser.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const month = '2027-01';
const fixture = JSON.parse(readFileSync(join(root, '..', 'tests', 'fixtures', 'synthetic', 'demo-dataset-2027-01.synthetic.json'), 'utf8'));

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
    const context = await browser.newContext({ locale: 'de-DE', acceptDownloads: true, viewport: { width: 1400, height: 1000 } });
    await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
    const page = await context.newPage();
    page.on('pageerror', (e) => check('no page error', false, e.message));
    page.on('dialog', (d) => d.accept());
    await page.goto(`${base}/login.html`);
    await page.fill('#j_username', user);
    await page.fill('#j_password', 'x');
    await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
    return page;
  };
  const open = async (page) => {
    // reloaded: the same address again would not draw the page anew
    await page.goto(`${base}/#/sichern`);
    await page.reload();
    await page.waitForSelector('#h-anonymize');
  };
  const download = async (page, click) => {
    const [d] = await Promise.all([page.waitForEvent('download', { timeout: 10000 }), click()]);
    return { name: d.suggestedFilename(), text: readFileSync(await d.path(), 'utf8') };
  };
  const api = (page, method, path, body) => page.evaluate(async ([m, p, b]) => {
    const r = await fetch(p, { method: m, headers: { 'Content-Type': 'application/json' }, body: b === undefined ? undefined : JSON.stringify(b) });
    return { status: r.status, body: await r.json().catch(() => null) };
  }, [method, path, body]);

  const page = await login('planung');
  await api(page, 'POST', '/api/import/demo');
  const main = page.locator('#main');

  // ---- the menu: Daten for the monthly work, Einstellungen for the rare things ----
  const go = async (hash, ready = '#main h1') => {
    await page.goto(`${base}/?n=${Math.random()}${hash}`);
    await page.waitForSelector(ready);
  };
  await go('#/plan');
  const menu = await page.$$eval('#nav a', (as) => as.map((a) => a.textContent));
  check('the menu of planners', menu.join('|') === 'Dienstplan|Eingaben|Prüfen|Personen|Daten|Einstellungen|Anleitung', menu.join('|'));
  const tabsOf = async (hash) => {
    await go(hash);
    return (await page.$$eval('.page-tabs a', (as) => as.map((a) => a.textContent))).join('|');
  };
  for (const [hash, expected] of [['#/plan', 'Personen|Funktionen|Zeitachse'], ['#/pruefen', 'Probleme|Fairness'], ['#/person', 'Liste|Bereiche'],
    ['#/uebernehmen', 'Übernehmen|Monate|Sichern'], ['#/regeln', 'Regeln|Stammdaten|Erweiterungen']]) {
    const tabs = await tabsOf(hash);
    check(`the tabs of ${hash}`, tabs === expected, tabs);
  }
  // old addresses lead to the new pages
  await go('#/import', '#main h1');
  check('the old Daten page leads to Übernehmen', page.url().endsWith('#/uebernehmen') && (await page.locator('#main h1').innerText()) === 'Daten übernehmen', page.url());
  // Einstellungen opens on the tab used last, also after a new start
  await go('#/stammdaten');
  await go('#/plan');
  check('Einstellungen opens on the tab used last', (await page.getAttribute('#nav a[data-group="settings"]', 'href')) === '#/stammdaten');
  await page.click('#nav a[data-group="settings"]');
  await page.waitForFunction(() => location.hash === '#/stammdaten');
  await go('#/regeln');
  check('… and again the other one', (await page.getAttribute('#nav a[data-group="settings"]', 'href')) === '#/regeln');

  // ---- Übernehmen: a dataset file in two steps ----
  await go('#/uebernehmen', '#dataset-read-run');
  check('without plugins the source is the file, no choice to make', await page.locator('#take-over-sources').count() === 0);
  check('demo data under Erweitert', await page.locator('#take-over-source details.advanced button', { hasText: 'Demodaten laden' }).count() === 1);
  check('no Übernehmen before Einlesen', await page.isDisabled('#dataset-take') && (await page.locator('#dataset-take-what').innerText()).includes('Erst nach dem Einlesen'));
  const other = structuredClone(fixture);
  other.people = other.people.slice(0, 40);
  await page.setInputFiles('#dataset-file', { name: 'datensatz.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(other)) });
  await page.click('#dataset-read-run');
  await page.waitForSelector('#dataset-read-result');
  check('Einlesen shows what came and changes nothing', (await page.locator('#dataset-read-result').innerText()).includes('40 Personen')
    && (await api(page, 'GET', '/api/dataset')).body.people.length === fixture.people.length);
  check('Übernehmen says what it changes', (await page.locator('#dataset-take-what').innerText()).startsWith('40 Personen, ')
    && (await page.locator('#dataset-take-what').innerText()).includes('Jänner 2027; ersetzt die Daten aus'), await page.locator('#dataset-take-what').innerText());
  // another month chosen in the picker, then the page without a reload (a reload would choose January again)
  const inApril = async (hash, ready) => {
    await go('#/plan', '#month');
    await page.selectOption('#month', '2027-04');
    await page.waitForFunction(() => sessionStorage.getItem('month') === '2027-04');
    await page.evaluate((x) => { location.hash = x; }, hash);
    await page.waitForSelector(ready);
  };
  await inApril('#/uebernehmen', '#dataset-read-run');
  await page.setInputFiles('#dataset-file', { name: 'datensatz.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(other)) });
  await page.click('#dataset-read-run');
  await page.waitForSelector('#dataset-read-result');
  await page.click('#dataset-take');
  await page.waitForSelector('#import-check-areas');
  check('Übernehmen imports, offers "Bereiche prüfen"', (await api(page, 'GET', '/api/dataset')).body.people.length === 40);
  check('the first month of the data is chosen, the result stays', (await page.evaluate(() => sessionStorage.getItem('month'))) === month
    && (await page.locator('#import-month').innerText()).includes('Jänner 2027'));
  await page.setInputFiles('#dataset-file', { name: 'x.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(fixture)) });
  check('another file: Einlesen again first', await page.isDisabled('#dataset-take'));
  // on Sichern (a page of a month) another month is not switched to under the result: the switch is offered
  await inApril('#/sichern', '#dataset-read-run');
  await page.setInputFiles('#dataset-file', { name: 'datensatz.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(other)) });
  await page.click('#dataset-read-run');
  await page.waitForSelector('#dataset-read-result');
  await page.click('#dataset-take');
  await page.waitForSelector('#import-check-areas');
  const offer = main.getByRole('button', { name: 'Zu Jänner 2027 wechseln' });
  check('Sichern: the result stays, the switch to the month is offered', await offer.count() === 1 && (await page.evaluate(() => sessionStorage.getItem('month'))) === '2027-04',
    await page.evaluate(() => sessionStorage.getItem('month')));
  await offer.click();
  await page.waitForFunction((m) => sessionStorage.getItem('month') === m, month);
  await api(page, 'POST', '/api/import/demo?replace=true');

  // ---- Monate: open and closed months, closed and opened again here ----
  await go('#/monate', '#months-table');
  check('Monate lists the month, open', (await page.locator(`#months-table tr[data-month="${month}"]`).innerText()).includes('offen'));
  await page.click(`[data-close="${month}"]`);
  await page.waitForSelector('dialog[open]');
  if (await page.locator('#close-anyway').count()) await page.check('#close-anyway');
  await page.locator('dialog[open] button.btn-primary').click();
  await page.waitForSelector(`[data-reopen="${month}"]`);
  check('closed on Monate', (await page.locator(`#months-table tr[data-month="${month}"]`).innerText()).includes('abgeschlossen'));
  await page.click(`[data-reopen="${month}"]`);
  await page.waitForSelector(`[data-close="${month}"]`);
  check('opened again on Monate', (await page.locator(`#months-table tr[data-month="${month}"]`).innerText()).includes('offen'));

  // ---- Sichern: sections, each with a short intro ----
  await open(page);
  const headings = await main.locator('h2').allInnerTexts();
  check('sections Import, Export, Korrekturdatei, Pseudonymisieren', ['Import', 'Export', 'Korrekturdatei', 'Pseudonymisieren'].every((x) => headings.includes(x)), headings.join(' | '));
  for (const id of ['h-import', 'h-export', 'h-corrections', 'h-anonymize']) {
    check(`intro in ${id}`, await page.locator(`section[aria-labelledby=${id}] > p.muted`).count() >= 1);
  }
  check('keep option off without changes', !(await page.locator('#dataset-keep').isChecked()));

  // loading a corrections file: refused with its reason, else a summary to confirm, the file sent as it is
  const pick = (text) => page.setInputFiles('#corrections-file', { name: 'korrekturen.json', mimeType: 'application/json', buffer: Buffer.from(text) });
  await pick('{"format": "other", "version": 2, "people": {}}');
  await main.getByRole('button', { name: 'Korrekturdatei laden', exact: true }).click();
  await main.getByText('keine Korrekturdatei (erwartet wird').waitFor();
  check('a file of another format is refused with the reason', true);
  const loadText = `{"format": "dispofold/corrections", "version": 2, "people": {"${fixture.people[0].id}": {"weeklyHours": 30.0, "note": "Jänner"}, "E999999": {"exclude": true}}}`;
  await pick(loadText);
  await main.getByRole('button', { name: 'Korrekturdatei laden', exact: true }).click();
  await page.locator('#corrections-confirm').waitFor();
  const confirmText = await page.locator('#corrections-confirm').innerText();
  check('summary before loading', confirmText.includes('2 Personen in der Datei') && confirmText.includes('Davon in den aktuellen Daten: 1')
    && confirmText.includes('Wochenstunden: 1') && confirmText.includes('nicht in den aktuellen Daten'), confirmText.replace(/\s+/g, ' '));
  check('nothing loaded before confirming', (await api(page, 'GET', '/api/data/corrections/state')).body.loaded === null);
  const sent = page.waitForRequest((r) => r.url().endsWith('/api/data/corrections/loaded') && r.method() === 'PUT');
  await page.locator('#corrections-confirm').getByRole('button', { name: 'Laden', exact: true }).click();
  check('the file is sent byte for byte', (await sent).postDataBuffer()?.toString('utf8') === loadText);
  await page.locator('#corrections-loaded').waitFor();
  check('the loaded file waits for the import', (await page.locator('#corrections-loaded').innerText()).includes('wartet auf den nächsten Import'));
  await pick(loadText);
  await main.getByRole('button', { name: 'Korrekturdatei laden', exact: true }).click();
  await page.locator('#corrections-confirm').waitFor();
  check('loading again asks to replace', await page.locator('#corrections-confirm').getByRole('button', { name: 'Laden und die geladene Datei ersetzen' }).count() === 1);
  await page.locator('#corrections-confirm').getByRole('button', { name: 'Abbrechen' }).click();
  await main.getByRole('button', { name: 'Geladene Datei entfernen' }).click();
  await page.locator('#corrections-loaded').waitFor({ state: 'detached' });
  check('removed', (await api(page, 'GET', '/api/data/corrections/state')).body.loaded === null);

  // no changes: no file
  await main.getByRole('button', { name: 'Änderungen als Korrekturdatei exportieren' }).click();
  await page.getByText('keine Änderung für die Korrekturdatei').waitFor();
  check('no corrections file without changes', true);

  // a change "in Dispofold": the corrections file has it
  const p1 = fixture.people[0];
  await api(page, 'PUT', `/api/dataset/people/${p1.id}`, { ...p1, weeklyHours: 30 });
  await open(page);
  check('keep option on after a change', await page.locator('#dataset-keep').isChecked());
  const corrections = await download(page, () => main.getByRole('button', { name: 'Änderungen als Korrekturdatei exportieren' }).click());
  const file = JSON.parse(corrections.text);
  check('corrections file name', corrections.name === 'korrekturen.json', corrections.name);
  check('corrections file content', file.format === 'dispofold/corrections' && file.version === 2 && file.people[p1.id]?.weeklyHours === 30,
    JSON.stringify(file.people));
  check('corrections file without names', !corrections.text.includes(p1.lastName) && !corrections.text.includes(p1.firstName));
  check('import without the applied corrections: the note says only the changes since', await main.getByText('nennt die schon angewendeten Korrekturen nicht').count() === 1);

  // import keeping the change: preview with the conflict, then import
  const upload = structuredClone(fixture);
  upload.people[0].weeklyHours = 35;
  upload.people[1].weeklyHours = 20;
  const setUpload = () => page.setInputFiles('#dataset-file', { name: 'datensatz.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(upload)) });
  const take = async () => {
    await page.click('#dataset-read-run');
    await page.waitForSelector('#dataset-read-result');
    await page.click('#dataset-take');
  };
  await setUpload();
  await take();
  const preview = page.locator('#import-preview');
  await preview.waitFor();
  const previewText = await preview.innerText();
  check('preview names the conflict by short name and field', previewText.includes(`${p1.shortName} (${p1.id}): Wochenstunden`), previewText.split('\n').find((l) => l.includes(p1.id)));
  check('preview counts the kept change', previewText.includes('Übernommen aus Dispofold: 1 Änderung'));
  const unchanged = await api(page, 'GET', '/api/dataset');
  check('preview writes nothing', unchanged.body.people[1].weeklyHours === fixture.people[1].weeklyHours && unchanged.body.people[0].weeklyHours === 30);
  await preview.getByRole('button', { name: 'Jetzt importieren' }).click();
  await main.getByText('1 Änderung aus Dispofold übernommen').waitFor();
  const after = (await api(page, 'GET', '/api/dataset')).body;
  check('Dispofold value wins, the rest from the import', after.people[0].weeklyHours === 30 && after.people[1].weeklyHours === 20 && after.changedSinceImport === true,
    `${after.people[0].weeklyHours} ${after.people[1].weeklyHours} ${after.changedSinceImport}`);

  // without the option: the warning about changed master data offers to keep them
  await open(page);
  await page.locator('#dataset-keep').uncheck();
  await setUpload();
  await take();
  await main.getByText('Stammdaten wurden geändert.').waitFor();
  await main.getByRole('button', { name: 'Änderungen behalten' }).click();
  await preview.waitFor();
  check('warning leads to the preview', (await preview.innerText()).includes('Übernommen aus Dispofold'));
  await preview.getByRole('button', { name: 'Abbrechen' }).click();

  // pseudonymised export: dataset and plan to share, the key separately
  await main.getByRole('button', { name: 'Pseudonymisierte Kopie erstellen' }).click();
  await main.getByText('pseudonymisiert. Datensatz und Plan').waitFor();
  const shared = await download(page, () => main.getByRole('button', { name: 'Datensatz speichern' }).click());
  const plan = await download(page, () => main.getByRole('button', { name: 'Plan speichern' }).click());
  const key = await download(page, () => main.getByRole('button', { name: 'Schlüssel speichern' }).click());
  check('anonymised file names', shared.name === 'dispofold-datensatz-anonym.json' && plan.name === `dienstplan-${month}-anonym.json` && key.name === 'schluessel.csv',
    `${shared.name} ${plan.name} ${key.name}`);
  const anon = JSON.parse(shared.text);
  check('people pseudonymised', anon.people[0].id === 'P001' && anon.people.every((p) => p.lastName === p.id), anon.people[0].id);
  check('no names or ids in the shared files', !fixture.people.some((p) => shared.text.includes(`"${p.lastName}"`) || shared.text.includes(`"${p.id}"`) || plan.text.includes(`"${p.id}"`)));
  check('key separate, with the names', key.text.replace(/^﻿/, '').startsWith('pseudonym;id;externe_id;nachname;vorname;kuerzel') && key.text.includes(p1.lastName)
    && !shared.text.includes('pseudonym;id'));

  // an import that lists its applied corrections: they stay in the file, counted apart from the new changes
  const listed = structuredClone(fixture);
  listed.people[1].weeklyHours = 25;
  listed.appliedCorrections = { format: 'dispofold/corrections', version: 2, people: { [fixture.people[1].id]: { weeklyHours: 25 } }, demand: {} };
  check('import with the applied corrections', (await api(page, 'POST', '/api/import/dataset?replace=true', listed)).status === 200);
  await api(page, 'PUT', `/api/dataset/people/${p1.id}`, { ...p1, weeklyHours: 31 });
  await open(page);
  const complete = await download(page, () => main.getByRole('button', { name: 'Änderungen als Korrekturdatei exportieren' }).click());
  const all = JSON.parse(complete.text);
  check('earlier and new corrections in the file', all.people[fixture.people[1].id]?.weeklyHours === 25 && all.people[p1.id]?.weeklyHours === 31, JSON.stringify(all.people));
  check('counted apart', await main.getByText('Aus früheren Korrekturen: 1, seit dem letzten Import geändert: 1.').count() === 1);
  check('no note with the applied corrections', await main.getByText('nennt die schon angewendeten Korrekturen nicht').count() === 0);

  // a person excluded and taken back again since the import: nothing left, but a file without entries that replaces
  // one saved in between
  const none = structuredClone(fixture);
  none.appliedCorrections = { format: 'dispofold/corrections', version: 2, people: {}, demand: {} };
  check('import with no applied corrections', (await api(page, 'POST', '/api/import/dataset?replace=true', none)).status === 200);
  const p2 = fixture.people[2];
  await api(page, 'PUT', `/api/dataset/people/${p2.id}/excluded`, true);
  await open(page);
  const excluding = JSON.parse((await download(page, () => main.getByRole('button', { name: 'Änderungen als Korrekturdatei exportieren' }).click())).text);
  check('exclusion in the file', JSON.stringify(excluding.people) === JSON.stringify({ [p2.id]: { exclude: true } }), JSON.stringify(excluding.people));
  await api(page, 'PUT', `/api/dataset/people/${p2.id}/excluded`, false);
  await open(page);
  const empty = JSON.parse((await download(page, () => main.getByRole('button', { name: 'Änderungen als Korrekturdatei exportieren' }).click())).text);
  check('taken back: a file without entries replaces it', Object.keys(empty.people).length === 0
    && await main.getByText('Korrekturdatei ohne Einträge gespeichert').count() === 1, JSON.stringify(empty.people));

  // viewers: refused by the server, no menu Daten, its pages lead to the first page
  const viewer = await login('ansicht');
  const viewerMenu = await viewer.$$eval('#nav a', (as) => as.map((a) => a.textContent));
  check('the menu of viewers', !viewerMenu.includes('Daten') && !viewerMenu.includes('Einstellungen'), viewerMenu.join('|'));
  for (const hash of ['#/uebernehmen', '#/monate', '#/sichern', '#/import']) {
    await viewer.goto(`${base}/?v=${Math.random()}${hash}`);
    await viewer.waitForSelector('#main h1');
    check(`viewers: ${hash} leads to the first page`, viewer.url().endsWith('#/plan'), viewer.url());
  }
  const refused = await Promise.all([api(viewer, 'GET', '/api/data/corrections'), api(viewer, 'GET', `/api/data/anonymized?month=${month}`),
    api(viewer, 'POST', '/api/import/preview', upload), api(viewer, 'POST', '/api/import/dataset?keep=true', upload)]);
  check('viewer refused', refused.every((r) => r.status === 403), refused.map((r) => r.status).join(' '));
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('Daten checked');
