// Bereichs-Zeitachse: duties of one area on a timeline (Bryntum via the adapter) with coverage vs demand below.
import { createTimeline } from '../lib/bryntum-adapter.js';
import { tint } from '../lib/colors.js';
import { areaDuties, coverageModel } from '../lib/coverage.js';
import { addDays, dayLabel, epochMinutes, formatLocalDateTime, monthDates, weekday } from '../lib/dates.js';
import { fmt, h } from '../lib/dom.js';
import { cellsEditable, personName } from '../lib/grid-model.js';
import { t } from '../lib/texts.js';
import { candidateList } from './candidate-list.js';
import { coverageChart, coverageLegend } from './coverage-chart.js';

const LABEL_WIDTH = 200;

/** params: area id and day from a link (#/zeitachse/<area>/<date>), e.g. of a gap or an overstaffing on Prüfen. */
export async function render(root, ctx, [linkArea, linkDate] = []) {
  const { api, month, canEdit } = ctx;
  const [dataset, initialState, period] = await Promise.all([ctx.dataset(), api.planState(month), api.period(month).catch(() => null)]);
  let state = initialState;
  const dates = monthDates(month);
  const known = (id) => dataset.areas.some((a) => a.id === id);
  const storedArea = sessionStorage.getItem('timeline-area');
  let areaId = known(linkArea) ? linkArea : known(storedArea) ? storedArea : dataset.areas[0]?.id;
  // a linked day opens as that day
  const linked = dates.includes(linkDate);
  let mode = linked ? 'day' : sessionStorage.getItem('timeline-mode') || 'day';
  let date = linked ? linkDate : dates[0];
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
    dates.map((d) => h('option', { value: d, selected: d === date }, dayLabel(d))));

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
      host.append(h('p.alert', {}, canEdit ? t('common.noMasterdata') : t('common.noMasterdataReadonly')));
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
    // planners only (viewers never ask for candidates): a button per gap opens the list of who could be asked
    const ask = cellsEditable({ canEdit, solving: state.solving, closed: state.plan.status === 'CLOSED' });
    gapList.replaceChildren(model.gaps.length
      ? h('table.data', {}, h('caption', { style: { textAlign: 'left', fontWeight: 700, padding: '.5rem 0' } }, t('timeline.gaps.caption')),
        h('thead', {}, h('tr', {}, h('th', {}, t('timeline.gaps.from')), h('th', {}, t('timeline.gaps.to')), h('th.num', {}, t('timeline.gaps.missing')), h('th.num', {}, t('timeline.gaps.hours')),
          ask && h('th', {}, t('timeline.gaps.ask')))),
        h('tbody', {}, model.gaps.map((g) => h('tr', {}, h('td', {}, fmtT(g.from)), h('td', {}, fmtT(g.to)),
          h('td.num', {}, String(g.maxShortfall)), h('td.num', {}, fmt.num(g.missingMinutes / 60, 1)),
          ask && h('td', {}, askButton(g))))))
      : h('p.muted', {}, segments.length ? t('timeline.noGaps') : ''));
  }

  /** Shows or hides a row below the gap with the list of who could be asked. */
  function askButton(g) {
    const gap = { areaId, from: formatLocalDateTime(g.from), to: formatLocalDateTime(g.to) };
    const button = h('button.btn.btn-secondary.btn-small', { type: 'button', 'aria-expanded': 'false', onclick: () => {
      const row = button.closest('tr');
      const open = row.nextElementSibling?.classList.contains('candidates-row');
      if (open) row.nextElementSibling.remove();
      else row.after(h('tr.candidates-row', {}, h('td', { colspan: 5 }, candidateList({ api, month, gap, dataset, onSet: afterSet }))));
      button.setAttribute('aria-expanded', String(!open));
    } }, t('candidates.title'));
    return button;
  }

  /** A duty was set from the list: new plan, coverage and drawing. */
  async function afterSet(plan) {
    if (disposed) return;
    state = { ...state, plan };
    await loadCoverage();
    draw();
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
