import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ApiError, createApi, errorMessage, parseScore } from '../src/lib/api.js';

function fakeFetch(status, body, calls = []) {
  return async (path, init) => {
    calls.push({ path, init });
    return { status, ok: status >= 200 && status < 300, text: async () => (body == null ? '' : typeof body === 'string' ? body : JSON.stringify(body)) };
  };
}

test('successful JSON response and request shape', async () => {
  const calls = [];
  const api = createApi({ fetchImpl: fakeFetch(202, { solving: true }, calls) });
  assert.deepEqual(await api.solve('2026-12', { seed: 42 }), { solving: true });
  assert.equal(calls[0].path, '/api/plans/2026-12/solve');
  assert.equal(calls[0].init.method, 'POST');
  assert.equal(calls[0].init.body, '{"seed":42}');
  assert.equal(calls[0].init.headers['Content-Type'], 'application/json');
});

test('empty body yields null', async () => {
  const api = createApi({ fetchImpl: fakeFetch(204, null) });
  assert.equal(await api.stop('2026-12'), null);
});

test('401 calls the handler and throws', async () => {
  let redirected = false;
  const api = createApi({ fetchImpl: fakeFetch(401, null), onUnauthorized: () => { redirected = true; } });
  await assert.rejects(api.me(), (e) => e instanceof ApiError && e.status === 401);
  assert.equal(redirected, true);
});

test('the login page instead of JSON counts as 401 (no reliance on redirect info)', async () => {
  let redirected = false;
  const headers = new Map([['Content-Type', 'text/html; charset=utf-8']]);
  const fetchImpl = async () => ({ status: 200, ok: true, headers, text: async () => '<html>' });
  const api = createApi({ fetchImpl, onUnauthorized: () => { redirected = true; } });
  await assert.rejects(api.months(), (e) => e.status === 401);
  assert.equal(redirected, true);
});

test('409 and server error texts become German messages', async () => {
  const api = createApi({ fetchImpl: fakeFetch(409, { error: 'Für 2026-12 gibt es keinen Plan' }) });
  await assert.rejects(api.setCell('2026-12', {}), (e) => e.status === 409 && e.message === 'Für 2026-12 gibt es keinen Plan');
  assert.match(errorMessage(409, null), /Konflikt/);
  assert.equal(errorMessage(500, { error: 'Server meldet Fehler' }), 'Server meldet Fehler');
  assert.equal(errorMessage(500, '<html>'), 'Serverfehler (500)');
  assert.equal(errorMessage(403, null), 'Keine Berechtigung für diese Aktion');
});

test('network failure', async () => {
  const api = createApi({ fetchImpl: async () => { throw new TypeError('failed'); } });
  await assert.rejects(api.months(), (e) => e.status === 0 && /nicht erreichbar/.test(e.message));
});

test('score parsing', () => {
  assert.deepEqual(parseScore('0hard/-120medium/-53210soft'), { hard: 0, medium: -120, soft: -53210 });
  assert.deepEqual(parseScore('-2init/-1hard/0medium/0soft'), { hard: -1, medium: 0, soft: 0 });
  assert.deepEqual(parseScore(null), { hard: null, medium: null, soft: null });
});

test('update file is sent as raw bytes', async () => {
  const calls = [];
  const api = createApi({ fetchImpl: fakeFetch(202, null, calls) });
  const file = new Blob([new Uint8Array([0x50, 0x4b, 3, 4])]);
  await api.desktopUploadUpdate(file);
  assert.equal(calls[0].path, '/api/desktop/update/file');
  assert.equal(calls[0].init.headers['Content-Type'], 'application/octet-stream');
  // never a Blob, File or FormData as request body (crashes the desktop window)
  assert.ok(calls[0].init.body instanceof Uint8Array);
  assert.ok(!(calls[0].init.body instanceof Blob));
  assert.deepEqual([...calls[0].init.body], [0x50, 0x4b, 3, 4]);
});

