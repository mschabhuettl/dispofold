// Personen: without an id a searchable list, with an id one page per person with the key figures of the month and
// "Warum dieser Dienst?" (constraint hits by date) and, for planners, the switch "Aus dem Quellsystem nicht mehr
// übernehmen" and one form for everything about the person (master data, entry and exit, areas, usual mix,
// restrictions, family rules), saved with the revision it was read on (docs/api.md, "Stammdaten bearbeiten").
// #/person/neu creates a person.
import { dayLabel, monthLabel, WEEKDAY_SHORT } from '../lib/dates.js';
import { fmt, h, toast } from '../lib/dom.js';
import { goalReasons, groupHitsByDate, hitEffect, hitSummary } from '../lib/explain.js';
import { personName } from '../lib/grid-model.js';
import { constraintLabel, hitText } from '../lib/codes.js';
import { CATEGORIES, conflictDecision, fromForm, inUseQuestion, itemLabel, restrictionFromForm, toForm } from '../lib/masterdata-edit.js';
import { exceptionLines } from '../lib/area-states.js';
import { accountRestShort, nightStatus } from '../lib/fairness.js';
import { info } from '../lib/info.js';
import { effectiveMix, MIX_CATEGORIES, mixFromForm, mixNightNote, mixRows, mixText, nightNote, sharePercent } from '../lib/shift-mix.js';
import { neverNightShifts } from '../lib/data-checks.js';
import { t } from '../lib/texts.js';
import { control, de, employmentText, problemAlert, readForm } from './masterdata.js';

const LEVEL = { HARD: t('person.level.hard'), MEDIUM: t('person.level.medium'), SOFT: t('person.level.soft') };
const CATEGORY = { EARLY: t('masterdata.category.early'), DAY: t('masterdata.category.day'), LATE: t('masterdata.category.late'), NIGHT: t('masterdata.category.night') };
const CATEGORY_NAMES = CATEGORY;
const WEEKDAY_OPTIONS = WEEKDAY_SHORT.map((d, i) => [String(i + 1), d]);
const NEW = 'neu';
const inline = { fontWeight: 400, marginRight: '.6rem' };
const bare = { border: 0, padding: 0, margin: 0 };
const wide = { flexBasis: '100%' };
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const byName = (a, b) => personName(a).localeCompare(personName(b), 'de');

export async function render(root, ctx, [personId]) {
  if (!personId) return renderList(root, ctx);
  const { api, month } = ctx;
  const dataset = await ctx.dataset();
  const rules = await api.rules().catch(() => null);
  const nightDistribution = rules?.nightDistribution;
  const back = h('p', {}, h('a', { href: '#/person' }, t('people.all')));
  if (personId === NEW && ctx.canEdit) {
    root.append(h('h1', {}, t('people.new')), back, editor(root, ctx, dataset, null, rules));
    return undefined;
  }
  const person = dataset.people.find((p) => p.id === personId);
  if (!person) {
    root.append(h('h1', {}, t('nav.person')), h('p.alert', {}, t('people.notFound')), back);
    return undefined;
  }

  const areaNames = new Map(dataset.areas.map((a) => [a.id, a.name]));
  const constraintLabels = new Map((await api.constraints().catch(() => [])).map((c) => [c.id, constraintLabel(c)]));
  const [explanation, state] = await Promise.all([api.person(month, person.id).catch((e) => ({ error: e.message })),
    api.planState(month).catch(() => null)]);
  // before the month is planned the figures of a plan are left out (without the plan state they show as before)
  const planned = state ? state.plan.assignments.length > 0 : true;
  const employment = employmentText(person);
  root.append(h('h1', {}, `${personName(person)}`),
    h('p.muted', {}, t('person.meta', { short: person.shortName ?? '', hours: fmt.num(person.weeklyHours, 1), areas: person.areaIds.map((a) => areaNames.get(a) ?? a).join(', '), month: monthLabel(month) }),
      employment && ` · ${t('masterdata.col.employment')}: ${employment}`),
    h('div.toolbar', {}, h('a', { href: '#/person' }, t('people.all')),
      ctx.canEdit && h('button.btn.btn-secondary.btn-small', { type: 'button', onclick: () => {
        root.querySelector('#h-edit')?.scrollIntoView();
        root.querySelector('#f-firstName')?.focus();
      } }, t('masterdata.edit'))));
  if (person.excludedFrom) root.append(h('p.alert', {}, t('people.excluded.note', { date: de(person.excludedFrom) })));
  if (explanation.error) root.append(h('p.alert', {}, t('person.noStats', { message: explanation.error })));
  else {
    if (!planned) root.append(h('p.alert', { id: 'person-no-plan' }, t('person.noPlan', { month: monthLabel(month) })));
    root.append(...figures(explanation, person, areaNames, constraintLabels, nightDistribution, ctx.canEdit, planned));
  }
  // the switch and the form are for planners only (viewers get no restrictions, family rules or balances anyway)
  if (ctx.canEdit) root.append(sourceSection(root, ctx, dataset, person), editor(root, ctx, dataset, person.id, rules));
  return undefined;
}

