// Regeln & Gewichte: planning goals with one Wichtigkeit each and the night setting, the limits of law and works
// agreement (locked until "Ändern"), and under "Erweitert" every weight, the night window and the rule catalogue.
import { guarded, h } from '../lib/dom.js';
import { constraintDescription, constraintLabel } from '../lib/codes.js';
import { CUSTOM, GROUPS, applyLevel, groupLevel, nightDistribution, nightRuleMissing } from '../lib/rule-groups.js';
import { info } from '../lib/info.js';
import { formatParams, hasText, t } from '../lib/texts.js';

const LEVELS = [
  ['SOFT', t('rules.level.soft.title'), t('rules.level.soft.help')],
  ['HARD', t('rules.level.hard.title'), t('rules.level.hard.help')],
  ['MEDIUM', t('rules.level.medium.title'), t('rules.level.medium.help')],
];

/** Rule parameters: key, label, help, type ('number' | 'time' | 'bool' | 'choice'), step ('any' for the decimal ones: the backend keeps any value) or the choices [value, label]. */
const PARAMS = [
  ['minDailyRestHours', t('rules.param.minDailyRestHours.label'), t('rules.param.minDailyRestHours.help'), 'number', 'any'],
  ['minWeeklyRestHours', t('rules.param.minWeeklyRestHours.label'), t('rules.param.minWeeklyRestHours.help'), 'number', 'any'],
  ['maxDutyHours', t('rules.param.maxDutyHours.label'), t('rules.param.maxDutyHours.help'), 'number', 'any'],
  ['maxWeekHours', t('rules.param.maxWeekHours.label'), t('rules.param.maxWeekHours.help'), 'number', 'any'],
  ['maxAverageWeekHours', t('rules.param.maxAverageWeekHours.label'), t('rules.param.maxAverageWeekHours.help'), 'number', 'any'],
  ['averagingPeriod', t('rules.param.averagingPeriod.label'), t('rules.param.averagingPeriod.help'), 'choice',
    [['HALF_YEAR', t('rules.param.averagingPeriod.halfYear')], ['ROLLING', t('rules.param.averagingPeriod.rolling')]]],
  ['averagingWeeks', t('rules.param.averagingWeeks.label'), t('rules.param.averagingWeeks.help'), 'number', 1],
  ['averageTolerancePercent', t('rules.param.averageTolerancePercent.label'), t('rules.param.averageTolerancePercent.help'), 'number', 'any'],
  ['minBlock', t('rules.param.minBlock.label'), t('rules.param.minBlock.help'), 'number', 1],
  ['maxBlock', t('rules.param.maxBlock.label'), t('rules.param.maxBlock.help'), 'number', 1],
  ['maxWishesPerPerson', t('rules.param.maxWishesPerPerson.label'), t('rules.param.maxWishesPerPerson.help'), 'number', 1],
  ['holidayWorkEarnsCompRest', t('rules.param.holidayWorkEarnsCompRest.label'), t('rules.param.holidayWorkEarnsCompRest.help'), 'bool'],
  ['nightStart', t('rules.param.nightStart.label'), '', 'time'],
  ['nightEnd', t('rules.param.nightEnd.label'), '', 'time'],
  ['nightMinHours', t('rules.param.nightMinHours.label'), t('rules.param.nightMinHours.help'), 'number', 'any'],
  ['maxOverTargetPercent', t('rules.param.maxOverTargetPercent.label'), t('rules.param.maxOverTargetPercent.help'), 'number', 'any'],
  ['minFreeSundays', t('rules.param.minFreeSundays.label'), t('rules.param.minFreeSundays.help'), 'number', 1],
  ['freeInsideAbsence', t('rules.param.freeInsideAbsence.label'), t('rules.param.freeInsideAbsence.help'), 'bool'],
  ['accountBalancing', t('rules.param.accountBalancing.label'), '', 'choice',
    [['PERIOD_END', t('rules.param.accountBalancing.periodEnd')], ['MONTHLY_SHARE', t('rules.param.accountBalancing.share')]]],
  ['accountCarryShare', t('rules.param.accountCarryShare.label'), t('rules.param.accountCarryShare.help'), 'number', 'any'],
  ['carryMinusRest', t('rules.param.carryMinusRest.label'), '', 'bool'],
  ['historyMonths', t('rules.param.historyMonths.label'), t('rules.param.historyMonths.help'), 'number', 1],
];
const PARAM = Object.fromEntries(PARAMS.map((p) => [p[0], p]));
/** Help per parameter: what it does and what raising or lowering it changes. */
const PARAM_HELP = Object.fromEntries(PARAMS.map(([key]) => [key, t(`help.param.${key}`)]));
const GOAL_HELP = { blocks: t('help.goal.blocks'), wishes: t('help.goal.wishes'), fairness: t('help.goal.fairness'), hours: t('help.goal.hours'),
  family: t('help.goal.family') };

