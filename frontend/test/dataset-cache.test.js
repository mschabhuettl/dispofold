import { test } from 'node:test';
import assert from 'node:assert/strict';
import { datasetCache } from '../src/lib/dataset-cache.js';

test('the master data is loaded once per view and again after invalidation', async () => {
  let loads = 0;
  let people = 0; // what the server has: another tab imports data in between
  const cache = datasetCache(async () => { loads++; return { people }; });
  assert.deepEqual(await cache.get(), { people: 0 });
  assert.deepEqual(await cache.get(), { people: 0 });
  assert.equal(loads, 1, 'views of one render share one load');
  people = 120;
  cache.invalidate(); // a navigation
  assert.deepEqual(await cache.get(), { people: 120 });
  assert.equal(loads, 2);
});

test('a failed load is not kept', async () => {
  let fail = true;
  const cache = datasetCache(async () => { if (fail) throw new Error('down'); return 'ok'; });
  await assert.rejects(cache.get(), /down/);
  fail = false;
  assert.equal(await cache.get(), 'ok');
});