/**
 * "Aus dem Quellsystem nicht mehr übernehmen": saved at once (with the revision the page was read on); the server sets
 * the first day the person is not planned. A conflict reloads the page with the newer version.
 */
function sourceSection(root, ctx, dataset, person) {
  const reload = async () => {
    ctx.invalidateDataset();
    root.replaceChildren();
    await render(root, ctx, [person.id]);
  };
  const problems = h('div.form-problems', { 'aria-live': 'polite' });
  const box = h('input', { id: 'f-excluded', type: 'checkbox', checked: Boolean(person.excludedFrom), onchange: async () => {
    const on = box.checked;
    const name = personName(person);
    if (!confirm(on ? t('people.excluded.confirmOn', { name }) : t('people.excluded.confirmOff', { name }))) {
      box.checked = !on;
      return;
    }
    box.disabled = true;
    try {
      await ctx.api.setExcluded(person.id, on, dataset.revision ?? 0);
    } catch (e) {
      box.checked = !on;
      box.disabled = false;
      if (e.code !== 'masterdata.conflict') return problems.replaceChildren(problemAlert(e));
      toast(t('masterdata.conflict.reloaded'), true);
      return reload();
    }
    toast(on ? t('people.excluded.on') : t('people.excluded.off'));
    return reload();
  } });
  return h('section.section', { 'aria-labelledby': 'h-source' }, h('h2', { id: 'h-source' }, t('people.section.source')),
    h('p.muted', {}, t('people.excluded.help')),
    h('div.with-info', {}, h('label', {}, box, ` ${t('people.excluded.switch')}`), info(t('help.field.people.excluded'), t('people.excluded.switch'))),
    problems);
}

/** The list of people: name, short name, areas and weekly hours, searchable by name, short name and area name. */
async function renderList(root, ctx) {
  const dataset = await ctx.dataset();
  const areaNames = new Map(dataset.areas.map((a) => [a.id, a.name]));
  // people no longer taken over from the source: greyed out, with a note after the name
  const rows = [...dataset.people].sort(byName).map((p) => {
    const areas = p.areaIds.map((id) => areaNames.get(id) ?? id).join(', ');
    return { text: [personName(p), p.shortName ?? '', areas].join(' ').toLocaleLowerCase('de'),
      tr: h('tr', { class: p.excludedFrom ? 'muted' : null, 'data-excluded': p.excludedFrom ? 'true' : null },
        h('td', {}, h('a', { href: `#/person/${encodeURIComponent(p.id)}` }, personName(p)), p.excludedFrom && [' ', h('span.badge', {}, t('people.excluded.badge'))]),
        h('td', {}, p.shortName ?? ''), h('td', {}, areas), h('td.num', {}, fmt.num(p.weeklyHours, 1))) };
  });
  const excluded = dataset.people.filter((p) => p.excludedFrom).length;
  const none = h('p.muted', { hidden: true }, t('people.noMatch'));
  const search = h('input', { id: 'person-search', type: 'search', autocomplete: 'off', placeholder: t('people.searchPlaceholder'), oninput: () => {
    const terms = search.value.trim().toLocaleLowerCase('de').split(/\s+/).filter(Boolean);
    let shown = 0;
    for (const r of rows) {
      r.tr.hidden = !terms.every((term) => r.text.includes(term));
      if (!r.tr.hidden) shown++;
    }
    none.hidden = shown > 0;
  } });
  root.append(h('h1', {}, t('people.title')), h('p.muted', {}, excluded ? t('people.countExcluded', { count: rows.length, excluded }) : t('people.count', { count: rows.length })),
    h('div.toolbar', {}, h('div.field', {}, h('label', { for: 'person-search' }, t('people.search')), search),
      ctx.canEdit && h('a.btn.btn-primary.btn-small', { href: `#/person/${NEW}` }, t('people.new'))),
    h('div', { style: { overflowX: 'auto' } }, h('table.data', { 'data-table': 'people' },
      h('thead', {}, h('tr', {}, h('th', {}, t('masterdata.col.name')), h('th', {}, t('masterdata.col.shortName')), h('th', {}, t('masterdata.col.areas')),
        h('th.num', {}, t('masterdata.col.weeklyHours')))),
      h('tbody', {}, rows.map((r) => r.tr)))), none);
}

/**
 * The figures of the month and "Warum dieser Dienst?"; personal ones (hours account, wishes, balances) for planners only.
 * Not planned yet: only what does not depend on a plan (Soll and Ziel, account before, available days, history).
 */
