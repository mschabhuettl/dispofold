// Lauf und Import (path uebersicht): runs the export with chosen months and options (POST lauf, nothing is imported), shows the last run
// with links to its report, the mapping and the comparison, and imports exactly that run with the core's import.
import { guarded, h, importFlow, info, monthLabel, saveBlob, t, toast } from '../../../frontend/src/lib/plugin-api.js';
import { proposalCount, unmatched } from './model.js';
import { ID, monthFields, optionFields, orNoRun, pageHref, problem } from './shared.js';

const COUNTS = ['people', 'areas', 'shifts', 'duties', 'demandRules', 'warnings', 'reportRows', 'corrections'];

export async function render(root, ctx) {
  const api = ctx.api.plugin(ID);
  let [settings, last] = await Promise.all([api.get('einstellungen'), orNoRun(() => api.get('lauf'))]);
  const months = monthFields(ctx, 'sp5-run');
  const options = optionFields('sp5-run', settings.options ?? {});
  const summary = h('div', { id: 'sp5-summary', 'aria-live': 'polite' });
  const importBox = h('div', {});
  const importHeading = h('h2', { id: 'h-sp5-import', tabindex: '-1' }, t('plugin.sp5.import.heading'),
    info(t('plugin.sp5.import.heading.help'), t('plugin.sp5.import.heading')));

  const button = (id, label, primary, fn) => {
    const b = h(`button.btn.${primary ? 'btn-primary' : 'btn-secondary'}`, { id, type: 'button' }, label);
    b.addEventListener('click', async () => {
      b.disabled = true;
      try { await fn(); } finally { b.disabled = false; }
    });
    return b;
  };
  const request = () => {
    const m = months.value();
    if (!m) toast(t('plugin.sp5.run.noMonth'), true);
    return m && { ...m, options: options.value() };
  };

  const missing = !settings.mapping ? 'noMapping' : !settings.root ? 'noFolder' : null;
  root.append(h('h1', {}, t('plugin.sp5.overview.title')),
    missing ? problem({ code: `plugin.sp5.${missing}`, message: t(`error.plugin.sp5.${missing}`) }) : '',
    h('section.section', { 'aria-labelledby': 'h-sp5-run' },
      h('h2', { id: 'h-sp5-run' }, t('plugin.sp5.run.heading'), info(t('plugin.sp5.run.heading.help'), t('plugin.sp5.run.heading'))),
      h('p.muted', {}, t('plugin.sp5.run.intro')),
      months.element,
      h('details.advanced', {}, h('summary', {}, t('plugin.sp5.options')),
        h('div.settings', {}, h('p.muted', {}, t('plugin.sp5.run.options')), ...options.elements,
          h('div.row', {}, button('sp5-run-default', t('plugin.sp5.run.saveDefault'), false, async () => {
            const saved = await guarded(() => api.put('einstellungen', { ...settings, options: options.value() }), t('plugin.sp5.run.defaultSaved'));
            if (saved) settings = saved;
          })))),
      h('div.toolbar', {},
        button('sp5-run', t('plugin.sp5.run.start'), true, async () => {
          const body = request();
          if (!body) return;
          try {
            last = await api.post('lauf', body);
          } catch (e) {
            summary.replaceChildren(problem(e));
            return;
          }
          showRun();
        }),
        button('sp5-run-result', t('plugin.sp5.result.run'), false, async () => {
          const body = request();
          if (!body) return;
          const file = await guarded(() => api.download('ergebnis', body, `dispofold-sp5-ergebnis-${body.month}.zip`));
          if (file) saveBlob(file.blob, file.filename);
        }),
        info(t('plugin.sp5.result.help'), t('plugin.sp5.result.run'))),
      summary),
    h('section.section', { 'aria-labelledby': 'h-sp5-import' }, importHeading, importBox));

  function showRun() {
    if (!last) {
      summary.replaceChildren(h('p.alert', { id: 'sp5-no-run' }, t('error.plugin.sp5.noRun')));
      importBox.replaceChildren(h('p.muted', {}, t('plugin.sp5.import.noRun')));
      return;
    }
    const r = last;
    const time = new Date(r.ranAt).toLocaleString('de-AT', { dateStyle: 'medium', timeStyle: 'short' });
    const mapping = h('span.btn-row', {});
    summary.replaceChildren(h('div', { id: 'sp5-run-summary' },
      h('p', {}, h('strong', {}, t('plugin.sp5.run.done', { time })), ' ', t('plugin.sp5.run.months', { months: r.months.map(monthLabel).join(', ') })),
      h('div.kpis', {}, COUNTS.map((k) => h('div.kpi', { 'data-count': k }, h('div.label', {}, t(`plugin.sp5.count.${k}`)),
        h('div.value', {}, String(r.counts?.[k] ?? '–'))))),
      // one statement about the corrections: applied with their number, or nothing (the import below says when there are none)
      r.corrections && r.counts?.corrections ? h('p', { id: 'sp5-run-corrections' }, t('plugin.sp5.run.corrections.applied', { count: r.counts.corrections })) : '',
      h('div.toolbar', {},
        h('a', { href: pageHref('bericht') }, t('plugin.sp5.run.toReport')),
        h('a', { href: pageHref('lauf-vergleich') }, t('plugin.sp5.run.toCompare')),
        mapping,
        h('button.btn-link', { type: 'button', onclick: () => importHeading.focus() }, t('plugin.sp5.run.toImport')))));
    // shifts or groups of the folder without a rule: a link to the mapping
    if (settings.mapping) {
      api.post('zuordnung/codes', settings.mapping).then((codes) => {
        const u = unmatched(codes);
        const count = u.shifts.length + u.groups.length;
        if (count) mapping.prepend(h('a', { href: pageHref('zuordnung') }, t('plugin.sp5.run.toMapping', { count })));
      }, () => {});
    }
    // new proposals for the mapping (also without one): their number with a link
    api.post('zuordnung/vorschlaege', settings.mapping ?? {}).then((p) => {
      const count = proposalCount(p);
      if (count) mapping.append(h('a', { id: 'sp5-run-proposals', href: pageHref('zuordnung') }, t('plugin.sp5.run.proposals', { count })));
    }, () => {});
    const at = r.request.to && r.request.to !== r.request.month
      ? t('plugin.sp5.import.range', { from: monthLabel(r.request.month), to: monthLabel(r.request.to) }) : monthLabel(r.request.month);
    // a closed month of the run: the import would be refused if it changed it; the way on before trying
    const closed = h('div', { id: 'sp5-import-closed' });
    importBox.replaceChildren(h('p.muted', { id: 'sp5-import-request' }, t('plugin.sp5.import.request', { months: at, time })),
      closed, importFlow(ctx, ID, () => (last ? { ...last.request, run: last.id } : null)));
    Promise.all(r.months.map((m) => ctx.api.planState(m).then((s) => (s?.plan?.status === 'CLOSED' ? m : null), () => null)))
      .then((months) => {
        const shut = months.filter(Boolean);
        if (shut.length) {
          closed.replaceChildren(h('div.alert', { role: 'note' }, h('p', {}, t('plugin.sp5.import.closed', { months: shut.map(monthLabel).join(', ') })),
            // reopened newest first, on Daten for the chosen month: that month is chosen on the way
            h('p', {}, h('a', { href: '#/import', onclick: () => ctx.setMonth(shut.at(-1)) },
              t('plugin.sp5.import.toData', { month: monthLabel(shut.at(-1)) })))));
        }
      });
  }
  showRun();
}
