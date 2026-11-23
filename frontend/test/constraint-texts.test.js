import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DEFAULT_RULES } from '../dev/mock-data.mjs';
import { constraintDescription } from '../src/lib/codes.js';
import { TEXTS } from '../src/lib/texts.js';

/** Constraints whose description names a rule parameter, with the parameters it names. */
const NAMED = {
  dailyRest: ['minDailyRestHours'], weeklyRest: ['minWeeklyRestHours'], maxDutyLength: ['maxDutyHours'],
  maxWeekHours: ['maxWeekHours'], averageWeekHours: ['maxAverageWeekHours', 'averageTolerancePercent'], freeSundays: ['minFreeSundays'],
  maxMonthHours: ['maxOverTargetPercent'], blockLength: ['minBlock', 'maxBlock'], wishes: ['maxWishesPerPerson'],
};
const descriptions = Object.entries(TEXTS).filter(([k]) => /^constraint\.\w+\.description$/.test(k));

test('every constraint with a parameter takes its number from the rules', () => {
  // values no text has on its own, each different
  const rules = Object.fromEntries(Object.values(NAMED).flat().map((key, i) => [key, 900 + i + 0.5]));
  for (const [id, keys] of Object.entries(NAMED)) {
    const text = constraintDescription({ id }, rules);
    for (const key of keys) assert.ok(text.includes(String(rules[key]).replace('.', ',')), `${id}: ${key} in "${text}"`);
    assert.doesNotMatch(text, /[{}]/, id);
  }
});

test('the placeholders of constraint descriptions are rule parameters', () => {
  for (const [k, v] of descriptions) {
    for (const m of v.matchAll(/\{(\w+)/g)) assert.ok(Object.hasOwn(DEFAULT_RULES, m[1]), `${k}: {${m[1]}}`);
  }
});

test('no constraint description has a parameter default as a fixed number', () => {
  const defaults = new Set(Object.values(DEFAULT_RULES).filter((v) => typeof v === 'number'));
  for (const [k, v] of descriptions) {
    const fixed = v.replace(/\{[^}]*\}/g, '').match(/\d+(?:,\d+)?/g) ?? [];
    for (const n of fixed) assert.ok(!defaults.has(Number(n.replace(',', '.'))), `${k}: fixed ${n}`);
  }
});