function figures(explanation, person, areaNames, constraintLabels, nightDistribution, canEdit, planned) {
  const s = explanation.stats;
  // every tile with its help: what the number counts
  const kpi = (label, value, help) => h('div.kpi', {}, h('div.label', {}, label, info(help, label)), h('div.value', {}, value));
  const hours = (v) => `${fmt.num(v, 1)} h`;
  // Ziel: the goal the soft rule aims at in this plan; personal (hours account), so null for viewers. Soll: the contract target.
  const goal = s.scaledGoalHours ?? s.goalHours;
  const reasons = goalReasons(s);
  // planners: planned plus credited hours, what the rule compares with the Ziel; viewers get neither credited nor Ziel
  const hoursTile = h('div.kpi.kpi-hours', {}, h('div.label', {}, t('person.kpi.hours'), info(canEdit ? t('help.figure.hours') : t('help.figure.hoursViewer'), t('person.kpi.hoursInfo'), 'hours-help')),
    h('div.value', {}, !planned
      ? (goal != null ? t('person.kpi.goalOnly', { goal: fmt.num(goal, 1), target: fmt.num(s.targetHours, 1) }) : t('person.kpi.targetOnly', { target: fmt.num(s.targetHours, 1) }))
      : goal != null
        ? t('person.kpi.hoursOfGoal', { hours: fmt.num(s.plannedHours + s.creditedHours, 1), credited: fmt.num(s.creditedHours, 1), goal: fmt.num(goal, 1), target: fmt.num(s.targetHours, 1) })
        : t('person.kpi.hoursOfTarget', { planned: fmt.num(s.plannedHours, 1), target: fmt.num(s.targetHours, 1) })),
    // next to Ziel and Soll: why they differ (planners only, viewers get no goal)
    reasons.length > 0 && h('p.kpi-help', { id: 'person-goal-reason' }, t('person.goal.why', { reasons: reasons.join('; ') })));
  return [
    h('section.section', { 'aria-labelledby': 'h-hours' }, h('h2', { id: 'h-hours' }, t('person.section.hours')),
      canEdit && s.creditFallbackDays != null && h('p.muted', { id: 'person-credit-fallback' }, t('person.creditFallback', { count: s.creditFallbackDays })),
      h('div.kpis', {},
        hoursTile,
        // the hours account is personal: planners only (viewers get null from the server)
        canEdit && kpi(t('person.kpi.accountBefore'), hours(s.accountBefore), t('help.figure.accountBefore')), canEdit && planned && kpi(t('person.kpi.accountAfter'), hours(s.accountAfter), t('help.figure.accountAfter')),
        canEdit && s.periodBalance != null && kpi(t('person.kpi.periodBalance'), hours(s.periodBalance), t('help.figure.periodBalance')),
        canEdit && s.accountRest != null && kpi(t('person.kpi.accountRest'), accountRestShort(s), t('help.figure.accountRest')),
        planned && kpi(t('person.kpi.duties'), fmt.num(s.duties), t('help.figure.duties')),
        s.availableDays != null && kpi(t('person.kpi.availableDays'), fmt.num(s.availableDays), t('help.figure.availableDays')),
        canEdit && s.chargedHours != null && kpi(t('person.kpi.charged'), hours(s.chargedHours), t('help.figure.chargedHours')),
        // the 52-week average is only binding when the history covers the whole period
        planned && s.averageWeekHours != null && kpi(s.averageComplete ? t('person.kpi.averageWeek') : t('person.kpi.averageWeekPartial'), s.averageLimitHours != null ? t('person.kpi.averageWeekOf', { value: hours(s.averageWeekHours), limit: hours(s.averageLimitHours) }) : hours(s.averageWeekHours), t('help.figure.averageWeekHours')))),
    planned && h('section.section', { 'aria-labelledby': 'h-load' }, h('h2', { id: 'h-load' }, t('person.section.load')),
      h('div.kpis', {},
        nightsTile(s, person.shiftMix), kpi(t('person.kpi.weekendsWorked'), fmt.num(s.weekendsWorked), t('help.figure.weekendsWorked')), kpi(t('person.kpi.weekendsOff'), fmt.num(s.weekendsOff), t('help.figure.weekendsOff')),
        kpi(t('person.kpi.holidays'), fmt.num(s.holidays), t('help.figure.holidays')), kpi(t('person.kpi.unpopular'), fmt.num(s.unpopular), t('help.figure.unpopular')), kpi(t('person.kpi.singleDays'), fmt.num(s.singleDays), t('help.figure.singleDays')),
        // wishes are personal: planners only
        canEdit && kpi(t('person.kpi.wishesMet'), t('common.outOf', { value: s.wishesMet, total: s.wishes }), t('help.figure.wishesMet')),
        kpi(t('person.kpi.compRestPlanned'), fmt.num(s.compRestPlanned), t('help.figure.compRestPlanned')),
        // compensatory-rest balance and the hours account are personal: planners only (viewers get null)
        canEdit && kpi(t('person.kpi.compRestEarned'), fmt.num(s.compRestEarned), t('help.figure.compRestEarned')),
        canEdit && kpi(t('person.kpi.compRestOpenAfter'), fmt.num(s.compRestBalanceAfter), t('help.figure.compRestBalanceAfter')))),
    planned && mixSection(s, person.shiftMix, areaNames, nightDistribution),
    h('section.section', { 'aria-labelledby': 'h-hist' }, h('h2', { id: 'h-hist' }, t('person.section.history')),
      h('div.kpis', {},
        kpi(t('person.kpi.nights'), fmt.num(s.historyNights), t('help.figure.historyNights')), kpi(t('person.kpi.weekends'), fmt.num(s.historyWeekends), t('help.figure.historyWeekends')), kpi(t('person.kpi.holidays'), fmt.num(s.historyHolidays), t('help.figure.historyHolidays')),
        kpi(t('person.kpi.unpopular'), fmt.num(s.historyUnpopular), t('help.figure.historyUnpopular')), canEdit && kpi(t('person.kpi.wishesMet'), t('common.outOf', { value: s.historyWishesMet, total: s.historyWishes }), t('help.figure.historyWishesMet')))),
    planned && h('section.section', { 'aria-labelledby': 'h-why' }, h('h2', { id: 'h-why' }, t('person.section.why'),
      info(t('help.person.why'), t('person.section.why'))),
      explanation.hits.length ? groupHitsByDate(explanation.hits).map((g) => h('div.hits-day', {},
        h('h3', {}, g.date ? dayLabel(g.date) : t('person.wholeMonth'), ' ', h('span.muted', {}, `(${hitSummary(g.hits).join(', ')})`)),
        g.hits.map((hit) => h('div.hit', {},
          h('span', { class: hit.level === 'HARD' ? 'badge badge-hard' : 'badge' }, LEVEL[hit.level] ?? hit.level),
          h('span', {}, h('strong', {}, constraintLabels.get(hit.constraintId) ?? constraintLabel({ id: hit.constraintId })), ': ', hitText(hit, areaNames)),
          h('span', { class: `effect ${hit.score < 0 ? 'score-neg' : hit.score > 0 ? 'score-pos' : ''}` }, hitEffect(hit))))))
        : h('p.muted', {}, t('person.noHits'))),
  ].filter(Boolean);
}

