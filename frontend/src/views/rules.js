// Regeln & Gewichte: constraint catalogue by level, editable (tunable) soft weights and rule parameters.
import { guarded, h } from '../lib/dom.js';
import { constraintDescription, constraintLabel } from '../lib/codes.js';
import { t } from '../lib/texts.js';

const LEVELS = [
  ['HARD', t('rules.level.hard.title'), t('rules.level.hard.help')],
  ['MEDIUM', t('rules.level.medium.title'), t('rules.level.medium.help')],
  ['SOFT', t('rules.level.soft.title'), t('rules.level.soft.help')],
];

/** Rule parameters: key, label, help, type ('number' | 'time' | 'bool'), step ('any' for the decimal ones: the backend keeps any value). */
const PARAMS = [
  ['minDailyRestHours', t('rules.param.minDailyRestHours.label'), t('rules.param.minDailyRestHours.help'), 'number', 'any'],
  ['minWeeklyRestHours', t('rules.param.minWeeklyRestHours.label'), t('rules.param.minWeeklyRestHours.help'), 'number', 'any'],
  ['maxDutyHours', t('rules.param.maxDutyHours.label'), t('rules.param.maxDutyHours.help'), 'number', 'any'],
  ['maxWeekHours', t('rules.param.maxWeekHours.label'), t('rules.param.maxWeekHours.help'), 'number', 'any'],
  ['maxAverageWeekHours', t('rules.param.maxAverageWeekHours.label'), t('rules.param.maxAverageWeekHours.help'), 'number', 'any'],
  ['minBlock', t('rules.param.minBlock.label'), t('rules.param.minBlock.help'), 'number', 1],
  ['maxBlock', t('rules.param.maxBlock.label'), t('rules.param.maxBlock.help'), 'number', 1],
  ['maxWishesPerPerson', t('rules.param.maxWishesPerPerson.label'), t('rules.param.maxWishesPerPerson.help'), 'number', 1],
  ['holidayWorkEarnsCompRest', t('rules.param.holidayWorkEarnsCompRest.label'), t('rules.param.holidayWorkEarnsCompRest.help'), 'bool'],
  ['nightStart', t('rules.param.nightStart.label'), '', 'time'],
  ['nightEnd', t('rules.param.nightEnd.label'), '', 'time'],
  ['nightMinHours', t('rules.param.nightMinHours.label'), t('rules.param.nightMinHours.help'), 'number', 'any'],
  ['maxOverTargetPercent', t('rules.param.maxOverTargetPercent.label'), t('rules.param.maxOverTargetPercent.help'), 'number', 'any'],
  ['minFreeSundays', t('rules.param.minFreeSundays.label'), t('rules.param.minFreeSundays.help'), 'number', 1],
  ['accountCarryShare', t('rules.param.accountCarryShare.label'), t('rules.param.accountCarryShare.help'), 'number', 'any'],
  ['historyMonths', t('rules.param.historyMonths.label'), t('rules.param.historyMonths.help'), 'number', 1],
];

