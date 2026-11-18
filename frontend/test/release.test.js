import { test } from 'node:test';
import assert from 'node:assert/strict';
import { dismiss, dismissed, isNewer, parseVersion, showNotice } from '../src/lib/release.js';

const memory = () => {
  const m = new Map();
  return { getItem: (k) => (m.has(k) ? m.get(k) : null), setItem: (k, v) => m.set(k, String(v)) };
};

test('versions compare by number, pre-releases and other text are no versions', () => {
  assert.deepEqual(parseVersion('1.10.0'), [1, 10, 0]);
  assert.deepEqual(parseVersion('v2.0.1'), [2, 0, 1]);
  for (const v of ['1.4.0-rc.1', '1.4', '01.2.3', '', null, 'neu']) assert.equal(parseVersion(v), null, String(v));
  assert.ok(isNewer('1.10.0', '1.9.0'));
  assert.ok(isNewer('2.0.0', '1.99.99'));
  assert.ok(!isNewer('1.3.0', '1.3.0'));
  assert.ok(!isNewer('1.2.9', '1.3.0'));
  assert.ok(!isNewer('1.4.0-rc.1', '1.3.0'));
  assert.ok(isNewer('1.3.0', null));
});

test('the notice shows, hides once dismissed and comes back for a newer version', () => {
  const storage = memory();
  const offer = (version) => ({ version, notes: 'x' });
  assert.equal(showNotice(null, dismissed(storage)), false);
  assert.equal(showNotice(offer('1.3.0'), dismissed(storage)), true);
  dismiss('1.3.0', storage);
  assert.equal(dismissed(storage), '1.3.0');
  assert.equal(showNotice(offer('1.3.0'), dismissed(storage)), false);
  assert.equal(showNotice(offer('1.3.1'), dismissed(storage)), true);
  assert.equal(showNotice(offer('1.4.0-rc.1'), dismissed(storage)), false);
});

test('without storage the notice still works for this start', () => {
  const blocked = { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('blocked'); } };
  assert.equal(dismissed(blocked), null);
  assert.doesNotThrow(() => dismiss('1.3.0', blocked));
});