test('JSON uploads are strings, every request path is relative', async () => {
  const calls = [];
  const api = createApi({ fetchImpl: fakeFetch(200, {}, calls) });
  await api.importDataset({ format: 'dispofold/dataset' });
  await api.importPlan('2027-01', { month: '2027-01' });
  for (const c of calls) {
    assert.equal(typeof c.init.body, 'string');
    assert.match(c.path, /^\/api\//);
  }
});

test('export is fetched as a blob with the file name from the server', async () => {
  const calls = [];
  const fetchImpl = async (path, init) => {
    calls.push({ path, init });
    return { status: 200, ok: true, headers: new Map([['Content-Disposition', 'attachment; filename="dienstplan-2027-01.csv"']]),
      blob: async () => new Blob(['a;b']) };
  };
  const { blob, filename } = await createApi({ fetchImpl }).exportPlan('2027-01', 'csv');
  assert.equal(calls[0].path, '/api/plans/2027-01/export?format=csv');
  assert.equal(filename, 'dienstplan-2027-01.csv');
  assert.equal(await blob.text(), 'a;b');
});

test('a blocked export shows the coded error, without a name it falls back to month and format', async () => {
  const blocked = createApi({ fetchImpl: fakeFetch(409, { code: 'plan.solving', params: {}, error: 'x' }) });
  await assert.rejects(blocked.exportPlan('2027-01', 'xlsx'), (e) => e.code === 'plan.solving' && e.status === 409);
  const fetchImpl = async () => ({ status: 200, ok: true, headers: new Map(), blob: async () => new Blob(['x']) });
  assert.equal((await createApi({ fetchImpl }).exportPlan('2027-01', 'xlsx')).filename, 'dienstplan-2027-01.xlsx');
});

test('saveBlob clicks a temporary download link and revokes the object URL', async () => {
  const { saveBlob } = await import('../src/lib/dom.js');
  const log = [];
  const a = { style: {}, click: () => log.push(`click ${a.href} ${a.download}`), remove: () => log.push('remove') };
  const doc = { createElement: (tag) => (log.push(`create ${tag}`), a), body: { append: () => log.push('append') } };
  const urls = { createObjectURL: () => 'blob:1', revokeObjectURL: (u) => log.push(`revoke ${u}`) };
  saveBlob(new Blob(['x']), 'plan.json', { doc, urls, later: (f) => f() });
  assert.deepEqual(log, ['create a', 'append', 'click blob:1 plan.json', 'remove', 'revoke blob:1']);
});

test('a short plain text error reason is shown, an HTML page is not', async () => {
  const api = createApi({ fetchImpl: fakeFetch(400, 'Das Paket ist nicht signiert.') });
  await assert.rejects(api.desktopInstall(), (e) => e.status === 400 && e.message === 'Das Paket ist nicht signiert.');
  assert.equal(errorMessage(400, '<html><body>Bad</body></html>'), errorMessage(400, null));
});

test('error toasts stay until closed, success toasts fade', async () => {
  const { toastDuration } = await import('../src/lib/dom.js');
  assert.equal(toastDuration(true), null);
  assert.equal(toastDuration(false), 4000);
});

test('coded errors keep code and params', async () => {
  const api = createApi({ fetchImpl: fakeFetch(409, { code: 'period.conflict', params: { revision: 4 }, error: 'Konflikt' }) });
  await assert.rejects(api.savePeriod('2027-01', {}), (e) => e.code === 'period.conflict' && e.params.revision === 4);
});

test('503 desktop.restarting is repeated with backoff until the backend answers, without an error', async () => {
  const answers = [
    { status: 503, body: { code: 'desktop.restarting', params: {}, error: 'Dispofold startet neu.' } },
    { status: 503, body: { code: 'desktop.restarting', params: {}, error: 'Dispofold startet neu.' } },
    { status: 200, body: [{ month: '2027-01' }] },
  ];
  const calls = [];
  const fetchImpl = async (path) => {
    const a = answers.shift();
    calls.push(path);
    return { status: a.status, ok: a.status < 300, text: async () => JSON.stringify(a.body) };
  };
  const events = [];
  const waits = [];
  const api = createApi({ fetchImpl, onRestarting: (on) => events.push(on), wait: async (ms) => { waits.push(ms); } });
  assert.deepEqual(await api.months(), [{ month: '2027-01' }]);
  assert.equal(calls.length, 3);
  assert.deepEqual(waits, [500, 1000]);
  assert.deepEqual(events, [true, false]);
});

test('another 503 stays an error', async () => {
  const api = createApi({ fetchImpl: fakeFetch(503, { code: 'plan.unavailable', params: {}, error: 'x' }), wait: async () => {} });
  await assert.rejects(api.months(), (e) => e.status === 503 && e.code === 'plan.unavailable');
});

test('restart backoff is capped at 5 s', async () => {
  const { restartDelay } = await import('../src/lib/api.js');
  assert.deepEqual([0, 1, 2, 3, 4, 10].map(restartDelay), [500, 1000, 2000, 4000, 5000, 5000]);
});

test('a coded 403 shows the permission text', async () => {
  const api = createApi({ fetchImpl: fakeFetch(403, { code: 'security.forbidden', params: {}, error: 'x' }) });
  await assert.rejects(api.exportPlan('2027-01', 'csv'), (e) => e.status === 403 && e.message === 'Dafür fehlt die Berechtigung.');
});
