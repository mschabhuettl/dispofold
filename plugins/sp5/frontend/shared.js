// What the plugin's settings and pages share: the month fields, the options of an export, errors with the way to fix
// them, the empty state without a run.
import { h, info, t } from '../../../frontend/src/lib/plugin-api.js';

export const ID = 'sp5';

/** Address of one of the plugin's pages. */
export const pageHref = (path) => `#/plugin/${ID}/${path}`;

/**
 * The options of an export: [key, kind] (number, optional number, a choice of values); shiftMix and nightsFair are
 * checkboxes. The first ones are what a beginner looks at; the others, which most sites leave as they are, stand under
 * "Erweitert".
 */
const CHOICES = { demand: ['history', 'sp5'], areaSource: ['reserve', 'history', 'groups'] };
const OPTIONS = [['historyMonths', 'number'], ['areaSource', 'choice'], ['historyAreaDuties', 'number'], ['demand', 'choice']];
const ADVANCED = [['areasFromHistory', 'optional'], ['shiftsFromHistory', 'optional'], ['mixMinDuties', 'number'],
  ['hiddenShiftsMonths', 'number'], ['recentMonths', 'number']];

/** First and last month; value() gives { month, to } or null without a first month. */
export function monthFields(ctx, prefix, start = {}) {
  const month = h('input', { id: `${prefix}-month`, type: 'month', value: start.month ?? ctx.month ?? '' });
  const to = h('input', { id: `${prefix}-to`, type: 'month', value: start.to ?? '' });
  // not .toolbar .field: its narrow inputs would cut the month off ("Januar 202")
  const element = h('div.params', {},
    h('div.field', {}, h('label', { for: `${prefix}-month` }, t('plugin.sp5.month')), month),
    h('div.field', {}, h('label', { for: `${prefix}-to` }, t('plugin.sp5.to'), info(t('plugin.sp5.to.help'), t('plugin.sp5.to'))), to));
  return { element, value: () => (month.value ? { month: month.value, to: to.value || null } : null) };
}

/** The controls of the options (ids <prefix>-<key>), filled from options; value() gives them as settings.options. */
export function optionFields(prefix, options = {}) {
  const inputs = {};
  const field = (key, control) => h('div.field', {}, h('label', { for: `${prefix}-${key}` }, t(`plugin.sp5.option.${key}`),
    info(t(`plugin.sp5.option.${key}.help`), t(`plugin.sp5.option.${key}`))), control);
  const row = ([key, kind]) => {
    const value = options[key];
    const control = kind === 'choice'
      ? h('select', { id: `${prefix}-${key}` }, CHOICES[key].map((v) => h('option', { value: v, selected: value === v },
        t(`plugin.sp5.option.${key}.${v}`))))
      : h('input', { id: `${prefix}-${key}`, type: 'number', min: kind === 'number' && key !== 'historyAreaDuties' ? 0 : 1, value: value ?? '' });
    inputs[key] = control;
    return field(key, control);
  };
  const shiftMix = h('input', { id: `${prefix}-shiftMix`, type: 'checkbox', checked: options.shiftMix !== false });
  // off unless chosen: a shift rule without "Fair verteilen" leaves night shifts unmarked
  const nightsFair = h('input', { id: `${prefix}-nightsFair`, type: 'checkbox', checked: options.nightsFair === true });
  const number = (v) => (v === '' ? null : Number(v));
  return {
    elements: [...OPTIONS.map(row),
      h('details.advanced', { id: `${prefix}-advanced` }, h('summary', {}, t('plugin.sp5.options.advanced')),
        h('p.muted', {}, t('plugin.sp5.options.advanced.intro')), ...ADVANCED.map(row),
        h('label.check', {}, shiftMix, t('plugin.sp5.option.shiftMix'), info(t('plugin.sp5.option.shiftMix.help'), t('plugin.sp5.option.shiftMix'))),
        h('label.check', {}, nightsFair, t('plugin.sp5.option.nightsFair'), info(t('plugin.sp5.option.nightsFair.help'), t('plugin.sp5.option.nightsFair'))))],
    value() {
      const out = Object.fromEntries([...OPTIONS, ...ADVANCED].map(([key, kind]) => [key, kind === 'choice' ? inputs[key].value : number(inputs[key].value)]));
      out.shiftMix = shiftMix.checked;
      out.nightsFair = nightsFair.checked;
      return out;
    },
  };
}

const SETTINGS_ERRORS = new Set(['plugin.sp5.noFolder', 'plugin.sp5.setting']);

/** An error as an alert; a missing folder links to the settings, a missing mapping to the page Zuordnung. */
export function problem(e) {
  const link = e.code === 'plugin.sp5.noMapping' ? h('a', { href: pageHref('zuordnung') }, t('plugin.sp5.toMapping'))
    : SETTINGS_ERRORS.has(e.code) ? h('a', { href: '#/erweiterungen' }, t('plugin.sp5.toSettings')) : null;
  return h('div.alert.alert-error', { role: 'alert' }, h('strong', {}, e.message), link ? [' ', link] : null);
}

/** No run since the server started: what to do. */
export const noRun = () => h('p.alert', { id: 'sp5-no-run' }, t('error.plugin.sp5.noRun'), ' ',
  h('a', { href: pageHref('uebersicht') }, t('plugin.sp5.toOverview')));

/** Loads a plugin route; a missing run (404 plugin.sp5.noRun) gives null. */
export async function orNoRun(load) {
  try {
    return await load();
  } catch (e) {
    if (e.code === 'plugin.sp5.noRun') return null;
    throw e;
  }
}
