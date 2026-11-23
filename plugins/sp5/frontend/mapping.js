// Zuordnung: a structured editor of settings.mapping (mapping.json). Every change is checked by the server (POST
// zuordnung/pruefen, debounced) and matched against the codes of the folder (POST zuordnung/codes): what each rule
// and pattern matches, and what has no rule. Saving puts the settings with the new mapping; the server checks again.
// Patterns are never evaluated here. "Zuordnung vorschlagen" (POST zuordnung/vorschlaege) lists entries for what the
// editor's mapping does not meet yet, each with its reason: taken into the editor (saved only with "Zuordnung
// speichern") or discarded (remembered by the server until the reset).
import { guarded, h, info, saveBlob, sortableTable, t, toast } from '../../../frontend/src/lib/plugin-api.js';
import { ENTRY_LISTS, KNOWN_KEYS, PATTERN_LISTS, PROPOSAL_SECTIONS, hitsBySection, isObject, mappingJson, moveEntry, newEntry, proposalTitle,
  resultText, sectionList, setOptional, takeProposal, toggleArea, unmatched } from './model.js';
import { ID, problem } from './shared.js';

const CATEGORIES = ['EARLY', 'DAY', 'LATE', 'NIGHT'];
const CATEGORY_KEY = { EARLY: 'early', DAY: 'day', LATE: 'late', NIGHT: 'night' };
/** What each pattern list's "trifft" cell names when it matches nothing. */
const NOTHING = { shifts: 'shift', ignoreShifts: 'shift', groups: 'group', ignoreGroups: 'group', excludeGroups: 'group',
  compensatoryRestAbsences: 'absence', ignoreAbsences: 'absence' };

