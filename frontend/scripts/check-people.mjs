// Playwright check of the Personen pages: the list searches by name, short name and area name and links to the page
// of each person; planners edit everything about a person in the one form there (master data, usual mix,
// per area its state (planbar, nur von Hand, nie, nicht dabei) with its exceptions folded below it, a whole area
// entered shift by shift reading as its state), create and remove a person; the matrix of all people and areas
// (click, keyboard, several at once, filter, save, read only for viewers); the switch "Aus dem Quellsystem nicht mehr
// übernehmen" (also for an exclusion the source applied) greys the person out in the list and is in the corrections
// file until taken back; viewers get no form, no switch, no "Person anlegen" and no personal data from the server. Demo data of the dev server (mock API), month 2027-01 planned.
//   npm run build && node scripts/check-people.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { launchBrowser } from './browser.mjs';

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

const browser = await launchBrowser();
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

  // Edit through the one form: weekly hours, mix, "nie" for area1, exceptions for a shift of area2 on two weekdays.
  const form = page.locator('#person-form');
  await page.fill('#f-weeklyHours', '30.5');
  await form.locator('input[data-mix-cat="DAY"]').fill('80');
  await form.locator('input[data-mix-cat="NIGHT"]').fill('20');
  const state1 = form.locator(`#f-area-${area1}`);
  const state2 = form.locator(`#f-area-${area2}`);
  check('each area has a state, labelled with its name', (await form.locator(`label[for="f-area-${area1}"]`).innerText()) === `${areaName(area1)} (${area1})`
    && await state1.inputValue() === 'PLAN', await state1.inputValue());
  check('the states in plain words', (await state1.locator('option').allInnerTexts()).join('|') === 'nicht dabei|planbar|nur von Hand|nie');
  await state1.selectOption('NEVER');
  const shift2 = shiftsOf(area2)[0];
  for (const day of ['1', '2']) {
    await form.locator('#f-target').selectOption(`shift:${shift2.id}`);
    await form.locator('#f-weekday').selectOption(day);
    await form.locator('#f-kind').selectOption('ON_REQUEST');
    await form.getByRole('button', { name: 'Einschränkung hinzufügen' }).click();
  }
  const lines2 = await form.locator(`[data-area="${area2}"] ul.area-exceptions li`).allInnerTexts();
  check('the exceptions of an area below it, one line per shift with the weekdays folded in',
    lines2.length === 1 && lines2[0].startsWith(`${shift2.code} ${shift2.name}: nur von Hand (Mo, Di)`), lines2.join(' / '));
  const requests = [];
  const listen = (r) => { if (r.method() !== 'GET' && r.url().includes('/api/dataset/')) requests.push(r); };
  page.on('request', listen);
  await form.getByRole('button', { name: 'Speichern' }).click();
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent === 'Gespeichert');
  await page.locator('#person-form').waitFor();
  page.off('request', listen);
  const paths = requests.map((r) => `${r.method()} ${new URL(r.url()).pathname}`);
  const personPath = `/api/dataset/people/${encodeURIComponent(person.id)}`;
  check('one form saves the person, then the exceptions', paths.join(',') === `PUT ${personPath},PUT ${personPath}/restrictions`, paths.join(','));
  const sent = requests[0]?.postDataJSON();
  check('master data, mix and the states sent', sent?.weeklyHours === 30.5 && JSON.stringify(sent?.shiftMix?.categories) === '{"DAY":0.8,"NIGHT":0.2}'
    && JSON.stringify(sent?.areaStates) === JSON.stringify({ [area1]: 'NEVER' }), JSON.stringify([sent?.weeklyHours, sent?.shiftMix, sent?.areaStates]));
  check('only the exceptions sent as restrictions', (requests[1]?.postDataJSON() ?? []).length === 2);
  let saved = await getDataset(page);
  const mine = (d) => d.people.find((p) => p.id === person.id);
  check('saved on the server: the state, the exceptions apart', mine(saved).weeklyHours === 30.5 && mine(saved).areaStates?.[area1] === 'NEVER'
    && saved.restrictions.filter((r) => r.personId === person.id).length === 2, JSON.stringify([mine(saved).areaStates, saved.restrictions.filter((r) => r.personId === person.id)]));
  check('form shows the saved state', await form.locator(`#f-area-${area1}`).inputValue() === 'NEVER' && await page.inputValue('#f-weeklyHours') === '30.5');

  // Every shift of area2 "nur von Hand" on every day, entered as exceptions: read back as the state of the area.
  for (const s of shiftsOf(area2)) {
    await form.locator('#f-target').selectOption(`shift:${s.id}`);
    await form.locator('#f-weekday').selectOption('0');
    await form.locator('#f-kind').selectOption('ON_REQUEST');
    await form.getByRole('button', { name: 'Einschränkung hinzufügen' }).click();
  }
  await page.evaluate(() => { const x = document.querySelector('#toast'); if (x) x.textContent = ''; }); // the toast of the save before
  await form.getByRole('button', { name: 'Speichern' }).click();
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent === 'Gespeichert');
  // the page draws itself anew after the save
  await page.waitForFunction((id) => document.querySelector(`#f-area-${id}`)?.value === 'ON_REQUEST', area2, { timeout: 5000 }).catch(() => {});
  check('a whole area entered shift by shift reads as its state', await page.locator(`#f-area-${area2}`).inputValue() === 'ON_REQUEST',
    await page.locator(`#f-area-${area2}`).inputValue());
  // planbar again for both: the states and their rows go, the weekday exceptions stay
  await page.waitForTimeout(300);
  await page.locator(`#f-area-${area1}`).selectOption('PLAN');
  await page.locator(`#f-area-${area2}`).selectOption('PLAN');
  await page.evaluate(() => { const x = document.querySelector('#toast'); if (x) x.textContent = ''; }); // the toast of the save before
  await page.locator('#person-form').getByRole('button', { name: 'Speichern' }).click();
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent === 'Gespeichert');
  await page.waitForTimeout(300);
  saved = await getDataset(page);
  check('planbar again: no state, the weekday exceptions stay', !Object.keys(mine(saved).areaStates ?? {}).length
    && saved.restrictions.filter((r) => r.personId === person.id).length === 2, JSON.stringify(saved.restrictions.filter((r) => r.personId === person.id)));

  // "Aus dem Quellsystem nicht mehr übernehmen": saved at once; the list greys the person out, the corrections file
  // excludes them; switched off, the file no longer does.
  const other = dataset.people.find((p) => p.id !== person.id);
  const otherPage = `${base}/#/person/${encodeURIComponent(other.id)}`;
  const corrections = () => page.evaluate(async () => (await fetch('/api/data/corrections')).json());
  const toastShown = (text) => page.waitForFunction((x) => document.querySelector('#toast')?.textContent === x, text);
  await page.goto(otherPage);
  await page.locator('#f-excluded').waitFor();
  check('switch off for a person taken over', !(await page.isChecked('#f-excluded'))
    && (await page.locator('label', { has: page.locator('#f-excluded') }).innerText()).includes('Aus dem Quellsystem nicht mehr übernehmen'));
  const switched = [];
  const onSwitch = (r) => { if (r.method() === 'PUT' && r.url().endsWith('/excluded')) switched.push(r.postData()); };
  page.on('request', onSwitch);
  await page.locator('#f-excluded').click();
  await toastShown('Wird nicht mehr übernommen');
  await page.locator('#f-excluded').waitFor();
  let excluded = (await getDataset(page)).people.find((p) => p.id === other.id);
  check('switch on: saved at once, from the first day of a month', switched.join() === 'true' && /^\d{4}-\d{2}-01$/.test(excluded.excludedFrom ?? ''),
    `${switched.join()} ${excluded.excludedFrom}`);
  check('switch shows on, the page says so', await page.isChecked('#f-excluded')
    && await page.getByText('Wird nicht mehr aus dem Quellsystem übernommen').isVisible());
  check('corrections file excludes the person', JSON.stringify((await corrections()).file.people[other.id]) === '{"exclude":true}');
  await page.goto(`${base}/#/person`);
  await page.locator('#person-search').waitFor();
  const greyed = page.locator('[data-table="people"] tbody tr[data-excluded="true"]');
  check('list greys the person out with a note', await greyed.count() === 1 && (await greyed.getAttribute('class')).includes('muted')
    && (await greyed.innerText()).includes(`${personName(other)} nicht mehr übernommen`), await greyed.count());
  check('list counts the person apart', await page.getByText(`${dataset.people.length} Personen, davon 1 nicht mehr aus dem Quellsystem übernommen`).isVisible());
  await page.goto(otherPage);
  await page.locator('#f-excluded').waitFor();
  await page.locator('#f-excluded').click();
  await toastShown('Wird wieder übernommen');
  excluded = (await getDataset(page)).people.find((p) => p.id === other.id);
  check('switch off: taken back', switched.join() === 'true,false' && !('excludedFrom' in excluded) && !(await page.isChecked('#f-excluded')));
  check('corrections file no longer excludes the person', !((await corrections()).file.people[other.id]));
  // an exclusion the source applied (the import leaves the person out and lists it): shown on, taken back the same way
  const third = dataset.people.find((p) => p.id !== person.id && p.id !== other.id);
  await page.evaluate(async (id) => {
    const d = await (await fetch('/api/dataset')).json();
    const upload = { ...d, people: d.people.filter((p) => p.id !== id), restrictions: d.restrictions.filter((r) => r.personId !== id),
      appliedCorrections: { format: 'dispofold/corrections', version: 2, people: { [id]: { exclude: true } }, demand: {} } };
    await fetch('/api/import/dataset?replace=true', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(upload) });
  }, third.id);
  await page.goto(`${base}/#/person/${encodeURIComponent(third.id)}`);
  await page.locator('#f-excluded').waitFor();
  check('an applied exclusion shows as on', await page.isChecked('#f-excluded'));
  await page.locator('#f-excluded').click();
  await toastShown('Wird wieder übernommen');
  const after = await corrections();
  check('an applied exclusion is taken back', !after.file.people[third.id] && after.withdrawn === 1, JSON.stringify([after.file.people[third.id], after.withdrawn]));
  page.off('request', onSwitch);

  // Create a person with the one form, then remove it.
  await page.goto(`${base}/#/person`);
  await page.getByRole('link', { name: 'Person anlegen' }).click();
  await page.locator('#person-form').waitFor();
  check('new person page', page.url().endsWith('#/person/neu') && await page.getByRole('button', { name: 'Person entfernen' }).count() === 0);
  await page.fill('#f-firstName', 'Testa');
  await page.fill('#f-lastName', 'Neuperson');
  await page.fill('#f-weeklyHours', '20');
  await page.locator(`#f-area-${area1}`).selectOption('ON_REQUEST');
  await page.locator('#person-form').getByRole('button', { name: 'Speichern' }).click();
  await page.waitForFunction(() => document.querySelector('#main h1')?.textContent === 'Neuperson Testa');
  const created = await getDataset(page);
  const newPerson = created.people.find((p) => p.lastName === 'Neuperson');
  check('new person saved with the state of its area', newPerson && page.url().endsWith(`#/person/${newPerson.id}`)
    && newPerson.areaStates?.[area1] === 'ON_REQUEST' && JSON.stringify(newPerson.areaIds) === JSON.stringify([area1]), page.url());
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
  check('viewer: no switch for the source', await viewer.locator('#f-excluded').count() === 0 && await viewer.locator('#h-source').count() === 0);
  const seen = await getDataset(viewer);
  check('viewer: no restrictions, family rules, balances, mix, external id or area sources', seen.restrictions.length === 0
    && seen.people.every((p) => !p.familyRules.length && p.hoursAccount == null && p.compRestBalance == null && p.shiftMix == null && p.externalId == null
      && p.areaSources == null));
  // next to each ticked area: where it comes from and whether it is on request; an import gives two people areaSources
  // (only an import writes them): the second area of the first a reserve all on request, that of the second set by hand
  // and partly on request
  const current = await getDataset(page);
  const [one, two] = current.people.filter((p) => p.excludedFrom == null).slice(0, 2);
  const [a1, a2] = current.areas.map((a) => a.id);
  const shiftsIn = (a) => current.shifts.filter((sh) => sh.areaId === a).map((sh) => sh.id);
  const people = current.people.map((p) => (p.id === one.id ? { ...p, areaIds: [a1, a2], areaSources: { [a1]: 'Gruppe Team Annahme; Historie: 14 Dienste', [a2]: 'Gruppe Alle' } }
    : p.id === two.id ? { ...p, areaIds: [a1, a2], areaSources: { [a1]: 'Historie: 22 Dienste' } } : p));
  const withRequest = [...current.restrictions.filter((r) => r.personId !== one.id && r.personId !== two.id),
    ...shiftsIn(a2).map((shiftId) => ({ personId: one.id, shiftId, weekday: 0, kind: 'ON_REQUEST' })),
    { personId: two.id, shiftId: shiftsIn(a2)[0], weekday: 0, kind: 'ON_REQUEST' }];
  const imported = await page.evaluate(async (body) => (await fetch('/api/import/dataset?replace=true', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body) })).status, { ...current, version: 2, people, restrictions: withRequest });
  check('a dataset with area sources is imported', imported === 200, String(imported));
  const areaInfo = async (id) => {
    await page.goto(`${base}/#/person/${encodeURIComponent(id)}`);
    await page.locator('#person-form').waitFor();
    return page.$$eval('#person-areas [data-area]', (rows) => Object.fromEntries(rows.map((r) => [r.dataset.area,
      [r.querySelector('select').value, r.querySelector('[data-area-source]').textContent, [...r.querySelectorAll('li')].map((li) => li.firstChild.textContent.trim())]])));
  };
  const reserve = await areaInfo(one.id);
  check('an area from a group and the history; a reserve area as its state', JSON.stringify(reserve[a1]) === JSON.stringify(['PLAN', 'Herkunft: Gruppe Team Annahme; Historie: 14 Dienste', []])
    && JSON.stringify(reserve[a2]) === JSON.stringify(['ON_REQUEST', 'Herkunft: Gruppe Alle', []]), JSON.stringify(reserve));
  if (process.env.SHOTS) {
    await page.locator('#person-areas').screenshot({ path: join(process.env.SHOTS, 'person-bereiche.png') });
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.locator('#person-areas').screenshot({ path: join(process.env.SHOTS, 'person-bereiche-dunkel.png') });
    await page.emulateMedia({ colorScheme: 'light' });
  }
  const byHand = await areaInfo(two.id);
  const firstShift = current.shifts.find((sh) => sh.id === shiftsIn(a2)[0]);
  check('an area set by hand, planbar, with one shift as an exception', byHand[a1][1] === 'Herkunft: Historie: 22 Dienste' && byHand[a2][0] === 'PLAN'
    && byHand[a2][1] === 'Herkunft: von Hand' && byHand[a2][2].join() === `${firstShift.code} ${firstShift.name}: nur von Hand`, JSON.stringify(byHand));

  // ---- the matrix of all people and areas ----
  await page.goto(`${base}/#/bereiche`);
  await page.locator('table.area-matrix').waitFor();
  const cells = await page.locator('table.area-matrix button[data-person]').count();
  check('one cell per person and area', cells === current.people.length * current.areas.length, String(cells));
  const cellOf = (pid, aid) => page.locator(`table.area-matrix button[data-person="${pid}"][data-area="${aid}"]`);
  check('a cell shows its state as a sign and a word', (await cellOf(one.id, a2).innerText()).includes('nur von Hand')
    && (await cellOf(one.id, a1).innerText()).includes('planbar'), await cellOf(one.id, a2).innerText());
  check('a mark for exceptions', await cellOf(two.id, a2).locator('sup').count() === 1);
  await cellOf(two.id, a2).focus();
  check('the source and the exceptions of the focused cell below the table', (await page.textContent('#area-matrix-status')).includes('Herkunft: von Hand')
    && (await page.textContent('#area-matrix-status')).includes('Ausnahmen:'), await page.textContent('#area-matrix-status'));
  await cellOf(one.id, a1).click();
  check('a click cycles planbar → nur von Hand', (await cellOf(one.id, a1).innerText()).includes('nur von Hand') && await page.isVisible('#area-matrix-dirty'));
  await page.keyboard.press('Space');
  check('the space key cycles on → nie', (await cellOf(one.id, a1).innerText()).includes('nie'));
  await page.keyboard.press('ArrowRight');
  check('the arrow keys move between cells', (await page.evaluate(() => document.activeElement?.dataset.area)) === a2);
  // several at once: the column a1 for the people found by name
  await page.fill('#area-matrix-name', two.lastName);
  check('the name filter', (await page.textContent('#area-matrix-count')).startsWith('1 von'), await page.textContent('#area-matrix-count'));
  await page.fill('#area-matrix-name', '');
  await page.locator(`th[data-col-area="${a1}"] input`).check();
  await page.locator(`tr[data-person-row="${one.id}"] th input`).check();
  await page.locator(`tr[data-person-row="${two.id}"] th input`).check();
  await page.selectOption('#area-matrix-bulk-state', 'ON_REQUEST');
  check('the bulk button says how many cells', (await page.textContent('#area-matrix-bulk')) === '2 Zellen setzen', await page.textContent('#area-matrix-bulk'));
  await page.click('#area-matrix-bulk');
  if (process.env.SHOTS) {
    await page.locator('#main').screenshot({ path: join(process.env.SHOTS, 'bereiche-matrix.png') });
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.locator('#main').screenshot({ path: join(process.env.SHOTS, 'bereiche-matrix-dunkel.png') });
    await page.emulateMedia({ colorScheme: 'light' });
  }
  await page.click('#area-matrix-save');
  await page.waitForFunction(() => document.querySelector('#toast')?.textContent.includes('gespeichert'));
  const afterMatrix = await getDataset(page);
  const stateIn = (d, pid, aid) => d.people.find((p) => p.id === pid).areaStates?.[aid];
  check('the matrix saves like the person page', stateIn(afterMatrix, one.id, a1) === 'ON_REQUEST' && stateIn(afterMatrix, two.id, a1) === 'ON_REQUEST'
    && stateIn(afterMatrix, one.id, a2) === 'ON_REQUEST', JSON.stringify(afterMatrix.people.filter((p) => [one.id, two.id].includes(p.id)).map((p) => p.areaStates)));
  // with the area filter: the bulk change and the arrow keys stay inside the shown column
  await page.locator('table.area-matrix').waitFor();
  await page.selectOption('#area-matrix-area', a2);
  await cellOf(one.id, a2).focus();
  await page.keyboard.press('ArrowRight');
  check('the arrow keys do not leave the shown column', (await page.evaluate(() => document.activeElement?.dataset.area)) === a2);
  const beforeA1 = await cellOf(two.id, a1).innerText();
  await page.check('#area-matrix-all-rows');
  await page.selectOption('#area-matrix-bulk-state', 'NEVER');
  await page.click('#area-matrix-bulk');
  check('the bulk change only sets the shown column', (await cellOf(two.id, a2).innerText()).includes('nie')
    && (await cellOf(two.id, a1).innerText()) === beforeA1, await cellOf(two.id, a1).innerText());
  await page.goto(`${base}/#/person`); // leaving asks about the unsaved changes; the dialog is accepted
  const matrixViewer = await login('ansicht');
  await matrixViewer.goto(`${base}/#/bereiche`);
  await matrixViewer.locator('table.area-matrix').waitFor();
  check('viewers see the matrix read only', await matrixViewer.locator('table.area-matrix button').count() === 0
    && await matrixViewer.locator('#area-matrix-save').count() === 0 && (await matrixViewer.locator('table.area-matrix td').first().innerText()).trim().length > 0);
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('people pages checked');
