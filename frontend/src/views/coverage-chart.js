// SVG step chart: demand (min, optional max) against planned staffing, gaps hatched in the theme colour for gaps.
import { stepLines } from '../lib/coverage.js';
import { formatLocalDateTime, localDate, WEEKDAY_SHORT, weekday } from '../lib/dates.js';
import { h, svg } from '../lib/dom.js';
import { t } from '../lib/texts.js';

// Colours come from the theme tokens through the chart-* classes in app.css.

/**
 * @param {object} model from coverageModel()
 * @param {{left: number, width: number, height?: number, mode: 'day'|'week'}} box left offset and width of the time axis
 */
export function coverageChart(model, { left, width, height = 170, mode }) {
  const top = 10;
  const bottom = 26;
  const plotH = height - top - bottom;
  const total = width + left;
  const yMax = model.yMax + 1;
  // local time like the Bryntum time axis, so both stay aligned on daylight saving days
  const ms = (t) => localDate(t).getTime();
  const x = (t) => left + ((ms(t) - ms(model.start)) / (ms(model.end) - ms(model.start))) * width;
  const y = (v) => top + plotH - (v / yMax) * plotH;
  const path = (lines) => lines.map((l) => l.map((p, i) => `${i ? 'L' : 'M'}${x(p.x).toFixed(1)},${y(p.y).toFixed(1)}`).join('')).join('');

  const els = [];
  els.push(svg('defs', {}, svg('pattern', { id: 'gap-hatch', width: 6, height: 6, patternUnits: 'userSpaceOnUse', patternTransform: 'rotate(45)' },
    svg('rect', { width: 6, height: 6, class: 'chart-gap-fill' }), svg('line', { x1: 0, y1: 0, x2: 0, y2: 6, class: 'chart-gap', 'stroke-width': 3 }))));

  // y grid and labels
  const step = yMax > 10 ? Math.ceil(yMax / 5) : 1;
  const yAxis = svg('g', { class: 'axis' });
  for (let v = 0; v <= yMax; v += step) {
    yAxis.append(svg('line', { x1: left, x2: total, y1: y(v), y2: y(v), class: 'chart-grid' }),
      svg('text', { x: left - 6, y: y(v) + 4, 'text-anchor': 'end' }, String(v)));
  }
  els.push(yAxis);

  // x ticks: every 3 h in the day view, every day in the week view
  const tick = mode === 'day' ? 180 : 1440;
  const xAxis = svg('g', { class: 'axis' });
  for (let t = Math.ceil(model.start / tick) * tick; t <= model.end; t += tick) {
    const stamp = formatLocalDateTime(t);
    const label = mode === 'day' ? stamp.slice(11, 16) : `${WEEKDAY_SHORT[weekday(stamp.slice(0, 10)) - 1]} ${stamp.slice(8, 10)}.`;
    xAxis.append(svg('line', { x1: x(t), x2: x(t), y1: top, y2: top + plotH, class: 'chart-grid' }));
    if (t < model.end) xAxis.append(svg('text', { x: x(t) + 3, y: height - 8 }, label));
  }
  els.push(xAxis);

  // planned staffing as filled steps
  for (const s of model.segments) {
    if (s.planned > 0) els.push(svg('rect', { x: x(s.from), width: Math.max(0, x(s.to) - x(s.from)), y: y(s.planned), height: y(0) - y(s.planned), class: 'chart-planned-fill' }));
  }
  // gaps: area between planned and required
  for (const s of model.segments) {
    if (s.planned >= s.required) continue;
    els.push(svg('rect', { x: x(s.from), width: Math.max(1, x(s.to) - x(s.from)), y: y(s.required), height: y(s.planned) - y(s.required),
      fill: 'url(#gap-hatch)', class: 'chart-gap', 'stroke-width': 1 },
    svg('title', {}, t('coverage.gapTitle', { from: formatLocalDateTime(s.from).replace('T', ' '), to: formatLocalDateTime(s.to).slice(11), required: s.required, planned: s.planned }))));
  }
  els.push(svg('path', { d: path(stepLines(model.segments, (s) => s.planned)), class: 'chart-planned', 'stroke-width': 2 }));
  els.push(svg('path', { d: path(stepLines(model.segments, (s) => s.max)), class: 'chart-max', 'stroke-width': 1.5, 'stroke-dasharray': '2 3' }));
  els.push(svg('path', { d: path(stepLines(model.segments, (s) => s.required)), class: 'chart-demand', 'stroke-width': 2, 'stroke-dasharray': '6 3' }));
  els.push(svg('line', { x1: left, x2: total, y1: y(0), y2: y(0), class: 'chart-axis' }));

  const desc = model.gaps.length
    ? t('coverage.summaryGaps', { count: model.gaps.length })
    : t('coverage.summaryCovered');
  return svg('svg', { width: total, height, viewBox: `0 0 ${total} ${height}`, role: 'img', 'aria-label': t('coverage.chartLabel', { summary: desc }) }, ...els);
}

export function coverageLegend() {
  const item = (swatch, text) => h('span', {}, swatch, text);
  const line = (dash, cls, w = 2) => svg('svg', { width: 28, height: 10, 'aria-hidden': 'true' },
    svg('line', { x1: 0, x2: 28, y1: 5, y2: 5, class: cls, 'stroke-width': w, 'stroke-dasharray': dash }));
  return h('div.legend', {},
    item(line('6 3', 'chart-demand'), ` ${t('coverage.legend.min')}`),
    item(line('2 3', 'chart-max', 1.5), ` ${t('coverage.legend.max')}`),
    item(h('span.swatch.planned', {}), t('coverage.legend.planned')),
    item(svg('svg', { width: 22, height: 14, 'aria-hidden': 'true' },
      svg('defs', {}, svg('pattern', { id: 'legend-hatch', width: 6, height: 6, patternUnits: 'userSpaceOnUse', patternTransform: 'rotate(45)' },
        svg('rect', { width: 6, height: 6, class: 'chart-gap-fill' }), svg('line', { x1: 0, y1: 0, x2: 0, y2: 6, class: 'chart-gap', 'stroke-width': 3 }))),
      svg('rect', { x: 0.5, y: 0.5, width: 21, height: 13, fill: 'url(#legend-hatch)', class: 'chart-gap' })), t('coverage.legend.gap')));
}