export async function render(root, ctx) {
  const api = ctx.api.plugin(ID);
  let settings = await api.get('einstellungen');
  let mapping = settings.mapping == null ? { areas: [], shifts: [], groups: [] } : structuredClone(settings.mapping);
  let dirty = false;
  let codes = null;
  let timer = null;
  let checked = 0; // the newest check; older answers are dropped
  ctx.isDirty = () => dirty;

  const editor = h('div', { id: 'sp5-map-editor' });
  const problems = h('div', { id: 'sp5-map-problems', 'aria-live': 'polite' });
  const marker = h('span.badge.badge-warn', { id: 'sp5-map-dirty', hidden: true }, t('plugin.sp5.map.unsaved'));
  const raw = h('textarea', { id: 'sp5-map-json', rows: 16, spellcheck: 'false', 'aria-label': t('plugin.sp5.map.json') });
  let rawEdited = false;
  let rawNewer = false; // the last edit was in the JSON view (not yet applied), not in the editor
  raw.addEventListener('input', () => {
    rawEdited = true;
    rawNewer = true;
    dirty = true;
    marker.hidden = false;
  });
  const codesBox = h('div', { id: 'sp5-codes', 'aria-live': 'polite' });

  const button = (label, onclick, attrs = {}) => h('button.btn.btn-secondary.btn-small', { type: 'button', onclick, ...attrs }, label);

  /** After every change: the marker, the JSON view, and a check soon. */
  function changed() {
    rawNewer = false;
    dirty = true;
    marker.hidden = false;
    if (!rawEdited) { // edits of the JSON not yet applied stay
      raw.value = mappingJson(mapping);
      rawEdited = false;
    }
    clearTimeout(timer);
    timer = setTimeout(check, 400);
  }

  async function check() {
    const token = ++checked;
    const body = mapping;
    const [validation, folder] = await Promise.allSettled([api.post('zuordnung/pruefen', body), api.post('zuordnung/codes', body)]);
    if (token !== checked) return;
    if (validation.status === 'fulfilled') {
      const errors = validation.value.errors ?? [];
      problems.replaceChildren(errors.length
        ? h('div.alert.alert-error', {}, h('strong', {}, t('plugin.sp5.map.problems', { count: errors.length })), h('ul', {}, errors.map((e) => h('li', {}, e))))
        : h('p.alert', {}, t('plugin.sp5.map.noProblems')));
    } else problems.replaceChildren(problem(validation.reason));
    codes = folder.status === 'fulfilled' ? folder.value : null;
    showCodes(folder.status === 'fulfilled' ? null : folder.reason);
    showHits();
  }

  // ---- the editor ----

  const label = (section, i) => t(`plugin.sp5.map.one.${section}`, { n: i + 1 });
  const keyed = (el, key) => { el.dataset.key = key; return el; };
  const text = (section, entry, field, i, attrs = {}) => {
    const input = keyed(h('input', { type: 'text', value: typeof entry[field] === 'string' ? entry[field] : '', 'aria-label': `${t(`plugin.sp5.map.field.${field}`)}, ${label(section, i)}`,
      spellcheck: 'false', ...attrs }), `${section}.${i}.${field}`);
    input.addEventListener('input', () => { entry[field] = input.value; changed(); });
    return input;
  };
  const choice = (key, aria, options, current, onchange) => {
    const select = keyed(h('select', { 'aria-label': aria }, options.map(([v, labelText]) => h('option', { value: v, selected: v === current }, labelText))), key);
    select.addEventListener('change', () => { onchange(select.value); changed(); });
    return select;
  };
  const areaIds = () => (Array.isArray(mapping.areas) ? mapping.areas : []).filter((a) => isObject(a) && typeof a.id === 'string' && a.id).map((a) => a.id);

  function actions(section, list, i) {
    const move = (delta, name) => button(delta < 0 ? '↑' : '↓', () => {
      const j = moveEntry(list, i, delta);
      if (j === i) return;
      codes = null; // the hits are by position: wait for the next check
      changed();
      draw(`${section}.${j}.${name}`);
    }, { 'aria-label': t(delta < 0 ? 'plugin.sp5.map.up' : 'plugin.sp5.map.down', { entry: label(section, i) }), 'data-key': `${section}.${i}.${name}` });
    return h('td', {}, h('div.btn-row', {}, move(-1, 'up'), move(1, 'down'),
      button('×', () => {
        list.splice(i, 1);
        codes = null;
        changed();
        draw(list.length ? `${section}.${Math.min(i, list.length - 1)}.remove` : `${section}.add`);
      }, { 'aria-label': t('plugin.sp5.map.remove', { entry: label(section, i) }), 'data-key': `${section}.${i}.remove` })));
  }

  const invalid = (value) => [h('code', {}, JSON.stringify(value)), ' ', h('span.badge.badge-hard', {}, t('plugin.sp5.map.invalid'))];
  const hitsCell = (section, i) => h('td', { 'data-hits': `${section}:${i}` }, '…');

  function cells(section, entry, i) {
    if (section === 'areas') {
      const color = h('input', { type: 'color', value: /^#[0-9a-f]{6}$/i.test(entry.color ?? '') ? entry.color : '#000000',
        'aria-label': `${t('plugin.sp5.map.field.color')}, ${label(section, i)}` });
      const hex = text(section, entry, 'color', i, { placeholder: '#RRGGBB', size: 8 });
      color.addEventListener('input', () => { entry.color = color.value.toUpperCase(); hex.value = entry.color; changed(); });
      hex.addEventListener('input', () => { if (/^#[0-9a-f]{6}$/i.test(hex.value)) color.value = hex.value; });
      const id = text(section, entry, 'id', i, { size: 6 });
      id.addEventListener('change', () => setTimeout(() => draw())); // the areas in the rules follow, after the focus moved on
      return [h('td', {}, id), h('td', {}, text(section, entry, 'name', i)), h('td', {}, h('div.btn-row', {}, color, hex))];
    }
    if (section === 'shifts') {
      const areas = areaIds();
      const current = typeof entry.areaId === 'string' ? entry.areaId : '';
      const areaOptions = [...(areas.includes(current) ? [] : [[current, current ? t('plugin.sp5.map.unknownArea', { area: current }) : t('plugin.sp5.map.noArea')]]),
        ...areas.map((a) => [a, a])];
      const category = 'category' in entry ? String(entry.category) : '';
      const unpopular = 'unpopular' in entry ? String(entry.unpopular) : '';
      return [h('td', {}, text(section, entry, 'match', i)),
        h('td', {}, choice(`${section}.${i}.areaId`, `${t('plugin.sp5.map.field.areaId')}, ${label(section, i)}`, areaOptions, current, (v) => { entry.areaId = v; })),
        h('td', {}, choice(`${section}.${i}.category`, `${t('plugin.sp5.map.field.category')}, ${label(section, i)}`,
          [['', t('plugin.sp5.map.category.auto')], ...CATEGORIES.map((c) => [c, t(`plugin.sp5.category.${CATEGORY_KEY[c]}`)]),
            ...(category && !CATEGORIES.includes(category) ? [[category, `${category} (${t('plugin.sp5.map.invalid')})`]] : [])],
          category, (v) => setOptional(entry, 'category', v === category && !CATEGORIES.includes(v) ? entry.category : v))),
        h('td', {}, choice(`${section}.${i}.unpopular`, `${t('plugin.sp5.map.field.unpopular')}, ${label(section, i)}`,
          [['', t('plugin.sp5.map.unpopular.default')], ['true', t('common.yes')], ['false', t('common.no')],
            ...(unpopular && unpopular !== 'true' && unpopular !== 'false' ? [[unpopular, `${unpopular} (${t('plugin.sp5.map.invalid')})`]] : [])],
          unpopular, (v) => setOptional(entry, 'unpopular', v === 'true' ? true : v === 'false' ? false : v === '' ? '' : entry.unpopular))),
        hitsCell(section, i)];
    }
    // groups
    const areas = areaIds();
    const ids = Array.isArray(entry.areaIds) ? entry.areaIds : [];
    const checks = [...areas, ...ids.filter((a) => !areas.includes(a))].map((a) => {
      const box = keyed(h('input', { type: 'checkbox', checked: ids.includes(a) }), `${section}.${i}.area.${a}`);
      box.addEventListener('change', () => { toggleArea(entry, a, box.checked, areas); changed(); });
      return h('label.check-inline', {}, box, areas.includes(a) ? a : t('plugin.sp5.map.unknownArea', { area: a }));
    });
    return [h('td', {}, text(section, entry, 'match', i)),
      h('td', {}, h('div.btn-row', { role: 'group', 'aria-label': `${t('plugin.sp5.map.field.areaIds')}, ${label(section, i)}` }, checks)),
      hitsCell(section, i)];
  }

  const HEADERS = { areas: ['id', 'name', 'color'], shifts: ['match', 'areaId', 'category', 'unpopular', 'hits'], groups: ['match', 'areaIds', 'hits'] };

  function listSection(section) {
    const id = `h-sp5-map-${section}`;
    const head = h('h2', { id }, t(`plugin.sp5.map.${section}`), info(t(`plugin.sp5.map.${section}.help`), t(`plugin.sp5.map.${section}`)));
    const value = mapping[section];
    if (value !== undefined && !Array.isArray(value)) {
      return h('section.section', { 'aria-labelledby': id, 'data-section': section }, head, h('p', {}, invalid(value)), h('p.muted', {}, t('plugin.sp5.map.notList')));
    }
    const list = value ?? [];
    const patterns = PATTERN_LISTS.includes(section);
    const headers = patterns ? ['pattern', 'hits'] : HEADERS[section];
    const rows = list.map((entry, i) => {
      const ok = patterns ? typeof entry === 'string' : isObject(entry);
      let content;
      if (!ok) content = [h('td', { colspan: headers.length }, invalid(entry))];
      else if (patterns) {
        const input = keyed(h('input', { type: 'text', value: entry, spellcheck: 'false', 'aria-label': `${t('plugin.sp5.map.field.pattern')}, ${label(section, i)}` }), `${section}.${i}.pattern`);
        input.addEventListener('input', () => { list[i] = input.value; changed(); });
        content = [h('td', {}, input), hitsCell(section, i)];
      } else content = cells(section, entry, i);
      return h('tr', { 'data-row': `${section}:${i}` }, h('td.num', {}, String(i + 1)), content, actions(section, list, i));
    });
    const add = keyed(h('button.btn.btn-secondary.btn-small', { type: 'button' }, t(`plugin.sp5.map.add.${section}`)), `${section}.add`);
    add.addEventListener('click', () => {
      sectionList(mapping, section).push(newEntry(section));
      codes = null;
      changed();
      const i = mapping[section].length - 1;
      draw(`${section}.${i}.${patterns ? 'pattern' : section === 'areas' ? 'id' : 'match'}`);
    });
    return h('section.section', { 'aria-labelledby': id, 'data-section': section }, head,
      section === 'shifts' ? h('p.muted', {}, t('plugin.sp5.map.firstWins')) : null,
      list.length ? h('table.data', { 'aria-labelledby': id },
        h('thead', {}, h('tr', {}, h('th.num', { scope: 'col' }, t('plugin.sp5.map.number')), headers.map((x) => h('th', { scope: 'col' }, t(`plugin.sp5.map.field.${x}`))),
          h('th', { scope: 'col' }, t('plugin.sp5.map.actions')))),
        h('tbody', {}, rows)) : h('p.muted', {}, t('plugin.sp5.map.empty')),
      h('div.toolbar', {}, add));
  }

  function flagSection() {
    const id = 'h-sp5-map-ignoreShiftsAsAbsence';
    const v = mapping.ignoreShiftsAsAbsence;
    const current = v === undefined ? '' : typeof v === 'boolean' ? String(v) : 'raw';
    const select = choice('ignoreShiftsAsAbsence', t('plugin.sp5.map.ignoreShiftsAsAbsence'),
      [['', t('plugin.sp5.map.flag.default')], ['true', t('common.yes')], ['false', t('common.no')],
        ...(current === 'raw' ? [['raw', `${JSON.stringify(v)} (${t('plugin.sp5.map.invalid')})`]] : [])], current,
      (x) => { if (x !== 'raw') setOptional(mapping, 'ignoreShiftsAsAbsence', x === '' ? '' : x === 'true'); });
    select.id = 'sp5-map-flag';
    return h('section.section', { 'aria-labelledby': id }, h('h2', { id }, t('plugin.sp5.map.ignoreShiftsAsAbsence'),
      info(t('plugin.sp5.map.ignoreShiftsAsAbsence.help'), t('plugin.sp5.map.ignoreShiftsAsAbsence'))),
    h('div.toolbar', {}, h('div.field', {}, h('label', { for: 'sp5-map-flag' }, t('plugin.sp5.map.flag.label')), select)));
  }

  function draw(focus = null) {
    const active = document.activeElement?.dataset?.key;
    if (!isObject(mapping)) {
      editor.replaceChildren(h('div.alert.alert-error', { role: 'alert' }, t('plugin.sp5.map.noObject')));
      return;
    }
    const unknown = Object.keys(mapping).filter((k) => !KNOWN_KEYS.includes(k));
    editor.replaceChildren(...[...ENTRY_LISTS, ...PATTERN_LISTS].map(listSection), flagSection(),
      unknown.length ? h('section.section', { 'aria-labelledby': 'h-sp5-map-unknown' }, h('h2', { id: 'h-sp5-map-unknown' }, t('plugin.sp5.map.unknown')),
        h('p.alert.alert-error', {}, t('plugin.sp5.map.unknown.help')),
        h('ul', { id: 'sp5-map-unknown' }, unknown.map((k) => h('li', {}, h('strong', {}, k), ': ', h('code', {}, JSON.stringify(mapping[k])))))) : '');
    showHits();
    const key = focus ?? active;
    if (key) editor.querySelector(`[data-key="${CSS.escape(key)}"]`)?.focus();
  }

  function showHits() {
    const hits = hitsBySection(codes);
    for (const td of editor.querySelectorAll('[data-hits]')) {
      const [section, i] = td.dataset.hits.split(':');
      if (!codes) { td.replaceChildren('…'); continue; }
      const found = hits[section]?.get(Number(i));
      td.replaceChildren(found?.length ? found.join(', ')
        : h('span.badge.badge-warn', {}, t(section === 'shifts' || section === 'groups' ? 'plugin.sp5.map.hitsNone' : `plugin.sp5.map.hitsNone.${NOTHING[section]}`)));
    }
  }

  // ---- the codes of the folder ----

  const tables = {
    shifts: sortableTable([
      { label: t('plugin.sp5.codes.code'), value: (x) => x.code },
      { label: t('plugin.sp5.codes.name'), value: (x) => x.name },
      { label: t('plugin.sp5.codes.hidden'), value: (x) => (x.hidden ? t('common.yes') : '') },
      { label: t('plugin.sp5.codes.result'), value: (x) => resultText('shifts', x.result), cell: (x) => result('shifts', x) },
    ], { label: t('plugin.sp5.codes.shifts') }),
    groups: sortableTable([
      { label: t('plugin.sp5.codes.name'), value: (x) => x.name },
      { label: t('plugin.sp5.codes.members'), value: (x) => x.members, num: true },
      { label: t('plugin.sp5.codes.result'), value: (x) => resultText('groups', x.result), cell: (x) => result('groups', x) },
    ], { label: t('plugin.sp5.codes.groups') }),
    absenceTypes: sortableTable([
      { label: t('plugin.sp5.codes.code'), value: (x) => x.code },
      { label: t('plugin.sp5.codes.name'), value: (x) => x.name },
      { label: t('plugin.sp5.codes.result'), value: (x) => resultText('absenceTypes', x.result) },
    ], { label: t('plugin.sp5.codes.absenceTypes') }),
  };
  function result(kind, x) {
    const s = resultText(kind, x.result);
    return x.result?.kind === 'none' ? h('span.badge.badge-warn', {}, s) : s;
  }
  function showCodes(error) {
    if (error) {
      codesBox.replaceChildren(problem(error));
      return;
    }
    const u = unmatched(codes);
    const parts = [u.shifts.length ? t('plugin.sp5.codes.unmatchedShifts', { list: u.shifts.join(', ') }) : null,
      u.groups.length ? t('plugin.sp5.codes.unmatchedGroups', { list: u.groups.join(', ') }) : null].filter(Boolean);
    for (const k of Object.keys(tables)) tables[k].show(codes?.[k] ?? []);
    codesBox.replaceChildren(
      h('p', { id: 'sp5-codes-summary', class: parts.length ? 'alert' : '' }, parts.length
        ? [h('strong', {}, t('plugin.sp5.codes.unmatched')), ' ', parts.join('; '), '. ', t('plugin.sp5.codes.unmatched.help')]
        : t('plugin.sp5.codes.complete')),
      ...Object.keys(tables).flatMap((k) => [h('h3', {}, t(`plugin.sp5.codes.${k}`)), (codes?.[k] ?? []).length ? tables[k].element : h('p.muted', {}, t('plugin.sp5.codes.none.table'))]));
  }

  // ---- proposals from the folder ----

  const proposalBox = h('div', { id: 'sp5-proposals', 'aria-live': 'polite' });
  let proposals = null;
  const proposalSettings = () => settings.proposals ?? {};
  const resetButton = button('', async () => {
    const saved = await guarded(() => api.post('zuordnung/vorschlaege/zuruecksetzen'), t('plugin.sp5.propose.resetDone'));
    if (saved) {
      settings = { ...settings, proposals: saved.proposals };
      await propose();
    }
  }, { id: 'sp5-propose-reset', hidden: true });
  const showReset = (n) => {
    resetButton.hidden = !n;
    resetButton.textContent = t('plugin.sp5.propose.reset', { count: n });
  };

  async function propose() {
    try {
      proposals = await api.post('zuordnung/vorschlaege', mapping);
    } catch (e) {
      proposalBox.replaceChildren(problem(e));
      return;
    }
    showReset(proposals.discarded ?? 0);
    showProposals();
  }

  /** Removes a proposal from the list after it was taken or discarded; the focus goes to the next one. */
  function done(section, p) {
    const list = proposals[section];
    const i = list.indexOf(p);
    list.splice(i, 1);
    showProposals();
    const next = list[Math.min(i, list.length - 1)];
    const target = next ? proposalBox.querySelector(`[data-proposal="${CSS.escape(next.key)}"] button`)
      : proposalBox.querySelector('[data-proposal] button') ?? document.getElementById('sp5-propose');
    target?.focus();
  }

  function take(section, p, choice = null) {
    const added = takeProposal(mapping, section, p, proposals.areas, choice);
    // an area that came along with a rule is no proposal of its own any more
    proposals.areas = proposals.areas.filter((a) => section === 'areas' ? true : !added.includes(a.id));
    codes = null;
    changed();
    draw();
    done(section, p);
  }

  async function discard(section, p) {
    const saved = await guarded(() => api.post('zuordnung/vorschlaege/verwerfen', { key: p.key }));
    if (!saved) return;
    settings = { ...settings, proposals: saved.proposals };
    proposals.discarded = (proposals.discarded ?? 0) + 1;
    showReset(proposals.discarded);
    done(section, p);
  }

  function showProposals() {
    const total = PROPOSAL_SECTIONS.reduce((n, s) => n + proposals[s].length, 0);
    if (!total) {
      proposalBox.replaceChildren(h('p.alert', { id: 'sp5-proposals-none' }, t('plugin.sp5.propose.none')));
      return;
    }
    proposalBox.replaceChildren(h('p', { id: 'sp5-proposals-summary' }, t('plugin.sp5.propose.found', { count: total })),
      ...PROPOSAL_SECTIONS.filter((s) => proposals[s].length).map((section) => {
        const list = proposals[section];
        const id = `h-sp5-propose-${section}`;
        const takeAll = button(t('plugin.sp5.propose.takeAll'), () => {
          for (const p of [...list].filter((x) => x.kind !== 'choice')) take(section, p);
        }, { 'data-take-all': section, 'aria-label': t('plugin.sp5.propose.takeAll.aria', { section: t(`plugin.sp5.propose.section.${section}`) }) });
        return h('section.section', { 'aria-labelledby': id, 'data-proposals': section },
          h('div.toolbar', {}, h('h3', { id }, t(`plugin.sp5.propose.section.${section}`), ` (${list.length})`), takeAll),
          list.some((x) => x.kind === 'choice') ? h('p.muted', {}, t('plugin.sp5.propose.choiceNote')) : null,
          h('ul.proposals', {}, list.map((p) => {
            const title = proposalTitle(section, p);
            const takeButtons = p.kind === 'choice'
              ? p.choices.map((c) => button(t('plugin.sp5.propose.takeArea', { area: c.areaId }), () => take(section, p, c.areaId),
                { 'aria-label': t('plugin.sp5.propose.takeArea.aria', { area: c.areaId, what: title }) }))
              : [button(t('plugin.sp5.propose.take'), () => take(section, p), { 'aria-label': t('plugin.sp5.propose.take.aria', { what: title }) })];
            return h('li', { 'data-proposal': p.key },
              h('div', {}, h('strong', {}, title)),
              h('p.muted', {}, p.reason),
              h('div.btn-row', {}, ...takeButtons,
                button(t('plugin.sp5.propose.discard'), () => discard(section, p), { 'aria-label': t('plugin.sp5.propose.discard.aria', { what: title }) })));
          })));
      }));
  }

  // the settings of the proposals: share, rare shifts, months, words for the compensatory rest
  const ps = proposalSettings();
  const numberField = (key, min, max) => h('div.field', {}, h('label', { for: `sp5-propose-${key}` }, t(`plugin.sp5.propose.setting.${key}`),
    info(t(`plugin.sp5.propose.setting.${key}.help`), t(`plugin.sp5.propose.setting.${key}`))),
  h('input', { id: `sp5-propose-${key}`, type: 'number', min, max, value: ps[key] ?? '' }));
  const words = h('input', { id: 'sp5-propose-restWords', type: 'text', value: (ps.restWords ?? []).join(', '), spellcheck: 'false' });
  const saveProposalSettings = button(t('plugin.sp5.propose.saveSettings'), async () => {
    const value = (key) => Number(document.getElementById(`sp5-propose-${key}`).value);
    const body = { ...settings, proposals: { ...proposalSettings(), share: value('share'), minDuties: value('minDuties'), months: value('months'),
      restWords: words.value.split(',').map((w) => w.trim()).filter(Boolean) } };
    const saved = await guarded(() => api.put('einstellungen', body), t('plugin.sp5.propose.settingsSaved'));
    if (saved) {
      settings = { ...settings, proposals: saved.proposals };
      if (proposals) await propose();
    }
  }, { id: 'sp5-propose-save' });
  const proposalSection = h('section.section', { 'aria-labelledby': 'h-sp5-propose' },
    h('h2', { id: 'h-sp5-propose' }, t('plugin.sp5.propose.title'), info(t('plugin.sp5.propose.title.help'), t('plugin.sp5.propose.title'))),
    h('p.muted', {}, t('plugin.sp5.propose.intro')),
    h('div.toolbar', {}, h('button.btn.btn-secondary', { id: 'sp5-propose', type: 'button', onclick: () => propose() }, t('plugin.sp5.propose.run')), resetButton),
    h('details.advanced', {}, h('summary', {}, t('plugin.sp5.propose.settings')),
      h('div.settings', {}, numberField('share', 1, 100), numberField('minDuties', 0), numberField('months', 0, 1200),
        h('div.field', {}, h('label', { for: 'sp5-propose-restWords' }, t('plugin.sp5.propose.setting.restWords'),
          info(t('plugin.sp5.propose.setting.restWords.help'), t('plugin.sp5.propose.setting.restWords'))), words),
        h('div.row', {}, saveProposalSettings))),
    proposalBox);

  // ---- saving and JSON ----

  const apply = (value) => {
    codes = null;
    rawEdited = false; // the JSON view shows what the editor holds again
    mapping = value;
    changed();
    draw();
  };
  const parse = (textValue) => {
    try { return JSON.parse(textValue); } catch { return undefined; }
  };
  const save = h('button.btn.btn-primary', { id: 'sp5-map-save', type: 'button' }, t('plugin.sp5.map.save'));
  save.addEventListener('click', async () => {
    // the newer of the two is saved: the JSON typed last, or the editor changed after it
    if (rawEdited && rawNewer) {
      const value = parse(raw.value);
      if (value === undefined) return toast(t('plugin.sp5.mapping.invalidJson'), true);
      apply(value);
    } else if (rawEdited) {
      rawEdited = false;
      raw.value = mappingJson(mapping);
    }
    const saved = await guarded(() => api.put('einstellungen', { ...settings, mapping }), t('plugin.sp5.map.saved'));
    if (!saved) return undefined;
    settings = saved;
    dirty = false;
    marker.hidden = true;
    return undefined;
  });
  const download = button(t('plugin.sp5.map.download'), () => saveBlob(new Blob([mappingJson(mapping)], { type: 'application/json' }), 'mapping.json'),
    { id: 'sp5-map-download' });
  const file = h('input', { id: 'sp5-map-file', type: 'file', accept: '.json,application/json' });
  file.addEventListener('change', async () => {
    const chosen = file.files[0];
    if (!chosen) return;
    const value = parse(await chosen.text());
    file.value = '';
    if (value === undefined) return toast(t('plugin.sp5.mapping.invalidJson'), true);
    if (confirm(t('plugin.sp5.map.replaceConfirm'))) apply(value);
    return undefined;
  });
  const applyRaw = button(t('plugin.sp5.map.applyJson'), () => {
    const value = parse(raw.value);
    if (value === undefined) return toast(t('plugin.sp5.mapping.invalidJson'), true);
    rawEdited = false;
    apply(value);
    return undefined;
  }, { id: 'sp5-map-apply' });
  raw.value = mappingJson(mapping);

  root.append(h('h1', {}, t('plugin.sp5.map.title')),
    settings.mapping == null ? h('p.alert', {}, t('plugin.sp5.map.none')) : '',
    h('div.toolbar', {}, save, marker, download,
      h('div', {}, h('label', { for: 'sp5-map-file' }, t('plugin.sp5.map.file')), ' ', file)), // not a .field: its input would be too narrow
    h('section.section', { 'aria-labelledby': 'h-sp5-map-check' }, h('h2', { id: 'h-sp5-map-check' }, t('plugin.sp5.map.check'),
      info(t('plugin.sp5.map.check.help'), t('plugin.sp5.map.check'))), problems),
    proposalSection,
    editor,
    h('section.section', { 'aria-labelledby': 'h-sp5-map-raw' }, h('h2', { id: 'h-sp5-map-raw' }, t('plugin.sp5.map.json'),
      info(t('plugin.sp5.map.json.help'), t('plugin.sp5.map.json'))),
    h('details.advanced', {}, h('summary', {}, t('plugin.sp5.map.json.show')), raw, h('div.toolbar', {}, applyRaw))),
    h('section.section', { 'aria-labelledby': 'h-sp5-codes' }, h('h2', { id: 'h-sp5-codes' }, t('plugin.sp5.codes.title'),
      info(t('plugin.sp5.codes.title.help'), t('plugin.sp5.codes.title'))), codesBox));
  draw();
  await check();
  return () => clearTimeout(timer);
}
