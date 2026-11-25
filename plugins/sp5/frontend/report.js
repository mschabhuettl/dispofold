// Bericht (inside the result of Einlesen on Daten → Übernehmen): the report of the last run (GET lauf/bericht), one section per row type with its own columns, a search
// over all columns, filters by row type, person and area, sorting by a click on a header; then the warnings with the
// same search and the person and area filters. "Bericht als CSV" saves the file as the export writes it.
import { guarded, h, info, matchesTerms, saveBlob, searchTerms, sortableTable, t } from '../../../frontend/src/lib/plugin-api.js';
import { NOTICE_STATUSES, cellText, columnLabel, isNumberColumn, reportGroups, rowAreas, rowMatches, rowPeople, sortValue, typeHelp, typeLabel,
  names, warningKind } from './model.js';
import { ID, noRun, orNoRun } from './shared.js';

/**
 * In plain words, for the top of a run's result: whether the run looks fine and what to look at first, from the run's
 * own rows and warnings; areas without minimum demand (nobody would be planned there) are never "in Ordnung".
 */
export function verdict(report, run) {
  const groups = reportGroups(report.columns ?? [], report.rows ?? []);
  const unmapped = groups.find((g) => g.type === 'nicht_zugeordnet')?.rows.length ?? 0;
  const notices = (report.rows ?? []).filter((r) => NOTICE_STATUSES.has(r.status)).length;
  const warned = (report.warnings ?? []).length;
  const noDemand = run?.counts?.areasWithoutDemand ?? 0;
  const allAreas = run?.counts?.areas ?? 0;
  const first = [noDemand && (noDemand >= allAreas ? t('plugin.sp5.report.first.noDemandAll') : t('plugin.sp5.report.first.noDemand', { count: noDemand })),
    unmapped && t('plugin.sp5.report.first.unmapped', { count: unmapped }), notices && t('plugin.sp5.report.first.notices', { count: notices })].filter(Boolean);
  return h(noDemand ? 'p.alert.alert-warn' : 'p.alert', { id: 'sp5-report-verdict', role: 'note' }, first.length
    ? t('plugin.sp5.report.first', { what: first.join('; '), warnings: warned })
    : t('plugin.sp5.report.fine', { warnings: warned }));
}