/** Planning goals: group (lib/rule-groups.js), title and sentences whose placeholders are parameter fields. */
const GOALS = [
  ['blocks', t('rules.goal.blocks.title'), [t('rules.goal.blocks.text')]],
  ['wishes', t('rules.goal.wishes.title'), [t('rules.goal.wishes.text')]],
  ['fairness', t('rules.goal.fairness.title'), [t('rules.goal.fairness.text')]],
  ['hours', t('rules.goal.hours.title'), [t('rules.goal.hours.over'), t('rules.goal.hours.balancing'), t('rules.goal.hours.account'),
    t('rules.goal.hours.minusRest')]],
  ['family', t('rules.goal.family.title'), [t('rules.goal.family.text')]],
];
const IMPORTANCE = [['less', t('rules.importance.less')], ['normal', t('rules.importance.normal')],
  ['more', t('rules.importance.more')], [CUSTOM, t('rules.importance.custom')]];
const NIGHT_MODES = [['HABIT', t('rules.night.habit')], ['EVEN', t('rules.night.even')]];
const LAW_FIELDS = ['minDailyRestHours', 'minWeeklyRestHours', 'maxDutyHours', 'maxWeekHours', 'maxAverageWeekHours',
  'averagingPeriod', 'averagingWeeks', 'averageTolerancePercent', 'minFreeSundays', 'freeInsideAbsence', 'holidayWorkEarnsCompRest'];

