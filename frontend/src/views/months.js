// Daten → Monate: the months with data or a plan, each open or closed; closing and opening again here (the plan page
// keeps its own "Monat abschließen …").
import { monthLabel } from '../lib/dates.js';
import { guarded, h, toast } from '../lib/dom.js';
import { info } from '../lib/info.js';
import { t } from '../lib/texts.js';
import { closeMonth } from './plan-output.js';

const planWord = (status) => ({ SOLVING: t('months.plan.SOLVING'), SOLVED: t('months.plan.SOLVED'), STOPPED: t('months.plan.STOPPED'),
  FAILED: t('months.plan.FAILED'), CLOSED: t('months.plan.CLOSED') })[status] ?? t('months.noPlan');

export async function render(root, ctx) {
  const { api } = ctx;
  const months = (await api.months()) ?? [];
  root.append(h('h1', {}, t('nav.months')));
  if (!months.length) {
    root.append(h('p.muted', { id: 'months-none' }, t('months.none'), ' ', h('a', { href: '#/uebernehmen' }, t('months.toTakeOver'))));
    return;
  }
  const again = () => {
    root.replaceChildren();
    return render(root, ctx);
  };
  const row = (m) => {
    const closed = m.status === 'CLOSED';
    const action = closed
      ? h('button.btn.btn-secondary', { type: 'button', 'data-reopen': m.month }, t('import.reopen'))
      : h('button.btn.btn-secondary', { type: 'button', 'data-close': m.month, disabled: !m.hasPeriod }, t('months.close'));
    action.addEventListener('click', async () => {
      if (closed) {
        if (!confirm(t('import.reopen.confirm', { month: monthLabel(m.month) }))) return;
        if (await guarded(() => api.reopen(m.month)) === undefined) return;
        toast(t('import.reopen.done', { month: monthLabel(m.month) }));
        await again();
        return;
      }
      // the checklist of the plan page; it chooses the month and draws this page again
      await closeMonth({ ...ctx, month: m.month, setMonth: (month) => { ctx.setMonth(month, { quiet: true }); again(); } });
    });
    const open = h('a', { href: '#/plan', onclick: () => ctx.setMonth(m.month, { quiet: true }) }, monthLabel(m.month));
    return h('tr', { 'data-month': m.month },
      h('th', { scope: 'row' }, open),
      h('td', {}, h(`span.badge${closed ? '' : '.badge-ok'}`, {}, closed ? t('months.closed') : t('months.open'))),
      h('td', {}, planWord(m.status)),
      h('td', {}, action));
  };
  root.append(
    h('p.muted', {}, t('months.intro'), info(t('help.import.finalize'), t('nav.months'))),
    h('table.data', { id: 'months-table' },
      h('thead', {}, h('tr', {}, [t('months.col.month'), t('months.col.state'), t('months.col.plan'), ''].map((x) => h('th', { scope: 'col' }, x)))),
      h('tbody', {}, [...months].reverse().map(row))));
}