/** Nights of the month and the night comparison (planners only: who is outside it tells the mix or restrictions). */
function nightsTile(s, usual) {
  const status = nightStatus(s, usual);
  return h('div.kpi.kpi-nights', {}, h('div.label', {}, t('person.kpi.nights'), info(t('help.figure.nights'), t('person.kpi.nights'))), h('div.value', {}, fmt.num(s.nights)), status && h('p.kpi-help', {}, status));
}

/**
 * Planned against usual share of the duties per category and area, and the shares that apply where the night share does
 * not count (EVEN, never nights); nothing to show without either.
 */
function mixSection(stats, usual, areaNames, nightDistribution) {
  const effective = effectiveMix(usual, nightDistribution, stats.nightReason === 'NEVER');
  const rows = mixRows(stats.plannedMix, usual, (id) => areaNames.get(id) ?? id, effective);
  if (!rows.length) return null;
  return h('section.section', { 'aria-labelledby': 'h-mix' }, h('h2', { id: 'h-mix' }, t('person.section.mix'),
    info(t('help.figure.plannedMix'), t('person.section.mix'))),
    Object.keys(usual?.categories ?? {}).length > 0 && h('p.muted', {}, mixNightNote(nightDistribution, stats, usual)),
    // viewers get no usual mix: no empty column
    h('table.data', {}, h('thead', {}, h('tr', {}, h('th', {}, t('person.mix.group')), h('th.num', {}, t('person.mix.planned')), usual && h('th.num', {}, t('person.mix.usual')),
      effective && h('th.num', {}, t('person.mix.effective')))),
    h('tbody', {}, rows.map((r) => h('tr', {}, h('td', {}, r.name), h('td.num', {}, sharePercent(r.planned)), usual && h('td.num', {}, sharePercent(r.usual)),
      effective && h('td.num', {}, r.effective == null && r.group === 'categories' && r.usual != null ? t('person.mix.ignored') : sharePercent(r.effective)))))));
}

/**
 * The one form for a person (personId null: a new one): master data, entry and exit, areas, usual mix, restrictions and
 * family rules. Saving sends the person (PUT or POST) and then, if they changed, the restrictions with the revision of
 * the first answer; a 409 masterdata.conflict is resolved as on the Stammdaten page.
 */