export async function render(root, ctx) {
  const { api, canEdit } = ctx;
  const [constraints, rules] = await Promise.all([api.constraints(), api.rules()]);
  const weights = Object.fromEntries(constraints.map((c) => [c.id, c.weight ?? rules.weights?.[c.id] ?? c.defaultWeight]));
  // a copy of everything loaded, so that fields this page does not know are saved unchanged
  const params = { ...rules, nightDistribution: nightDistribution(rules) };
  params.maxOverTargetPercent ??= 10; // rules saved before the setting existed
  params.minFreeSundays ??= 2;
  params.averageTolerancePercent ??= 10;
  params.freeInsideAbsence ??= true;
  params.averagingPeriod ??= 'HALF_YEAR';
  params.accountBalancing ??= 'PERIOD_END';
  params.carryMinusRest ??= false;

  root.append(h('h1', {}, t('nav.rules')));
  if (!canEdit) root.append(h('p.alert', {}, t('rules.readonly')));

  let dirty = false;
  let edits = 0; // counts changes, so that a save does not clear an edit made while it was under way
  const status = h('span.muted', { role: 'status' });
  const saveBar = canEdit && h('div.save-bar', {}, h('button.btn.btn-primary', { type: 'button', onclick: save }, t('common.save')), status);
  const descriptions = [];
  const weightInputs = {};
  const groupRadios = [];
  const form = h('form', { onsubmit: (e) => { e.preventDefault(); save(); }, oninput: changed });

  // 1. Planning goals
  const nightWarning = h('div', { 'aria-live': 'polite' });
  const goalOf = Object.fromEntries(GOALS.map(([group, title]) => [group, title]));
  form.append(h('section.section', { 'aria-labelledby': 'h-goals' }, h('h2', { id: 'h-goals' }, t('rules.goals.title')),
    h('p.muted', {}, t('rules.goals.help')),
    h('div.goal', {}, h('fieldset.night', {}, h('legend', {}, h('strong', {}, t('rules.night.title')), info(t('help.param.nightDistribution'), t('rules.night.title'))),
      NIGHT_MODES.map(([mode, label]) => h('label.choice', {},
        h('input', { type: 'radio', name: 'nightDistribution', value: mode, checked: params.nightDistribution === mode, disabled: !canEdit,
          oninput: () => { params.nightDistribution = mode; } }), ' ', label))), nightWarning),
    GOALS.map(([group, title, texts]) => {
      const importance = importanceControl(group, title);
      return h('div.goal', { 'data-group': group }, h('div', {}, h('h3', {}, title, info(GOAL_HELP[group], title)), texts.map((text) => h('p', {}, sentence(text)))), importance);
    })));

  // 2. Law and works agreement: text until "Ändern"
  const lawText = h('ul.law-text', {});
  const lawHelp = {};
  const monthNote = h('p.muted', {});
  const lawFields = h('div.params', { id: 'law-fields', hidden: true }, LAW_FIELDS.map(paramField), monthNote);
  const unlock = h('button.btn.btn-secondary', { type: 'button', 'aria-expanded': 'false', 'aria-controls': 'law-fields', 'aria-describedby': 'h-law',
    onclick: () => { lawFields.hidden = false; lawText.hidden = true; unlock.hidden = true; unlock.setAttribute('aria-expanded', 'true'); lawFields.querySelector('input').focus(); } },
  t('rules.law.change'));
  form.append(h('section.section', { 'aria-labelledby': 'h-law' }, h('h2', { id: 'h-law' }, t('rules.law.title')),
    h('p.muted', {}, t('rules.law.help')), lawText, canEdit && unlock, canEdit && lawFields));

  // 3. Advanced, collapsed: night window, all weights and the rule catalogue
  const advanced = h('details.rules-advanced', {}, h('summary', {}, h('h2', { id: 'h-advanced' }, t('rules.advanced.title'))),
    h('p.muted', {}, t('rules.advanced.help')),
    h('section', { 'aria-labelledby': 'h-night-window' }, h('h3', { id: 'h-night-window' }, t('rules.nightWindow')),
      h('div.params', {}, ['nightStart', 'nightEnd', 'nightMinHours'].map(paramField))));
  for (const [level, title, help] of LEVELS) {
    const list = constraints.filter((c) => c.level === level);
    if (!list.length) continue;
    advanced.append(h('section', { 'aria-labelledby': `lvl-${level}` },
      h('h3', { id: `lvl-${level}` }, title), h('p.muted', {}, help),
      list.map((c) => h('div.rule-row', {},
        h('div', {}, h('strong', {}, constraintLabel(c)), hasText(`help.constraint.${c.id}`) && info(t(`help.constraint.${c.id}`), constraintLabel(c)), description(c),
          groupName(c) && h('div.muted', {}, t('rules.inGoal', { goal: groupName(c) }))),
        level === 'SOFT' && !c.tunable ? h('div.muted', {}, t('rules.weightFixed', { weight: c.defaultWeight }))
          : level === 'SOFT' ? (canEdit ? weightControl(c) : h('div', {}, t('rules.weight', { weight: weights[c.id] }), h('div.muted', { style: { fontSize: '.8rem' } }, t('rules.default', { weight: c.defaultWeight })))) : h('div.muted', {}, level === 'HARD' ? t('rules.always') : t('rules.weight', { weight: weights[c.id] }))))));
  }
  form.append(advanced);
  if (saveBar) form.append(saveBar);
  root.append(form);
  ctx.isDirty = () => dirty;
  refresh();

  /** The goal whose Wichtigkeit sets the weight of a constraint, if any. */
  function groupName(c) {
    const group = c.tunable && Object.keys(GROUPS).find((g) => GROUPS[g].includes(c.id));
    return group && goalOf[group];
  }

  /** A text with {key} placeholders as parameter fields (viewers: the values). */
  function sentence(text) {
    return text.split(/\{(\w+)\}/).map((part, i) => (i % 2 ? [canEdit ? [h('label.visually-hidden', { for: `param-${part}` }, PARAM[part][1]), paramInput(part)]
      : h('strong', {}, shown(part)), info(PARAM_HELP[part], PARAM[part][1])] : part));
  }

  /** Wichtigkeit of a goal: weniger, normal, mehr, and "angepasst" while weights set by hand differ. */
  function importanceControl(group, title) {
    if (groupLevel(group, constraints, weights) === null) return null;
    return h('fieldset.importance', {}, h('legend', {}, t('rules.importance'), h('span.visually-hidden', {}, ` ${title}`),
      info(t(group === 'fairness' ? 'help.rules.importance.fairness' : 'help.rules.importance'), `${t('rules.importance')} ${title}`)),
      IMPORTANCE.map(([level, label]) => {
        const input = h('input', { type: 'radio', name: `importance-${group}`, value: level, disabled: !canEdit,
          oninput: () => {
            if (level === CUSTOM) return;
            applyLevel(group, level, constraints, weights);
            for (const [id, el] of Object.entries(weightInputs)) el.value = weights[id];
          } });
        groupRadios.push([group, level, input]);
        return h('label', { 'data-level': level }, input, h('span', {}, label));
      }));
  }

  function paramInput(key) {
    const [, , , type, step] = PARAM[key];
    if (type === 'choice') {
      return h('select', { id: `param-${key}`, oninput: (e) => { params[key] = e.target.value; } },
        PARAM[key][4].map(([value, text]) => h('option', { value, selected: params[key] === value }, text)));
    }
    if (type === 'bool') return h('input', { id: `param-${key}`, type: 'checkbox', checked: Boolean(params[key]), oninput: (e) => { params[key] = e.target.checked; } });
    // the hours account share is stored as a fraction and shown in percent
    return h('input', { id: `param-${key}`, type, step, min: type === 'number' ? 0 : null, max: key === 'accountCarryShare' ? 100 : null, required: true,
      value: type === 'time' ? String(params[key] ?? '').slice(0, 5) : key === 'accountCarryShare' ? percent(params[key]) : params[key],
      oninput: (e) => { params[key] = type === 'time' ? e.target.value : Number(e.target.value) / (key === 'accountCarryShare' ? 100 : 1); } });
  }

  function shown(key) {
    const type = PARAM[key][3];
    if (type === 'choice') return PARAM[key][4].find(([value]) => value === params[key])?.[1] ?? String(params[key]);
    return type === 'bool' ? (params[key] ? t('common.yes') : t('common.no')) : type === 'time' ? String(params[key] ?? '').slice(0, 5)
      : key === 'accountCarryShare' ? String(percent(params[key])).replace('.', ',') : String(params[key]).replace('.', ',');
  }

  /** A labelled field with its help; viewers get the value as text. */
  function paramField(key) {
    const [, label, help, type] = PARAM[key];
    const more = info(PARAM_HELP[key], label);
    if (!canEdit) return h('div.field', {}, h('div.with-info', {}, h('strong', {}, label), more), h('span', {}, help ? t('rules.valueWithHelp', { value: shown(key), help }) : shown(key)));
    if (type === 'bool') {
      return h('div.field', {}, h('div.with-info', {}, h('label', {}, h('input', { id: `param-${key}`, type: 'checkbox', checked: Boolean(params[key]),
        oninput: (e) => { params[key] = e.target.checked; } }), ' ', label), more), help && h('span', {}, help));
    }
    if (type === 'choice') {
      return h('div.field', {}, h('div.with-info', {}, h('label', { for: `param-${key}` }, label), more),
        h('select', { id: `param-${key}`, oninput: (e) => { params[key] = e.target.value; } },
          PARAM[key][4].map(([value, text]) => h('option', { value, selected: params[key] === value }, text))), help && h('span', {}, help));
    }
    return h('div.field', {}, h('div.with-info', {}, h('label', { for: `param-${key}` }, label), more), paramInput(key), help && h('span', {}, help));
  }

  /** The description with the numbers of the current parameters; refreshed while they are edited. */
  function description(c) {
    const el = h('div.muted', {}, constraintDescription(c, params));
    descriptions.push([c, el]);
    return el;
  }

  /** Everything that follows the parameters and weights: rule texts, law text, Wichtigkeit, the night warning. */
  function refresh() {
    for (const [c, el] of descriptions) el.textContent = constraintDescription(c, params);
    const p = formatParams(params);
    // each line with its own help, made once so that an open help stays open while the values change
    lawText.replaceChildren(...[[t('rules.law.rest', p), t('help.law.rest')], [t('rules.law.day', p), t('help.law.day')],
      [t('rules.law.week', p), t('help.law.week')], [t('rules.law.month', p), t('help.law.month')], [params.averagingPeriod === 'ROLLING' ? t('rules.law.average', p) : t('rules.law.averageHalfYear', p), t('help.law.average')],
      [t('rules.law.sundays', p), t('help.law.sundays')],
      [params.freeInsideAbsence ? t('rules.law.insideAbsence') : t('rules.law.noInsideAbsence'), t('help.law.insideAbsence')],
      [params.holidayWorkEarnsCompRest ? t('rules.law.holiday') : t('rules.law.noHoliday'), t('help.law.holiday')]]
      .map(([line, help]) => h('li', {}, line, lawHelp[help] ??= info(help, line))));
    monthNote.textContent = t('rules.law.month', p);
    for (const [group, level, input] of groupRadios) {
      const current = groupLevel(group, constraints, weights);
      input.checked = current === level;
      if (level === CUSTOM) input.parentElement.hidden = current !== CUSTOM;
    }
    nightWarning.replaceChildren(nightRuleMissing(params.nightDistribution, weights) ? h('p.alert.alert-error', {}, t('rules.night.warning')) : '');
  }

  function changed() {
    dirty = true;
    edits++;
    status.textContent = t('rules.unsaved');
    refresh();
  }

  function weightControl(c) {
    const max = Math.max(1000, c.defaultWeight * 4, weights[c.id]);
    const id = `w-${c.id}`;
    const number = h('input', { id, type: 'number', min: 0, max, step: 1, required: true, value: weights[c.id],
      oninput: (e) => { weights[c.id] = Number(e.target.value); } });
    weightInputs[c.id] = number;
    const reset = () => { number.value = c.defaultWeight; weights[c.id] = c.defaultWeight; changed(); };
    return h('div.weight', {}, h('label.visually-hidden', { for: id }, t('rules.weightLabel', { label: constraintLabel(c) })), number,
      h('span.muted', {}, t('rules.default', { weight: c.defaultWeight })),
      h('button.btn.btn-secondary.btn-small', { type: 'button', onclick: reset,
        'aria-label': t('rules.resetWeightLabel', { label: constraintLabel(c), weight: c.defaultWeight }) }, t('rules.resetWeight')));
  }

  async function save() {
    // a field that the browser cannot show its message on: open its section first
    const invalid = form.querySelector('input:invalid');
    if (invalid?.closest('details')) advanced.open = true;
    if (invalid && lawFields.contains(invalid) && lawFields.hidden) unlock.click();
    if (!form.reportValidity()) return;
    const body = { ...params, weights: weightsToSave(constraints, weights, rules.weights) };
    const sent = edits;
    if (await guarded(async () => { await api.saveRules(body); return true; }, t('rules.saved')) && edits === sent) {
      dirty = false;
      status.textContent = '';
    }
  }
}

/** A share (0..1) in percent, rounded so that 0.29 shows as 29 and not 28.999…. */
const percent = (share) => Math.round(share * 1e6) / 1e4;

/** Weights for PUT /api/rules: the stored ones, overwritten by the tunable soft weights (others are not settings). */
export function weightsToSave(constraints, weights, stored) {
  const tunable = constraints.filter((c) => c.level === 'SOFT' && c.tunable).map((c) => [c.id, weights[c.id]]);
  return { ...(stored ?? {}), ...Object.fromEntries(tunable) };
}
