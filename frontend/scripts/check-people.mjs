// Playwright check of the Personen pages: the list searches by name, short name and area name and links to the page
// of each person; planners edit everything about a person in the one form there (master data, usual mix,
// restrictions with the area switch "ganzer Bereich", which gives the same records as entering each shift by hand
// and shows "gemischt" when the shifts differ), create and remove a person; viewers get no form, no "Person anlegen"
// and no personal data from the server. Demo data of the dev server (mock API), month 2027-01 planned.
//   npm run build && node scripts/check-people.mjs     (Chromium via `npx playwright install chromium`)
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
  const login = async (user) => {
    const context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1600, height: 1000 } });
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
  const getDataset = (page) => page.evaluate(async () => (await fetch('/api/dataset')).json());
  const personName = (p) => `${p.lastName} ${p.firstName}`;

  const page = await login('planung');
  await page.evaluate(async (m) => {
    await fetch('/api/import/demo', { method: 'POST' });
    await fetch(`/api/plans/${m}/solve`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{"seed":1,"timeLimitSeconds":1}' });
  }, month);
  // waitForFunction does not await an async predicate: poll from here until the solve has ended
  for (const end = Date.now() + 60000; await page.evaluate(async (m) => (await (await fetch(`/api/plans/${m}`)).json()).solving, month);) {
    if (Date.now() > end) throw new Error('solve did not end within 60 s');
    await page.waitForTimeout(500);
  }
  const dataset = await getDataset(page);
  const shiftsOf = (areaId) => dataset.shifts.filter((s) => s.areaId === areaId);
  // a person without restrictions in two areas with at least two shifts each
  const person = dataset.people.find((p) => !dataset.restrictions.some((r) => r.personId === p.id)
    && p.areaIds.filter((a) => shiftsOf(a).length >= 2).length >= 2);
  const [area1, area2] = person.areaIds.filter((a) => shiftsOf(a).length >= 2);
  const areaName = (id) => dataset.areas.find((a) => a.id === id).name;

  // The list: every person, searchable by short name, area name and name.
  await page.goto(`${base}/#/person`);
  await page.locator('#person-search').waitFor();
  const rows = page.locator('[data-table="people"] tbody tr');
  const visible = () => rows.evaluateAll((trs) => trs.filter((tr) => !tr.hidden).map((tr) => [...tr.children].map((td) => td.textContent)));
  check('list shows every person', (await visible()).length === dataset.people.length, String((await visible()).length));
  check('planners may create a person', await page.getByRole('link', { name: 'Person anlegen' }).count() === 1);
  await page.fill('#person-search', person.shortName.toLowerCase());
  let shown = await visible();
  check('search by short name', shown.some((r) => r[0] === personName(person)) && shown.every((r) => r.join(' ').toLowerCase().includes(person.shortName.toLowerCase())),
    shown.map((r) => r[0]).join(' | '));
  await page.fill('#person-search', areaName(area2));
  shown = await visible();
  check('search by area name', shown.length === dataset.people.filter((p) => p.areaIds.includes(area2)).length && shown.every((r) => r[2].includes(areaName(area2))),
    String(shown.length));
  await page.fill('#person-search', `${person.firstName} ${person.lastName}`);
  shown = await visible();
  check('search by name (both parts)', shown.length >= 1 && shown.every((r) => r[0].includes(person.firstName) && r[0].includes(person.lastName)), shown.map((r) => r[0]).join(' | '));
  await page.fill('#person-search', 'zzzzqq');
  check('no match is said', (await visible()).length === 0 && await page.getByText('Keine Person gefunden.').isVisible());

  // Open the page of the person from the list.
  await page.fill('#person-search', person.shortName);
  await page.getByRole('link', { name: personName(person), exact: true }).click();
  await page.locator('#person-form').waitFor();
  check('the link opens the page of the person', page.url().endsWith(`#/person/${encodeURIComponent(person.id)}`)
    && (await page.locator('#main h1').innerText()) === personName(person), page.url());
  check('figures and explanation stay on the page', await page.locator('#h-hours').count() === 1 && await page.locator('#h-why').count() === 1);

  // Edit through the one form: weekly hours, mix, area switch for area1, each shift of area2 by hand.
  const form = page.locator('#person-form');
  await page.fill('#f-weeklyHours', '30.5');
  await form.locator('input[data-mix-cat="DAY"]').fill('80');
  await form.locator('input[data-mix-cat="NIGHT"]').fill('20');
  const switch1 = form.locator(`select[data-area="${area1}"]`);
  const switch2 = form.locator(`select[data-area="${area2}"]`);
  check('area switch labelled', (await form.locator(`label[for="f-area-${area1}"]`).innerText()) === `Ganzer Bereich ${areaName(area1)}`);
  check('area switch starts at keine', await switch1.inputValue() === 'NONE');
  await switch1.selectOption('NEVER');
  check('area switch lists one restriction per shift', await form.locator('li', { hasText: /^Nie: .*, jeden Tag/ }).count() === shiftsOf(area1).length);
  for (const s of shiftsOf(area2)) {
    await form.locator('#f-target').selectOption(`shift:${s.id}`);
    await form.locator('#f-weekday').selectOption('0');
    await form.locator('#f-kind').selectOption('ON_REQUEST');
    await form.getByRole('button', { name: 'Einschränkung hinzufügen' }).click();
  }
  check('shifts entered by hand show as the whole area', await switch2.inputValue() === 'ON_REQUEST', await switch2.inputValue());
  const requests = [];
  const listen = (r) => { if (r.method() !== 'GET' && r.url().includes('/api/dataset/')) requests.push(r); };
  page.on('request', listen);
  await form.getByRole('button', { name: 'Speichern' }).click();
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent === 'Gespeichert');
  await page.locator('#person-form').waitFor();
  page.off('request', listen);
  const paths = requests.map((r) => `${r.method()} ${new URL(r.url()).pathname}`);
  const personPath = `/api/dataset/people/${encodeURIComponent(person.id)}`;
  check('one form saves the person, then the restrictions', paths.join(',') === `PUT ${personPath},PUT ${personPath}/restrictions`, paths.join(','));
  const sent = requests[0]?.postDataJSON();
  check('master data and mix sent', sent?.weeklyHours === 30.5 && JSON.stringify(sent?.shiftMix?.categories) === '{"DAY":0.8,"NIGHT":0.2}',
    JSON.stringify([sent?.weeklyHours, sent?.shiftMix]));
  const restrictions = requests[1]?.postDataJSON() ?? [];
  const byArea = (areaId) => restrictions.filter((r) => shiftsOf(areaId).some((s) => s.id === r.shiftId));
  const shape = (r) => JSON.stringify({ ...r, shiftId: 'S', kind: 'K' });
  check('area switch: one record per shift for every day', byArea(area1).length === shiftsOf(area1).length
    && byArea(area1).every((r) => r.kind === 'NEVER' && r.weekday === 0 && r.personId === person.id), JSON.stringify(byArea(area1)));
  check('area switch records equal the ones entered by hand', byArea(area2).length === shiftsOf(area2).length
    && new Set([...byArea(area1), ...byArea(area2)].map(shape)).size === 1, [...new Set(restrictions.map(shape))].join(' '));
  const saved = await getDataset(page);
  check('saved on the server', saved.people.find((p) => p.id === person.id).weeklyHours === 30.5
    && saved.restrictions.filter((r) => r.personId === person.id).length === shiftsOf(area1).length + shiftsOf(area2).length);
  check('form shows the saved state', await form.locator(`select[data-area="${area1}"]`).inputValue() === 'NEVER'
    && await page.inputValue('#f-weeklyHours') === '30.5');

  // One shift differing: the area is "gemischt"; "keine" removes the area's restrictions.
  const first = shiftsOf(area1)[0];
  await form.getByRole('button', { name: `Einschränkung „Nie: ${first.code} ${first.name}, jeden Tag“ entfernen` }).click();
  const mixed = form.locator(`select[data-area="${area1}"]`);
  check('area switch shows gemischt', await mixed.inputValue() === 'MIXED' && (await mixed.locator('option:checked').innerText()) === 'gemischt');
  await mixed.selectOption('NONE');
  check('keine removes the restrictions of the area', await form.locator('li', { hasText: /^Nie: / }).count() === 0);

  // Create a person with the one form, then remove it.
  await page.goto(`${base}/#/person`);
  await page.getByRole('link', { name: 'Person anlegen' }).click();
  await page.locator('#person-form').waitFor();
  check('new person page', page.url().endsWith('#/person/neu') && await page.getByRole('button', { name: 'Person entfernen' }).count() === 0);
  await page.fill('#f-firstName', 'Testa');
  await page.fill('#f-lastName', 'Neuperson');
  await page.fill('#f-weeklyHours', '20');
  await page.locator(`#person-form input[name="areaIds"][value="${area1}"]`).check();
  await page.locator(`#person-form select[data-area="${area1}"]`).selectOption('ON_REQUEST');
  await page.locator('#person-form').getByRole('button', { name: 'Speichern' }).click();
  await page.waitForFunction(() => document.querySelector('#main h1')?.textContent === 'Neuperson Testa');
  const created = await getDataset(page);
  const newPerson = created.people.find((p) => p.lastName === 'Neuperson');
  check('new person saved with its restrictions', newPerson && page.url().endsWith(`#/person/${newPerson.id}`)
    && created.restrictions.filter((r) => r.personId === newPerson.id && r.kind === 'ON_REQUEST').length === shiftsOf(area1).length, page.url());
  await page.getByRole('button', { name: 'Person entfernen' }).click();
  await page.waitForURL((u) => u.hash === '#/person');
  check('person removed', !(await getDataset(page)).people.some((p) => p.lastName === 'Neuperson'));

  // Viewers: the list and the figures, no form, no personal data from the server.
  const viewer = await login('ansicht');
  await viewer.goto(`${base}/#/person`);
  await viewer.locator('#person-search').waitFor();
  check('viewer: list without Person anlegen', await viewer.getByRole('link', { name: 'Person anlegen' }).count() === 0
    && await viewer.locator('[data-table="people"] tbody tr').count() === dataset.people.length);
  await viewer.goto(`${base}/#/person/${encodeURIComponent(person.id)}`);
  await viewer.locator('#h-hours').waitFor();
  check('viewer: no edit form', await viewer.locator('#person-form').count() === 0 && await viewer.getByRole('button', { name: 'Bearbeiten' }).count() === 0
    && await viewer.getByRole('button', { name: 'Person entfernen' }).count() === 0);
  check('viewer: no hours account tile', !(await viewer.locator('#main').innerText()).includes('Stundenkonto'));
  const seen = await getDataset(viewer);
  check('viewer: no restrictions, family rules, balances, mix or external id', seen.restrictions.length === 0
    && seen.people.every((p) => !p.familyRules.length && p.hoursAccount == null && p.compRestBalance == null && p.shiftMix == null && p.externalId == null));
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('people pages checked');
