// Playwright check of "Regeln & Gewichte" and the person page. Rules: three sections (Planungsziele, Gesetz und
// Betriebsvereinbarung, Erweitert collapsed with every weight); the night setting round-trips and warns in HABIT
// when the usual distribution has weight 0; a Wichtigkeit sets the group's weights to 0.5 / 1 / 2 x default, a weight
// set by hand shows "angepasst"; the law fields are locked until "Ändern"; fields the page does not know are saved
// unchanged. The save bar stays in view while scrolling, an edit marks the page unsaved and leaving it asks (dismiss
// stays, accept leaves), weights are number fields with their default and a reset to it, and a rule text takes its
// number from the parameter field. Person: one hour tile "x h von Ziel y h (Soll z h)" (viewers without Ziel) and
// "Warum dieser Dienst?" in words, without scores.
// Demo data of the dev server (mock API, with the constraint catalogue of the backend). With an output folder as argument, it also saves screenshots of both pages.
//   npm run build && node scripts/check-rules-person.mjs [output folder]     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const month = '2027-01';
const out = process.argv[2] ?? null;
const port = await new Promise((resolve) => {
  const s = createServer().listen(0, () => { const p = s.address().port; s.close(() => resolve(p)); });
});
const server = spawn(process.execPath, [join(root, 'scripts', 'dev-server.mjs'), String(port)], { stdio: ['ignore', 'pipe', 'inherit'] });
await new Promise((resolve) => server.stdout.on('data', (d) => { if (String(d).includes('dev server on')) resolve(); }));
const base = `http://localhost:${port}`;
if (out) mkdirSync(out, { recursive: true });

/** The quality rules of the backend catalogue (ConstraintCatalog) with their default weights. */
let failed = false;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);
  if (!ok) failed = true;
};

