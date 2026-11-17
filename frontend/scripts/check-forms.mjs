// Playwright check: forms save stored decimals unchanged. A person with weekly hours 46.15 and an hours account
// of 12.25, and rules with decimal parameters, are opened and saved without a change, with the law fields locked and
// again after "Ändern" (the hours account share is shown in percent and saved as a fraction); the browser must not reject
// the values (step) and the saved values must be the stored ones. Demo data of the dev server (mock API).
//   npm run build && node scripts/check-forms.mjs     (Chromium via `npx playwright install chromium`)
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
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
  const page = await browser.newPage();
  page.on('dialog', (d) => d.accept());
  page.on('pageerror', (e) => check('no page error', false, e.message));
  await page.goto(`${base}/login.html`);
  await page.fill('#j_username', 'planung');
  await page.fill('#j_password', 'x');
  await Promise.all([page.waitForURL((u) => !u.pathname.endsWith('login.html')), page.click('button[type=submit]')]);
  const person = await page.evaluate(async () => {
    await fetch('/api/import/demo', { method: 'POST' });
    const p = (await (await fetch('/api/dataset')).json()).people[0];
    const changed = { ...p, weeklyHours: 46.15, hoursAccount: 12.25 };
    await fetch(`/api/dataset/people/${encodeURIComponent(p.id)}`,
      { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(changed) });
    const rules = await (await fetch('/api/rules')).json();
    await fetch('/api/rules', { method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ ...rules, minDailyRestHours: 11.25, maxWeekHours: 48.5, maxAverageWeekHours: 38.25,
        accountCarryShare: 0.33, maxOverTargetPercent: 12.5, averagingWeeks: 26, nightDistribution: 'EVEN' }) });
    return changed;
  });

  // The person: open the form, save it unchanged.
  await page.goto(`${base}/#/stammdaten`);
  const edit = page.locator(`button[data-kind="people"][data-key="${person.id}"]`).first();
  await edit.waitFor();
  await edit.click();
  const form = page.locator('tr.edit-row form');
  await form.waitFor();
  check('weekly hours shown', await page.inputValue('#f-weeklyHours') === '46.15', await page.inputValue('#f-weeklyHours'));
  check('person form valid', await form.evaluate((f) => f.checkValidity()),
    await form.evaluate((f) => [...f.querySelectorAll(':invalid')].map((x) => `${x.id}: ${x.validationMessage}`).join('; ')));
  const [put] = await Promise.all([
    page.waitForRequest((r) => r.method() === 'PUT' && r.url().includes('/api/dataset/people/'), { timeout: 5000 }),
    form.locator('button[type=submit]').click(),
  ]);
  const sent = put.postDataJSON();
  check('person saved unchanged', sent.weeklyHours === 46.15 && sent.hoursAccount === 12.25,
    `${sent.weeklyHours} / ${sent.hoursAccount}`);

  // The rules: decimal parameters save unchanged as well.
  await page.goto(`${base}/#/regeln`);
  await page.locator('#h-goals').waitFor();
  const rulesForm = page.locator('form:has(#h-goals)');
  check('rules form valid', await rulesForm.evaluate((f) => f.checkValidity()),
    await rulesForm.evaluate((f) => [...f.querySelectorAll(':invalid')].map((x) => `${x.id}: ${x.validationMessage}`).join('; ')));
  const savedRules = async () => (await Promise.all([
    page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith('/api/rules'), { timeout: 5000 }),
    rulesForm.locator('button.btn-primary').click(),
  ]))[0].postDataJSON();
  const unchanged = (rules) => rules.minDailyRestHours === 11.25 && rules.maxWeekHours === 48.5 && rules.maxAverageWeekHours === 38.25
    && rules.accountCarryShare === 0.33 && rules.maxOverTargetPercent === 12.5 && rules.averagingWeeks === 26 && rules.nightDistribution === 'EVEN';
  const shown = (rules) => JSON.stringify([rules.minDailyRestHours, rules.maxWeekHours, rules.maxAverageWeekHours, rules.accountCarryShare,
    rules.maxOverTargetPercent, rules.averagingWeeks, rules.nightDistribution]);
  const locked = await savedRules();
  check('rules saved unchanged', unchanged(locked), shown(locked));
  await page.getByRole('button', { name: 'Ändern' }).click();
  check('law field shows the decimal', await page.inputValue('#param-maxAverageWeekHours') === '38.25', await page.inputValue('#param-maxAverageWeekHours'));
  check('rules form valid after Ändern', await rulesForm.evaluate((f) => f.checkValidity()));
  const unlocked = await savedRules();
  check('rules saved unchanged after Ändern', unchanged(unlocked), shown(unlocked));
  // The hours account share is shown and typed in percent, stored as a fraction.
  check('account share shown in percent', await page.inputValue('#param-accountCarryShare') === '33', await page.inputValue('#param-accountCarryShare'));
  await page.fill('#param-accountCarryShare', '25');
  const [percentPut] = await Promise.all([
    page.waitForRequest((r) => r.method() === 'PUT' && r.url().endsWith('/api/rules'), { timeout: 5000 }),
    rulesForm.locator('button.btn-primary').click(),
  ]);
  check('account share saved as a fraction', percentPut.postDataJSON().accountCarryShare === 0.25, String(percentPut.postDataJSON().accountCarryShare));
} finally {
  await browser.close();
  server.kill();
}
if (failed) process.exit(1);
console.log('forms save stored decimals');
