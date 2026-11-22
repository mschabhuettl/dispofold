// Prüfen: what is wrong with the plan of the month (rule violations, overstaffing, quality; open demand) and with
// the master data. Planners only.
import { gapLabel } from '../lib/candidates.js';
import { constraintLabel, hitText } from '../lib/codes.js';
import { dataChecks } from '../lib/data-checks.js';
import { dayLabel, monthLabel, parseLocalDateTime } from '../lib/dates.js';
import { append, h } from '../lib/dom.js';
import { windowOverlapsShift } from '../lib/functions-model.js';
import { cellsEditable, personName } from '../lib/grid-model.js';
import { info } from '../lib/info.js';
import { planHeadline } from '../lib/plan-status.js';
import { filterProblems, groupKey, groupProblems, problemEntries } from '../lib/problems.js';
import { t } from '../lib/texts.js';
import { candidateList } from './candidate-list.js';

const LEVEL = { HARD: t('person.level.hard'), MEDIUM: t('person.level.medium'), SOFT: t('person.level.soft') };
const BY = { rule: t('check.by.rule'), person: t('check.by.person'), day: t('check.by.day') };
/** A list longer than this shows only its first group open. */
const FOLD = 20;

/** A group that folds: the heading opens and closes its list. */
const fold = (open, heading, list) => h('details.check-group', { open }, h('summary', {}, heading), list);

export async function render(root, ctx) {
  const { api, month } = ctx;
  const [dataset, state, rules, period] = await Promise.all([ctx.dataset(), api.planState(month), api.rules(), api.period(month).catch(() => null)]);
  const planned = state.plan.assignments.length > 0;
  const [analysis, problems] = planned
    ? await Promise.all([api.analysis(month).catch(() => null), api.problems(month).catch(() => null)])
    : [null, null];
  root.append(h('h1', {}, t('check.title', { month: monthLabel(month) })));
  if (!planned) root.append(h('p.alert', {}, t('check.noPlan')));
  else {
    root.append(problemSection(dataset, analysis, problems));
    // who could be asked: only where a duty can be set
    const ask = cellsEditable({ canEdit: ctx.canEdit, solving: state.solving, closed: state.plan.status === 'CLOSED' });
    root.append(gapSection(ctx, dataset, analysis, ask));
  }
  root.append(dataSection(dataset, month, rules, period?.absences ?? []));
}

/** Rule violations, overstaffing and quality, with a grouping and three filters. */
function problemSection(dataset, analysis, problems) {
  const areaName = new Map(dataset.areas.map((a) => [a.id, a.name]));
  const section = h('section.check-section', { id: 'check-problems' }, h('h2', {}, t('check.problems.title'), info(t('help.check.problems'), t('check.problems.title'))),
    h('p.muted', {}, t('check.problems.help')));
  if (!problems || !analysis) return append(section, [h('p.alert.alert-error', {}, t('check.problems.failed'))]);
  const entries = problemEntries(problems, analysis.constraints, dataset.people);
  if (!entries.length) return append(section, [h('p', {}, t('check.problems.none'))]);

  let by = 'rule';
  const filter = { rule: null, person: null, day: null };
  const list = h('div', {});
  const select = (id, label, options, onchange) => h('div.field', {}, h('label', { for: id }, label),
    h('select', { id, onchange: (e) => onchange(e.target.value) }, options.map(([value, text]) => h('option', { value }, text))));
  const keys = (kind) => groupProblems(entries, kind).map((g) => [g.key, groupLabel(kind, g)]);
  const any = ['*', t('check.all')];
  section.append(h('div.toolbar', {},
    select('check-group', t('check.groupBy'), Object.entries(BY), (v) => { by = v; draw(); }),
    ['rule', 'person', 'day'].map((kind) => select(`check-filter-${kind}`, BY[kind], [any, ...keys(kind)],
      (v) => { filter[kind] = v === '*' ? null : v; draw(); }))), list);

  function draw() {
    const shown = filterProblems(entries, filter);
    list.replaceChildren(h('p.muted', {}, t('check.problems.count', { count: shown.length })),
      ...groupProblems(shown, by).map((g, i) => fold(shown.length <= FOLD || i === 0,
        [groupLabel(by, g), ' ', h('span.muted', {}, `(${g.entries.length})`)],
        h('ul.check-list', {}, g.entries.map((e) => entryItem(e, by, areaName))))));
  }
  draw();
  return section;
}

/** Heading of a group: the rule with its level, the person or "ohne Person", the day or "ganzer Monat". */
function groupLabel(by, g) {
  if (by === 'rule') return `${constraintLabel({ id: g.key })} · ${LEVEL[g.level] ?? g.level}`;
  if (by === 'person') return g.person ? personName(g.person) : t('check.noPerson');
  return g.key ? dayLabel(g.key) : t('roster.chipMonth');
}

