import { test } from 'node:test';
import assert from 'node:assert/strict';

// a few DOM nodes, enough for lib/dom.js h() and the steps of an import
class FakeNode {}
class FakeText extends FakeNode {
  constructor(data) { super(); this.data = data; }
  get textContent() { return this.data; }
  all() { return []; }
}
class FakeElement extends FakeNode {
  constructor(tag) {
    super();
    Object.assign(this, { tagName: tag.toUpperCase(), children: [], attributes: {}, listeners: {}, style: {}, className: '', disabled: false, checked: false });
  }
  setAttribute(k, v) { this.attributes[k] = String(v); }
  getAttribute(k) { return this.attributes[k] ?? null; }
  addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); }
  append(...items) { for (const c of items) this.children.push(c instanceof FakeNode ? c : new FakeText(String(c))); }
  replaceChildren(...items) { this.children = []; this.append(...items); }
  contains() { return false; }
  get textContent() { return this.children.map((c) => c.textContent).join(''); }
  all() { return this.children.flatMap((c) => (c instanceof FakeElement ? [c, ...c.all()] : [])); }
  button(text) { return this.all().find((e) => e.tagName === 'BUTTON' && e.textContent === text); }
  async click() { await Promise.all((this.listeners.click ?? []).map((f) => f({}))); }
}
globalThis.Node = FakeNode;
globalThis.document = { createElement: (tag) => new FakeElement(tag), addEventListener() {}, removeEventListener() {}, getElementById: () => null };

const { importFlow } = await import('../src/lib/import-flow.js');
const { ApiError } = await import('../src/lib/api.js');

const SUMMARY = { source: 'Quelle', people: 3, areas: 2, shifts: 4, months: ['2027-01'], warnings: [], kept: 0 };

function setup({ changed = false, fail = null } = {}) {
  const calls = [];
  const api = {
    pluginImport: async (id, req, replace, keep = false) => {
      calls.push(['import', id, req, Boolean(replace), keep]);
      if (fail && !replace && !keep) throw fail;
      return { ...SUMMARY, kept: keep ? 1 : 0 };
    },
    pluginImportPreview: async (id, req) => {
      calls.push(['preview', id, req]);
      return { changed: true, kept: 1, conflicts: [{ personId: 'P1', field: 'weeklyHours' }], notes: [] };
    },
    exportDataset: async () => null,
  };
  let invalidated = 0;
  const ctx = { api, month: '2027-01', setMonth() {}, invalidateDataset: () => { invalidated++; },
    dataset: async () => ({ changedSinceImport: changed, people: [{ id: 'P1', shortName: 'ABC' }] }) };
  return { ctx, calls, invalidated: () => invalidated };
}
const tick = () => new Promise((r) => setTimeout(r, 0));

test('a plugin request is imported after a confirmation and shows the summary', async () => {
  const { ctx, calls, invalidated } = setup();
  globalThis.confirm = () => true;
  const el = importFlow(ctx, 'demo', () => ({ month: '2027-01' }));
  await tick();
  await el.button('Importieren').click();
  assert.deepEqual(calls, [['import', 'demo', { month: '2027-01' }, false, false]]);
  assert.match(el.textContent, /Import aus „Quelle“/);
  assert.equal(invalidated(), 1);
});

test('without a request nothing is sent; declined, nothing is imported', async () => {
  const { ctx, calls } = setup();
  globalThis.confirm = () => false;
  const el = importFlow(ctx, 'demo', () => null);
  await el.button('Importieren').click();
  const declined = importFlow(ctx, 'demo', () => ({ month: '2027-01' }));
  await declined.button('Importieren').click();
  assert.deepEqual(calls, []);
});

test('with changes in Dispofold the choice to keep them is on and the preview comes first, then the import keeping them', async () => {
  const { ctx, calls } = setup({ changed: true });
  const el = importFlow(ctx, 'demo', () => ({ month: '2027-01' }));
  await tick();
  const keep = el.all().find((e) => e.attributes.id === 'plugin-keep-demo');
  assert.equal(keep.checked, true);
  await el.button('Importieren').click();
  assert.match(el.textContent, /ABC \(P1\)/, 'the conflict names the person by short name');
  await el.button('Jetzt importieren').click();
  assert.deepEqual(calls.map((c) => [c[0], c[4]]), [['preview', undefined], ['import', true]]);
  assert.match(el.textContent, /1 Änderung/);
});

test('a choice made before the dataset arrives is kept', async () => {
  const { ctx } = setup({ changed: true });
  let resolve;
  ctx.dataset = () => new Promise((r) => { resolve = r; });
  const el = importFlow(ctx, 'demo', () => ({ month: '2027-01' }));
  const keep = el.all().find((e) => e.attributes.id === 'plugin-keep-demo');
  keep.checked = false;
  await Promise.all(keep.listeners.change.map((f) => f({})));
  resolve({ changedSinceImport: true, people: [] });
  await tick();
  assert.equal(keep.checked, false);
});

test('master data changed since the import: keep them or replace them, as on the Daten page', async () => {
  const fail = new ApiError(409, 'geändert', 'import.masterChanged', {});
  const { ctx, calls } = setup({ fail });
  globalThis.confirm = () => true;
  const el = importFlow(ctx, 'demo', () => ({ month: '2027-01' }));
  await tick();
  await el.button('Importieren').click();
  assert.ok(el.button('Änderungen behalten'));
  await el.button('Trotzdem importieren').click();
  assert.deepEqual(calls.map((c) => [c[0], c[3], c[4]]), [['import', false, false], ['import', true, false]]);
  assert.match(el.textContent, /Import aus „Quelle“/);
});

test('an error shows with all its messages in a shared result region', async () => {
  const fail = new ApiError(400, 'Ungültig', 'import.invalid', {}, { errors: [{ code: 'x.one', error: 'Erster' }, { code: 'x.two', error: 'Zweiter' }] });
  const { ctx } = setup({ fail });
  globalThis.confirm = () => true;
  const result = document.createElement('div');
  const el = importFlow(ctx, 'demo', () => ({ month: '2027-01' }), { result, current: null });
  await el.button('Importieren').click();
  assert.equal(el.tagName, 'DIV');
  assert.match(result.textContent, /Ungültig.*Erster.*Zweiter/);
});
