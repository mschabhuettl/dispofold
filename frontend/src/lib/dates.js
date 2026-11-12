// Calendar helpers. Dates are 'YYYY-MM-DD' strings, times 'HH:MM' or 'HH:MM:SS'. All arithmetic is done in UTC
// so that the local time zone and daylight saving never shift a day; the backend's LocalDateTime has no zone.
import { t } from './texts.js';

export const WEEKDAY_SHORT = [t('dates.weekday.mon'), t('dates.weekday.tue'), t('dates.weekday.wed'), t('dates.weekday.thu'), t('dates.weekday.fri'),
  t('dates.weekday.sat'), t('dates.weekday.sun')];
export const MONTH_NAMES = [t('dates.month.jan'), t('dates.month.feb'), t('dates.month.mar'), t('dates.month.apr'), t('dates.month.may'),
  t('dates.month.jun'), t('dates.month.jul'), t('dates.month.aug'), t('dates.month.sep'), t('dates.month.oct'), t('dates.month.nov'),
  t('dates.month.dec')];
const DAY_CODES = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];

const pad = (n) => String(n).padStart(2, '0');

/** Minutes after midnight of 'HH:MM' or 'HH:MM:SS'. */
export function parseTime(text) {
  const m = /^(\d{1,2}):(\d{2})(?::(\d{2}))?$/.exec(text ?? '');
  if (!m) throw new Error(`invalid time: ${text}`);
  return Number(m[1]) * 60 + Number(m[2]);
}

export function formatTime(minutes) {
  const m = ((minutes % 1440) + 1440) % 1440;
  return `${pad(Math.floor(m / 60))}:${pad(m % 60)}`;
}

/** Days since 1970-01-01 of a 'YYYY-MM-DD' date. */
export function dayNumber(date) {
  const [y, m, d] = date.split('-').map(Number);
  return Date.UTC(y, m - 1, d) / 86400000;
}

export function dateFromDayNumber(n) {
  return new Date(n * 86400000).toISOString().slice(0, 10);
}

export function addDays(date, n) {
  return dateFromDayNumber(dayNumber(date) + n);
}

/** ISO weekday 1 (Monday) .. 7 (Sunday). */
export function weekday(date) {
  return ((dayNumber(date) + 3) % 7 + 7) % 7 + 1;
}

export function dayCode(date) {
  return DAY_CODES[weekday(date) - 1];
}

export function isWeekend(date) {
  return weekday(date) >= 6;
}

/** All dates of a 'YYYY-MM' month. */
export function monthDates(month) {
  const [y, m] = month.split('-').map(Number);
  const count = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return Array.from({ length: count }, (_, i) => `${month}-${pad(i + 1)}`);
}

export function addMonths(month, n) {
  const [y, m] = month.split('-').map(Number);
  const d = new Date(Date.UTC(y, m - 1 + n, 1));
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}`;
}

export function monthLabel(month) {
  const [y, m] = month.split('-').map(Number);
  return `${MONTH_NAMES[m - 1]} ${y}`;
}

/** 'Di 01.12.' */
export function dayLabel(date) {
  const [, m, d] = date.split('-');
  return `${WEEKDAY_SHORT[weekday(date) - 1]} ${d}.${m}.`;
}

/** Minutes since 1970-01-01 00:00 of a date plus minutes of that day. */
export function epochMinutes(date, minutes = 0) {
  return dayNumber(date) * 1440 + minutes;
}

/** Epoch minutes of an ISO LocalDateTime 'YYYY-MM-DDTHH:MM[:SS]'. */
export function parseLocalDateTime(text) {
  const [date, time] = text.split('T');
  return epochMinutes(date, parseTime(time.slice(0, 8)));
}

export function formatLocalDateTime(epochMin) {
  const day = Math.floor(epochMin / 1440);
  return `${dateFromDayNumber(day)}T${formatTime(epochMin - day * 1440)}`;
}

/**
 * Local Date with the same wall clock time as an epoch minutes value. On daylight saving days the local day has 23 or
 * 25 hours; views that place times on a pixel axis use this so that they agree with each other.
 */
export function localDate(epochMin) {
  const u = new Date(epochMin * 60000);
  return new Date(u.getUTCFullYear(), u.getUTCMonth(), u.getUTCDate(), u.getUTCHours(), u.getUTCMinutes());
}

/** Start and end (epoch minutes) of a shift on a date; end <= start means it ends on the next day. */
export function shiftInterval(date, shift) {
  const start = epochMinutes(date, parseTime(shift.start));
  let end = epochMinutes(date, parseTime(shift.end));
  if (end <= start) end += 1440;
  return { start, end };
}

/** Paid hours of a shift. */
export function shiftHours(shift) {
  const { start, end } = shiftInterval('2000-01-01', shift);
  return (end - start) / 60;
}
