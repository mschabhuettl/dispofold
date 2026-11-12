// Person panel: key figures of the month and "Warum dieser Dienst?" (constraint hits by date).
import { dayLabel, monthLabel } from '../lib/dates.js';
import { fmt, h } from '../lib/dom.js';
import { groupHitsByDate } from '../lib/explain.js';
import { personName } from '../lib/grid-model.js';
import { constraintLabel, hitText } from '../lib/codes.js';
import { t } from '../lib/texts.js';

const LEVEL = { HARD: t('person.level.hard'), MEDIUM: t('person.level.medium'), SOFT: t('person.level.soft') };

export async function render(root, ctx, [personId]) {
  const { api, month } = ctx;
  const dataset = await ctx.dataset();
  const people = [...dataset.people].sort((a, b) => personName(a).localeCompare(personName(b), 'de'));
  const person = people.find((p) => p.id === personId);

  root.append(h('div.toolbar', {},
    h('div.field', {}, h('label', { for: 'person-select' }, t('nav.person')),
      h('select', { id: 'person-select', onchange: (e) => ctx.navigate(`person/${encodeURIComponent(e.target.value)}`) },
        h('option', { value: '' }, t('person.choose')),
        people.map((p) => h('option', { value: p.id, selected: p.id === personId }, `${personName(p)} (${p.shortName ?? p.id})`))))));
  if (!person) {
    root.prepend(h('h1', {}, t('nav.person')));
    root.append(h('p.muted', {}, t('person.hint')));
    return;
  }

  const areaNames = new Map(dataset.areas.map((a) => [a.id, a.name]));
  const constraintLabels = new Map((await api.constraints().catch(() => [])).map((c) => [c.id, constraintLabel(c)]));
  const explanation = await api.person(month, person.id).catch((e) => ({ error: e.message }));
  root.prepend(h('h1', {}, `${personName(person)}`),
    h('p.muted', {}, t('person.meta', { short: person.shortName ?? '', hours: fmt.num(person.weeklyHours, 1), areas: person.areaIds.map((a) => areaNames.get(a) ?? a).join(', '), month: monthLabel(month) })));
  if (explanation.error) {
    root.append(h('p.alert', {}, t('person.noStats', { message: explanation.error })));
    return;
  }
  const s = explanation.stats;
  const kpi = (label, value) => h('div.kpi', {}, h('div.label', {}, label), h('div.value', {}, value));
  const hours = (v) => `${fmt.num(v, 1)} h`;
  root.append(
    h('section.section', { 'aria-labelledby': 'h-hours' }, h('h2', { id: 'h-hours' }, t('person.section.hours')),
      h('div.kpis', {},
        kpi(t('person.kpi.target'), hours(s.targetHours)), kpi(t('person.kpi.planned'), hours(s.plannedHours)), ctx.canEdit && kpi(t('person.kpi.credited'), hours(s.creditedHours)),
        // the hours account is personal: planners only (viewers get 0 from the server)
        ctx.canEdit && kpi(t('person.kpi.accountBefore'), hours(s.accountBefore)), ctx.canEdit && kpi(t('person.kpi.accountAfter'), hours(s.accountAfter)),
        kpi(t('person.kpi.duties'), fmt.num(s.duties)),
        s.availableDays != null && kpi(t('person.kpi.availableDays'), fmt.num(s.availableDays)),
        ctx.canEdit && s.chargedHours != null && kpi(t('person.kpi.charged'), hours(s.chargedHours)),
        // the 52-week average is only binding when the history covers the whole period
        s.averageWeekHours != null && kpi(s.averageComplete ? t('person.kpi.averageWeek') : t('person.kpi.averageWeekPartial'), hours(s.averageWeekHours)))),
    h('section.section', { 'aria-labelledby': 'h-load' }, h('h2', { id: 'h-load' }, t('person.section.load')),
      h('div.kpis', {},
        kpi(t('person.kpi.nights'), fmt.num(s.nights)), kpi(t('person.kpi.weekendsWorked'), fmt.num(s.weekendsWorked)), kpi(t('person.kpi.weekendsOff'), fmt.num(s.weekendsOff)),
        kpi(t('person.kpi.holidays'), fmt.num(s.holidays)), kpi(t('person.kpi.unpopular'), fmt.num(s.unpopular)), kpi(t('person.kpi.singleDays'), fmt.num(s.singleDays)),
        // wishes are personal: planners only
        ctx.canEdit && kpi(t('person.kpi.wishesMet'), t('common.outOf', { value: s.wishesMet, total: s.wishes })),
        kpi(t('person.kpi.compRestPlanned'), fmt.num(s.compRestPlanned)),
        // compensatory-rest balance and the hours account are personal: planners only (viewers get null)
        ctx.canEdit && kpi(t('person.kpi.compRestEarned'), fmt.num(s.compRestEarned)),
        ctx.canEdit && kpi(t('person.kpi.compRestOpenAfter'), fmt.num(s.compRestBalanceAfter)))),
    h('section.section', { 'aria-labelledby': 'h-hist' }, h('h2', { id: 'h-hist' }, t('person.section.history')),
      h('div.kpis', {},
        kpi(t('person.kpi.nights'), fmt.num(s.historyNights)), kpi(t('person.kpi.weekends'), fmt.num(s.historyWeekends)), kpi(t('person.kpi.holidays'), fmt.num(s.historyHolidays)),
        kpi(t('person.kpi.unpopular'), fmt.num(s.historyUnpopular)), ctx.canEdit && kpi(t('person.kpi.wishesMet'), t('common.outOf', { value: s.historyWishesMet, total: s.historyWishes })))),
    h('section.section', { 'aria-labelledby': 'h-why' }, h('h2', { id: 'h-why' }, t('person.section.why')),
      explanation.hits.length ? groupHitsByDate(explanation.hits).map((g) => h('div.hits-day', {},
        h('h3', {}, g.date ? dayLabel(g.date) : t('person.wholeMonth'), ' ', h('span.muted', {}, t('person.sum', { total: fmt.num(g.total) }))),
        g.hits.map((hit) => h('div.hit', {},
          h('span', { class: hit.level === 'HARD' ? 'badge badge-hard' : 'badge' }, LEVEL[hit.level] ?? hit.level),
          h('span', {}, h('strong', {}, constraintLabels.get(hit.constraintId) ?? constraintLabel({ id: hit.constraintId })), ': ', hitText(hit)),
          h('span', { class: `score ${hit.score < 0 ? 'score-neg' : hit.score > 0 ? 'score-pos' : ''}` }, fmt.num(hit.score))))))
        : h('p.muted', {}, t('person.noHits'))),
  );
}