function editor(root, ctx, initial, personId, rules) {
  const { api } = ctx;
  let dataset = initial;
  let key = personId;
  let base;
  let baseRestrictions;
  const rebase = () => {
    base = key == null ? null : dataset.people.find((p) => p.id === key) ?? null;
    // the exceptions as read: the states of the areas belong to the person (lib/area-states.js)
    baseRestrictions = key == null ? [] : (dataset.exceptions ?? dataset.restrictions ?? []).filter((r) => r.personId === key);
  };
  rebase();
  const master = [{ name: 'firstName', label: t('masterdata.col.firstName'), help: t('help.field.people.firstName'), required: true }, { name: 'lastName', label: t('masterdata.col.lastName'), help: t('help.field.people.lastName'), required: true },
    { name: 'shortName', label: t('masterdata.col.shortName'), help: t('help.field.people.shortName') },
    { name: 'weeklyHours', label: t('masterdata.col.weeklyHours'), help: t('help.field.people.weeklyHours'), type: 'number', step: 'any', min: 0, required: true },
    { name: 'hoursAccount', label: t('masterdata.col.account'), help: t('help.field.people.hoursAccount'), type: 'number', step: 'any', required: true },
    { name: 'compRestBalance', label: t('masterdata.col.compRestOpen'), help: t('help.field.people.compRestBalance'), type: 'number', step: 1, min: 0, required: true },
    { name: 'externalId', label: t('masterdata.col.externalId'), help: t('help.field.people.externalId') }];
  const employment = [{ name: 'entryDate', label: t('masterdata.entryDate'), help: t('help.field.people.entryDate'), type: 'date' }, { name: 'exitDate', label: t('masterdata.exitDate'), help: t('help.field.people.exitDate'), type: 'date' }];
  const addFields = () => [
    { name: 'target', label: t('masterdata.restriction.target'), help: t('help.field.restrictions.target'), type: 'select', options: [...CATEGORIES.map((c) => [`category:${c}`, t('masterdata.restriction.category', { category: CATEGORY[c] })]),
      ...dataset.shifts.map((s) => [`shift:${s.id}`, `${s.code} ${s.name}`])] },
    { name: 'weekday', label: t('masterdata.restriction.weekday'), help: t('help.field.restrictions.weekday'), type: 'select', options: [['0', t('masterdata.restriction.everyDay')], ...WEEKDAY_OPTIONS] },
    { name: 'kind', label: t('masterdata.restriction.kind'), help: t('help.field.restrictions.kind'), type: 'select', options: [['NEVER', t('masterdata.restriction.never')], ['ON_REQUEST', t('masterdata.restriction.onRequest')]] },
    { name: 'validFrom', label: t('masterdata.restriction.validFrom'), help: t('help.field.restrictions.validFrom'), type: 'date' }, { name: 'validTo', label: t('masterdata.restriction.validTo'), help: t('help.field.restrictions.validTo'), type: 'date' }];
  const section = h('section.section', { 'aria-labelledby': 'h-edit' }, h('h2', { id: 'h-edit' }, key == null ? t('people.section.new') : t('people.section.edit')));
  let form;
  let local; // the restrictions as edited, saved as a whole

  /** Draws the form with values (after a conflict or a rejected save the own ones), else with the person as stored. */
  function draw(values = toForm('people', base), restrictions = baseRestrictions) {
    local = restrictions.map((r) => ({ ...r }));
    const shifts = new Map(dataset.shifts.map((s) => [s.id, s]));
    const add = addFields();
    const chosen = () => [...form.querySelectorAll('select[data-area-state]')].filter((x) => x.value).map((x) => x.dataset.areaState);
    // per area its state, where it comes from (an import's areaSources) and below it only its exceptions, one line per
    // shift with the weekdays and dates folded in; exceptions by category and of shifts of other areas after them
    const areaRows = new Map();
    const extra = h('div', {});
    const lineItem = (line) => h('li', { 'data-exception': line.target }, line.text, ' ',
      h('button.btn-link', { type: 'button', 'aria-label': t('masterdata.restriction.removeLabel', { text: line.text }),
        onclick: () => { local = local.filter((r) => !line.rows.includes(r)); drawExceptions(); } }, t('masterdata.remove')));
    const drawExceptions = () => {
      const areas = chosen();
      const lines = exceptionLines(local, key, shifts, CATEGORY_NAMES, areas);
      for (const [areaId, row] of areaRows) {
        const own = lines.filter((l) => l.areaId === areaId);
        row.list.replaceChildren(...own.map(lineItem));
        row.list.hidden = !own.length || !areas.includes(areaId);
        const source = base?.areaSources ? (base.areaSources[areaId] || t('people.area.byHand')) : null;
        row.source.textContent = areas.includes(areaId) && source ? t('people.area.source', { source }) : '';
      }
      const rest = lines.filter((l) => !l.areaId);
      extra.replaceChildren(...(rest.length ? [h('p', {}, h('strong', {}, t('people.exceptions.other'))), h('ul', {}, rest.map(lineItem))] : []));
    };
    const states = values.areaStates ?? {};
    const areasBlock = h('div', { style: wide, id: 'person-areas' }, h('div.with-info', {}, h('strong', {}, t('masterdata.col.areas')),
      info(t('help.field.people.areaIds'), t('masterdata.col.areas'))),
    dataset.areas.map((a) => {
      const id = `f-area-${a.id}`;
      const value = !(values.areaIds ?? []).includes(a.id) ? '' : states[a.id] ?? 'PLAN';
      const select = h('select', { id, 'data-area-state': a.id },
        [['', t('areaState.out')], ['PLAN', t('areaState.plannable')], ['ON_REQUEST', t('areaState.manual')], ['NEVER', t('areaState.never')]]
          .map(([v, label]) => h('option', { value: v, selected: v === value }, label)));
      const row = { list: h('ul.area-exceptions', {}), source: h('span.muted', { 'data-area-source': a.id }) };
      areaRows.set(a.id, row);
      return h('div.area-state', { 'data-area': a.id }, h('div.field', {}, h('label', { for: id }, `${a.name} (${a.id})`), select), row.source, row.list);
    }), extra);
    const restrictionsBlock = h('div', { style: wide }, h('strong', {}, t('people.exceptions')),
      info(t('help.field.people.restrictions'), t('people.exceptions')), h('p.muted', {}, t('people.restrictions.help')),
      h('div', { style: { display: 'flex', flexWrap: 'wrap', gap: '.4rem .8rem', alignItems: 'end' } },
        add.map((f) => control(f, f.type === 'date' ? '' : f.options[0][0])),
        h('button.btn.btn-secondary.btn-small', { type: 'button', onclick: () => {
          try {
            local.push(restrictionFromForm(key, readForm(form, add)));
            drawExceptions();
          } catch (err) {
            showProblem(err);
          }
        } }, t('masterdata.restriction.add'))));
    form = h('form.toolbar', { id: 'person-form', onsubmit: (e) => {
      e.preventDefault();
      submit();
    } },
    base && h('span.muted', { style: wide }, `${t('masterdata.col.id')}: ${base.id}`),
    master.map((f) => control(f, values[f.name])), employment.map((f) => control(f, values[f.name])),
    areasBlock,
    // the night note and the shares that apply, from the person as stored (a new person: the note of the mode)
    mixEditor(dataset, values.shiftMix, rules?.nightDistribution, base != null && neverNightShifts(dataset, base, ctx.month, rules)), restrictionsBlock, familyEditor(values.familyRules ?? []),
    h('div.toolbar', { style: wide }, h('button.btn.btn-primary', { type: 'submit' }, t('common.save')),
      h('button.btn.btn-secondary', { type: 'button', onclick: () => (key == null ? ctx.navigate('person') : draw()) }, t('common.cancel')),
      base && h('button.btn.btn-secondary', { type: 'button', onclick: remove }, t('people.remove'))),
    h('div.form-problems', { 'aria-live': 'polite', style: wide }));
    // the area shares and the exceptions follow the chosen areas
    const sync = () => {
      const on = new Set(chosen());
      form.querySelectorAll('div[data-mix-area]').forEach((el) => { el.hidden = !on.has(el.dataset.mixArea); });
      drawExceptions();
    };
    form.addEventListener('change', (e) => { if (e.target.dataset.areaState) sync(); });
    section.querySelector('form')?.remove();
    section.append(form);
    sync();
  }

  /** The areas and their states as the form shows them. */
  const readAreas = () => {
    const selects = [...form.querySelectorAll('select[data-area-state]')].filter((x) => x.value);
    return { areaIds: selects.map((x) => x.dataset.areaState),
      areaStates: Object.fromEntries(selects.filter((x) => x.value !== 'PLAN').map((x) => [x.dataset.areaState, x.value])) };
  };

  const readValues = () => ({ ...readForm(form, [...master, ...employment]), ...readAreas(), familyRules: readFamily(form), shiftMix: readMix(form) });

  async function submit() {
    const values = readValues();
    let item;
    try {
      const areaName = (id) => dataset.areas.find((a) => a.id === id)?.name ?? id;
      item = fromForm('people', values, base, areaName);
    } catch (err) {
      showProblem(err);
      return;
    }
    const restrictions = same(local, baseRestrictions) ? null : local.map((r) => ({ ...r }));
    await busy(() => send({ item, restrictions, values, local: local.map((r) => ({ ...r })), base, baseRestrictions, personSaved: false }));
  }

  async function busy(action) {
    const button = form.querySelector('button[type=submit]');
    button.disabled = true;
    try { await action(); } finally { button.disabled = false; }
  }

  /** The person first, then the restrictions with the revision of the first answer (and the id of a new person). */
  async function send(op) {
    let revision = dataset.revision ?? 0;
    try {
      if (!op.personSaved) {
        const res = key == null ? await api.createMaster('people', op.item, revision) : await api.updateMaster('people', key, op.item, revision);
        op.personSaved = true;
        if (key == null) {
          key = res.key;
          history.replaceState(null, '', `#/person/${encodeURIComponent(key)}`);
          // the restrictions entered before belong to the new id (also when the form is drawn again after an error)
          op.local = op.local.map((r) => ({ ...r, personId: key }));
        }
        revision = res?.revision ?? revision;
      }
      if (op.restrictions) await api.saveRestrictions(key, op.restrictions.map((r) => ({ ...r, personId: key })), revision);
    } catch (e) {
      if (e.code === 'masterdata.conflict') return resolveConflict(op);
      // the person is saved, the restrictions not: compare with the saved version from now on
      if (op.personSaved && await reload()) draw(op.values, op.local);
      return showProblem(e);
    }
    toast(t('masterdata.saved'));
    ctx.invalidateDataset();
    root.replaceChildren();
    await render(root, ctx, [key]);
    return undefined;
  }

  async function reload() {
    ctx.invalidateDataset();
    try {
      dataset = await ctx.dataset();
    } catch (e) {
      toast(e.message, true);
      return false;
    }
    rebase();
    return true;
  }

  /** Someone else changed master data meanwhile: apply the own change onto the newer version, or drop it. */
  async function resolveConflict(op) {
    if (!(await reload())) return;
    if (!confirm(t('masterdata.conflict.prompt'))) {
      toast(t('masterdata.conflict.reloaded'));
      if (base || key == null) draw();
      return;
    }
    const decisions = [!op.personSaved && conflictDecision('people', key, op.base, dataset),
      op.restrictions && key != null && conflictDecision('restrictions', key, op.baseRestrictions, dataset)].filter(Boolean);
    if (decisions.some((d) => d.action === 'gone')) {
      toast(t('masterdata.conflict.gone'), true);
      return;
    }
    // the own values back in the form on the newer version, so that a rejected retry can be corrected
    draw(op.values, op.local);
    if (decisions.some((d) => d.action === 'changed')) {
      showProblem(new Error(t('masterdata.conflict.changed')));
      return;
    }
    await send({ ...op, base, baseRestrictions });
  }

  async function remove() {
    if (confirm(t('masterdata.remove.confirm', { label: itemLabel('people', base) }))) await sendRemove(false);
  }

  async function sendRemove(confirmed) {
    const before = base;
    try {
      await api.deleteMaster('people', key, dataset.revision ?? 0, confirmed);
    } catch (e) {
      if (e.code === 'masterdata.inUse' && !confirmed) {
        if (confirm(inUseQuestion('people', before, e.params))) await sendRemove(true);
        return;
      }
      if (e.code === 'masterdata.conflict') {
        if (!(await reload())) return;
        // the form shows the newer version, so that a later save does not undo the other change
        if (base) draw();
        const decision = conflictDecision('people', key, before, dataset);
        if (decision.action === 'retry' && confirm(t('masterdata.conflict.prompt'))) await sendRemove(confirmed);
        else if (decision.action !== 'retry') toast(decision.action === 'gone' ? t('masterdata.conflict.gone') : t('masterdata.conflict.changedRemove'), true);
        return;
      }
      // a person in a closed month stays: the exit date ends the employment instead
      showProblem(e, e.code === 'masterdata.inUseClosed' ? ` ${t('masterdata.inUseClosed.person')}` : '');
      return;
    }
    toast(t('masterdata.removed'));
    ctx.invalidateDataset();
    ctx.navigate('person');
  }

  function showProblem(e, hint = '') {
    form.querySelector('.form-problems').replaceChildren(problemAlert(e, hint));
  }

  draw();
  return section;
}

