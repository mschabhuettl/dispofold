// The only module that knows Bryntum. Loaded lazily so other views do not pay for the bundle.
// Interface: createTimeline(container, options) -> { axisBox(), destroy() }. Times are epoch minutes (wall clock).

let loading = null;

async function load() {
  const { default: Scheduler } = await import('@bryntum/scheduler-community/lib/Scheduler/view/Scheduler.js');
  const { default: LocaleManager } = await import('@bryntum/core-community/lib/Core/localization/LocaleManager.js');
  await import('@bryntum/scheduler-community/locales/scheduler.locale.De.js');
  LocaleManager.applyLocale('De');
  return Scheduler;
}

import { localDate } from './dates.js';
import { t } from './texts.js';

const wallClock = localDate;

/**
 * @param {HTMLElement} container
 * @param {object} o
 * @param {{id: string, name: string}[]} o.resources
 * @param {{id: string, resourceId: string, name: string, start: number, end: number, color: string, textColor: string,
 *   tooltip: string}[]} o.events tooltip is HTML
 * @param {number} o.start range start (epoch minutes)
 * @param {number} o.end range end (epoch minutes)
 * @param {'day'|'week'} o.mode
 * @param {number} o.labelWidth width of the people column in px
 */
export async function createTimeline(container, o) {
  const Scheduler = await (loading ??= load());
  const scheduler = new Scheduler({
    appendTo: container,
    detectCSSCompatibilityIssues: false,
    readOnly: true,
    autoHeight: true,
    forceFit: true,
    rowHeight: 34,
    barMargin: 5,
    eventStyle: 'filled',
    startDate: wallClock(o.start),
    endDate: wallClock(o.end),
    viewPreset: o.mode === 'day' ? 'hourAndDay' : 'dayAndWeek',
    columns: [{ text: t('timeline.personColumn'), field: 'name', width: o.labelWidth, sortable: false }],
    resources: o.resources,
    events: o.events.map((e) => ({ id: e.id, resourceId: e.resourceId, name: e.name, startDate: wallClock(e.start),
      endDate: wallClock(e.end), eventColor: e.color, style: `color:${e.textColor}`, tooltip: e.tooltip })),
    features: {
      eventTooltip: { template: ({ eventRecord }) => eventRecord.tooltip },
      eventMenu: false,
      scheduleMenu: false,
      headerMenu: false,
      timeAxisHeaderMenu: false,
    },
    emptyText: t('timeline.empty'),
  });
  return {
    /** Left offset and width of the time axis relative to the container, to align the coverage chart. */
    axisBox() {
      const c = container.getBoundingClientRect();
      const a = scheduler.timeAxisSubGridElement.getBoundingClientRect();
      return { left: a.left - c.left, width: a.width };
    },
    destroy() {
      scheduler.destroy();
    },
  };
}