export async function render(root, ctx) {
  const { api, canEdit } = ctx;
  const [constraints, rules] = await Promise.all([api.constraints(), api.rules()]);
  const weights = Object.fromEntries(constraints.map((c) => [c.id, c.weight ?? rules.weights?.[c.id] ?? c.defaultWeight]));
  const params = { ...rules };
  params.maxOverTargetPercent ??= 10; // rules saved before the setting existed
  params.minFreeSundays ??= 2;

  root.append(h('h1', {}, t('nav.rules')));
  if (!canEdit) root.append(h('p.alert', {}, t('rules.readonly')));

  let dirty = false;
  const status = h('span.muted', { role: 'status' });
  const saveBar = canEdit && h('div.save-bar', {}, h('button.btn.btn-primary', { type: 'button', onclick: save }, t('common.save')), status);
  const descriptions = [];
  const form = h('form', { onsubmit: (e) => { e.preventDefault(); save(); }, oninput: changed });

  for (const [level, title, help] of LEVELS) {
    const list = constraints.filter((c) => c.level === level);
    if (!list.length) continue;
    form.append(h('section.section', { 'aria-labelledby': `lvl-${level}` },
      h('h2', { id: `lvl-${level}` }, title), h('p.muted', {}, help),
      list.map((c) => h('div.rule-row', {},
        h('div', {}, h('strong', {}, constraintLabel(c)), description(c)),
        level === 'SOFT' && !c.tunable ? h('div.muted', {}, t('rules.weightFixed', { weight: c.defaultWeight }))
          : level === 'SOFT' ? (canEdit ? weightControl(c) : h('div', {}, t('rules.weight', { weight: weights[c.id] }), h('div.muted', { style: { fontSize: '.8rem' } }, t('rules.default', { weight: c.defaultWeight })))) : h('div.muted', {}, level === 'HARD' ? t('rules.always') : t('rules.weight', { weight: weights[c.id] }))))));
  }

  form.append(h('section.section', { 'aria-labelledby': 'h-params' }, h('h2', { id: 'h-params' }, t('rules.params')),
    h('div.params', {}, PARAMS.map(([key, label, help, type, step]) => {
      const id = `param-${key}`;
      if (!canEdit) {
        const shown = type === 'bool' ? (params[key] ? t('common.yes') : t('common.no')) : type === 'time' ? String(params[key] ?? '').slice(0, 5) : String(params[key]);
        return h('div.field', {}, h('strong', {}, label), h('span', {}, help ? t('rules.valueWithHelp', { value: shown, help }) : shown));
      }
      if (type === 'bool') {
        return h('div.field', {}, h('label', {}, h('input', { id, type: 'checkbox', checked: Boolean(params[key]),
          oninput: (e) => { params[key] = e.target.checked; } }), ' ', label), help && h('span', {}, help));
      }
      return h('div.field', {}, h('label', { for: id }, label),
        h('input', { id, type, step, min: type === 'number' ? 0 : null, max: key === 'accountCarryShare' ? 1 : null, required: true,
          value: type === 'time' ? String(params[key] ?? '').slice(0, 5) : params[key],
          oninput: (e) => { params[key] = type === 'time' ? e.target.value : Number(e.target.value); } }),
        help && h('span', {}, help));
    }))));
  if (saveBar) form.append(saveBar);
  root.append(form);
  ctx.isDirty = () => dirty;

  /** The description with the numbers of the current parameters; refreshed while they are edited. */
  function description(c) {
    const el = h('div.muted', {}, constraintDescription(c, params));
    descriptions.push([c, el]);
    return el;
  }

  function changed() {
    dirty = true;
    status.textContent = t('rules.unsaved');
    for (const [c, el] of descriptions) el.textContent = constraintDescription(c, params);
  }

  function weightControl(c) {
    const max = Math.max(1000, c.defaultWeight * 4, weights[c.id]);
    const id = `w-${c.id}`;
    const number = h('input', { id, type: 'number', min: 0, max, step: 1, required: true, value: weights[c.id],
      oninput: (e) => { weights[c.id] = Number(e.target.value); } });
    const reset = () => { number.value = c.defaultWeight; weights[c.id] = c.defaultWeight; changed(); };
    return h('div.weight', {}, h('label.visually-hidden', { for: id }, t('rules.weightLabel', { label: constraintLabel(c) })), number,
      h('span.muted', {}, t('rules.default', { weight: c.defaultWeight })),
      h('button.btn.btn-secondary.btn-small', { type: 'button', onclick: reset,
        'aria-label': t('rules.resetWeightLabel', { label: constraintLabel(c), weight: c.defaultWeight }) }, t('rules.resetWeight')));
  }

  async function save() {
    if (!form.reportValidity()) return;
    const body = { ...params, weights: weightsToSave(constraints, weights, rules.weights) };
    if (await guarded(async () => { await api.saveRules(body); return true; }, t('rules.saved'))) {
      dirty = false;
      status.textContent = '';
    }
  }
}

/** Weights for PUT /api/rules: the stored ones, overwritten by the tunable soft weights (others are not settings). */
export function weightsToSave(constraints, weights, stored) {
  const tunable = constraints.filter((c) => c.level === 'SOFT' && c.tunable).map((c) => [c.id, weights[c.id]]);
  return { ...(stored ?? {}), ...Object.fromEntries(tunable) };
}