/** The report of the last run, inside the result of Einlesen (its verdict stands on top of that result). */
export async function render(root, ctx) {
  const api = ctx.api.plugin(ID);
  const [report, dataset] = await Promise.all([orNoRun(() => api.get('lauf/bericht')), ctx.dataset().catch(() => null)]);
  if (!report) {
    root.append(noRun());
    return;
  }
  const people = new Set((dataset?.people ?? []).map((p) => p.id));
  const groups = reportGroups(report.columns ?? [], report.rows ?? []);
  const sorted = (values) => [...new Set(values)].sort((a, b) => a.localeCompare(b, 'de', { numeric: true }));

  const select = (id, label, all, options) => h('div.field', {}, h('label', { for: id }, label),
    h('select', { id, onchange: apply }, h('option', { value: '' }, all), options.map(([v, text]) => h('option', { value: v }, text))));
  const search = h('input', { id: 'sp5-report-search', type: 'search', autocomplete: 'off', oninput: apply });
  const typeFilter = select('sp5-report-type', t('plugin.sp5.report.type'), t('plugin.sp5.report.all'),
    groups.map((g) => [g.type, `${typeLabel(g.type)} (${g.rows.length})`]));
  const personFilter = select('sp5-report-person', t('plugin.sp5.col.person'), t('plugin.sp5.report.all'),
    sorted((report.rows ?? []).flatMap(rowPeople)).map((p) => [p, p]));
  const areaFilter = select('sp5-report-area', t('plugin.sp5.col.bereich'), t('plugin.sp5.report.all'),
    sorted((report.rows ?? []).flatMap(rowAreas)).map((a) => [a, a]));
  const count = h('p', { id: 'sp5-report-count', 'aria-live': 'polite' });
  const csv = h('button.btn.btn-secondary', { id: 'sp5-report-csv', type: 'button' }, t('plugin.sp5.report.csv'));
  csv.addEventListener('click', async () => {
    const file = await guarded(() => api.file('lauf/bericht.csv', 'dispofold-sp5-bericht.csv'));
    if (file) saveBlob(file.blob, file.filename);
  });

  const cell = (column, row) => {
    const value = row[column];
    const text = cellText(column, value);
    if (column === 'person' && value && people.has(value)) return h('a', { href: `#/person/${encodeURIComponent(value)}` }, value);
    if (column === 'status' && NOTICE_STATUSES.has(value)) return h('span.badge.badge-hard', { title: value }, text);
    return text !== String(value ?? '') ? h('span', { title: value }, text) : text;
  };
  const sections = groups.map((g) => {
    const id = `sp5-type-${g.type.replace(/[^a-z0-9_-]/gi, '')}`;
    const table = sortableTable(g.columns.map((c) => ({ label: columnLabel(c), title: c, num: isNumberColumn(c),
      value: (row) => sortValue(c, row[c]), cell: (row) => cell(c, row) })), { label: typeLabel(g.type) });
    const heading = h('h4', { id: `h-${id}` });
    const element = h('section.section', { id, 'aria-labelledby': `h-${id}`, 'data-type': g.type }, heading,
      h('p.muted', {}, typeHelp(g.type)), table.element);
    return { g, table, heading, element };
  });

  // the export program's words shown with today's (the file keeps them, for the comparison byte for byte)
  const warnings = (report.warnings ?? []).map((w) => w.replaceAll('nur auf Anfrage', 'nur von Hand'));
  const kinds = sorted(warnings.map(warningKind));
  const kindFilter = select('sp5-warning-kind', t('plugin.sp5.report.warningKind'), t('plugin.sp5.report.all'),
    kinds.map((k) => [k, `${k} (${warnings.filter((w) => warningKind(w) === k).length})`]));
  const warningHeading = h('h4', { id: 'h-sp5-warnings' });
  const warningList = h('ul', { id: 'sp5-warnings' });

  function apply() {
    const filter = { terms: searchTerms(search.value), type: typeFilter.querySelector('select').value,
      person: personFilter.querySelector('select').value, area: areaFilter.querySelector('select').value };
    let shown = 0;
    for (const s of sections) {
      const rows = s.g.rows.filter((r) => rowMatches(r, filter));
      shown += rows.length;
      s.table.show(rows);
      s.element.hidden = !rows.length;
      s.heading.replaceChildren(typeLabel(s.g.type), ' ', h('span.muted', {},
        rows.length === s.g.rows.length ? `(${rows.length})` : t('plugin.sp5.report.some', { shown: rows.length, total: s.g.rows.length })));
    }
    const total = report.rows?.length ?? 0;
    count.textContent = shown === total ? t('plugin.sp5.report.count', { total })
      : [t('plugin.sp5.report.countSome', { shown, total }), shown ? '' : t('plugin.sp5.report.noMatch')].join(' ').trim();
    const kind = kindFilter.querySelector('select').value;
    const list = warnings.filter((w) => (!kind || warningKind(w) === kind) && matchesTerms(w, filter.terms)
      && names(w, filter.person) && names(w, filter.area));
    warningHeading.replaceChildren(t('plugin.sp5.report.warnings'), ' ', h('span.muted', {}, list.length === warnings.length
      ? `(${warnings.length})` : t('plugin.sp5.report.some', { shown: list.length, total: warnings.length })));
    warningList.replaceChildren(...list.map((w) => h('li', {}, w)));
    if (!list.length) warningList.append(h('li.muted', {}, warnings.length ? t('plugin.sp5.report.noMatch') : t('plugin.sp5.report.noWarnings')));
  }

  root.append(
    h('div.toolbar', {},
      h('div.field', {}, h('label', { for: 'sp5-report-search' }, t('plugin.sp5.report.search')), search),
      info(t('plugin.sp5.report.search.help'), t('plugin.sp5.report.search')),
      typeFilter, personFilter, areaFilter, csv),
    count,
    ...sections.map((s) => s.element),
    h('section.section', { 'aria-labelledby': 'h-sp5-warnings' }, warningHeading, h('div.toolbar', {}, kindFilter), warningList));
  apply();
}
