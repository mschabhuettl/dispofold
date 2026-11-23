// Playwright check of the Eingaben page: absences are shown as date ranges (one row per range of consecutive days of
// a person and type) and entered as a range, which is saved as one record per day; a range inside another one splits
// it; removing a row removes all its days; the person filter shows only that person in absences, wishes and Vorgaben.
// For beginners: links to the three sections, the save button in a bar at the bottom of the window, a new row marked
// "Hinzugefügt, noch nicht gespeichert" and scrolled to until saved, the wish count with the real limit in its
// heading, the shift of a wish only for the kinds that need one. Demo data of the dev server (mock API), month 2027-01.
//   npm run build && node scripts/check-inputs.mjs     (Chromium via `npx playwright install chromium`)
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

/** Ranges as the page should show them: consecutive days of one person with one type. */
function ranges(absences) {
  const next = (d) => new Date(Date.parse(`${d}T00:00:00Z`) + 86400000).toISOString().slice(0, 10);
  const sorted = [...absences].sort((a, b) => a.personId.localeCompare(b.personId) || a.date.localeCompare(b.date));
  const out = [];
  for (const a of sorted) {
    const last = out.at(-1);
    if (last && last.personId === a.personId && last.typeCode === a.typeCode && next(last.to) === a.date) last.to = a.date;
    else out.push({ personId: a.personId, typeCode: a.typeCode, from: a.date, to: a.date });
  }
  return out;
}

