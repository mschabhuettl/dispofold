import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fairnessRows, metricSpreads, nightOutside, nightReasonText, nightStatus, sortRows, spread, standouts, wishRate } from '../src/lib/fairness.js';

const people = [
  { id: 'P1', firstName: 'A', lastName: 'Alpha', weeklyHours: 40 },
  { id: 'P2', firstName: 'B', lastName: 'Beta', weeklyHours: 20 },
];
const base = { plannedHours: 0, accountAfter: 0, weekendsOff: 0, holidays: 0, unpopular: 0, singleDays: 0,
  compRestBalanceAfter: 0, historyNights: 0, historyWeekends: 0, historyHolidays: 0, historyUnpopular: 0,
  historyWishes: 0, historyWishesMet: 0, weekendsWorked: 0 };
const stats = [
  { ...base, personId: 'P1', nights: 4, wishes: 4, wishesMet: 3, weekendLoad: 1.25 },
  { ...base, personId: 'P2', nights: 3, wishes: 0, wishesMet: 0 },
  { ...base, personId: 'GONE', nights: 9, wishes: 0, wishesMet: 0 },
];

test('wish rate is null without wishes', () => {
  assert.equal(wishRate(0, 0), null);
  assert.equal(wishRate(4, 3), 0.75);
});

test('rows show the counts as they are and the loads the fairness rules compare', () => {
  const rows = fairnessRows(stats, people);
  assert.equal(rows.length, 2); // unknown person dropped
  assert.equal(rows[1].values.nights, 3); // no scaling to 40 h any more: the loads are the comparable figures
  assert.equal(rows[0].values.wishRate, 0.75);
  assert.equal(rows[0].values.weekendLoad, 1.25);
  assert.equal(rows[1].values.weekendLoad, null); // not in that comparison
});

test('spread ignores nulls; the mean of a load is the mean of the people compared', () => {
  assert.deepEqual(spread([1, null, 3]), { min: 1, max: 3, mean: 2, sd: 1, count: 2 });
  assert.equal(spread([]).mean, null);
  const s = metricSpreads(fairnessRows(stats, people));
  assert.equal(s.nights.max, 4);
  assert.equal(s.wishRate.count, 1);
  assert.equal(s.weekendLoad.count, 1);
  assert.equal(s.weekendLoad.mean, 1.25);
});

test('sorting keeps nulls last in both directions', () => {
  const rows = fairnessRows(stats, people);
  assert.deepEqual(sortRows(rows, 'wishRate', true).map((r) => r.person.id), ['P1', 'P2']);
  assert.deepEqual(sortRows(rows, 'wishRate', false).map((r) => r.person.id), ['P1', 'P2']);
  assert.deepEqual(sortRows(rows, 'nights', true).map((r) => r.person.id), ['P2', 'P1']);
  assert.deepEqual(sortRows(rows, 'name', false).map((r) => r.person.id), ['P2', 'P1']);
});

test('viewers do not get the personal metrics (wishes, hours account, compensatory rest)', async () => {
  const { METRICS, metricsFor } = await import('../src/lib/fairness.js');
  assert.equal(metricsFor(true).length, METRICS.length);
  const ids = metricsFor(false).map((m) => m.id);
  for (const id of ['wishRate', 'historyWishRate', 'accountAfter', 'compRestBalanceAfter']) assert.ok(!ids.includes(id), id);
  // the loads are planners only (the night load would tell the usual mix, restrictions or family rules)
  for (const id of ['nightLoad', 'weekendLoad', 'holidayLoad', 'unpopularLoad']) assert.ok(!ids.includes(id), id);
  assert.ok(ids.includes('nights') && ids.includes('plannedHours'));
});

