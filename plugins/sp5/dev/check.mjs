// Browser check of the plugin's pages on the dev server with dev/mock.mjs (run by frontend/scripts/check-plugin-pages.mjs):
// the source under Daten → Übernehmen (old addresses lead there, the card under Erweiterungen only links to it, the
// result as files under Sichern), its Quelle (folder and people found, the mapping's state, the stored options saved
// at once, a bad one refused), Einlesen (nothing changes; the result on top, the options it used, the codes without a
// rule, the minimum demand in all, some or no areas) and Übernehmen (only after Einlesen, off after an option changed,
// keeping changes, the first month chosen, a closed month with the way to Monate), the report and the comparison as
// fold-outs of the result, the mapping editor (an invalid rule, a rule that matches nothing, moving and removing rows
// with the keyboard, saving, JSON in and out for experts, unknown keys, leaving with unsaved changes), the assistant
// (one click gives the mapping of "Alle übernehmen" in every section), readers without the menu Daten, and the light
// and dark theme.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { MAPPING } from './mock.mjs';

const MONTH = '2027-01';
const P = '#/plugin/sp5';
const T = '#/uebernehmen/sp5';
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
  const shotOf = async (page, selector, name) => { if (shots) await page.locator(selector).screenshot({ path: join(shots, `${name}.png`) }); };

  const page = await session('planung');
  await api(page, 'PUT', '/api/extensions/sp5', { enabled: true });
  await open(page, T, '#sp5-run');
  const menu = await page.$$eval('#nav a', (as) => as.map((a) => a.textContent));
  check('no own menu group: the source is under Daten', menu.includes('Daten') && !menu.includes('Schichtplaner 5'), menu.join(', '));
  const tabs = await page.$$eval('.page-tabs a', (as) => as.map((a) => a.textContent));
  check('Daten: Übernehmen, Zuordnung, Monate, Sichern', tabs.join('|') === 'Übernehmen|Zuordnung|Monate|Sichern', tabs.join('|'));
  check('intro with help link', (await page.locator('.page-intro a').count()) === 1);
  check('the source is chosen', await page.isChecked('#source-sp5'));
  // the pages of earlier versions lead here
  for (const path of ['uebersicht', 'bericht', 'lauf-vergleich']) {
    await open(page, `${P}/${path}`, '#sp5-run');
    check(`the old page ${path} leads to Daten → Übernehmen`, page.url().endsWith(T), page.url());
  }
  await open(page, '#/import', '#sp5-run');
  check('the old Daten page leads to Übernehmen, the source chosen last', page.url().endsWith('#/uebernehmen'), page.url());
  await open(page, `${P}/zuordnung`, '#main h1');
  check('Zuordnung: the page title is the name of its tab', (await text(page, '#main h1')) === (await text(page, '.page-tabs a[aria-current="page"]')));
  // the card under Erweiterungen: on/off and a link, no fields
  await open(page, '#/erweiterungen', '#sp5-set-up');
  check('the card under Erweiterungen only links to Übernehmen', (await page.getAttribute('#sp5-set-up', 'href')) === T
    && (await page.locator('.extension-settings input, .extension-settings select, .extension-settings textarea').count()) === 0);
  // Sichern: the result as files, for experts
  await open(page, '#/sichern', '#backup-experts');
  check('the result as files is under Sichern → Für Fachleute', !(await page.$eval('#backup-experts', (d) => d.open)) && (await page.locator('#backup-experts #sp5-result').count()) === 1);
  await page.click('#backup-experts summary');
  const zip = await download(page, () => page.click('#sp5-result'));
  check('"Ergebnis speichern" downloads the ZIP', zip.name === 'dispofold-sp5-ergebnis-2027-01.zip', zip.name);

  // ---- before the first run ----
  await open(page, T, '#sp5-run');
  check('the months as names of the program', (await page.$eval('#sp5-run-month', (x) => x.selectedOptions[0]?.textContent)) === 'Jänner 2027'
    && (await page.$eval('#sp5-run-to', (x) => x.selectedOptions[0]?.textContent)) === 'nur der erste Monat');
  check('Einlesen says it changes nothing', (await text(page, '#sp5-run-note')) === 'ändert noch nichts in Dispofold');
  check('no Übernehmen without Einlesen', await page.isDisabled('[data-import-run="sp5"]') && (await text(page, '#sp5-take-what'))?.includes('Erst nach dem Einlesen'));

  // ---- missing mapping, bad option ----
  await api(page, 'PUT', '/api/plugins/sp5/einstellungen', { root: '/srv/dispofold/quellen', path: '2027', mapping: null });
  await open(page, T, '#sp5-run');
  check('without a mapping the source is open and says so, with a link to Zuordnung', await page.$eval('#sp5-source', (d) => d.open)
    && (await text(page, '#sp5-mapping-line'))?.includes('noch keine') && (await page.getAttribute('#sp5-mapping-line a', 'href')) === `${P}/zuordnung`);
  await page.click('#sp5-run');
  await page.waitForSelector('#sp5-summary .alert-error');
  check('Einlesen without mapping is refused, with a link to Zuordnung', (await text(page, '#sp5-summary'))?.includes('Die Zuordnung fehlt')
    && (await page.getAttribute('#sp5-summary .alert-error a', 'href')) === `${P}/zuordnung`);
  await page.click('#sp5-summary .alert-error a');
  await page.waitForSelector('#sp5-codes-summary, #sp5-propose');
  check('"Zur Zuordnung" opens the tab Zuordnung', page.url().endsWith(`${P}/zuordnung`)
    && (await text(page, '.page-tabs a[aria-current="page"]')) === 'Zuordnung', page.url());
  check('without a saved mapping the page says so', await page.isVisible('#sp5-map-none'));
  await page.click('#sp5-map-experts summary');
  await page.fill('#sp5-map-json', JSON.stringify(MAPPING));
  await page.click('#sp5-map-apply');
  await page.click('#sp5-map-save');
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent.includes('Zuordnung gespeichert'));
  check('saved, the page no longer says that no mapping is saved', await page.isHidden('#sp5-map-none'));
  await open(page, T, '#sp5-run');
  await page.waitForFunction(() => document.querySelector('#sp5-source-line')?.textContent.includes('gefunden'));
  check('set up, the source is collapsed with one line: the folder and the people found', !(await page.$eval('#sp5-source', (d) => d.open))
    && (await text(page, '#sp5-source-line')) === 'Ordner /srv/dispofold/quellen/2027: 150 Personen gefunden', await text(page, '#sp5-source-line'));
  await page.click('#sp5-source summary');
  check('options as stored', (await page.inputValue('#sp5-historyMonths')) === '12');
  check('the areas of a person from history with the groups as a reserve, recommended', (await page.inputValue('#sp5-areaSource')) === 'reserve'
    && (await page.textContent('#sp5-areaSource option[value="reserve"]'))?.includes('(empfohlen)') && (await page.inputValue('#sp5-historyAreaDuties')) === '3');
  check('the options a beginner never needs are collapsed under Erweitert, shifts from history empty', !(await page.$eval('#sp5-advanced', (d) => d.open))
    && (await page.locator('#sp5-advanced #sp5-recentMonths').count()) === 1 && (await page.inputValue('#sp5-shiftsFromHistory')) === '');
  await page.click('#sp5-advanced summary');
  await shotOf(page, '#sp5-source', 'sp5-lauf-optionen');
  await page.click('#sp5-advanced summary');
  check('no options per run and no "Als Standard speichern"', (await page.locator('#sp5-run-historyMonths, #sp5-run-default').count()) === 0);
  await page.fill('#sp5-historyMonths', '30');
  await page.locator('#sp5-historyMonths').dispatchEvent('change');
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent.includes('ungültig'));
  check('a bad option is refused with its name', (await text(page, '#toast'))?.includes('ungültig: Monate Historie (0 bis 24)'), await text(page, '#toast'));
  await page.fill('#sp5-historyMonths', '6');
  await page.locator('#sp5-historyMonths').dispatchEvent('change');
  await page.waitForFunction(() => document.querySelector('#sp5-options-saved')?.textContent === 'gespeichert');
  const saved = await api(page, 'GET', '/api/plugins/sp5/einstellungen');
  check('a changed option is saved at once, the rest of the settings kept', saved.body.options.historyMonths === 6 && saved.body.root === '/srv/dispofold/quellen'
    && saved.body.mapping?.areas?.length === 2);

  // ---- Einlesen, started with the keyboard ----
  await page.focus('#sp5-run');
  await page.keyboard.press('Enter');
  await page.waitForSelector('#sp5-run-summary');
  check('the run shows its counts', (await text(page, '[data-count="people"] .value')) === '150' && (await text(page, '[data-count="corrections"] .value')) === '5');
  check('the run names its months and the corrections, once', (await text(page, '#sp5-run-summary'))?.includes('Jänner 2027')
    && (await text(page, '#sp5-run-corrections')) === 'Die Korrekturdatei wurde angewendet: 5 Korrekturen.'
    && !(await text(page, '#sp5-run-summary'))?.includes('noch keine'), await text(page, '#sp5-run-summary'));
  check('the run names the options it used', (await text(page, '#sp5-run-options'))?.includes('Monate Historie: 6'), await text(page, '#sp5-run-options'));
  const lauf = await api(page, 'GET', '/api/plugins/sp5/lauf');
  check('the run used the stored options', lauf.body.request.options.historyMonths === 6, JSON.stringify(lauf.body.request));
  check('the result in plain words on top', (await text(page, '#sp5-run-summary #sp5-report-verdict'))?.startsWith('Zuerst ansehen: 1 Schicht ohne Bereich')
    && (await text(page, '#sp5-report-verdict'))?.includes('2 Zeilen mit rotem Hinweis'), await text(page, '#sp5-report-verdict'));
  await page.waitForSelector('#sp5-details-mapping:not([hidden])');
  check('the codes without a rule, named, with the way to the mapping', (await text(page, '#sp5-details-mapping summary')) === 'Zuordnung: 2 Kürzel ohne Regel');
  await page.click('#sp5-details-mapping summary');
  check('… T1 and the group Projekt', (await text(page, '#sp5-unmatched'))?.includes('T1') && (await text(page, '#sp5-unmatched'))?.includes('Gruppe Projekt'), await text(page, '#sp5-unmatched'));
  check('Übernehmen says what it changes', await page.isEnabled('[data-import-run="sp5"]')
    && (await text(page, '#sp5-take-what'))?.startsWith('150 Personen, 2 Bereiche, Jänner 2027; ersetzt die Daten aus'), await text(page, '#sp5-take-what'));
  // an option changed after Einlesen: Übernehmen waits for a new Einlesen; back as it was, it fits again
  await page.fill('#sp5-historyMonths', '7');
  await page.locator('#sp5-historyMonths').dispatchEvent('change');
  await page.waitForSelector('#sp5-run-stale');
  check('an option changed after Einlesen: Übernehmen is off until read again', await page.isDisabled('[data-import-run="sp5"]'));
  await page.fill('#sp5-historyMonths', '6');
  await page.locator('#sp5-historyMonths').dispatchEvent('change');
  await page.waitForFunction(() => !document.querySelector('[data-import-run="sp5"]')?.disabled);
  check('the option as it was: Übernehmen again, the note gone, what it changes said again', await page.locator('#sp5-run-stale').count() === 0
    && (await text(page, '#sp5-take-what'))?.startsWith('150 Personen'), await text(page, '#sp5-take-what'));
  await open(page, T, '#sp5-run-summary');
  check('the option is used next time too', (await page.inputValue('#sp5-historyMonths')) === '6' && await page.isEnabled('[data-import-run="sp5"]'));
  await shot(page, 'sp5-uebersicht');

  // ---- Vergleich before any import ----
  await page.click('#sp5-details-compare summary');
  await page.waitForSelector('#sp5-no-baseline');
  check('Vergleich: nothing imported yet, the upload is offered', (await text(page, '#sp5-no-baseline'))?.includes('Noch nichts importiert'));

  // ---- Übernehmen, then the first month of the run is chosen ----
  // another month chosen in the picker, then the page without a reload (a reload would choose January again)
  await open(page, '#/plan', '#month');
  await page.selectOption('#month', '2027-03');
  await page.waitForFunction(() => sessionStorage.getItem('month') === '2027-03');
  await page.evaluate((x) => { location.hash = x; }, T);
  await page.waitForSelector('#sp5-run-summary');
  check('keep changes is off without changes', !(await page.isChecked('#plugin-keep-sp5')));
  await page.waitForSelector('#take-over-source .corrections-hint .alert');
  check('without any corrections the step says so, with the way to load a file',
    (await text(page, '#take-over-source .corrections-hint'))?.includes('keine Korrekturen')
    && (await page.getAttribute('#take-over-source .corrections-hint a', 'href')) === '#/sichern/korrekturen');
  await page.click('[data-import-run="sp5"]');
  await page.waitForSelector('#import-check-areas');
  const done = await text(page, '#take-over-source');
  check('Übernehmen without a question, the summary with "Bereiche prüfen"', done?.includes('Import aus') && (await page.getAttribute('#import-check-areas', 'href')) === '#/bereiche', done);
  check('the first month of the run is chosen, the result stays', (await page.evaluate(() => sessionStorage.getItem('month'))) === MONTH
    && (await text(page, '#import-month'))?.includes('Jänner 2027') && await page.isVisible('#import-check-areas'), await text(page, '#import-month'));
  const p1 = (await api(page, 'GET', '/api/dataset')).body.people[0];
  await api(page, 'PUT', `/api/dataset/people/${p1.id}`, { ...p1, weeklyHours: 30 });
  const state = page.waitForResponse((r) => r.url().endsWith('/api/data/corrections/state'));
  await open(page, T, '#sp5-run-summary');
  await page.waitForFunction(() => document.querySelector('#plugin-keep-sp5')?.checked);
  check('keep changes is on after a change in Dispofold', true);
  const answered = await (await state).json();
  await page.waitForTimeout(200);
  check('with a change in Dispofold there is no hint', answered.none === false && await page.locator('.corrections-hint .alert').count() === 0);
  await page.click('[data-import-run="sp5"]');
  await page.waitForSelector('#import-preview');
  check('the preview comes first', (await text(page, '#import-preview'))?.includes('Vorschau'));
  await page.click('#import-preview button.btn-primary');
  await page.waitForFunction(() => document.querySelector('#take-over-source')?.textContent.includes('aus Dispofold übernommen'));
  check('the import keeps the changes', true);
  // a closed month of the run: the note with the next step, on Daten → Monate
  await api(page, 'POST', `/api/plans/${MONTH}/finalize`);
  await open(page, T, '#sp5-run-summary');
  await page.waitForSelector('#sp5-import-closed .alert');
  check('a closed month of the run: the note says the next step', (await text(page, '#sp5-import-closed'))?.includes('Abgeschlossen: Jänner 2027')
    && (await page.getAttribute('#sp5-import-closed a', 'href')) === '#/monate', await text(page, '#sp5-import-closed'));
  await page.click('#sp5-import-closed a');
  await page.waitForSelector('#months-table');
  check('Monate: the month closed, with "Monat wieder öffnen"', (await text(page, `#months-table tr[data-month="${MONTH}"]`))?.includes('abgeschlossen')
    && (await page.locator(`[data-reopen="${MONTH}"]`).count()) === 1);
  await page.click(`[data-reopen="${MONTH}"]`);
  await page.waitForSelector(`[data-close="${MONTH}"]`);
  check('opened again on Monate', (await text(page, `#months-table tr[data-month="${MONTH}"]`))?.includes('offen'));
  await open(page, T, '#sp5-run-summary');
  await page.waitForTimeout(300);
  check('an open month: no note', (await page.locator('#sp5-import-closed .alert').count()) === 0);

  // ---- areas without minimum demand (stored options): never "in Ordnung", all or some named, in the accent colour ----
  const stored0 = (await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body;
  // (each run differs from the one before in the options it names: the wait is for the new one)
  for (const [options, expected, named] of [[{ demand: 'sp5' }, 'für keinen Bereich gibt es einen Mindestbedarf', 'Bedarf: aus Schichtplaner 5'],
    [{ historyMonths: 1 }, 'für 1 Bereich gibt es keinen Mindestbedarf', 'Monate Historie: 1;'], [{}, null, 'Monate Historie: 6;']]) {
    await api(page, 'PUT', '/api/plugins/sp5/einstellungen', { ...stored0, options: { ...stored0.options, ...options } });
    await open(page, T, '#sp5-run');
    await page.click('#sp5-run');
    await page.waitForFunction((o) => document.querySelector('#sp5-run-options')?.textContent.includes(o), named);
    await page.waitForSelector('#sp5-report-verdict');
    const verdict = await text(page, '#sp5-report-verdict');
    check(`the minimum demand on top of the result: ${JSON.stringify(options)}`, expected
      ? verdict?.includes(expected) && verdict.includes('Option Bedarf prüfen') && await page.locator('#sp5-report-verdict.alert-warn').count() === 1
      : !verdict?.includes('Mindestbedarf') && await page.locator('#sp5-report-verdict.alert-warn').count() === 0, verdict);
    if (shots && options.demand) await page.locator('#sp5-run-summary').screenshot({ path: join(shots, 'sp5-mindestbedarf.png') });
  }

  // ---- Bericht, in the result ----
  await open(page, T, '#sp5-run-summary');
  await page.click('#sp5-details-report summary');
  await page.waitForSelector('#sp5-report-count');
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
  check('mapping.json in and out only for experts, collapsed', !(await page.$eval('#sp5-map-experts', (d) => d.open)) && await page.isHidden('#sp5-map-download'));
  await page.click('#sp5-map-experts summary');
  const json = await download(page, () => page.click('#sp5-map-download'));
  check('mapping.json with two spaces and a final newline', json.name === 'mapping.json' && json.text.endsWith('}\n') && json.text.includes('\n  "areas": ['));
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
  const put = page.waitForResponse((r) => r.url().endsWith('/api/plugins/sp5/einstellungen') && r.request().method() === 'PUT');
  await page.click('#sp5-map-save');
  await put;
  await page.waitForFunction(() => document.querySelector('[data-key="ignoreShifts.0.pattern"]')?.value === 'J*');
  check('JSON typed after an editor change is what is saved', JSON.stringify((await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body.mapping.ignoreShifts) === '["J*"]');
  await page.fill('[data-key="ignoreShifts.0.pattern"]', 'T*');
  await shot(page, 'sp5-zuordnung');
  // leaving with unsaved changes asks: declined, the page stays
  page.removeAllListeners('dialog');
  let asked = '';
  page.once('dialog', (d) => { asked = d.message(); d.dismiss(); });
  await page.click('.page-tabs a:text("Monate")');
  await page.waitForTimeout(300);
  check('leaving with unsaved changes asks', asked.includes('ungespeicherte Änderungen') && page.url().includes('zuordnung'), asked);
  page.on('dialog', (d) => d.accept());
  await page.click('.page-tabs a:text("Übernehmen")');
  await page.waitForSelector('#sp5-run-summary');
  await page.click('#sp5-details-compare summary');
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
  await open(page, T, '#sp5-run-summary');
  await page.click('#sp5-details-compare summary');
  await page.waitForSelector('[data-section="removed"] tbody');
  check('a removed person excluded in Dispofold: the import keeps her', (await text(page, '[data-section="removed"] tbody'))?.includes('in Dispofold ausgeschlossen'),
    await text(page, '[data-section="removed"] tbody'));
  check('restrictions as text', (await text(page, '[data-section="restrictions"] tbody'))?.includes('+ nur von Hand: A1 Annahme früh 06:30, Mo, 01.01.2027 bis 31.03.2027'),
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
      text: getComputedStyle(document.querySelector('#main h1')).color, textVar: rgb(css.getPropertyValue('--text')),
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

  // ---- a page that explains itself, the areas from the shift names ----
  await open(page, `${P}/zuordnung`, '#sp5-codes-summary');
  const jumps = await page.$$eval('nav.jump-links [data-jump]', (bs) => bs.map((b) => b.textContent));
  check('jump links to the sections of the long page', jumps.join('|') === 'Vorschläge aus dem Ordner|Bereiche|Schichtregeln|Gruppen|Ignorierte Schichten|Ignorierte Gruppen|Ausgeschlossene Gruppen|Ersatzruhe|Ignorierte Abwesenheiten|Im Ordner',
    jumps.join('|'));
  await page.click('nav.jump-links [data-jump="h-sp5-map-groups"]');
  check('a jump link moves the focus to its section', (await page.evaluate(() => document.activeElement?.id)) === 'h-sp5-map-groups');
  if (shots) await page.screenshot({ path: join(shots, 'sp5-zuordnung-sprung.png') });
  check('the page says what the mapping is', (await text(page, '#sp5-map-intro'))?.startsWith('Die Zuordnung übersetzt Schichtplaner 5 in Dispofold'));
  const intros = await page.$$eval('[data-intro]', (ps) => Object.fromEntries(ps.map((x) => [x.dataset.intro, x.textContent])));
  check('each section says what it is for', intros.areas?.startsWith('Arbeitsplätze, die getrennt besetzt werden')
    && intros.shifts?.startsWith('Zu welchem Bereich gehört dieser Dienstcode?') && intros.groups?.includes('Sammelgruppen')
    && intros.compensatoryRestAbsences?.includes('Ersatzruhe'), JSON.stringify(intros));
  check('how to read a reason and what to do when unsure', (await text(page, '#sp5-propose-reading'))?.includes('14 von 15 Mitgliedern')
    && (await text(page, '#sp5-propose-reading'))?.includes('Im Zweifel'));
  await page.click('#sp5-propose');
  await page.waitForSelector('#sp5-basis');
  check('the proposals come from the groups here, recommended, with the reason', await page.isChecked('#sp5-basis-groups')
    && (await text(page, 'label[for="sp5-basis-groups"]'))?.includes('(empfohlen)') && (await text(page, '#sp5-basis-reason'))?.includes('Hier passen die Gruppen'));
  await page.check('#sp5-basis-families');
  await page.waitForSelector('[data-proposal="shift:families:NW *"]');
  check('from the shift names: an area per family, a pattern rule with the codes it covers',
    (await text(page, '[data-proposal="area:families:LK, LK-F, LK-S"]'))?.includes('Bereich LK LK, LK-F, LK-S (aus den Schichtnamen LK, LK-F, LK-S)')
    && (await text(page, '[data-proposal="shift:families:NW *"]'))?.includes('NW * → NW')
    && (await text(page, '[data-proposal="shift:families:NW *"]'))?.includes('Deckt heute NW 7-19 (54 Dienste), NW 19-7 (40 Dienste)')
    && (await text(page, '[data-proposal="group:families:Tagdienst"]'))?.includes('Sammelgruppe'), await text(page, '#sp5-proposals'));
  check('the chosen basis keeps its focus', await page.waitForFunction(() => document.activeElement?.id === 'sp5-basis-families', null, { timeout: 3000 })
    .then(() => true, () => false));
  await page.click('[data-proposal="shift:families:NW *"] button:text("Übernehmen")');
  await page.waitForFunction(() => !document.querySelector('[data-proposal="shift:families:NW *"]'));
  const familyRules = await page.$$eval('[data-section="shifts"] [data-key$=".match"]', (xs) => xs.map((x) => x.value));
  check('a family rule is taken with its new area', familyRules.at(-1) === 'NW *'
    && (await page.$$eval('[data-section="areas"] [data-key$=".id"]', (xs) => xs.map((x) => x.value))).includes('NW')
    && !(await page.locator('[data-proposal="area:families:NW"]').count()), familyRules.join(','));
  await page.click('#sp5-propose');
  await page.waitForSelector('#sp5-proposals-summary');
  check('asked again, the chosen basis stays and the taken rule is not proposed again', await page.isChecked('#sp5-basis-families')
    && !(await page.locator('[data-proposal="shift:families:NW *"]').count()));
  // the pictures of the guide: a folder whose groups are coarse, from an empty mapping
  const before = (await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body;
  await api(page, 'PUT', '/api/plugins/sp5/einstellungen', { ...before, path: 'familien', mapping: null });
  await open(page, `${P}/zuordnung`, '#sp5-codes-summary');
  if (shots) await page.screenshot({ path: join(shots, 'sp5-zuordnung-erklaert.png') }); // the top of the page, as it opens
  await page.click('#sp5-propose');
  await page.waitForSelector('[data-proposal="shift:families:NW *"]');
  check('a code without a time range and without demand is proposed to be ignored, with its reason',
    (await text(page, '[data-proposal="shift:AUSB"]'))?.includes('Kein Zeitraum im Kürzel, kein Bedarf in Schichtplaner 5, 40 Dienste'));
  await shotOf(page, '[data-proposal="shift:AUSB"]', 'sp5-ohne-zeitraum');
  check('the setting of that rule is on', await page.isChecked('#sp5-propose-ignoreUntimed'));
  check('a folder with coarse groups recommends the shift names', await page.isChecked('#sp5-basis-families')
    && (await text(page, 'label[for="sp5-basis-families"]'))?.includes('(empfohlen)') && (await text(page, '#sp5-basis-reason'))?.includes('Hier passen die Schichtnamen'));
  await shotOf(page, 'section[aria-labelledby="h-sp5-propose"]', 'sp5-zuordnung-familien');
  await page.emulateMedia({ colorScheme: 'dark' });
  await shotOf(page, 'section[aria-labelledby="h-sp5-propose"]', 'sp5-zuordnung-familien-dunkel');
  await page.emulateMedia({ colorScheme: 'light' });
  // ---- the assistant: the proposals for a check, one click takes and saves them as "Alle übernehmen" in every section does ----
  await api(page, 'PUT', '/api/plugins/sp5/einstellungen', { ...before, path: 'familien', mapping: null });
  await open(page, `${P}/zuordnung`, '#sp5-assistant-take');
  check('the assistant on top: the number of proposals and the areas they bring', (await text(page, '#sp5-assistant-count'))?.match(/^\d+ Vorschläge/)
    && (await page.locator('#sp5-assistant-areas li').count()) > 0
    && (await page.evaluate(() => document.querySelector('#sp5-assistant').compareDocumentPosition(document.querySelector('#sp5-map-editor')) === Node.DOCUMENT_POSITION_FOLLOWING)),
  await text(page, '#sp5-assistant'));
  if (shots) await page.locator('#sp5-assistant').screenshot({ path: join(shots, 'sp5-assistent.png') });
  await page.click('#sp5-assistant-take');
  await page.waitForSelector('#sp5-assistant-done');
  check('the focus on what was done', (await page.evaluate(() => document.activeElement?.id)) === 'sp5-assistant-done');
  const oneClick = (await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body.mapping;
  check('one click: taken and saved, nothing left unsaved', oneClick?.areas?.length > 0 && await page.isHidden('#sp5-map-dirty'), await text(page, '#sp5-assistant-done'));
  await api(page, 'PUT', '/api/plugins/sp5/einstellungen', { ...before, path: 'familien', mapping: null });
  await open(page, `${P}/zuordnung`, '#sp5-codes-summary');
  await page.click('#sp5-propose');
  await page.waitForSelector('#sp5-proposals-summary');
  for (const section of ['areas', 'shifts', 'groups', 'compensatoryRest']) {
    if (await page.locator(`[data-take-all="${section}"]`).count()) await page.click(`[data-take-all="${section}"]`);
  }
  await page.click('#sp5-map-save');
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent.includes('Zuordnung gespeichert'));
  const byHand = (await api(page, 'GET', '/api/plugins/sp5/einstellungen')).body.mapping;
  check('one click gives the mapping of "Alle übernehmen" in every section', JSON.stringify(oneClick) === JSON.stringify(byHand),
    `${JSON.stringify(oneClick)} / ${JSON.stringify(byHand)}`);
  await api(page, 'PUT', '/api/plugins/sp5/einstellungen', before);

  // ---- readers ----
  const reader = await session('ansicht');
  for (const old of [`${P}/uebersicht`, T, `${P}/zuordnung`]) {
    await reader.goto(`${base}/${old}`);
    await reader.waitForFunction(() => document.querySelector('#main h1'));
    const readerMenu = await reader.$$eval('#nav a', (as) => as.map((a) => a.textContent));
    check(`readers see neither Daten nor the pages: ${old}`, !readerMenu.includes('Daten') && !reader.url().includes('plugin/sp5') && !reader.url().includes('uebernehmen'),
      `${readerMenu.join(', ')} ${reader.url()}`);
  }
  check('readers get 403 from the routes', (await api(reader, 'GET', '/api/plugins/sp5/lauf')).status === 403);
}
