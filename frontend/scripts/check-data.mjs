// Playwright check of the Daten page (mock API): sections with intros, corrections file of the corrections in effect
// (earlier ones and the changes made in Dispofold), import keeping those changes (preview with conflicts, also from
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
    await page.goto(`${base}/#/import`);
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
  await open(page);
  const main = page.locator('#main');

  // sections, each with a short intro; demo data under Erweitert
  const headings = await main.locator('h2').allInnerTexts();
  check('sections Import, Export, Korrekturdatei, Anonymisieren', ['Import', 'Export', 'Korrekturdatei', 'Anonymisieren'].every((x) => headings.includes(x)), headings.join(' | '));
  for (const id of ['h-import', 'h-export', 'h-corrections', 'h-anonymize']) {
    check(`intro in ${id}`, await page.locator(`section[aria-labelledby=${id}] > p.muted`).count() >= 1);
  }
  check('demo data under Erweitert', await page.locator('section[aria-labelledby=h-import] details.advanced button', { hasText: 'Demodaten laden' }).count() === 1);
  check('keep option off without changes', !(await page.locator('#dataset-keep').isChecked()));

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
  await setUpload();
  await main.getByRole('button', { name: 'Datensatz importieren' }).click();
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
  await main.getByRole('button', { name: 'Datensatz importieren' }).click();
  await main.getByText('Stammdaten wurden geändert.').waitFor();
  await main.getByRole('button', { name: 'Änderungen behalten' }).click();
  await preview.waitFor();
  check('warning leads to the preview', (await preview.innerText()).includes('Übernommen aus Dispofold'));
  await preview.getByRole('button', { name: 'Abbrechen' }).click();

  // pseudonymised export: dataset and plan to share, the key separately
  await main.getByRole('button', { name: 'Pseudonymisieren' }).click();
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

  // viewers: refused by the server, no Daten page
  const viewer = await login('ansicht');
  const refused = await Promise.all([api(viewer, 'GET', '/api/data/corrections'), api(viewer, 'GET', `/api/data/anonymized?month=${month}`),
    api(viewer, 'POST', '/api/import/preview', upload), api(viewer, 'POST', '/api/import/dataset?keep=true', upload)]);
  check('viewer refused', refused.every((r) => r.status === 403), refused.map((r) => r.status).join(' '));
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('Daten page checked');