test('the night tile tells whether a person is in the night comparison and why not', () => {
  const mix = { categories: { DAY: 0.9, NIGHT: 0.1 }, areas: {} };
  assert.equal(nightStatus({ nightPool: true, nightLoad: 4.236, nightMean: 3.9 }, null), 'im Nachtausgleich: Belastung 4,2, Mittel 3,9');
  assert.equal(nightStatus({ nightPool: false, nightReason: 'MIX' }, mix), 'folgt gewohnter Verteilung (10 % Nacht)');
  assert.equal(nightStatus({ nightPool: false, nightReason: 'NEVER' }, null), 'nicht im Nachtausgleich (nie Nacht)');
  // never nights wins over the night share of the usual mix: it does not count
  assert.equal(nightStatus({ nightPool: false, nightReason: 'NEVER' }, mix), 'nicht im Nachtausgleich (nie Nacht); der Nachtanteil der gewohnten Verteilung (10 %) zählt nicht');
  assert.equal(nightStatus({ nightPool: false, nightReason: 'NEVER' }, { categories: { DAY: 1 }, areas: {} }), 'nicht im Nachtausgleich (nie Nacht)');
  assert.equal(nightStatus({ nightPool: false, nightReason: null }, null), 'nicht im Nachtausgleich');
  // viewers get none of the figures
  assert.equal(nightStatus({ nightPool: null, nightReason: null }, null), null);
  assert.equal(nightStatus({}, null), null);
});

test('people outside the night comparison are marked with the reason', () => {
  assert.equal(nightOutside({ nightPool: true }), null);
  assert.equal(nightOutside({ nightPool: null }), null);
  assert.equal(nightOutside({ nightPool: false, nightReason: 'MIX' }), 'MIX');
  assert.equal(nightOutside({ nightPool: false, nightReason: 'NEVER' }), 'NEVER');
  assert.equal(nightOutside({ nightPool: false, nightReason: null }), 'OUT');
  assert.equal(nightReasonText('MIX'), 'gewohnte Verteilung');
  assert.equal(nightReasonText('NEVER'), 'nie Nacht');
});

test('the rest of an account correction as text', async () => {
  const { accountRestText } = await import('../src/lib/fairness.js');
  assert.equal(accountRestText({ accountRest: 13.2, accountCap: 184.8 }), '13,2 h fehlen, das Ziel ist schon bei der Obergrenze von 184,8 h');
  assert.equal(accountRestText({ accountRest: -32, accountCap: 184.8 }), '32,0 h Plus bleiben, das Ziel ist schon bei 0 h');
  const { accountRestShort } = await import('../src/lib/fairness.js');
  assert.equal(accountRestShort({ accountRest: 13.2, accountCap: 184.8 }), '13,2 h fehlen (Obergrenze 184,8 h)');
  assert.equal(accountRestShort({ accountRest: -32, accountCap: 184.8 }), '32,0 h Plus bleiben');
});

test('"Fällt auf": far from the mean in the direction that matters, farthest first, viewers only their figures', () => {
  const many = Array.from({ length: 20 }, (_, i) => ({ id: `Q${i}`, firstName: 'X', lastName: `N${i}`, weeklyHours: 40 }));
  const stats = many.map((p, i) => ({ personId: p.id, nightLoad: i === 0 ? 9 : 4, weekendLoad: i === 1 ? 0 : 3, accountAfter: i === 2 ? -40 : 0,
    wishes: 4, wishesMet: i === 3 ? 0 : 4, nights: i === 4 ? 12 : 2 }));
  const rows = fairnessRows(stats, many);
  const found = standouts(rows, true);
  // a low weekend load is not a burden: left out; the very low wishes and account stand out
  assert.deepEqual(found.map((x) => [x.person.id, x.metric.id, x.high]).sort(),
    [['Q0', 'nightLoad', true], ['Q2', 'accountAfter', false], ['Q3', 'wishRate', false]].sort());
  assert.ok(found.every((x, i) => i === 0 || found[i - 1].distance >= x.distance));
  assert.equal(standouts(rows, true, 1).length, 1);
  // viewers: counts only, no loads, account or wishes
  assert.deepEqual(standouts(rows, false).map((x) => [x.person.id, x.metric.id]), [['Q4', 'nights']]);
  // everybody alike: nothing stands out
  assert.deepEqual(standouts(fairnessRows(many.map((p) => ({ personId: p.id, nightLoad: 3 })), many), true), []);
});
