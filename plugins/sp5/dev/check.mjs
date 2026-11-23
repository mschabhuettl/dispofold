// Browser check of the plugin's pages on the dev server with dev/mock.mjs (run by frontend/scripts/check-plugin-pages.mjs):
// the empty state before a run, missing settings and a bad option, a run and its summary, the report (sections, filters,
// search, sorting by mouse and keyboard, person links, the CSV), the mapping editor (an invalid rule, a rule that
// matches nothing, moving and removing rows with the keyboard, saving, JSON in and out, unknown keys, leaving with
// unsaved changes), the comparison (no import yet, with and without the corrections, an uploaded file), the import from
// Lauf und Import (also keeping changes, a closed month), no import section on Daten, page titles as their tabs, month
// fields wide enough, readers without the group, and the light and dark theme.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { MAPPING } from './mock.mjs';

const MONTH = '2027-01';
const P = '#/plugin/sp5';
let loads = 0;

export default async function run({ base, browser, check, shots }) {
  const session = async (user) => {
    const context = await browser.newContext({ locale: 'de-DE', acceptDownloads: true, viewport: { width: 1400, height: 1000 } });
    await context.addInitScript((m) => sessionStorage.setItem('month', m), MONTH);
    const page = await context.newPage();
    page.on('pageerror', (e) => check('no page error', false, e.message));
    page.on('dialog', (d) => d.accept());
    await page.goto(`${base}/login.html`);
    await page.fill('#j_username', user);
    await page.fill('#j_password', 'x');
    await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
    return page;
  };
  const api = (page, method, path, body) => page.evaluate(async ([m, p, b]) => {
    const r = await fetch(p, { method: m, headers: { 'Content-Type': 'application/json' }, body: b === undefined ? undefined : JSON.stringify(b) });
    return { status: r.status, body: await r.json().catch(() => null) };
  }, [method, path, body]);
  const open = async (page, hash, ready) => {
    // a fresh load each time (a new query): a change of the hash alone would not draw the page anew
    await page.goto(`${base}/?load=${++loads}${hash}`);
    await page.waitForSelector(ready).catch(async (e) => {
      throw new Error(`${hash}: ${ready} not shown; the page: ${await page.locator('#main').innerText().catch(() => '?')} ${page.url()}\n${e.message}`);
    });
    await page.waitForTimeout(200);
    const stray = /\b(null|undefined|NaN)\b|\[object /.exec(await page.locator('#main').innerText());
    check(`${hash}: no stray value on the page`, !stray, stray?.[0]);
  };
  const text = (page, sel) => page.locator(sel).first().innerText().catch(() => null);
  const download = async (page, click) => {
    const [d] = await Promise.all([page.waitForEvent('download', { timeout: 10000 }), click()]);
    return { name: d.suggestedFilename(), text: readFileSync(await d.path(), 'utf8') };
  };
  const shot = async (page, name) => { if (shots) await page.screenshot({ path: join(shots, `${name}.png`), fullPage: true }); };

  const page = await session('planung');
  await api(page, 'PUT', '/api/extensions/sp5', { enabled: true });
  await open(page, `${P}/uebersicht`, '#h-sp5-run');
  const menu = await page.$$eval('#nav a', (as) => as.map((a) => a.textContent));
  check('own menu group', menu.includes('Schichtplaner 5'), menu.join(', '));
  const tabs = await page.$$eval('.page-tabs a', (as) => as.map((a) => a.textContent));
  check('four pages as tabs', tabs.join('|') === 'Lauf und Import|Bericht|Zuordnung|Vergleich', tabs.join('|'));
  check('intro with help link', (await page.locator('.page-intro a').count()) === 1);
  for (const path of ['uebersicht', 'bericht', 'zuordnung', 'lauf-vergleich']) {
    await open(page, `${P}/${path}`, '#main h1');
    const [title, tab] = [await text(page, '#main h1'), await text(page, '.page-tabs a[aria-current="page"]')];
    check(`${path}: the page title is the name of its tab`, title === tab, `${title} / ${tab}`);
  }
  await open(page, `${P}/uebersicht`, '#h-sp5-run');
  const widths = await page.$$eval('#sp5-run-month, #sp5-run-to', (xs) => xs.map((x) => {
    const free = document.createElement('input');
    free.type = 'month';
    document.body.append(free);
    const w = free.offsetWidth;
    free.remove();
    return x.offsetWidth >= w;
  }));
  check('the month fields are not cut off (at least as wide as a month field on its own)', widths.length === 2 && widths.every(Boolean));
  await open(page, '#/import', '#main h1');
  await page.waitForTimeout(300);
  check('one import place: no import section of the plugin on Daten', (await page.locator('#h-plugin-sp5').count()) === 0);
  await open(page, `${P}/uebersicht`, '#h-sp5-run');

  // ---- before the first run ----
  check('Übersicht: empty state without a run', (await text(page, '#sp5-no-run'))?.includes('Noch kein Lauf'), await text(page, '#sp5-summary'));
  check('Übersicht: no import without a run', (await text(page, '#h-sp5-import + div'))?.includes('Erst ausführen'));
  await open(page, `${P}/bericht`, '#main h1');
  await page.waitForSelector('#sp5-no-run');
  check('Bericht: empty state links to Übersicht', (await page.getAttribute('#sp5-no-run a', 'href')) === `${P}/uebersicht`);
  await open(page, `${P}/lauf-vergleich`, '#sp5-compare');
  await page.waitForSelector('#sp5-compare #sp5-no-run');
  check('Vergleich: empty state without a run', true);

  // ---- missing mapping, bad option ----
  await api(page, 'PUT', '/api/plugins/sp5/einstellungen', { root: '/srv/dispofold/quellen', path: '2027', mapping: null });
  await open(page, `${P}/uebersicht`, '#h-sp5-run');
  check('a missing mapping shows with a link to the page Zuordnung', (await text(page, '#main .alert-error'))?.includes('Die Zuordnung fehlt')
    && (await page.getAttribute('#main .alert-error a', 'href')) === `${P}/zuordnung`);
  await page.click('#sp5-run');
  await page.waitForSelector('#sp5-summary .alert-error');
  check('a run without mapping is refused', (await text(page, '#sp5-summary'))?.includes('Die Zuordnung fehlt'));
  await page.click('#main .alert-error a');
  await page.waitForSelector('#sp5-codes-summary, #sp5-propose');
  check('"Zur Zuordnung" opens the tab Zuordnung', page.url().endsWith(`${P}/zuordnung`)
    && (await text(page, '.page-tabs a[aria-current="page"]')) === 'Zuordnung', page.url());
  await api(page, 'PUT', '/api/plugins/sp5/einstellungen', { root: '/srv/dispofold/quellen', path: '2027', mapping: MAPPING });
  await open(page, `${P}/uebersicht`, '#h-sp5-run');
  check('options prefilled from the settings', (await page.inputValue('#sp5-run-historyMonths')) === '12');
  await page.click('details.advanced summary');
  await page.fill('#sp5-run-historyMonths', '30');
  await page.click('#sp5-run');
  await page.waitForSelector('#sp5-summary .alert-error');
  check('a bad option is refused with its name on the page', (await text(page, '#sp5-summary'))?.includes('ungültig: Monate Historie (0 bis 24)'), await text(page, '#sp5-summary'));
  await page.fill('#sp5-run-historyMonths', '6');
  await page.click('#sp5-run-default');
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent.includes('als Standard gespeichert'));
  const saved = await api(page, 'GET', '/api/plugins/sp5/einstellungen');
  check('"Als Standard speichern" keeps the rest of the settings', saved.body.options.historyMonths === 6 && saved.body.root === '/srv/dispofold/quellen'
    && saved.body.mapping?.areas?.length === 2);

  // ---- a run, started with the keyboard ----
  await page.fill('#sp5-run-month', MONTH);
  await page.focus('#sp5-run');
  await page.keyboard.press('Enter');
  await page.waitForSelector('#sp5-run-summary');
  check('the run shows its counts', (await text(page, '[data-count="people"] .value')) === '150' && (await text(page, '[data-count="corrections"] .value')) === '5');
  check('the run names its months and the corrections', (await text(page, '#sp5-run-summary'))?.includes('Jänner 2027')
    && (await text(page, '#sp5-run-summary'))?.includes('Korrekturdatei wurde angewendet'), await text(page, '#sp5-run-summary'));
  await page.waitForSelector(`#sp5-run-summary a[href="${P}/zuordnung"]`);
  check('a link to the mapping for codes without a rule', (await text(page, `#sp5-run-summary a[href="${P}/zuordnung"]`))?.includes('2 Kürzel ohne Regel'));
  await page.waitForSelector('#sp5-run-proposals');
  check('the number of new proposals with a link', (await text(page, '#sp5-run-proposals')) === 'Zuordnung: 2 neue Vorschläge');
  const lauf = await api(page, 'GET', '/api/plugins/sp5/lauf');
  check('the run used the options of the page', lauf.body.request.options.historyMonths === 6, JSON.stringify(lauf.body.request));
  check('the import names the run', (await text(page, '#sp5-import-request'))?.includes('Jänner 2027'));
  const zip = await download(page, () => page.click('#sp5-run-result'));
  check('"Ergebnis speichern" downloads the ZIP', zip.name === 'dispofold-sp5-ergebnis-2027-01.zip', zip.name);
  await shot(page, 'sp5-uebersicht');

  // ---- Vergleich before any import ----
  await open(page, `${P}/lauf-vergleich`, '#sp5-compare');
  await page.waitForSelector('#sp5-no-baseline');
  check('Vergleich: nothing imported yet, the upload is offered', (await text(page, '#sp5-no-baseline'))?.includes('Noch nichts importiert'));

  // ---- the import from Übersicht, as on Daten ----
  await open(page, `${P}/uebersicht`, '#sp5-import-request');
  check('keep changes is off without changes', !(await page.isChecked('#plugin-keep-sp5')));
  await page.waitForSelector('#h-sp5-import ~ div .corrections-hint .alert');
  check('without any corrections the import box says so, with the way to load a file',
    (await text(page, '#h-sp5-import ~ div .corrections-hint'))?.includes('keine Korrekturen')
    && (await page.getAttribute('#h-sp5-import ~ div .corrections-hint a', 'href')) === '#/import');
  await page.click('#h-sp5-import ~ div button.btn-primary');
  await page.waitForSelector('#h-sp5-import ~ div .alert:not(.corrections-hint > .alert) strong');
  check('the import of the run shows the summary', (await text(page, '#h-sp5-import ~ div .alert:not(.corrections-hint > .alert)'))?.includes('Import aus'), await text(page, '#h-sp5-import ~ div'));
  const p1 = (await api(page, 'GET', '/api/dataset')).body.people[0];
  await api(page, 'PUT', `/api/dataset/people/${p1.id}`, { ...p1, weeklyHours: 30 });
  const state = page.waitForResponse((r) => r.url().endsWith('/api/data/corrections/state'));
  await open(page, `${P}/uebersicht`, '#sp5-import-request');
  await page.waitForFunction(() => document.querySelector('#plugin-keep-sp5')?.checked);
  check('keep changes is on after a change in Dispofold', true);
  const answered = await (await state).json();
  await page.waitForTimeout(200);
  check('with a change in Dispofold there is no hint', answered.none === false && await page.locator('.corrections-hint .alert').count() === 0);
  await page.click('#h-sp5-import ~ div button.btn-primary');
  await page.waitForSelector('#import-preview');
  check('the preview comes first', (await text(page, '#import-preview'))?.includes('Vorschau'));
  await page.click('#import-preview button.btn-primary');
  await page.waitForFunction(() => document.querySelector('#h-sp5-import ~ div .alert:not(.corrections-hint > .alert)')?.textContent.includes('aus Dispofold übernommen'));
  check('the import keeps the changes', true);
  // a closed month of the run: the note with the next step before the import
  await api(page, 'POST', `/api/plans/${MONTH}/finalize`);
  await open(page, `${P}/uebersicht`, '#sp5-import-request');
  await page.waitForSelector('#sp5-import-closed .alert');
  check('a closed month of the run: the note says the next step', (await text(page, '#sp5-import-closed'))?.includes('Abgeschlossen: Jänner 2027')
    && (await text(page, '#sp5-import-closed'))?.includes('Monat wieder öffnen')
    && (await page.getAttribute('#sp5-import-closed a', 'href')) === '#/import', await text(page, '#sp5-import-closed'));
  await api(page, 'POST', `/api/plans/${MONTH}/reopen`);
  await open(page, `${P}/uebersicht`, '#sp5-import-request');
  await page.waitForTimeout(300);
  check('an open month: no note', (await page.locator('#sp5-import-closed .alert').count()) === 0);

  // ---- Bericht ----
  await open(page, `${P}/bericht`, '#sp5-report-count');
  const types = await page.$$eval('#main section[data-type]', (s) => s.map((x) => x.dataset.type));
  check('one section per row type, unknown ones too', types.join(',') === 'bedarf,bereich,schicht,person,nicht_zugeordnet,ignoriert,verworfen,wochenstunden,korrektur,ausgeblendet,nicht_mehr,sonderfall', types.join(','));
  const korrHeads = await page.$$eval('#sp5-type-korrektur th', (ths) => ths.map((th) => [th.textContent.replace(/[▲▼]/g, '').trim(), th.querySelector('button')?.title]));
  check('a type shows only its columns, in German with the raw name as title', korrHeads.map((x) => x[0]).join('|') === 'Person|Feld|Status|Grund|Wert aus dem Export|Wert der Korrektur'
    && korrHeads[0][1] === 'person', JSON.stringify(korrHeads));
  check('the unknown type shows its filled columns', (await page.$$eval('#sp5-type-sonderfall th', (ths) => ths.map((th) => th.textContent.trim()))).join('|') === 'Schicht|Grund');
  check('invalid corrections and missing people are highlighted', (await page.locator('#sp5-type-korrektur .badge-hard').count()) === 2);
  check('a person in Dispofold links to the person page', (await page.locator('#sp5-type-bereich a[href="#/person/P0001"]').count()) === 1
    && (await page.locator('#sp5-type-bereich a[href="#/person/E1001"]').count()) === 0);
  check('day types in German', (await page.$$eval('#sp5-type-bedarf tbody tr td:nth-child(2)', (tds) => tds.map((x) => x.textContent))).join(',') === 'Mo,Sa,Feiertag');
  check('the explanation of a type', (await text(page, '#sp5-type-wochenstunden p.muted'))?.includes('HRSWEEK weicht'));
  check('the count of all rows', (await text(page, '#sp5-report-count')) === '26 Zeilen.', await text(page, '#sp5-report-count'));
  check('the warnings with their count', (await page.locator('#sp5-warnings li').count()) === 6 && (await text(page, '#h-sp5-warnings'))?.includes('(6)'));
  // sorting: the bedarf rows by duties, by mouse, then the other way with the keyboard
  const duties = () => page.$$eval('#sp5-type-bedarf tbody tr td:nth-child(3)', (tds) => tds.map((x) => x.textContent).join(','));
  await page.click('#sp5-type-bedarf th button:text("Dienste (Historie)")');
  check('sorting by a header, numbers as numbers', (await duties()) === '9,40,120', await duties());
  await page.keyboard.press('Enter');
  check('the header keeps the focus and sorts the other way with the keyboard', (await duties()) === '120,40,9'
    && (await page.evaluate(() => document.activeElement?.closest('th')?.getAttribute('aria-sort'))) === 'descending', await duties());
  const dayTypes = () => page.$$eval('#sp5-type-bedarf tbody tr td:nth-child(2)', (tds) => tds.map((x) => x.textContent).join(','));
  await page.click('#sp5-type-bedarf th button:text("Tagestyp")');
  await page.click('#sp5-type-bedarf th button:text("Tagestyp")');
  check('day types sort in week order, not by their codes', (await dayTypes()) === 'Feiertag,Sa,Mo', await dayTypes());
  // search over all columns, several words, also German values
  await page.fill('#sp5-report-search', 'ungültig');
  check('the search finds German values', (await page.locator('#main section[data-type]:not([hidden])').count()) === 1
    && (await page.locator('#sp5-type-korrektur tbody tr').count()) === 1);
  await page.fill('#sp5-report-search', 'a1 p0001');
  const shown = await page.$$eval('#main section[data-type]:not([hidden])', (s) => s.map((x) => x.dataset.type).join(','));
  check('several words must all match', shown === 'schicht' && (await page.locator('#sp5-type-schicht tbody tr').count()) === 1, shown);
  await page.fill('#sp5-report-search', 'früh');
  check('the search filters the warnings too', (await page.locator('#sp5-warnings li').count()) === 1 && (await text(page, '#sp5-warnings')) === 'Keine Treffer.');
  await page.fill('#sp5-report-search', '');
  await page.selectOption('#sp5-report-type', 'korrektur');
  check('the filter by row type', (await page.locator('#main section[data-type]:not([hidden])').count()) === 1);
  await page.selectOption('#sp5-report-type', '');
  await page.selectOption('#sp5-report-person', 'P0002');
  check('the filter by person', (await page.locator('#main section[data-type]:not([hidden]) tbody tr').count()) === 5, String(await page.locator('#main section[data-type]:not([hidden]) tbody tr').count()));
  await page.selectOption('#sp5-report-person', '');
  await page.selectOption('#sp5-report-area', 'DR');
  check('the filter by area', (await text(page, '#sp5-report-count')) === '6 von 26 Zeilen.', await text(page, '#sp5-report-count'));
  check('the area filter keeps the warnings naming the area', (await text(page, '#sp5-warnings')) === 'Keine Treffer.', await text(page, '#sp5-warnings'));
  await page.selectOption('#sp5-report-area', 'AN');
  check('two warnings name the area AN', (await page.locator('#sp5-warnings li').count()) === 2, await text(page, '#sp5-warnings'));
  await page.selectOption('#sp5-report-area', '');
  await page.selectOption('#sp5-warning-kind', 'Bedarf');
  check('the warnings by kind', (await page.locator('#sp5-warnings li').count()) === 1);
  const csv = await download(page, () => page.click('#sp5-report-csv'));
  check('"Bericht als CSV" downloads the report', csv.name === 'dispofold-sp5-bericht-2027-01.csv' && csv.text.startsWith('pruefung;person'), csv.name);
  await shot(page, 'sp5-bericht');

  // ---- Zuordnung ----
  await open(page, `${P}/zuordnung`, '#sp5-codes-summary');
  check('the mapping checks without problems', (await text(page, '#sp5-map-problems')) === 'Keine Probleme.');
  check('the codes without a rule', (await text(page, '#sp5-codes-summary'))?.includes('Schichten T1') && (await text(page, '#sp5-codes-summary'))?.includes('Gruppen Projekt'));
  check('what a rule matches', (await text(page, '[data-hits="shifts:0"]')) === 'A1, A2' && (await text(page, '[data-hits="ignoreShifts:0"]')) === 'X1'
    && (await text(page, '[data-hits="ignoreShifts:1"]')) === 'trifft keine bekannte Schicht');
  check('the folder tables with the result of each code', (await text(page, '#sp5-codes table'))?.includes('Regel 2 → DR, Tag')
    && (await page.locator('#sp5-codes .badge-warn', { hasText: 'keine Regel' }).count()) === 2, `${await text(page, '#sp5-codes table')} ${await page.locator('#sp5-codes .badge-warn').count()}`);
  check('nothing unsaved yet', await page.isHidden('#sp5-map-dirty'));
  // a rule that matches nothing, then an invalid one
  await page.click('button:text("Schichtregel hinzufügen")');
  check('a new rule gets the focus', (await page.evaluate(() => document.activeElement?.dataset.key)) === 'shifts.3.match');
  await page.keyboard.type('ZZ*');
  await page.selectOption('[data-key="shifts.3.areaId"]', 'AN');
  await page.waitForFunction(() => document.querySelector('[data-hits="shifts:3"]')?.textContent === 'trifft nichts');
  check('a rule that matches nothing says so', true);
  check('unsaved changes are marked', await page.isVisible('#sp5-map-dirty'));
  await page.fill('[data-key="shifts.2.match"]', 're:(');
  await page.waitForFunction(() => document.querySelector('#sp5-map-problems')?.textContent.includes('regulärer Ausdruck'));
  check('an invalid rule shows the message in plain words', (await text(page, '#sp5-map-problems'))?.includes('Schichtregel 3: Das Muster „re:(“ ist kein gültiger regulärer Ausdruck'), await text(page, '#sp5-map-problems'));
  await page.click('#sp5-map-save');
  await page.waitForSelector('#toast:not([hidden])');
  check('the server refuses to save an invalid mapping', (await text(page, '#toast'))?.includes('regulärer Ausdruck'), await text(page, '#toast'));
  await page.fill('[data-key="shifts.2.match"]', 're:^N[0-9]$');
  // keyboard: move the last rule up, then remove it
  await page.focus('[data-key="shifts.3.up"]');
  await page.keyboard.press('Enter');
  check('moved up with the keyboard, the focus follows', (await page.evaluate(() => document.activeElement?.dataset.key)) === 'shifts.2.up'
    && (await page.inputValue('[data-key="shifts.2.match"]')) === 'ZZ*', await page.evaluate(() => document.activeElement?.dataset.key));
  check('the up button names its row', (await page.getAttribute('[data-key="shifts.2.up"]', 'aria-label')) === 'Schichtregel 3 nach oben');
  await page.keyboard.press('Tab');
  await page.keyboard.press('Tab');
  check('Tab reaches the remove button', (await page.evaluate(() => document.activeElement?.dataset.key)) === 'shifts.2.remove');
  await page.keyboard.press('Enter');
  check('removed with the keyboard', (await page.locator('[data-section="shifts"] tbody tr').count()) === 3
    && (await page.inputValue('[data-key="shifts.2.match"]')) === 're:^N[0-9]$');
  // a group gets an area by its checkbox, in area order
  await page.check('[data-key="groups.1.area.AN"]');
  await page.click('#sp5-map-save');
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent.includes('Zuordnung gespeichert'));
  const stored = (await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body;
  check('saved: the mapping, the other settings unchanged', JSON.stringify(stored.mapping.groups[1].areaIds) === '["AN","DR"]'
    && stored.mapping.shifts.length === 3 && stored.options.historyMonths === 6 && stored.root === '/srv/dispofold/quellen', JSON.stringify(stored.mapping.groups));
  check('saved: no longer marked', await page.isHidden('#sp5-map-dirty'));
  // JSON: download, unknown keys and an entry that is no object through the JSON view, upload
  const json = await download(page, () => page.click('#sp5-map-download'));
  check('mapping.json with two spaces and a final newline', json.name === 'mapping.json' && json.text.endsWith('}\n') && json.text.includes('\n  "areas": ['));
  await page.click('summary:text("JSON anzeigen und bearbeiten")');
  await page.fill('#sp5-map-json', JSON.stringify({ ...MAPPING, areas: [...MAPPING.areas, 7], extra: { a: 1 } }));
  await page.click('#sp5-map-apply');
  await page.waitForSelector('#sp5-map-unknown');
  check('unknown keys are shown with the note', (await text(page, '#sp5-map-unknown'))?.includes('extra') && (await text(page, '[data-section="areas"]'))?.includes('ungültig'));
  await page.focus('[data-key="areas.2.remove"]');
  await page.keyboard.press('Enter');
  check('an entry that is no object can be removed', !(await text(page, '[data-section="areas"]'))?.includes('ungültig'));
  await page.fill('#sp5-map-json', JSON.stringify({ ...MAPPING, areas: [] }));
  await page.click('#sp5-map-apply');
  await page.waitForFunction(() => document.querySelector('#sp5-map-problems')?.textContent.includes('Bereich'));
  check('no area: said in plain words', (await text(page, '#sp5-map-problems'))?.includes('Es ist noch kein Bereich eingetragen.')
    && !(await text(page, '#sp5-map-problems'))?.includes("'areas'"), await text(page, '#sp5-map-problems'));
  await page.fill('#sp5-map-json', '{ kaputt');
  await page.click('#sp5-map-apply');
  await page.waitForSelector('#toast:not([hidden])');
  check('invalid JSON is refused', (await text(page, '#toast'))?.includes('kein gültiges JSON'));
  await page.setInputFiles('#sp5-map-file', { name: 'mapping.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ ...MAPPING, ignoreShifts: ['T*'] })) });
  await page.waitForFunction(() => document.querySelector('[data-key="ignoreShifts.0.pattern"]')?.value === 'T*');
  check('an uploaded file replaces the editor after the confirmation', (await page.locator('[data-section="ignoreShifts"] tbody tr').count()) === 1);
  // the newer edit is saved: the editor changed after unapplied JSON, then JSON typed after the editor
  await page.fill('#sp5-map-json', JSON.stringify({ ...MAPPING, ignoreShifts: ['J*'] }));
  await page.fill('[data-key="ignoreShifts.0.pattern"]', 'U*');
  await page.click('#sp5-map-save');
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent.includes('Zuordnung gespeichert'));
  check('an editor change after unapplied JSON is what is saved', JSON.stringify((await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body.mapping.ignoreShifts) === '["U*"]');
  check('the JSON view shows what was saved', (await page.inputValue('#sp5-map-json')).includes('"U*"'));
  await page.fill('[data-key="ignoreShifts.0.pattern"]', 'W*');
  await page.fill('#sp5-map-json', JSON.stringify({ ...MAPPING, ignoreShifts: ['J*'] }));
  await page.click('#sp5-map-save');
  await page.waitForFunction(() => document.querySelector('[data-key="ignoreShifts.0.pattern"]')?.value === 'J*');
  check('JSON typed after an editor change is what is saved', JSON.stringify((await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body.mapping.ignoreShifts) === '["J*"]');
  await page.fill('[data-key="ignoreShifts.0.pattern"]', 'T*');
  await shot(page, 'sp5-zuordnung');
  // leaving with unsaved changes asks: declined, the page stays
  page.removeAllListeners('dialog');
  let asked = '';
  page.once('dialog', (d) => { asked = d.message(); d.dismiss(); });
  await page.click('.page-tabs a:text("Bericht")');
  await page.waitForTimeout(300);
  check('leaving with unsaved changes asks', asked.includes('ungespeicherte Änderungen') && page.url().includes('zuordnung'), asked);
  page.on('dialog', (d) => d.accept());
  await page.click('.page-tabs a:text("Vergleich")');
  await page.waitForSelector('#sp5-cmp-against');

  // ---- Vergleich ----
  const compare = async () => page.waitForSelector('#sp5-cmp-against');
  check('compared with the import, with corrections', (await text(page, '#sp5-cmp-against'))?.includes('letzten Import') && (await text(page, '#sp5-cmp-against'))?.includes('mit Korrekturen'));
  const fieldRows = () => page.$$eval('[data-section="fields"] tbody tr', (trs) => trs.map((tr) => [...tr.cells].map((c) => c.textContent).join('|')));
  const withCorr = await fieldRows();
  check('changed fields with German labels and readable values', withCorr.some((r) => r.includes('Wochenstunden|40|38,5'))
    && withCorr.some((r) => r.includes('Austritt|offen|30.06.2027')) && withCorr.some((r) => r.includes('Gewohnte Verteilung|Früh 50 %, Spät 50 %')), withCorr.join(' / '));
  check('natural id order of new people', (await page.$$eval('[data-section="added"] tbody tr td:first-child', (tds) => tds.map((x) => x.textContent))).join(',') === 'E9,E10,E100');
  check('a removed person in Dispofold links to the person', (await page.locator('[data-section="removed"] a[href="#/person/P0150"]').count()) === 1);
  check('no note on a removed person still taken over', !(await text(page, '[data-section="removed"] tbody'))?.includes('ausgeschlossen'));
  await api(page, 'PUT', '/api/dataset/people/P0150/excluded', true);
  await page.reload();
  await page.waitForSelector('[data-section="removed"] tbody');
  check('a removed person excluded in Dispofold: the import keeps her', (await text(page, '[data-section="removed"] tbody'))?.includes('in Dispofold ausgeschlossen'),
    await text(page, '[data-section="removed"] tbody'));
  check('restrictions as text', (await text(page, '[data-section="restrictions"] tbody'))?.includes('+ Nur auf Anfrage: A1 Annahme früh 06:30, Mo, 01.01.2027 bis 31.03.2027'),
    await text(page, '[data-section="restrictions"] tbody'));
  check('demand with label and staffing', (await text(page, '[data-section="demand"] tbody'))?.includes('AN Mo–Fr 07:00–19:00\t1–2\t2'), await text(page, '[data-section="demand"] tbody'));
  check('changed counts highlighted with the difference', (await page.locator('[data-section="counts"] .badge-warn', { hasText: '+1' }).count()) >= 1
    && (await page.locator('[data-section="counts"] strong', { hasText: 'Personen' }).count()) === 1);
  check('duties per month and area, unknown shifts named', (await text(page, '[data-section="duties"] tbody'))?.includes('unbekannte Schicht'));
  await page.uncheck('#sp5-cmp-corrections');
  await page.waitForFunction(() => document.querySelector('#sp5-cmp-against')?.textContent.includes('ohne Korrekturen'));
  check('without corrections the difference they make shows', !(await fieldRows()).some((r) => r.includes('Wochenstunden')));
  await page.check('#sp5-cmp-corrections');
  await page.setInputFiles('#sp5-cmp-file', { name: 'alt.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ format: 'dispofold/dataset', version: 1 })) });
  await page.waitForFunction(() => document.querySelector('#sp5-cmp-against')?.textContent.includes('der Datei'));
  check('compared with an uploaded dataset', await page.isChecked('#sp5-cmp-against-file'));
  await page.setInputFiles('#sp5-cmp-file', { name: 'x.json', mimeType: 'application/json', buffer: Buffer.from('{"foo":1}') });
  await page.waitForSelector('#sp5-compare .alert-error');
  check('a file that is no dataset is refused', (await text(page, '#sp5-compare .alert-error'))?.includes('kein Datensatz'));
  await page.check('#sp5-cmp-import');
  await compare();
  await shot(page, 'sp5-vergleich');

  // ---- light and dark: only the core's colours ----
  const colours = () => page.evaluate(() => {
    const css = getComputedStyle(document.documentElement);
    const rgb = (v) => { const d = document.createElement('div'); d.style.color = v; document.body.append(d); const c = getComputedStyle(d).color; d.remove(); return c; };
    return { theme: document.documentElement.dataset.theme, body: getComputedStyle(document.body).backgroundColor, bg: rgb(css.getPropertyValue('--bg')),
      th: getComputedStyle(document.querySelector('#main table.data th')).backgroundColor, surface2: rgb(css.getPropertyValue('--surface-2')),
      text: getComputedStyle(document.querySelector('#main h2')).color, textVar: rgb(css.getPropertyValue('--text')),
      styled: [...document.querySelectorAll('#main [style]')].filter((e) => e.style.length && !e.closest('table.data') && e.style.overflowX !== 'auto')
        .map((e) => `${e.tagName}.${e.className}[${e.getAttribute('style')}]`) };
  });
  const light = await colours();
  await page.emulateMedia({ colorScheme: 'dark' });
  await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark');
  const dark = await colours();
  for (const [name, c] of [['light', light], ['dark', dark]]) {
    check(`${name} theme: page, table header and text use the core's colours`, c.body === c.bg && c.th === c.surface2 && c.text === c.textVar, JSON.stringify(c));
  }
  check('the themes differ', light.body !== dark.body);
  check('the pages bring no inline styles', !light.styled.length, light.styled.join(', '));
  await open(page, `${P}/zuordnung`, '#sp5-codes-summary');
  await shot(page, 'sp5-zuordnung-dunkel');

  // ---- proposals: listed with their reasons in both themes, taken and discarded with the keyboard, reset ----
  await page.click('#sp5-propose');
  await page.waitForSelector('#sp5-proposals-summary');
  check('the proposals with their reasons', (await page.locator('#sp5-proposals li').count()) === 2
    && (await text(page, '[data-proposal="shift:T1"]'))?.includes('Kein Bereich erreicht 80 %')
    && (await text(page, '[data-proposal="group:Projekt"]'))?.includes('keine Dienste'), await text(page, '#sp5-proposals'));
  await page.locator('#sp5-proposals').scrollIntoViewIfNeeded();
  await shot(page, 'sp5-vorschlaege-dunkel');
  await page.emulateMedia({ colorScheme: 'light' });
  await shot(page, 'sp5-vorschlaege');
  const saved0 = (await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body.mapping;
  await page.focus('[data-proposal="group:Projekt"] button[aria-label="Projekt: ignorieren verwerfen"]');
  await page.keyboard.press('Enter');
  await page.waitForFunction(() => !document.querySelector('[data-proposal="group:Projekt"]'));
  check('a discarded proposal leaves the list, the reset counts it', (await text(page, '#sp5-propose-reset')) === 'Verworfene zurücksetzen (1)'
    && JSON.stringify((await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body.proposals.discarded) === '["group:Projekt"]');
  check('the focus moves on to the next proposal', (await page.evaluate(() => document.activeElement?.closest('[data-proposal]')?.dataset.proposal)) === 'shift:T1');
  await page.focus('[data-proposal="shift:T1"] button:text("Übernehmen: DR")');
  await page.keyboard.press('Enter');
  await page.waitForSelector('#sp5-proposals-none');
  const rules = await page.$$eval('[data-section="shifts"] [data-key$=".match"]', (xs) => xs.map((x) => x.value));
  check('a choice is taken with the chosen area into the editor', rules.at(-1) === 'T1'
    && (await page.inputValue(`[data-key="shifts.${rules.length - 1}.areaId"]`)) === 'DR' && await page.isVisible('#sp5-map-dirty'), rules.join(','));
  check('nothing is saved before "Zuordnung speichern"', JSON.stringify((await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body.mapping) === JSON.stringify(saved0));
  await page.click('#sp5-propose-reset');
  await page.waitForSelector('[data-proposal="group:Projekt"]');
  check('the reset brings the discarded proposal back, the taken code has a rule now', (await page.locator('#sp5-proposals li').count()) === 1
    && await page.isHidden('#sp5-propose-reset'));
  await page.click('[data-take-all="groups"]');
  await page.waitForSelector('#sp5-proposals-none');
  await page.click('#sp5-map-save');
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent.includes('Zuordnung gespeichert'));
  const after = (await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body.mapping;
  check('saved with the taken proposals', after.shifts.at(-1).match === 'T1' && after.shifts.at(-1).areaId === 'DR' && after.ignoreGroups.includes('Projekt'),
    JSON.stringify(after));
  // the settings of the proposals are checked by the server
  await page.click('summary:text("Einstellungen der Vorschläge")');
  await page.fill('#sp5-propose-share', '0');
  await page.click('#sp5-propose-save');
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent.includes('ungültig'));
  check('a share of 0 is refused, named as on the page', (await text(page, '#toast'))?.includes('Anteil für eine Schicht'), await text(page, '#toast'));
  await page.fill('#sp5-propose-share', '70');
  await page.click('#sp5-propose-save');
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent.includes('Einstellungen der Vorschläge gespeichert'));
  check('the settings of the proposals are saved, the mapping stays', (await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body.proposals.share === 70
    && (await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body.mapping.ignoreGroups.includes('Projekt'));

  // ---- readers ----
  const reader = await session('ansicht');
  await reader.goto(`${base}/${P}/uebersicht`);
  await reader.waitForFunction(() => document.querySelector('#main h1'));
  const readerMenu = await reader.$$eval('#nav a', (as) => as.map((a) => a.textContent));
  check('readers see neither the group nor the pages', !readerMenu.includes('Schichtplaner 5') && !reader.url().includes('plugin/sp5'), `${readerMenu.join(', ')} ${reader.url()}`);
  check('readers get 403 from the routes', (await api(reader, 'GET', '/api/plugins/sp5/lauf')).status === 403);
}