const browser = await launchBrowser();
try {
  const context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1600, height: 900 } });
  await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
  const page = await context.newPage();
  page.on('pageerror', (e) => check('no page error', false, e.message));
  page.on('dialog', (d) => d.accept());
  await page.goto(`${base}/login.html`);
  await page.fill('#j_username', 'planung');
  await page.fill('#j_password', 'x');
  await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
  const { dataset, period, rules } = await page.evaluate(async (m) => {
    await fetch('/api/import/demo', { method: 'POST' });
    return { dataset: await (await fetch('/api/dataset')).json(), period: await (await fetch(`/api/periods/${m}`)).json(),
      rules: await (await fetch('/api/rules')).json() };
  }, month);
  const name = (p) => `${p.lastName} ${p.firstName}`;
  // a person with wishes and without absences this month
  const person = dataset.people.find((p) => period.wishes.some((w) => w.personId === p.id) && !period.absences.some((a) => a.personId === p.id));

  await page.goto(`${base}/#/eingaben`);
  await page.locator('#inputs-filter').waitFor();
  const absenceRows = page.locator('[data-table="absences"] tbody tr');
  const cells = (locator) => locator.evaluateAll((trs) => trs.map((tr) => [...tr.children].map((td) => td.textContent)));
  const expected = ranges(period.absences);
  check('one row per range, not per day', await absenceRows.count() === expected.length && expected.length < period.absences.length,
    `${await absenceRows.count()} rows, ${expected.length} ranges, ${period.absences.length} days`);

  // Enter a range: shown as one row, saved as one record per day.
  const absences = page.locator('section[aria-labelledby="h-absences"]');
  const enter = async (from, to, type) => {
    await page.selectOption('#abs-person', person.id);
    await page.selectOption('#abs-from', from);
    await page.selectOption('#abs-to', to);
    await page.selectOption('#abs-type', type);
    await absences.getByRole('button', { name: 'Hinzufügen' }).click();
  };
  await enter(`${month}-10`, `${month}-14`, 'U');
  const own = async () => (await cells(absenceRows)).filter((r) => r[1] === name(person));
  let rows = await own();
  check('range entered as one row', rows.length === 1 && rows[0][0] === 'So 10.01. bis Do 14.01.' && rows[0][3] === '5', JSON.stringify(rows));
  const save = async () => (await Promise.all([
    page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith(`/api/periods/${month}`)),
    page.getByRole('button', { name: 'Speichern' }).click(),
  ]))[0].postDataJSON();
  let sent = await save();
  let mine = sent.absences.filter((a) => a.personId === person.id);
  check('saved as one record per day', mine.length === 5 && mine.every((a, i) => a.date === `${month}-${String(10 + i).padStart(2, '0')}` && a.typeCode === 'U'
    && Object.keys(a).sort().join() === 'date,personId,typeCode'), JSON.stringify(mine));
  check('other absences unchanged', sent.absences.length === period.absences.length + 5);

  // An end before the start is refused.
  await enter(`${month}-14`, `${month}-10`, 'K');
  check('end before start refused', (await page.locator('#toast span').textContent()) === 'Das Ende liegt vor dem Beginn.' && (await own()).length === 1);

  // A range inside another one splits it.
  await enter(`${month}-12`, `${month}-12`, 'K');
  rows = await own();
  check('a range inside another splits it', JSON.stringify(rows.map((r) => [r[0], r[2].split(' ')[0], r[3]]))
    === JSON.stringify([['So 10.01. bis Mo 11.01.', 'U', '2'], ['Di 12.01.', 'K', '1'], ['Mi 13.01. bis Do 14.01.', 'U', '2']]), JSON.stringify(rows));

  // The person filter: absences, wishes and Vorgaben of that person only.
  await page.selectOption('#inputs-filter', person.id);
  const personColumn = async (section) => (await cells(page.locator(`section[aria-labelledby="h-${section}"] table tbody tr`))).map((r) => r[1]);
  const shownAbsences = await personColumn('absences');
  const shownWishes = await personColumn('wishes');
  const shownFixed = await personColumn('fixed');
  check('filter: absences of the person only', shownAbsences.length === 3 && shownAbsences.every((n) => n === name(person)), shownAbsences.join(' | '));
  check('filter: wishes of the person only', shownWishes.length === period.wishes.filter((w) => w.personId === person.id).length
    && shownWishes.every((n) => n === name(person)), shownWishes.join(' | '));
  check('filter: Vorgaben of the person only', shownFixed.every((n) => n === name(person)), shownFixed.join(' | '));
  check('filter: new entries are for the person', await page.inputValue('#abs-person') === person.id && await page.inputValue('#fix-person') === person.id);

  // Removing a row removes all its days; the filter does not change what is saved.
  await absences.getByRole('button', { name: `Abwesenheit So 10.01. bis Mo 11.01. ${name(person)} entfernen` }).click();
  sent = await save();
  mine = sent.absences.filter((a) => a.personId === person.id);
  check('removing a row removes its days', JSON.stringify(mine.map((a) => a.date).sort()) === JSON.stringify([`${month}-12`, `${month}-13`, `${month}-14`]),
    JSON.stringify(mine.map((a) => a.date)));
  check('filter does not change what is saved', sent.absences.length === period.absences.length + 3 && sent.wishes.length === period.wishes.length);
  await page.selectOption('#inputs-filter', '');
  check('filter off: every range again', await absenceRows.count() === expected.length + 2, String(await absenceRows.count()));

  // Links to the three sections: each brings its heading to the top of the window
  await page.evaluate(() => window.scrollTo(0, 0));
  const jumps = await page.locator('nav.jump-links button').allTextContents();
  check('links to the sections', jumps.join() === 'Abwesenheiten,Wünsche,Vorgaben', jumps.join());
  for (const [label, id] of [['Vorgaben', 'h-fixed'], ['Wünsche', 'h-wishes'], ['Abwesenheiten', 'h-absences']]) {
    await page.locator('nav.jump-links').getByRole('button', { name: label }).click();
    // at the top of the window, or in view with the page scrolled to its end (the last section)
    const { top, end } = await page.locator(`#${id}`).evaluate((e) => ({ top: e.getBoundingClientRect().top,
      end: window.scrollY + window.innerHeight >= document.scrollingElement.scrollHeight - 1 }));
    check(`link ${label} shows its section`, top >= 0 && (top < 60 || (end && top < 700)) && page.url().endsWith('#/eingaben'),
      `${top} ${end} ${page.url()}`);
  }

  // The save bar stays at the bottom of the window, at the top of the page and at the wishes
  const bar = page.locator('.save-bar');
  const barBottom = () => bar.evaluate((b) => Math.round(b.getBoundingClientRect().bottom));
  await page.evaluate(() => window.scrollTo(0, 0));
  check('save bar with Speichern at the bottom of the window', await bar.getByRole('button', { name: 'Speichern' }).isVisible()
    && Math.abs(await barBottom() - 900) <= 1, String(await barBottom()));
  await page.locator('#h-wishes').scrollIntoViewIfNeeded();
  check('save bar still at the bottom further down', Math.abs(await barBottom() - 900) <= 1, String(await barBottom()));

  // A new row: marked and scrolled to, the note gone after saving
  const added = page.locator('tr.row-added');
  check('no row marked at first', await added.count() === 0);
  await page.evaluate(() => window.scrollTo(0, 0));
  const other = dataset.people.find((p) => p.id !== person.id && !period.absences.some((a) => a.personId === p.id && a.date === `${month}-28`));
  await page.selectOption('#abs-person', other.id);
  await page.selectOption('#abs-from', `${month}-28`);
  await page.selectOption('#abs-to', `${month}-28`);
  await absences.getByRole('button', { name: 'Hinzufügen' }).click();
  const shown = await added.evaluateAll((trs) => trs.map((tr) => {
    const r = tr.getBoundingClientRect();
    return { text: tr.textContent, inView: r.top >= 0 && r.bottom <= window.innerHeight };
  }));
  check('the new row is marked and in view', shown.length === 1 && shown[0].inView && shown[0].text.includes(name(other))
    && shown[0].text.includes('Hinzugefügt, noch nicht gespeichert'), JSON.stringify(shown));
  check('unsaved note next to the button', await page.locator('.save-bar #dirty-note').textContent() === 'Ungespeicherte Änderungen');
  await save();
  check('after saving no row is marked', await added.count() === 0 && await page.locator('#dirty-note').textContent() === '');

  // Wishes: the count with the real limit in its heading; the shift only for the kinds that need one
  const heading = await page.locator('section[aria-labelledby="h-wishes"] thead th').nth(3).textContent();
  check('wish count heading with the limit', heading === `Wünsche dieser Person (max. ${rules.maxWishesPerPerson})`, heading);
  const shiftField = page.locator('#wish-shift');
  for (const [kind, needed] of [['OFF', false], ['DUTY', false], ['SHIFT', true], ['NOT_SHIFT', true]]) {
    await page.selectOption('#wish-kind', kind);
    check(`wish ${kind}: shift ${needed ? 'to choose' : 'disabled'}`, await shiftField.isEnabled() === needed);
  }
  await page.selectOption('#wish-kind', 'OFF');
  await page.selectOption('#wish-person', other.id);
  await page.selectOption('#wish-date', `${month}-20`);
  await page.locator('section[aria-labelledby="h-wishes"]').getByRole('button', { name: 'Hinzufügen' }).click();
  const wish = await added.evaluateAll((trs) => trs.map((tr) => {
    const r = tr.getBoundingClientRect();
    return { text: tr.textContent, inView: r.top >= 0 && r.bottom <= window.innerHeight };
  }));
  check('a new wish is marked and in view', wish.length === 1 && wish[0].inView && wish[0].text.startsWith('Mi 20.01.'), JSON.stringify(wish));
  sent = await save();
  check('the wish without a shift is saved', sent.wishes.some((w) => w.personId === other.id && w.date === `${month}-20` && w.kind === 'OFF' && w.shiftId === null));
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('inputs checked');
