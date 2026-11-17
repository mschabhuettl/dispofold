import { test } from 'node:test';
import assert from 'node:assert/strict';
import { CUSTOM, GROUPS, applyLevel, groupConstraints, groupLevel, levelWeight, levels, nightDistribution, nightRuleMissing } from '../src/lib/rule-groups.js';

/** The soft part of the backend catalogue (ConstraintCatalog), with the default weights. */
const CATALOGUE = [
  ['targetHours', 20], ['fairNights', 3], ['fairWeekends', 3], ['fairHolidays', 3], ['fairUnpopular', 2], ['wishes', 300],
  ['blockLength', 6000], ['isolatedDayOff', 10000], ['forwardRotation', 500], ['splitWeekend', 300], ['compRestUse', 500],
  ['shiftVariety', 5], ['areaVariety', 1], ['shiftMix', 100], ['family', 800],
].map(([id, defaultWeight]) => ({ id, level: 'SOFT', defaultWeight, tunable: true }))
  .concat([{ id: 'onRequest', level: 'SOFT', defaultWeight: 1000, tunable: false }, { id: 'dailyRest', level: 'HARD', defaultWeight: 1, tunable: false }]);
const defaults = () => Object.fromEntries(CATALOGUE.map((c) => [c.id, c.defaultWeight]));

test('the groups name tunable constraints of the catalogue, each in one group only', () => {
  const ids = Object.values(GROUPS).flat();
  assert.equal(new Set(ids).size, ids.length);
  for (const id of ids) assert.ok(CATALOGUE.some((c) => c.id === id && c.tunable), id);
  assert.deepEqual(groupConstraints('blocks', CATALOGUE).map((c) => c.id), ['blockLength', 'isolatedDayOff']);
});

test('Wichtigkeit is 0.5, 1 or 2 times the default weight, rounded to whole numbers', () => {
  const block = CATALOGUE.find((c) => c.id === 'blockLength');
  assert.deepEqual(['less', 'normal', 'more'].map((l) => levelWeight(block, l)), [3000, 6000, 12000]);
  const hours = CATALOGUE.find((c) => c.id === 'targetHours');
  assert.deepEqual(['less', 'normal', 'more'].map((l) => levelWeight(hours, l)), [10, 20, 40]);
  assert.equal(levelWeight({ defaultWeight: 15 }, 'less'), 8);
});

test('Faire Verteilung uses 0.25, 1 and 4 times the default weight', () => {
  const nights = CATALOGUE.find((c) => c.id === 'fairNights');
  const unpopular = CATALOGUE.find((c) => c.id === 'fairUnpopular');
  assert.deepEqual(['less', 'normal', 'more'].map((l) => levelWeight(nights, l)), [1, 3, 12]); // 0.75 rounds up
  assert.deepEqual(['less', 'normal', 'more'].map((l) => levelWeight(unpopular, l)), [1, 2, 8]);
  assert.deepEqual(levels('fairness'), { less: 0.25, normal: 1, more: 4 });
  assert.deepEqual(levels('blocks'), { less: 0.5, normal: 1, more: 2 });
});

test('choosing a level overwrites the weights of the group and nothing else', () => {
  const weights = { ...defaults(), fairNights: 17, shiftMix: 0 };
  applyLevel('fairness', 'more', CATALOGUE, weights);
  assert.deepEqual([weights.fairNights, weights.fairWeekends, weights.fairHolidays, weights.fairUnpopular], [12, 12, 12, 8]);
  assert.equal(weights.shiftMix, 0);
  assert.equal(weights.blockLength, 6000);
  assert.equal(groupLevel('fairness', CATALOGUE, weights), 'more');
  applyLevel('fairness', 'less', CATALOGUE, weights);
  assert.deepEqual([weights.fairNights, weights.fairUnpopular], [1, 1]);
  assert.equal(groupLevel('fairness', CATALOGUE, weights), 'less');
});

test('weights set by hand show as custom', () => {
  const weights = defaults();
  for (const group of Object.keys(GROUPS)) assert.equal(groupLevel(group, CATALOGUE, weights), 'normal', group);
  weights.isolatedDayOff = 9000;
  assert.equal(groupLevel('blocks', CATALOGUE, weights), CUSTOM);
  // not uniform: one constraint at 2x, the other at 1x
  Object.assign(weights, { blockLength: 12000, isolatedDayOff: 10000 });
  assert.equal(groupLevel('blocks', CATALOGUE, weights), CUSTOM);
  assert.equal(groupLevel('blocks', CATALOGUE, { ...weights, isolatedDayOff: 20000 }), 'more');
});

test('a default of 1 reads as normal, not as less', () => {
  const one = [{ id: 'wishes', defaultWeight: 1, tunable: true }];
  assert.equal(groupLevel('wishes', one, { wishes: 1 }), 'normal');
  assert.equal(groupLevel('wishes', one, { wishes: 2 }), 'more');
});

test('a group without constraints in the catalogue has no level', () => {
  const catalogue = CATALOGUE.filter((c) => c.id !== 'family');
  assert.equal(groupLevel('family', catalogue, defaults()), null);
  assert.deepEqual(applyLevel('family', 'more', catalogue, {}), {});
});

test('old rules without the night setting mean HABIT; the setting is kept otherwise', () => {
  assert.equal(nightDistribution({ minBlock: 2, weights: {} }), 'HABIT');
  assert.equal(nightDistribution({ nightDistribution: 'EVEN' }), 'EVEN');
  assert.equal(nightDistribution({ nightDistribution: 'HABIT' }), 'HABIT');
});

test('weight 0 for the usual distribution warns only in HABIT', () => {
  assert.equal(nightRuleMissing('HABIT', { shiftMix: 0 }), true);
  assert.equal(nightRuleMissing('HABIT', { shiftMix: 100 }), false);
  assert.equal(nightRuleMissing('EVEN', { shiftMix: 0 }), false);
});