/** One entry: level, what the group heading does not say (rule, day), the person (link), the text. */
function entryItem(e, by, areaName) {
  const { hit, person } = e;
  const level = hit.level === 'HARD' ? 'badge-hard' : hit.level === 'MEDIUM' ? 'badge-warn' : null;
  return h('li', { 'data-rule': hit.constraintId, 'data-level': hit.level },
    h('span.badge', { class: level }, LEVEL[hit.level] ?? hit.level),
    by !== 'rule' && h('strong', {}, constraintLabel({ id: hit.constraintId })),
    // the link stays when grouped by person: the heading is no link
    person ? h('a', { href: `#/person/${encodeURIComponent(person.id)}` }, personName(person))
      : h('a', { href: timelineLink(hit.params?.areaId, hit.date) }, t('check.toTimeline')),
    by !== 'day' && h('span', {}, groupKey(e, 'day') ? dayLabel(hit.date) : t('roster.chipMonth')),
    h('span.hit-text', {}, hitText(hit, areaName)));
}

/** The Zeitachse on the area and day of an entry, where it names them. */
const timelineLink = (areaId, date) => (areaId && date ? `#/zeitachse/${encodeURIComponent(areaId)}/${date}` : '#/zeitachse');

/** Open demand of the month by day: area, time, missing count, the functions of the area and who could be asked. */
function gapSection(ctx, dataset, analysis, ask) {
  const section = h('section.check-section', { id: 'check-gaps' }, h('h2', {}, t('check.gaps.title'), info(t('help.check.gaps'), t('check.gaps.title'))));
  if (!analysis) return append(section, [h('p.alert.alert-error', {}, t('check.problems.failed'))]);
  const gaps = analysis.gaps ?? [];
  if (!gaps.length) return append(section, [h('p', {}, t('check.gaps.none'))]);
  // the same words as the headline of the Dienstplan
  section.append(h('p.check-headline', {}, planHeadline({ gaps: gaps.length, uncoveredHours: analysis.uncoveredHours }).text));
  const areaName = new Map(dataset.areas.map((a) => [a.id, a.name]));
  const days = new Map();
  for (const g of [...gaps].sort((a, b) => a.from.localeCompare(b.from) || a.areaId.localeCompare(b.areaId))) {
    const day = g.from.slice(0, 10);
    if (!days.has(day)) days.set(day, []);
    days.get(day).push(g);
  }
  [...days].forEach(([day, list], i) => section.append(fold(gaps.length <= FOLD || i === 0,
    [dayLabel(day), ' ', h('span.muted', {}, `(${list.length})`)], h('ul.check-list', {}, list.map((g) => gapItem(ctx, dataset, areaName, g, ask))))));
  return section;
}

/** One gap with the functions (shifts) of its area at that time and, on demand, who could be asked. */
function gapItem(ctx, dataset, areaName, g, ask) {
  // a gap of a day or more (gaps over several days are merged) concerns every shift of the area
  const window = { from: g.from.slice(11, 16), to: g.to.slice(11, 16) };
  const whole = parseLocalDateTime(g.to) - parseLocalDateTime(g.from) >= 1440;
  const codes = dataset.shifts.filter((s) => s.areaId === g.areaId && (whole || windowOverlapsShift(window, s))).map((s) => s.code);
  const item = h('li.gap-item', {}, h('strong', {}, gapLabel(g, areaName.get(g.areaId))),
    codes.length > 0 && h('span.muted', {}, t('check.gaps.functions', { codes: codes.join(', ') })),
    h('a', { href: timelineLink(g.areaId, g.from.slice(0, 10)) }, t('check.toTimeline')));
  // loaded on demand: one request per gap
  const toggle = h('button.btn-link', { type: 'button', 'aria-expanded': 'false', onclick: () => {
    const open = item.querySelector('.candidates');
    toggle.setAttribute('aria-expanded', String(!open));
    if (open) open.remove();
    else item.append(candidateList({ api: ctx.api, month: ctx.month, gap: g, dataset, onSet: () => ctx.setMonth(ctx.month) }));
  } }, t('check.gaps.candidates'));
  if (ask) item.append(toggle);
  return item;
}

/** Findings on the master data with what to do and where. */
function dataSection(dataset, month, rules, absences) {
  const section = h('section.check-section', { id: 'check-data' }, h('h2', {}, t('check.data.title'), info(t('help.check.data'), t('check.data.title'))), h('p.muted', {}, t('check.data.help')));
  const findings = dataChecks(dataset, { month, rules, absences });
  if (!findings.length) return append(section, [h('p', {}, t('check.data.none'))]);
  const people = new Map(dataset.people.map((p) => [p.id, p]));
  return append(section, [h('ul.check-list', {}, findings.map((f) => h('li', { 'data-kind': f.kind, 'data-severity': f.severity },
    f.severity === 'info' && h('span.badge', {}, t('check.data.info')),
    h('span', {}, f.text),
    h('span.muted', {}, f.todo),
    f.personId && people.has(f.personId) && h('a', { href: `#/person/${encodeURIComponent(f.personId)}` }, personName(people.get(f.personId))),
    f.link === 'rules' ? h('a', { href: '#/regeln' }, t('check.data.toRules'))
      : f.demandIndex != null ? h('a', { href: `#/stammdaten/bedarf/${f.demandIndex}` }, t('check.data.toDemand'))
        : h('a', { href: '#/stammdaten' }, t('check.data.toMasterdata')))))]);
}

