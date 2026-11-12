import { test } from 'node:test';
import assert from 'node:assert/strict';
import { waitForRestart } from '../src/lib/desktop.js';

/** Fake clock and health answers: each poll advances the clock by one second. */
function setup(answers) {
  let clock = 0;
  let reloaded = false;
  const seen = [];
  const fetchImpl = async () => {
    const a = answers.shift() ?? 'up';
    seen.push(a);
    if (a === 'error') throw new Error('refused');
    // 'page': the desktop shell's HTML restart page answers with 200 while the backend is away
    const type = a === 'page' ? 'text/html' : 'application/json';
    return { ok: a === 'up' || a === 'page', headers: new Map([['Content-Type', type]]) };
  };
  const opts = { fetchImpl, intervalMs: 1000, settleMs: 5000, timeoutMs: 20000, reload: () => { reloaded = true; },
    now: () => clock, wait: async (ms) => { clock += ms; } };
  return { opts, seen, reloaded: () => reloaded };
}

test('reloads only after the old instance went down and the new one is ready', async () => {
  const s = setup(['up', 'up', 'error', 'down', 'up']);
  assert.equal(await waitForRestart(s.opts), true);
  assert.equal(s.reloaded(), true);
  assert.deepEqual(s.seen, ['up', 'up', 'error', 'down', 'up']);
});

test('reloads after the settle time when the restart was too quick to be seen', async () => {
  const s = setup([]);
  assert.equal(await waitForRestart(s.opts), true);
  assert.equal(s.seen.length, 5);
});

test('gives up after the timeout', async () => {
  const s = setup(Array(100).fill('down'));
  assert.equal(await waitForRestart(s.opts), false);
  assert.equal(s.reloaded(), false);
});

test('update check: errorCode is rendered, "no update source" is not a fault', async () => {
  const { updateProblem } = await import('../src/lib/codes.js');
  assert.equal(updateProblem({ available: null }), null);
  const none = updateProblem({ errorCode: 'desktop.update.noSource', errorParams: {}, error: 'x' });
  assert.equal(none.fault, false);
  assert.match(none.text, /Keine Update-Quelle/);
  const status = updateProblem({ errorCode: 'desktop.update.sourceStatus', errorParams: { status: 502 }, error: 'x' });
  assert.deepEqual(status, { text: 'Die Update-Quelle antwortet mit Fehler 502.', fault: true });
  assert.deepEqual(updateProblem({ error: 'alter Text' }), { text: 'alter Text', fault: true });
  const { messageText } = await import('../src/lib/codes.js');
  assert.equal(messageText({ code: 'desktop.bundle.notNewer', params: { version: '1.2.0', current: '1.3.0' } }),
    'Version 1.2.0 ist nicht neuer als die installierte Version 1.3.0.');
});

test('the shell restart page does not count as ready', async () => {
  const s = setup(['page', 'page', 'up']);
  assert.equal(await waitForRestart(s.opts), true);
  assert.deepEqual(s.seen, ['page', 'page', 'up']);
});

test('shell mode: browser only when the shell says so, window otherwise', async () => {
  const { frontEndMode } = await import('../src/lib/desktop.js');
  const answer = (status, type, body) => async () => ({ ok: status < 300, status, headers: new Map([['Content-Type', type]]),
    json: async () => body });
  assert.equal(await frontEndMode(answer(200, 'application/json', { frontEnd: 'browser' })), 'browser');
  assert.equal(await frontEndMode(answer(200, 'application/json', { frontEnd: 'window' })), 'window');
  assert.equal(await frontEndMode(answer(404, 'text/plain', null)), 'window');
  assert.equal(await frontEndMode(answer(200, 'text/html', null)), 'window');
  assert.equal(await frontEndMode(async () => { throw new Error('refused'); }), 'window');
});