const browser = await chromium.launch();
try {
  const login = async (user) => {
    const context = await browser.newContext({ locale: 'de-DE', viewport: { width: 1600, height: 1000 }, colorScheme: 'light' });
    await context.addInitScript((m) => sessionStorage.setItem('month', m), month);
    const page = await context.newPage();
    page.on('pageerror', (e) => check('no page error', false, e.message));
    await page.goto(`${base}/login.html`);
    await page.fill('#j_username', user);
    await page.fill('#j_password', 'x');
    await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
    return page;
  };
  const page = await login('planung');
  const personId = await page.evaluate(async (m) => {
    await fetch('/api/import/demo', { method: 'POST' });
    await fetch(`/api/plans/${m}/solve`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: '{"seed":1,"timeLimitSeconds":1}' });
    return (await (await fetch('/api/dataset')).json()).people[0].id;
  }, month);
  await page.waitForTimeout(1500);

  const isPut = (r) => r.request().method() === 'PUT' && r.url().endsWith('/api/rules');
  const bar = page.locator('.save-bar');
  const save = () => bar.locator('button.btn-primary').click();
  /** The body of the PUT /api/rules that a save sends, once the page has taken the answer. */
  const putBody = async () => {
    const [response] = await Promise.all([page.waitForResponse(isPut), save()]);
    await page.waitForFunction(() => !document.querySelector('.save-bar').textContent.includes('Nicht gespeichert'));
    return response.request().postDataJSON();
  };
  const importance = (group, label) => page.locator(`.goal[data-group="${group}"] label`).filter({ hasText: new RegExp(`^${label}$`) });
  const checkedLevel = (group) => page.locator(`.goal[data-group="${group}"] .importance input:checked`).getAttribute('value');
  const nightMode = () => page.locator('input[name=nightDistribution]:checked').getAttribute('value');
  const advanced = page.locator('details.rules-advanced');
  /** Opens the rules page, loaded again from the server; with unlock, also the law fields. */
  const openRules = async (unlock = false) => {
    if (page.url().endsWith('#/regeln')) await page.reload();
    else await page.goto(`${base}/#/regeln`);
    await page.locator('#h-goals').waitFor();
    if (unlock) await page.getByRole('button', { name: 'Ändern' }).click();
  };

  // Rules saved by an older version: no night setting, and a field this version does not know.
  await page.evaluate(async () => {
    const { nightDistribution, ...old } = await (await fetch('/api/rules')).json();
    await fetch('/api/rules', { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...old, futureSetting: 'x' }) });
  });
  await openRules();
  check('three sections', await page.locator('#h-goals').textContent() === 'Planungsziele'
    && await page.locator('#h-law').textContent() === 'Gesetz und Betriebsvereinbarung' && await page.locator('#h-advanced').textContent() === 'Erweitert');
  check('Erweitert is collapsed', !(await advanced.evaluate((d) => d.open)) && !(await page.locator('.rule-row').first().isVisible()));
  check('old rules show the night setting HABIT', await nightMode() === 'HABIT');
  const levels = await Promise.all(['blocks', 'wishes', 'fairness', 'hours', 'family'].map(checkedLevel));
  check('every goal has a Wichtigkeit at normal', levels.every((l) => l === 'normal'), levels.join());
  check('no "angepasst" at default weights', await page.locator('.importance label[data-level="custom"]:visible').count() === 0);
  if (out) await page.screenshot({ path: join(out, 'rules.png') });

  // Law and works agreement: text, fields locked until "Ändern".
  const lawText = page.locator('.law-text');
  check('law values as text', (await lawText.textContent()).includes('täglich mindestens 11 Stunden'), await lawText.textContent());
  check('law fields locked', !(await page.locator('#param-minDailyRestHours').isVisible()) && !(await page.locator('#param-maxWeekHours').isVisible()));
  const change = page.getByRole('button', { name: 'Ändern' });
  check('Ändern is collapsed', await change.getAttribute('aria-expanded') === 'false');
  await change.click();
  check('Ändern unlocks the law fields', await page.locator('#param-minDailyRestHours').isEditable() && await page.locator('#param-averagingWeeks').isVisible()
    && await page.locator('#param-minFreeSundays').isVisible() && !(await lawText.isVisible()));
  check('the first law field has the focus', await page.evaluate(() => document.activeElement?.id) === 'param-minDailyRestHours');
  if (out) {
    await page.locator('#h-law').evaluate((el) => el.scrollIntoView());
    await page.screenshot({ path: join(out, 'rules-law.png') });
  }

  // Erweitert: every tunable weight with its default and reset, the night window, the rule catalogue.
  await advanced.locator('summary').click();
  const catalogue = await page.evaluate(async () => (await (await fetch('/api/constraints')).json()));
  const tunable = catalogue.filter((c) => c.tunable).map((c) => c.id);
  const inAdvanced = await advanced.locator('input[id^="w-"]').evaluateAll((els) => els.map((e) => e.id.slice(2)));
  check('Erweitert has every weight', tunable.length > 10 && tunable.every((id) => inAdvanced.includes(id)), inAdvanced.join(' '));
  check('Erweitert has the night window', await advanced.locator('#param-nightStart').isVisible() && await advanced.locator('#param-nightMinHours').isVisible());
  check('Erweitert lists every rule', await advanced.locator('.rule-row').count() === catalogue.length);
  if (out) {
    await page.locator('#h-advanced').evaluate((el) => el.scrollIntoView());
    await page.screenshot({ path: join(out, 'rules-advanced.png') });
  }

  // The save bar stays in view at the bottom and at the top of the page.
  const inView = () => bar.evaluate((el) => { const r = el.getBoundingClientRect(); return r.top >= 0 && r.bottom <= innerHeight && r.height > 0; });
  check('rules page scrolls', await page.evaluate(() => document.scrollingElement.scrollHeight > innerHeight + 200));
  await page.evaluate(() => scrollTo(0, document.scrollingElement.scrollHeight));
  check('save bar in view at the bottom', await inView());
  await page.evaluate(() => scrollTo(0, 0));
  check('save bar in view at the top', await inView());
  check('save bar not marked unsaved at first', (await bar.textContent()).includes('Nicht gespeichert') === false);

  // Weights: number fields with their default and a reset, no sliders.
  check('no weight sliders', await page.locator('input[type=range]').count() === 0);
  const weightRow = page.locator('.rule-row:has(input[id^="w-"])').first();
  const weight = weightRow.locator('input[id^="w-"]');
  const defaultText = await weightRow.locator('.weight .muted').textContent();
  const defaultWeight = defaultText.match(/Standard (\d+)/)?.[1];
  check('weight is a number field', await weight.getAttribute('type') === 'number');
  check('weight shows its default', Boolean(defaultWeight), defaultText);
  await weight.fill(String(Number(defaultWeight) + 7));
  check('an edit marks the page unsaved', (await bar.textContent()).includes('Nicht gespeichert'), await bar.textContent());
  if (out) {
    await weightRow.evaluate((el) => el.scrollIntoView({ block: 'center' }));
    await page.screenshot({ path: join(out, 'rules-weights.png') });
  }
  await weightRow.getByRole('button', { name: /auf Standard .* zurücksetzen/ }).click();
  check('reset restores the default weight', await weight.inputValue() === defaultWeight, await weight.inputValue());

  // A rule text takes its number from the parameter field.
  const restRow = page.locator('.rule-row').filter({ hasText: 'Tägliche Ruhezeit' }).first();
  check('rest rule names the stored hours', (await restRow.textContent()).includes('mindestens 11 Stunden'), await restRow.textContent());
  await page.fill('#param-minDailyRestHours', '9.5');
  check('rest rule follows the field', (await restRow.textContent()).includes('mindestens 9,5 Stunden'), await restRow.textContent());

  // Leaving with unsaved changes asks: dismissing stays on the page, accepting leaves it.
  const dialogs = [];
  page.once('dialog', (d) => { dialogs.push(d.message()); d.dismiss(); });
  await page.click('#nav a[data-group="people"]');
  await page.waitForTimeout(300);
  check('leaving asks', dialogs.length === 1, dialogs.join());
  check('dismiss stays on the rules page', page.url().endsWith('#/regeln') && await page.locator('#h-goals').count() > 0, page.url());
  check('dismiss keeps the edit', await page.inputValue('#param-minDailyRestHours') === '9.5');
  page.once('dialog', (d) => { dialogs.push(d.message()); d.accept(); });
  await page.click('#nav a[data-group="people"]');
  await page.locator('#person-search').waitFor();
  check('accept leaves the rules page', dialogs.length === 2 && page.url().endsWith('#/person'), page.url());
  // the edit was not saved, and a page without edits leaves without asking
  await openRules();
  check('discarded edit not saved', await page.inputValue('#param-minDailyRestHours') === '11');
  page.once('dialog', (d) => { dialogs.push(d.message()); d.accept(); });
  await page.click('#nav a[data-group="inputs"]');
  await page.waitForURL((u) => u.hash === '#/eingaben');
  check('no question without edits', dialogs.length === 2);

  // Wichtigkeit: weniger / normal / mehr = 0.5 / 1 / 2 x the default weights of the group (fairness: 0.25 / 1 / 4), on save.
  await openRules();
  const defaults = Object.fromEntries(catalogue.map((c) => [c.id, c.defaultWeight]));
  await importance('blocks', 'mehr').click();
  await importance('fairness', 'weniger').click();
  check('Wichtigkeit marks the page unsaved', (await bar.textContent()).includes('Nicht gespeichert'));
  check('Wichtigkeit fills the weight fields', await page.inputValue('#w-blockLength') === String(defaults.blockLength * 2), await page.inputValue('#w-blockLength'));
  const first = await putBody();
  const w = first.weights;
  check('mehr doubles the group weights', w.blockLength === defaults.blockLength * 2 && w.isolatedDayOff === defaults.isolatedDayOff * 2, JSON.stringify(w));
  check('weniger quarters the fairness weights', ['fairNights', 'fairWeekends', 'fairHolidays', 'fairUnpopular'].every((id) => w[id] === Math.round(defaults[id] * 0.25)), JSON.stringify(w));
  check('other weights keep their default', ['targetHours', 'wishes', 'family', 'shiftMix', 'forwardRotation'].every((id) => w[id] === defaults[id]), JSON.stringify(w));
  check('old rules save HABIT', first.nightDistribution === 'HABIT', first.nightDistribution);
  check('unknown fields saved unchanged', first.futureSetting === 'x', JSON.stringify(first).slice(0, 200));
  await openRules();
  check('levels shown after loading', await checkedLevel('blocks') === 'more' && await checkedLevel('fairness') === 'less' && await checkedLevel('hours') === 'normal');
  await importance('fairness', 'mehr').click();
  const more = (await putBody()).weights;
  check('mehr quadruples the fairness weights', ['fairNights', 'fairWeekends', 'fairHolidays', 'fairUnpopular'].every((id) => more[id] === defaults[id] * 4), JSON.stringify(more));
  await openRules();
  check('fairness mehr shown after loading', await checkedLevel('fairness') === 'more');
  await importance('blocks', 'normal').click();
  await importance('fairness', 'normal').click();
  const normal = (await putBody()).weights;
  check('normal restores the defaults', ['blockLength', 'isolatedDayOff', 'fairNights', 'fairUnpopular'].every((id) => normal[id] === defaults[id]), JSON.stringify(normal));

  // A weight set by hand shows "angepasst"; choosing a level overwrites it.
  await advanced.locator('summary').click();
  await page.fill('#w-wishes', String(defaults.wishes + 50));
  const custom = page.locator('.goal[data-group="wishes"] label[data-level="custom"]');
  check('a hand-set weight shows angepasst', await custom.isVisible() && await checkedLevel('wishes') === 'custom');
  if (out) {
    await page.locator('.goal[data-group="wishes"]').evaluate((el) => el.scrollIntoView({ block: 'center' }));
    await page.screenshot({ path: join(out, 'rules-custom.png') });
  }
  check('angepasst is saved as set', (await putBody()).weights.wishes === defaults.wishes + 50);
  await openRules();
  check('angepasst after loading', await checkedLevel('wishes') === 'custom');
  await importance('wishes', 'mehr').click();
  check('a level overwrites the hand-set weight', await page.inputValue('#w-wishes') === String(defaults.wishes * 2) && !(await custom.isVisible()));
  await importance('wishes', 'normal').click();

  // The night setting round-trips; HABIT with weight 0 for the usual distribution warns.
  const warning = page.locator('.goal .alert');
  check('no night warning at first', await warning.count() === 0);
  await advanced.locator('summary').click();
  await page.fill('#w-shiftMix', '0');
  check('weight 0 in HABIT warns', await warning.count() === 1 && (await warning.textContent()).includes('keine Regel für Nachtdienste'));
  if (out) {
    await page.evaluate(() => scrollTo(0, 0));
    await page.screenshot({ path: join(out, 'rules-night-warning.png') });
  }
  await page.getByLabel('Gleichmäßig: Nachtdienste fair auf alle verteilen').check();
  check('no warning in EVEN', await warning.count() === 0);
  const even = await putBody();
  check('the night setting is saved', even.nightDistribution === 'EVEN' && even.weights.shiftMix === 0, even.nightDistribution);
  await openRules();
  check('the night setting is loaded', await nightMode() === 'EVEN');
  await page.getByLabel('Wie bisher: jede Person behält ihren gewohnten Nachtanteil').check();
  check('back to HABIT warns again', await warning.isVisible());
  await advanced.locator('summary').click();
  await page.locator('.rule-row:has(#w-shiftMix)').getByRole('button', { name: /zurücksetzen/ }).click();
  check('reset of the weight ends the warning', await warning.count() === 0);
  check('HABIT is saved', (await putBody()).nightDistribution === 'HABIT');

  // An edit made while a save is under way stays unsaved.
  await openRules(true);
  await page.route('**/api/rules', async (route) => {
    if (route.request().method() === 'PUT') await new Promise((r) => setTimeout(r, 500));
    await route.continue();
  });
  await page.fill('#param-minDailyRestHours', '12');
  const slowSave = page.waitForResponse(isPut);
  await save();
  await page.fill('#param-minDailyRestHours', '13');
  await slowSave;
  await page.waitForTimeout(100);
  check('an edit during the save stays unsaved', (await bar.textContent()).includes('Nicht gespeichert'));
  await page.unroute('**/api/rules');
  // after saving there is nothing to ask either
  await page.fill('#param-minDailyRestHours', '11');
  await Promise.all([page.waitForResponse(isPut), save()]);
  await page.waitForTimeout(100);
  check('saving clears the note', !(await bar.textContent()).includes('Nicht gespeichert'));
  await page.click('#nav a[data-group="inputs"]');
  await page.waitForURL((u) => u.hash === '#/eingaben');
  check('no question after saving', dialogs.length === 2, dialogs.join(' | '));

  // Person page: one hour tile, no scores in "Warum dieser Dienst?".
  await page.goto(`${base}/#/person/${encodeURIComponent(personId)}`);
  await page.locator('#h-why').waitFor();
  const tiles = page.locator('.kpi .value').filter({ hasText: /Soll|Ziel/ });
  check('one hour tile with Soll and Ziel', await tiles.count() === 1, String(await tiles.count()));
  const tile = (await tiles.first().textContent()).trim();
  check('hour tile format', /^\d+(,\d)? h \(davon \d+(,\d)? h angerechnet\) von Ziel \d+(,\d)? h \(Soll \d+(,\d)? h\)$/.test(tile), tile);
  // the first figure is what the rule compares with the Ziel: planned plus credited hours
  const stats = await page.evaluate(async ([m, id]) => (await (await fetch(`/api/plans/${m}/people/${encodeURIComponent(id)}`)).json()).stats, [month, personId]);
  const num = (v) => v.toLocaleString('de-AT', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  check('hour tile: planned plus credited, then credited', tile.startsWith(`${num(stats.plannedHours + stats.creditedHours)} h (davon ${num(stats.creditedHours)} h angerechnet)`),
    `${tile} / ${stats.plannedHours} + ${stats.creditedHours}`);
  check('no separate tile for credited hours', await page.locator('.kpi .label').filter({ hasText: /^Angerechnet/ }).count() === 0);
  check('no tile is labelled Soll or Ziel', await page.locator('.kpi .label').filter({ hasText: /^(Soll|Ziel)/ }).count() === 0);
  const info = page.getByRole('button', { name: 'Was bedeuten Soll und Ziel?' });
  await info.click();
  check('the explanation opens by keyboard or click', await info.getAttribute('aria-expanded') === 'true' && await page.locator('#hours-help').isVisible());
  const why = page.locator('section[aria-labelledby="h-why"]');
  const whyText = await why.textContent();
  check('hits are listed', await why.locator('.hit').count() > 0);
  check('no raw score strings', !/-?\d+hard\//.test(whyText) && !whyText.includes('Summe'), whyText.slice(0, 200));
  const effects = await why.locator('.hit > span:last-child').allTextContents();
  check('hits show words, not numbers', effects.every((x) => /^(Verstoß|Nachteil|Vorteil|)$/.test(x.trim())), effects.join('|'));
  const summaries = await why.locator('h3 .muted').allTextContents();
  check('each day is summed up in words', summaries.length > 0 && summaries.every((x) => /^\((verletzt keine Regel|\d+ Regelverstö)/.test(x)), summaries.slice(0, 3).join('|'));
  if (out) {
    await page.evaluate(() => { document.getElementById('toast').hidden = true; }); // the note of the save above
    await page.screenshot({ path: join(out, 'person-full.png'), fullPage: true });
    await page.locator('#h-hours').evaluate((el) => el.scrollIntoView());
    await page.screenshot({ path: join(out, 'person.png') });
  }

  // Viewers get no goal: the tile shows only Soll.
  const viewer = await login('ansicht');
  await viewer.goto(`${base}/#/person/${encodeURIComponent(personId)}`);
  await viewer.locator('#h-hours').waitFor();
  const viewerTile = (await viewer.locator('.kpi-hours .value').textContent()).trim();
  check('viewer hour tile without Ziel', /^\d+(,\d)? h \(Soll \d+(,\d)? h\)$/.test(viewerTile), viewerTile);
  // Viewers have no rules page: its address shows the Dienstplan.
  await viewer.goto(`${base}/#/regeln`);
  await viewer.locator('#main h1').waitFor();
  check('viewer without rules page', viewer.url().endsWith('#/plan') && await viewer.locator('#h-goals').count() === 0, viewer.url());
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('rules and person page checked');
