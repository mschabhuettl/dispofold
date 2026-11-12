import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addDays, addMonths, dayLabel, monthDates, parseLocalDateTime, parseTime, formatLocalDateTime, shiftHours,
  shiftInterval, weekday, isWeekend } from '../src/lib/dates.js';

test('parseTime accepts HH:MM and HH:MM:SS', () => {
  assert.equal(parseTime('06:30'), 390);
  assert.equal(parseTime('18:00:00'), 1080);
  assert.throws(() => parseTime('5h'));
});

test('weekday is ISO and independent of the time zone', () => {
  assert.equal(weekday('2026-12-01'), 2); // Tuesday
  assert.equal(weekday('2026-12-06'), 7);
  assert.equal(isWeekend('2026-12-05'), true);
  assert.equal(isWeekend('2026-12-07'), false);
});

test('month helpers', () => {
  assert.equal(monthDates('2026-12').length, 31);
  assert.equal(monthDates('2028-02').length, 29);
  assert.equal(monthDates('2026-02').at(-1), '2026-02-28');
  assert.equal(addMonths('2026-12', 1), '2027-01');
  assert.equal(addMonths('2027-01', -1), '2026-12');
  assert.equal(addDays('2026-12-31', 1), '2027-01-01');
  assert.equal(addDays('2026-03-29', 1), '2026-03-30'); // DST change in Austria
  assert.equal(dayLabel('2026-12-24'), 'Do 24.12.');
});

test('night shift ends on the next day, also across the month boundary', () => {
  const night = { start: '19:00', end: '07:00' };
  const { start, end } = shiftInterval('2026-12-31', night);
  assert.equal(end - start, 720);
  assert.equal(formatLocalDateTime(end), '2027-01-01T07:00');
  assert.equal(shiftHours({ start: '08:00', end: '16:30' }), 8 + 30 / 60);
  assert.equal(shiftHours({ start: '06:30', end: '06:30' }), 24);
});

test('LocalDateTime round trip', () => {
  assert.equal(formatLocalDateTime(parseLocalDateTime('2026-12-01T06:30:00')), '2026-12-01T06:30');
  assert.equal(parseLocalDateTime('2026-12-02T00:00') - parseLocalDateTime('2026-12-01T00:00'), 1440);
});
