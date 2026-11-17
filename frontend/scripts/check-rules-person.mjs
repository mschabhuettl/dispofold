// Playwright check of "Regeln & Gewichte" and the person page. Rules: the save bar stays in view while scrolling,
// an edit marks the page unsaved and leaving it asks (dismiss stays, accept leaves), weights are number fields with
// their default and a reset to it, and a rule text takes its number from the parameter field. Person: one hour tile
// "x h von Ziel y h (Soll z h)" (viewers without Ziel) and "Warum dieser Dienst?" in words, without scores.
// Demo data of the dev server (mock API). With an output folder as argument, it also saves screenshots of both pages.
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

let failed = false;
const check = (name, ok, detail = '') => {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${detail ? `: ${detail}` : ''}`);
  if (!ok) failed = true;
};

const browser = await chromium.launch();
try {
  const login = async (user) => {
    const context = await browser.newContext({ viewport: { width: 1600, height: 1000 }, colorScheme: 'light' });
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

  // Rules: the save bar stays in view at the bottom and at the top of the page.
  await page.goto(`${base}/#/regeln`);
  await page.locator('.rule-row').first().waitFor();
  const bar = page.locator('.save-bar');
  const inView = () => bar.evaluate((el) => { const r = el.getBoundingClientRect(); return r.top >= 0 && r.bottom <= innerHeight && r.height > 0; });
  check('rules page scrolls', await page.evaluate(() => document.scrollingElement.scrollHeight > innerHeight + 200));
  await page.evaluate(() => scrollTo(0, document.scrollingElement.scrollHeight));
  check('save bar in view at the bottom', await inView());
  await page.evaluate(() => scrollTo(0, 0));
  check('save bar in view at the top', await inView());
  if (out) await page.screenshot({ path: join(out, 'rules.png') });
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
  await page.click('#nav a[data-path="person"]');
  await page.waitForTimeout(300);
  check('leaving asks', dialogs.length === 1, dialogs.join());
  check('dismiss stays on the rules page', page.url().endsWith('#/regeln') && await page.locator('.rule-row').count() > 0, page.url());
  check('dismiss keeps the edit', await page.inputValue('#param-minDailyRestHours') === '9.5');
  page.once('dialog', (d) => { dialogs.push(d.message()); d.accept(); });
  await page.click('#nav a[data-path="person"]');
  await page.locator('#person-select').waitFor();
  check('accept leaves the rules page', dialogs.length === 2 && page.url().endsWith('#/person'), page.url());
  // the edit was not saved, and a page without edits leaves without asking
  await page.goto(`${base}/#/regeln`);
  await page.locator('.rule-row').first().waitFor();
  check('discarded edit not saved', await page.inputValue('#param-minDailyRestHours') === '11');
  page.once('dialog', (d) => { dialogs.push(d.message()); d.accept(); });
  await page.click('#nav a[data-path="fairness"]');
  await page.waitForURL((u) => u.hash === '#/fairness');
  check('no question without edits', dialogs.length === 2);
  // an edit made while a save is under way stays unsaved
  await page.goto(`${base}/#/regeln`);
  await page.locator('.rule-row').first().waitFor();
  await page.route('**/api/rules', async (route) => {
    if (route.request().method() === 'PUT') await new Promise((r) => setTimeout(r, 500));
    await route.continue();
  });
  const isPut = (r) => r.request().method() === 'PUT' && r.url().endsWith('/api/rules');
  await page.fill('#param-minDailyRestHours', '12');
  const slowSave = page.waitForResponse(isPut);
  await bar.locator('button.btn-primary').click();
  await page.fill('#param-minDailyRestHours', '13');
  await slowSave;
  await page.waitForTimeout(100);
  check('an edit during the save stays unsaved', (await bar.textContent()).includes('Nicht gespeichert'));
  await page.unroute('**/api/rules');
  // after saving there is nothing to ask either
  await page.fill('#param-minDailyRestHours', '11');
  await Promise.all([page.waitForResponse(isPut), bar.locator('button.btn-primary').click()]);
  await page.waitForTimeout(100);
  check('saving clears the note', !(await bar.textContent()).includes('Nicht gespeichert'));
  await page.click('#nav a[data-path="fairness"]');
  await page.waitForURL((u) => u.hash === '#/fairness');
  check('no question after saving', dialogs.length === 2);

  // Person page: one hour tile, no scores in "Warum dieser Dienst?".
  await page.goto(`${base}/#/person/${encodeURIComponent(personId)}`);
  await page.locator('#h-why').waitFor();
  const tiles = page.locator('.kpi .value').filter({ hasText: /Soll|Ziel/ });
  check('one hour tile with Soll and Ziel', await tiles.count() === 1, String(await tiles.count()));
  const tile = (await tiles.first().textContent()).trim();
  check('hour tile format', /^\d+(,\d)? h von Ziel \d+(,\d)? h \(Soll \d+(,\d)? h\)$/.test(tile), tile);
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
  // Viewers read the rules: texts with the stored numbers, no save bar.
  await viewer.goto(`${base}/#/regeln`);
  await viewer.locator('.rule-row').first().waitFor();
  check('viewer rules without save bar', await viewer.locator('.save-bar').count() === 0);
  check('viewer rule text with the stored number',
    (await viewer.locator('.rule-row').filter({ hasText: 'Tägliche Ruhezeit' }).first().textContent()).includes('mindestens 11 Stunden'));
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('rules and person page checked');