/** Family rules of the person form: one line per rule, added and removed in the form, saved with the person. */
function familyEditor(rules) {
  let n = 0;
  const line = (r) => {
    const i = n++;
    const id = (k) => `f-fr${i}-${k}`;
    const timeField = (k, label, help) => h('div.field', {}, h('div.with-info', {}, h('label', { for: id(k) }, label), info(help, label)),
      h('input', { id: id(k), 'data-fr': k, type: 'time', value: r[k] }));
    const el = h('fieldset.family-rule', { style: { ...bare, display: 'flex', flexWrap: 'wrap', gap: '.4rem .8rem', alignItems: 'end' } },
      h('legend', { style: { fontWeight: 600 } }, t('masterdata.family.rule', { number: i + 1 })),
      h('span', {}, WEEKDAY_OPTIONS.map(([v, label]) => h('label', { style: inline },
        h('input', { type: 'checkbox', 'data-fr': 'weekdays', value: v, checked: r.weekdays.includes(v) }), ` ${label}`)),
      info(t('help.field.family.weekdays'), t('masterdata.family.weekdays'))),
      timeField('latestEnd', t('masterdata.family.col.latestEnd'), t('help.field.family.latestEnd')),
      timeField('earliestStart', t('masterdata.family.col.earliestStart'), t('help.field.family.earliestStart')),
      h('span', {}, h('label', { style: inline }, h('input', { type: 'checkbox', 'data-fr': 'noNights', checked: r.noNights }), ` ${t('masterdata.family.noNights')}`),
        info(t('help.field.family.noNights'), t('masterdata.family.noNights'))),
      h('div.field', {}, h('div.with-info', {}, h('label', { for: id('note') }, t('masterdata.family.col.note')), info(t('help.field.family.note'), t('masterdata.family.col.note'))),
        h('input', { id: id('note'), 'data-fr': 'note', value: r.note })),
      h('button.btn-link', { type: 'button', onclick: () => el.remove() }, t('masterdata.family.remove')));
    return el;
  };
  const lines = h('div', {}, rules.map(line));
  return h('div', { style: wide }, h('strong', {}, t('masterdata.col.familyRules')), info(t('help.field.people.familyRules'), t('masterdata.col.familyRules')), lines,
    h('button.btn.btn-secondary.btn-small', { type: 'button', onclick: () => lines.append(line({ weekdays: [], latestEnd: '', earliestStart: '', noNights: false, note: '' })) },
      t('masterdata.family.add')));
}

