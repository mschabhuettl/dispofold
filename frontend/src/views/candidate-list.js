// "Wer kann gefragt werden?" for gaps (planners only): loads the candidates, a click on a name sets the duty fixed.
import { candidateRows, gapLabel } from '../lib/candidates.js';
import { tintStyle } from '../lib/colors.js';
import { guarded, h } from '../lib/dom.js';
import { t } from '../lib/texts.js';

/**
 * The list for one gap {areaId, from, to}. onSet(plan, request) runs after a duty was set. The element gets
 * data-loaded once the answer is shown; an answer arriving after the element was removed is dropped.
 */
export function candidateList({ api, month, gap, dataset, onSet }) {
  const people = new Map(dataset.people.map((p) => [p.id, p]));
  const shifts = new Map(dataset.shifts.map((s) => [s.id, s]));
  const box = h('div.candidates', {}, h('p.muted', {}, t('common.loading')));

  async function pick(row) {
    const plan = await guarded(() => api.setCell(month, row.request), t('candidates.set', { name: row.name, code: row.code, day: row.day }));
    if (plan) onSet(plan, row.request);
  }

  (async () => {
    let content;
    try {
      const rows = candidateRows(await api.candidates(month, gap), people, shifts);
      content = rows.length
        ? [h('p.muted.candidates-hint', {}, t('candidates.hint')), h('ul', {}, rows.map((r) => h('li', {}, h('button', { type: 'button', 'aria-label': r.label, onclick: () => pick(r) },
          h('span.cell-code', r.color ? { class: 'tinted', style: tintStyle(r.color) } : { class: 'outlined' }, r.code),
          h('span.candidate-name', {}, r.name), h('span.muted', {}, r.day), h('span.muted', {}, r.hours)))))]
        : h('p.muted', {}, t('candidates.none'));
    } catch (e) {
      content = h('p.alert.alert-error', {}, e.message || String(e));
    }
    if (!box.isConnected) return;
    box.replaceChildren(...[content].flat());
    box.dataset.loaded = 'true';
  })();
  return box;
}

/** Gaps of the analysis, each with its area, time and missing count, and the list of who could be asked. */
export function gapsWithCandidates({ api, month, gaps, dataset, onSet }) {
  const areaName = new Map(dataset.areas.map((a) => [a.id, a.name]));
  return gaps.map((g) => h('div.gap-item', {},
    h('p', {}, h('strong', {}, gapLabel(g, areaName.get(g.areaId)))),
    h('p.muted', {}, t('candidates.title')),
    candidateList({ api, month, gap: g, dataset, onSet })));
}
