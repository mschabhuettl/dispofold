// Texts for codes sent by the server (docs/api.md, "Fehlercodes"): errors, import messages, constraints and hits.
import { codeText, t } from './texts.js';

/** Entry kinds named in import messages (param "kind"). */
const KIND = {
  area: t('kind.area'), areas: t('kind.areas'), shift: t('kind.shift'), shifts: t('kind.shifts'),
  person: t('kind.person'), people: t('kind.people'), absenceType: t('kind.absenceType'), absenceTypes: t('kind.absenceTypes'),
  absence: t('kind.absence'), absences: t('kind.absences'), wish: t('kind.wish'), wishes: t('kind.wishes'),
  holiday: t('kind.holiday'), holidays: t('kind.holidays'), demand: t('kind.demand'), history: t('kind.history'),
  fixed: t('kind.fixed'), previousDuty: t('kind.previousDuty'), previousDuties: t('kind.previousDuties'),
  restriction: t('kind.restriction'), restrictions: t('kind.restrictions'), period: t('kind.period'),
  periods: t('kind.periods'), familyRule: t('kind.familyRule'),
};

const CATEGORY = { EARLY: t('masterdata.category.early'), DAY: t('masterdata.category.day'),
  LATE: t('masterdata.category.late'), NIGHT: t('masterdata.category.night') };

/** Durations as decimal hours with one decimal, like the figures on the cards ("130,7"). */
const hours = (minutes) => (minutes / 60).toLocaleString('de-AT', { minimumFractionDigits: 1, maximumFractionDigits: 1 });

/**
 * Params as the texts use them: kinds and categories in German, durations in minutes also as decimal hours
 * ('restMinutes' gives 'restHours', 'minutes' gives 'hours'), loads in thousandths as decimals, a 0-based 'index'
 * also as 'number' counting from 1. Clock times (from, to) stay HH:MM.
 */
export function displayParams(params) {
  const out = { ...(params ?? {}) };
  for (const [k, v] of Object.entries(params ?? {})) {
    if (k === 'kind') out.kind = KIND[v] ?? v;
    else if (k === 'category') out.category = CATEGORY[v] ?? v;
    else if (k === 'minutes' && typeof v === 'number') out.hours = hours(v);
    else if (/Minutes$/.test(k) && typeof v === 'number') out[k.replace(/Minutes$/, 'Hours')] = hours(v);
    else if ((k === 'load' || k === 'mean') && typeof v === 'number') out[k] = v / 1000;
    else if (k === 'index' && typeof v === 'number') out.number = v + 1;
  }
  return out;
}

/** Error or import message { code, params, error? } (a plain string from an older server passes through). */
export function messageText(m) {
  if (m == null) return '';
  if (typeof m === 'string') return m;
  return m.code ? codeText(`error.${m.code}`, displayParams(m.params), m.error ?? m.text) : (m.error ?? m.text ?? '');
}

/**
 * Result of an update check (GET /api/desktop/update): text of errorCode/errorParams, and whether it is a fault.
 * Having no update source is a setting, not a fault: the program then never goes online.
 */
export function updateProblem(info) {
  if (!info?.errorCode && !info?.error) return null;
  const text = info.errorCode ? codeText(`error.${info.errorCode}`, displayParams(info.errorParams), info.error) : info.error;
  return { text, fault: info.errorCode !== 'desktop.update.noSource' };
}

export function constraintLabel(c) {
  return codeText(`constraint.${c.id}.label`, {}, c.label ?? c.id);
}

export function constraintDescription(c) {
  return codeText(`constraint.${c.id}.description`, {}, c.description ?? '');
}

/** Explanation of one constraint hit; the code may come with or without the 'hit.' prefix. */
export function hitText(hit) {
  if (!hit.code) return hit.text ?? '';
  const key = hit.code.startsWith('hit.') ? hit.code : `hit.${hit.code}`;
  return codeText(key, displayParams(hit.params), hit.text);
}