const readFamily = (form) => [...form.querySelectorAll('fieldset.family-rule')].map((el) => ({
  weekdays: [...el.querySelectorAll('[data-fr=weekdays]:checked')].map((c) => c.value),
  latestEnd: el.querySelector('[data-fr=latestEnd]').value, earliestStart: el.querySelector('[data-fr=earliestStart]').value,
  noNights: el.querySelector('[data-fr=noNights]').checked, note: el.querySelector('[data-fr=note]').value }));

/** Help of each category field of the usual distribution. */
const MIX_HELP = { EARLY: t('help.field.mix.early'), DAY: t('help.field.mix.day'), LATE: t('help.field.mix.late'), NIGHT: t('help.field.mix.night') };

/**
 * Usual shift mix of the person form: a percentage per category and per area, cleared with one button. never: the
 * person never does nights; then, as in EVEN, a line under the inputs names the category shares that apply.
 */
function mixEditor(dataset, mix, nightDistribution, never) {
  let n = 0;
  const field = (wrapper, attr, key, name, value, help) => {
    const id = `f-mix-${n++}`;
    const label = t('masterdata.shiftMix.input', { name });
    return h('div.field', wrapper, h('div.with-info', {}, h('label', { for: id }, label), info(help, label)),
      h('input', { id, type: 'text', inputmode: 'decimal', size: 5, value, [attr]: key }));
  };
  const inputs = h('div', { style: { display: 'flex', flexWrap: 'wrap', gap: '.4rem .8rem', alignItems: 'end' } },
    MIX_CATEGORIES.map((c) => field({}, 'data-mix-cat', c, CATEGORY[c], mix?.categories?.[c] ?? '', MIX_HELP[c])),
    dataset.areas.map((a) => field({ 'data-mix-area': a.id }, 'data-mix-area', a.id, `${a.name} (${a.id})`, mix?.areas?.[a.id] ?? '',
      t('help.field.mix.area', { name: a.name }))));
  const applies = h('p.muted', { id: 'mix-effective' });
  const update = () => {
    let shares = '';
    try {
      const values = Object.fromEntries([...inputs.querySelectorAll('input[data-mix-cat]')].map((i) => [i.dataset.mixCat, i.value]));
      shares = mixText({ categories: effectiveMix(mixFromForm({ categories: values }, []), nightDistribution, never)?.categories });
    } catch {
      // a share out of range or a sum other than 100 %: saving names it
    }
    applies.textContent = shares && t('mix.effective.form', { shares });
    applies.hidden = !shares;
  };
  inputs.addEventListener('input', update);
  const clear = h('button.btn.btn-secondary.btn-small', { type: 'button',
    onclick: () => { inputs.querySelectorAll('input').forEach((i) => { i.value = ''; }); update(); } }, t('masterdata.shiftMix.remove'));
  update();
  return h('div', { style: wide }, h('strong', {}, t('masterdata.col.shiftMix')), info(t('help.field.people.shiftMix'), t('masterdata.col.shiftMix')),
    h('p.muted', {}, t('masterdata.shiftMix.help'), ' ', never ? t('mix.never.form') : nightNote(nightDistribution)),
    inputs, applies, clear);
}

function readMix(form) {
  const read = (attr) => Object.fromEntries([...form.querySelectorAll(`input[${attr}]`)].map((i) => [i.getAttribute(attr), i.value]));
  return { categories: read('data-mix-cat'), areas: read('data-mix-area') };
}
