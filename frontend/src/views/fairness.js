// Fairness-Übersicht: sortable table of per-person metrics plus dot strips showing the spread per metric. Planners see
// the loads the fairness rules compare first; the mean line of a load is the mean the rule compares against.
import { monthLabel } from '../lib/dates.js';
import { fmt, h, svg } from '../lib/dom.js';
import { fairnessRows, metricSpreads, metricsFor, nightOutside, nightReasonText, sortRows, standouts } from '../lib/fairness.js';
import { personName } from '../lib/grid-model.js';
import { info } from '../lib/info.js';
import { nightShareIgnored } from '../lib/shift-mix.js';
import { t } from '../lib/texts.js';

export async function render(root, ctx) {
  const metrics = metricsFor(ctx.canEdit);
  const { api, month } = ctx;
  const [dataset, analysis] = await Promise.all([ctx.dataset(), api.analysis(month).catch(() => null)]);
  root.append(h('h1', {}, t('fairness.title', { month: monthLabel(month) })));
  if (!analysis?.people?.length) {
    root.append(h('p.alert', {}, t('common.noPlan')));
    return;
  }
  let sortKey = 'name';
  let ascending = true;
  const charts = h('div', {});
  const tableHost = h('div', { style: { overflowX: 'auto' } });

  root.append(
    ctx.canEdit ? h('p.fairness-measure', {}, t('fairness.measure')) : '',
    standoutSection(fairnessRows(analysis.people, dataset.people), ctx.canEdit),
    h('h2', {}, t('fairness.spreadHeading')),
    h('p.muted', {}, t('fairness.spreadHelp')),
    charts,
    h('h2', { style: { marginTop: '1.5rem' } }, t('fairness.tableHeading')),
    analysis.people.some(nightOutside) ? h('p.muted', {}, t('fairness.nightHelp'),
      fairnessRows(analysis.people, dataset.people).some((r) => nightShareIgnored(r.stats, r.person.shiftMix)) ? [' ', t('mix.never.help')] : '') : '',
    tableHost,
  );

  function draw() {
    const rows = fairnessRows(analysis.people, dataset.people);
    const spreads = metricSpreads(rows);
    charts.replaceChildren(h('div.grid-2', {}, metrics.map((m) => spreadStrip(m, rows, spreads[m.id]))));
    const sorted = sortRows(rows, sortKey, ascending);
    const th = (key, label, num, help) => h('th', { class: num ? 'num' : '', scope: 'col', 'aria-sort': sortKey === key ? (ascending ? 'ascending' : 'descending') : 'none' },
      h('button', { type: 'button', onclick: () => { ascending = sortKey === key ? !ascending : true; sortKey = key; draw(); } },
        label, sortKey === key ? (ascending ? ' ▲' : ' ▼') : ''), help && info(help, label));
    tableHost.replaceChildren(h('table.data', {},
      h('thead', {}, h('tr', {}, th('name', t('fairness.person'), false), metrics.map((m) => th(m.id, m.label, true, t(`help.figure.${m.id}`))))),
      h('tbody', {}, sorted.map((r) => h('tr', {},
        h('th', { scope: 'row' }, h('a', { href: `#/person/${encodeURIComponent(r.person.id)}` }, personName(r.person))),
        metrics.map((m) => h('td.num', {}, format(m, r.values[m.id]), m.id === 'nightLoad' && nightMark(r.stats, r.person.shiftMix))))))));
  }
  draw();
}

/** "Fällt auf": the few people far from the mean of a figure, above the charts; or that nobody is. */
function standoutSection(rows, canEdit) {
  const list = standouts(rows, canEdit);
  return h('section.section', { id: 'fairness-standouts', 'aria-labelledby': 'h-standouts' },
    h('h2', { id: 'h-standouts' }, t('fairness.standout.title'), info(t('help.fairness.standout'), t('fairness.standout.title'))),
    list.length ? h('ul', {}, list.map((x) => {
      const values = { metric: x.metric.label, value: format(x.metric, x.value), mean: format(x.metric, x.mean) };
      return h('li', {}, h('a', { href: `#/person/${encodeURIComponent(x.person.id)}` }, personName(x.person)), ': ',
        x.high ? t('fairness.standout.high', values) : t('fairness.standout.low', values));
    })) : h('p', {}, t('fairness.standout.none')));
}

/** Marks a person outside the night comparison, with the reason (planners only: viewers get no reason). usual: the stored mix. */
function nightMark(stats, usual) {
  const reason = nightOutside(stats);
  if (!reason) return null;
  const text = nightReasonText(reason);
  const title = nightShareIgnored(stats, usual) ? t('mix.never.title') : t('fairness.night.title', { reason: text });
  return [' ', h('span.badge.night-outside', { title, 'data-reason': reason }, text)];
}

function format(metric, v) {
  if (metric.percent) return fmt.pct(v);
  return fmt.num(v, metric.digits ?? (Number.isInteger(v) ? 0 : 1));
}

function spreadStrip(metric, rows, s) {
  const width = 420;
  const height = 46;
  const pad = 12;
  const title = h('div', { style: { fontWeight: 600 } }, metric.label, ' ',
    h('span.muted', { style: { fontWeight: 400 } }, s.count ? t('fairness.summary', { min: format(metric, s.min), mean: format(metric, s.mean), max: format(metric, s.max) }) : t('fairness.noValues')));
  if (!s.count) return h('div.spread', {}, title);
  const lo = metric.percent ? 0 : Math.min(0, s.min);
  const hi = metric.percent ? 1 : Math.max(s.max, lo + 1);
  const x = (v) => pad + ((v - lo) / (hi - lo)) * (width - 2 * pad);
  const dots = rows.filter((r) => r.values[metric.id] != null).map((r, i) =>
    svg('circle', { cx: x(r.values[metric.id]), cy: 18 + ((i % 3) - 1) * 5, r: 3.5, class: 'chart-dot' },
      svg('title', {}, `${personName(r.person)}: ${format(metric, r.values[metric.id])}`)));
  return h('div.spread', {}, title, svg('svg', { width, height, viewBox: `0 0 ${width} ${height}`, role: 'img',
    'aria-label': t('fairness.chartLabel', { metric: metric.label, min: format(metric, s.min), mean: format(metric, s.mean), max: format(metric, s.max) }) },
  svg('rect', { x: x(s.min) - 5, y: 6, width: x(s.max) - x(s.min) + 10, height: 24, class: 'chart-range' }),
  ...dots,
  svg('line', { x1: x(s.mean), x2: x(s.mean), y1: 2, y2: 34, class: 'chart-mean', 'stroke-width': 2.5 }),
  svg('text', { x: pad, y: 44 }, format(metric, lo)),
  svg('text', { x: width - pad, y: 44, 'text-anchor': 'end' }, format(metric, hi))));
}
