// Regeln & Gewichte: constraint catalogue by level, editable soft weights and rule parameters.
import { guarded, h } from '../lib/dom.js';
import { constraintDescription, constraintLabel } from '../lib/codes.js';
import { t } from '../lib/texts.js';

const LEVELS = [
  ['HARD', t('rules.level.hard.title'), t('rules.level.hard.help')],
  ['MEDIUM', t('rules.level.medium.title'), t('rules.level.medium.help')],
  ['SOFT', t('rules.level.soft.title'), t('rules.level.soft.help')],
];

/** Rule parameters: key, label, help, type ('number' | 'time' | 'bool'), step. */
const PARAMS = [
  ['minDailyRestHours', t('rules.param.minDailyRestHours.label'), t('rules.param.minDailyRestHours.help'), 'number', 0.5],
  ['minWeeklyRestHours', t('rules.param.minWeeklyRestHours.label'), t('rules.param.minWeeklyRestHours.help'), 'number', 1],
  ['maxDutyHours', t('rules.param.maxDutyHours.label'), t('rules.param.maxDutyHours.help'), 'number', 0.5],
  ['maxWeekHours', t('rules.param.maxWeekHours.label'), t('rules.param.maxWeekHours.help'), 'number', 1],
  ['maxAverageWeekHours', t('rules.param.maxAverageWeekHours.label'), t('rules.param.maxAverageWeekHours.help'), 'number', 0.01],
  ['minBlock', t('rules.param.minBlock.label'), t('rules.param.minBlock.help'), 'number', 1],
  ['maxBlock', t('rules.param.maxBlock.label'), t('rules.param.maxBlock.help'), 'number', 1],
  ['maxWishesPerPerson', t('rules.param.maxWishesPerPerson.label'), t('rules.param.maxWishesPerPerson.help'), 'number', 1],
  ['holidayWorkEarnsCompRest', t('rules.param.holidayWorkEarnsCompRest.label'), t('rules.param.holidayWorkEarnsCompRest.help'), 'bool'],
  ['nightStart', t('rules.param.nightStart.label'), '', 'time'],
  ['nightEnd', t('rules.param.nightEnd.label'), '', 'time'],
  ['nightMinHours', t('rules.param.nightMinHours.label'), t('rules.param.nightMinHours.help'), 'number', 0.5],
  ['accountCarryShare', t('rules.param.accountCarryShare.label'), t('rules.param.accountCarryShare.help'), 'number', 0.05],
  ['historyMonths', t('rules.param.historyMonths.label'), t('rules.param.historyMonths.help'), 'number', 1],
];

export async function render(root, ctx) {
  const { api, canEdit } = ctx;
  const [constraints, rules] = await Promise.all([api.constraints(), api.rules()]);
  const weights = Object.fromEntries(constraints.map((c) => [c.id, c.weight ?? rules.weights?.[c.id] ?? c.defaultWeight]));
  const params = { ...rules };

  root.append(h('h1', {}, t('nav.rules')));
  if (!canEdit) root.append(h('p.alert', {}, t('rules.readonly')));

  const saveBtn = canEdit && h('button.btn.btn-primary', { type: 'button', onclick: save }, t('common.save'));
  const form = h('form', { onsubmit: (e) => { e.preventDefault(); save(); } });

  for (const [level, title, help] of LEVELS) {
    const list = constraints.filter((c) => c.level === level);
    if (!list.length) continue;
    form.append(h('section.section', { 'aria-labelledby': `lvl-${level}` },
      h('h2', { id: `lvl-${level}` }, title), h('p.muted', {}, help),
      list.map((c) => h('div.rule-row', {},
        h('div', {}, h('strong', {}, constraintLabel(c)), h('div.muted', {}, constraintDescription(c))),
        level === 'SOFT' ? (canEdit ? weightControl(c) : h('div', {}, t('rules.weight', { weight: weights[c.id] }), h('div.muted', { style: { fontSize: '.8rem' } }, t('rules.default', { weight: c.defaultWeight })))) : h('div.muted', {}, level === 'HARD' ? t('rules.always') : t('rules.weight', { weight: weights[c.id] }))))));
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
          onchange: (e) => { params[key] = e.target.checked; } }), ' ', label), help && h('span', {}, help));
      }
      return h('div.field', {}, h('label', { for: id }, label),
        h('input', { id, type, step, min: type === 'number' ? 0 : null, max: key === 'accountCarryShare' ? 1 : null, required: true,
          value: type === 'time' ? String(params[key] ?? '').slice(0, 5) : params[key],
          onchange: (e) => { params[key] = type === 'time' ? e.target.value : Number(e.target.value); } }),
        help && h('span', {}, help));
    }))));
  if (saveBtn) form.append(saveBtn);
  root.append(form);

  function weightControl(c) {
    const max = Math.max(1000, c.defaultWeight * 4, weights[c.id]);
    const id = `w-${c.id}`;
    const number = h('input', { id, type: 'number', min: 0, max, step: 1, required: true, value: weights[c.id],
      oninput: (e) => { weights[c.id] = Number(e.target.value); range.value = e.target.value; } });
    const range = h('input', { type: 'range', min: 0, max, value: weights[c.id], 'aria-label': t('rules.weightLabel', { label: constraintLabel(c) }),
      oninput: (e) => { weights[c.id] = Number(e.target.value); number.value = e.target.value; } });
    return h('div', {}, h('label.visually-hidden', { for: id }, t('rules.weightLabel', { label: constraintLabel(c) })), h('div.weight', {}, range, number),
      h('div.muted', { style: { fontSize: '.8rem' } }, t('rules.default', { weight: c.defaultWeight })));
  }

  async function save() {
    if (!form.reportValidity()) return;
    const soft = Object.fromEntries(constraints.filter((c) => c.level === 'SOFT').map((c) => [c.id, weights[c.id]]));
    const body = { ...params, weights: { ...(rules.weights ?? {}), ...soft } };
    await guarded(() => api.saveRules(body), t('rules.saved'));
  }
}
