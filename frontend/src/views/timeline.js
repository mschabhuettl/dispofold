// Bereichs-Zeitachse: duties of one area on a timeline (Bryntum via the adapter) with coverage vs demand below.
import { createTimeline } from '../lib/bryntum-adapter.js';
import { tint } from '../lib/colors.js';
import { areaDuties, coverageModel } from '../lib/coverage.js';
import { addDays, dayLabel, epochMinutes, formatLocalDateTime, monthDates, weekday } from '../lib/dates.js';
import { fmt, h } from '../lib/dom.js';
import { personName } from '../lib/grid-model.js';
import { t } from '../lib/texts.js';
import { coverageChart, coverageLegend } from './coverage-chart.js';

const LABEL_WIDTH = 200;

export async function render(root, ctx) {
  const { api, month } = ctx;
  const [dataset, state, period] = await Promise.all([ctx.dataset(), api.planState(month), api.period(month).catch(() => null)]);
  const dates = monthDates(month);
  const storedArea = sessionStorage.getItem('timeline-area');
  let areaId = dataset.areas.some((a) => a.id === storedArea) ? storedArea : dataset.areas[0]?.id;
  let mode = sessionStorage.getItem('timeline-mode') || 'day';
  let date = dates[0];
  let segments = [];
  let timeline = null;
  let disposed = false;
  let generation = 0;

  const shifts = new Map(dataset.shifts.map((s) => [s.id, s]));
  const people = new Map(dataset.people.map((p) => [p.id, p]));
  const host = h('div.timeline-host', {});
  const chartHost = h('div.coverage', {});
  const gapList = h('div.gap-list', {});
  const dateInput = h('select', { id: 'tl-date', onchange: (e) => { date = e.target.value; draw(); } },
    dates.map((d) => h('option', { value: d }, dayLabel(d))));

  root.append(
    h('h1', {}, t('nav.timeline')),
    h('div.toolbar', {},
      h('div.field', {}, h('label', { for: 'tl-area' }, t('timeline.area')),
        h('select', { id: 'tl-area', onchange: async (e) => { areaId = e.target.value; sessionStorage.setItem('timeline-area', areaId); await loadCoverage(); draw(); } },
          dataset.areas.map((a) => h('option', { value: a.id, selected: a.id === areaId }, a.name)))),
      h('div.field', {}, h('label', { for: 'tl-mode' }, t('timeline.view')),
        h('select', { id: 'tl-mode', onchange: (e) => { mode = e.target.value; sessionStorage.setItem('timeline-mode', mode); draw(); } },
          h('option', { value: 'day', selected: mode === 'day' }, t('timeline.day')),
          h('option', { value: 'week', selected: mode === 'week' }, t('timeline.week')))),
      h('button.btn-icon', { type: 'button', 'aria-label': t('timeline.back'), onclick: () => move(-1) }, '‹'),
      h('div.field', {}, h('label', { for: 'tl-date' }, t('timeline.date')), dateInput),
      h('button.btn-icon', { type: 'button', 'aria-label': t('timeline.forward'), onclick: () => move(1) }, '›')),
    host,
    h('h2', { style: { marginTop: '1rem' } }, t('timeline.coverageHeading')),
    coverageLegend(),
    chartHost,
    gapList,
  );

  function range() {
    const start = mode === 'day' ? date : addDays(date, 1 - weekday(date));
    const days = mode === 'day' ? 1 : 7;
    return { start: epochMinutes(start), end: epochMinutes(addDays(start, days)) };
  }

  function move(dir) {
    const next = addDays(date, dir * (mode === 'day' ? 1 : 7));
    date = next < dates[0] ? dates[0] : next > dates.at(-1) ? dates.at(-1) : next;
    dateInput.value = date;
    draw();
  }

  async function loadCoverage() {
    const area = areaId;
    const loaded = state.plan.assignments.length && area ? await api.coverage(month, area).catch(() => []) : [];
    if (area === areaId) segments = loaded; // ignore answers for an area that is no longer selected
  }

  async function draw() {
    const gen = ++generation;
    const { start, end } = range();
    const events = [];
    const rows = new Map();
    for (const d of areaDuties(state.plan.assignments, period?.previousDuties, shifts, areaId, start, end)) {
      const s = d.shift;
      const p = people.get(d.personId);
      const name = p ? personName(p) : d.personId;
      const note = d.previous ? t('timeline.note.previous') : d.pinned ? t('timeline.note.pinned') : '';
      rows.set(d.personId, Math.min(rows.get(d.personId) ?? Infinity, d.start));
      events.push({ id: `${d.personId}|${d.date}`, resourceId: d.personId, name: s.code, start: d.start, end: d.end, ...eventColors(s.color),
        tooltip: escapeHtml(t('timeline.tooltip', { name, code: s.code, shift: s.name, from: s.start.slice(0, 5), to: s.end.slice(0, 5), note })) });
    }
    const resources = [...rows.entries()].sort((x, y) => x[1] - y[1]).map(([id]) => {
      const p = people.get(id);
      return { id, name: p ? personName(p) : id };
    });

    timeline?.destroy();
    timeline = null;
    host.replaceChildren();
    if (!dataset.areas.length) {
      host.append(h('p.alert', {}, t('common.noMasterdata')));
    } else if (!state.plan.assignments.length) {
      host.append(h('p.alert', {}, t('common.noPlan')));
    } else {
      const created = await createTimeline(host, { resources, events, start, end, mode, labelWidth: LABEL_WIDTH });
      if (disposed || gen !== generation) { created.destroy(); return; }
      timeline = created;
    }
    drawCoverage(start, end);
  }

  function drawCoverage(start, end) {
    const model = coverageModel(segments, start, end);
    const box = timeline?.axisBox() ?? { left: LABEL_WIDTH, width: Math.max(300, host.clientWidth - LABEL_WIDTH) };
    chartHost.replaceChildren(coverageChart(model, { ...box, mode }));
    const fmtT = (t) => { const s = formatLocalDateTime(t); return `${dayLabel(s.slice(0, 10))} ${s.slice(11)}`; };
    gapList.replaceChildren(model.gaps.length
      ? h('table.data', {}, h('caption', { style: { textAlign: 'left', fontWeight: 700, padding: '.5rem 0' } }, t('timeline.gaps.caption')),
        h('thead', {}, h('tr', {}, h('th', {}, t('timeline.gaps.from')), h('th', {}, t('timeline.gaps.to')), h('th.num', {}, t('timeline.gaps.missing')), h('th.num', {}, t('timeline.gaps.hours')))),
        h('tbody', {}, model.gaps.map((g) => h('tr', {}, h('td', {}, fmtT(g.from)), h('td', {}, fmtT(g.to)),
          h('td.num', {}, String(g.maxShortfall)), h('td.num', {}, fmt.num(g.missingMinutes / 60, 1))))))
      : h('p.muted', {}, segments.length ? t('timeline.noGaps') : ''));
  }

  const onResize = () => { if (timeline) drawCoverage(range().start, range().end); };
  window.addEventListener('resize', onResize);
  // shift colours are dimmed in the dark theme, so the timeline is drawn again when the theme changes
  document.addEventListener('themechange', draw);
  await loadCoverage();
  // draw after the container is attached so Bryntum can measure it
  setTimeout(draw, 0);
  return () => {
    disposed = true;
    window.removeEventListener('resize', onResize);
    document.removeEventListener('themechange', draw);
    timeline?.destroy();
  };
}

function eventColors(hex) {
  const c = tint(hex, document.documentElement.dataset.theme === 'dark');
  return c ? { color: c.bg, textColor: c.fg } : { color: null, textColor: null };
}

function escapeHtml(s) {
  return s.replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}
