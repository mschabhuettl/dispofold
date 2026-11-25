// The logic of the plugin's pages without DOM: the report's row types and columns, its filters, the texts of the
// comparison (fields, restrictions, demand, differences) and the editing of the mapping. Tested in
// frontend/test/import-plugin-pages.test.js.
import { WEEKDAY_SHORT, compareValues, familyText, fieldLabel, matchesTerms, mixText, restrictionText, t } from '../../../frontend/src/lib/plugin-api.js';
import { TEXTS } from './texts.js';

// ---- report ----

/** The row types of the report in the order of the page, each with the columns it fills. */
export const ROW_TYPES = {
  bedarf: ['bereich', 'tagestyp', 'dienste_historie', 'spitze_historie', 'spitze_historie_max', 'spitze_sp5', 'dienste_nicht_uebernommen',
    'dienste_nicht_uebernommen_gezaehlt'],
  bereich: ['person', 'bereich', 'dienste_historie', 'bereich_aus_gruppen'],
  schicht: ['person', 'bereich', 'schicht', 'dienste_historie', 'einschraenkung'],
  person: ['person', 'dienste_historie', 'wochenstunden', 'bereiche', 'mix_kategorien', 'mix_bereiche', 'auf_anfrage'],
  nicht_zugeordnet: ['schicht', 'dienste_historie', 'stunden_historie'],
  ignoriert: ['schicht', 'dienste_historie', 'stunden_historie'],
  verworfen: ['schicht', 'dienste_historie', 'stunden_historie', 'grund', 'sp5_schicht'],
  wochenstunden: ['person', 'calcbase', 'hrsweek', 'wochenstunden_calcbase', 'wochenstunden'],
  korrektur: ['person', 'feld', 'status', 'grund', 'wert_export', 'wert_korrektur'],
  ausgeblendet: ['schicht', 'dienste_historie', 'stunden_historie', 'sp5_schicht', 'status', 'grund', 'letzter_dienst'],
  nicht_mehr: ['person', 'bereich', 'dienste_historie', 'einschraenkung', 'letzter_dienst'],
};
const TYPE_COLUMN = 'pruefung';
const NUMBER_COLUMNS = new Set(['dienste_historie', 'spitze_historie', 'spitze_historie_max', 'spitze_sp5', 'dienste_nicht_uebernommen',
  'dienste_nicht_uebernommen_gezaehlt', 'stunden_historie', 'wochenstunden', 'auf_anfrage', 'calcbase', 'hrsweek', 'wochenstunden_calcbase']);
/** Statuses of a correction that need a look. */
export const NOTICE_STATUSES = new Set(['ungueltig', 'person_fehlt']);

/** The plugin's text of a key made from a value, or null when it has none. */
const known = (prefix, key) => (Object.hasOwn(TEXTS, `${prefix}.${key}`) ? t(`${prefix}.${key}`) : null);

/** The German header of a report column (the raw name for an unknown one). */
export const columnLabel = (column) => known('plugin.sp5.col', column) ?? column;
export const isNumberColumn = (column) => NUMBER_COLUMNS.has(column);
/** The name and the explanation of a row type; an unknown one under its raw name. */
export const typeLabel = (type) => known('plugin.sp5.type', type) ?? type;
export const typeHelp = (type) => known('plugin.sp5.type', `${type}.help`) ?? t('plugin.sp5.type.unknown.help');

/**
 * The rows grouped by row type, in the order of ROW_TYPES, then unknown types in the order they come: [{ type, columns,
 * rows }]. columns: the type's columns that one of its rows fills, then any other filled column (in the order of the
 * report's columns), so that nothing is dropped.
 */
export function reportGroups(columns, rows) {
  const byType = new Map();
  for (const row of rows) {
    const type = row[TYPE_COLUMN] ?? '';
    if (!byType.has(type)) byType.set(type, []);
    byType.get(type).push(row);
  }
  const order = [...Object.keys(ROW_TYPES).filter((x) => byType.has(x)), ...[...byType.keys()].filter((x) => !Object.hasOwn(ROW_TYPES, x))];
  return order.map((type) => {
    const list = byType.get(type);
    const filled = new Set(list.flatMap((r) => Object.keys(r).filter((k) => k !== TYPE_COLUMN && r[k] !== '' && r[k] != null)));
    const listed = (ROW_TYPES[type] ?? []).filter((c) => filled.has(c));
    const rest = [...columns, ...filled].filter((c, i, all) => filled.has(c) && !listed.includes(c) && all.indexOf(c) === i);
    return { type, columns: [...listed, ...rest], rows: list };
  });
}

const DAY_TYPES = { MON: 0, TUE: 1, WED: 2, THU: 3, FRI: 4, SAT: 5, SUN: 6 };
const dayLabel = (code) => (code === 'HOL' ? t('plugin.sp5.day.HOL') : WEEKDAY_SHORT[DAY_TYPES[code]] ?? code);

/** How a report value shows: day types, restrictions, statuses and fields in German, else as the report has it. */
export function cellText(column, value) {
  if (value == null || value === '') return '';
  if (column === 'tagestyp') return dayLabel(value);
  if (column === 'einschraenkung' || column === 'status') return known('plugin.sp5.value', value) ?? value;
  if (column === 'feld') return value.startsWith('demand:') ? t('plugin.sp5.value.demandOf', { area: value.slice(7) }) : fieldLabel(value);
  return String(value);
}

/** What a report value sorts by: day types in week order (the holiday last), German values by their text, else as it is. */
export function sortValue(column, value) {
  if (value == null || value === '') return '';
  if (column === 'tagestyp') return value === 'HOL' ? 7 : DAY_TYPES[value] ?? value;
  return column === 'einschraenkung' || column === 'status' || column === 'feld' ? cellText(column, value) : value;
}

/** The people and areas a row is about (for the filters). */
export const rowPeople = (row) => (row.person ? [row.person] : []);
export function rowAreas(row) {
  const areas = new Set([row.bereich, ...String(row.bereiche ?? '').split(/[\s,]+/)].filter(Boolean));
  if (String(row.feld ?? '').startsWith('demand:')) areas.add(row.feld.slice(7));
  return [...areas];
}

/** True when the row passes the filters { terms, type, person, area } (empty: any); the search sees raw and German values. */
export function rowMatches(row, { terms = [], type = '', person = '', area = '' } = {}) {
  if (type && row[TYPE_COLUMN] !== type) return false;
  if (person && !rowPeople(row).includes(person)) return false;
  if (area && !rowAreas(row).includes(area)) return false;
  const text = Object.entries(row).map(([k, v]) => `${v} ${cellText(k, v)}`).join(' ');
  return matchesTerms(`${text} ${typeLabel(row[TYPE_COLUMN] ?? '')}`, terms);
}

/** A warning names the id or code as a whole word (or nothing is asked for): the person and area filters. */
export function names(warning, value) {
  if (!value) return true;
  return new RegExp(`(^|[^\\p{L}\\p{N}_])${value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}($|[^\\p{L}\\p{N}_])`, 'u').test(warning);
}

/** The kind of a warning for its filter: its first word ("Schicht", "Bedarf", ...). */
export const warningKind = (warning) => String(warning).trim().split(/[\s:,]+/, 1)[0] ?? '';

// ---- comparison ----

/** Natural order of ids (E9 before E10), then by name. */
export const byId = (list) => [...list].sort((a, b) => compareValues(a.id, b.id) || compareValues(a.name, b.name));

/** The German name of a section of the dataset in the counts (the raw name for an unknown one). */
export const sectionName = (section) => known('plugin.sp5.compare.count', section) ?? section;

/** "+3", "−2" or "0". */
export function difference(old, now) {
  const d = (Number(now) || 0) - (Number(old) || 0);
  return d > 0 ? `+${d.toLocaleString('de-AT')}` : d < 0 ? `−${(-d).toLocaleString('de-AT')}` : '0';
}

const isoDate = /^\d{4}-\d{2}-\d{2}$/;
const de = (iso) => `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}`;

/** A person's field value as text: dates, numbers, lists, shift mix and family rules readable, empty as "offen". */
export function fieldValue(field, value, areaName = (id) => id) {
  if (value == null || value === '' || (Array.isArray(value) && !value.length)) return t('plugin.sp5.compare.empty');
  if (field === 'shiftMix') return mixText(value, areaName) || t('plugin.sp5.compare.empty');
  if (field === 'familyRules' && Array.isArray(value)) return value.map(familyText).join('; ');
  if (field === 'areaIds' && Array.isArray(value)) return value.map(areaName).join(', ');
  if (typeof value === 'boolean') return value ? t('common.yes') : t('common.no');
  if (typeof value === 'number') return value.toLocaleString('de-AT', { maximumFractionDigits: 2 });
  if (typeof value === 'string' && isoDate.test(value)) return de(value);
  if (Array.isArray(value)) return value.map((v) => fieldValue(field, v, areaName)).join(', ');
  if (typeof value === 'object') return JSON.stringify(value);
  return String(value);
}

/** The label of a person field: the core's, else the plugin's own. */
export const fieldName = (field) => (fieldLabel(field) !== field ? fieldLabel(field) : known('plugin.sp5.field', field) ?? field);

/** The restriction changes of a person as ["+ text", "− text"]; shifts maps shift ids to shift types. */
export function restrictionLines(change, shifts = new Map()) {
  return [...(change.added ?? []).map((r) => `+ ${restrictionText(r, shifts)}`), ...(change.removed ?? []).map((r) => `− ${restrictionText(r, shifts)}`)];
}

const DAYS = ['MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT', 'SUN'];
/** Days of a demand rule: runs of weekdays as "Mo–Fr", then single days and the holiday. */
export function daysLabel(days) {
  const set = new Set(days ?? []);
  const parts = [];
  for (let i = 0; i < DAYS.length; i++) {
    if (!set.has(DAYS[i])) continue;
    let j = i;
    while (j + 1 < DAYS.length && set.has(DAYS[j + 1])) j++;
    parts.push(j - i >= 2 ? `${dayLabel(DAYS[i])}–${dayLabel(DAYS[j])}` : DAYS.slice(i, j + 1).map(dayLabel).join(', '));
    i = j;
  }
  if (set.has('HOL')) parts.push(dayLabel('HOL'));
  for (const d of set) if (!DAYS.includes(d) && d !== 'HOL') parts.push(d);
  return parts.join(', ');
}

/** "AN Mo–Fr 07:00–19:00", or with the date "AN 06.01.2027 00:00–00:00". */
export function demandLabel(d) {
  const when = d.date ? de(d.date) : daysLabel(d.days);
  return [d.areaId, when, `${String(d.from).slice(0, 5)}–${String(d.to).slice(0, 5)}`].filter(Boolean).join(' ');
}

/**
 * The staffing of a demand group: "1–2" for each rule; an empty max means the minimum is also the maximum (as on the
 * core's demand page), so "2"; no rule "–".
 */
export function demandValues(list) {
  if (!list?.length) return '–';
  // an empty max: the minimum is also the maximum ("2"); a max equal to the minimum is written out ("2–2")
  return list.map((x) => (x.max == null ? String(x.min) : `${x.min}–${x.max}`)).join(', ');
}

// ---- mapping editor ----

export const ENTRY_LISTS = ['areas', 'shifts', 'groups'];
export const PATTERN_LISTS = ['ignoreShifts', 'ignoreGroups', 'excludeGroups', 'compensatoryRestAbsences', 'ignoreAbsences'];
export const KNOWN_KEYS = [...ENTRY_LISTS, ...PATTERN_LISTS, 'ignoreShiftsAsAbsence'];
export const isObject = (x) => x != null && typeof x === 'object' && !Array.isArray(x);

/** A new entry of a section. */
export function newEntry(section) {
  if (section === 'areas') return { id: '', name: '', color: '#E8E8E8' };
  if (section === 'shifts') return { match: '', areaId: '' };
  if (section === 'groups') return { match: '', areaIds: [] };
  return '';
}

/** The mapping with a list of a section (created when missing); the mapping's key order stays. */
export function sectionList(mapping, section) {
  if (!Array.isArray(mapping[section])) mapping[section] = [];
  return mapping[section];
}

/** Moves the entry at i by delta (−1 up, +1 down) within the list; returns its new index. */
export function moveEntry(list, i, delta) {
  const j = i + delta;
  if (j < 0 || j >= list.length) return i;
  [list[i], list[j]] = [list[j], list[i]];
  return j;
}

/** Sets an optional key: absent for '' (the default), else the value; the other keys keep their order. */
export function setOptional(entry, key, value) {
  if (value === '' || value === undefined) delete entry[key];
  else entry[key] = value;
}

/** Switches an area of a group rule on or off; areaIds stay in the order of the areas, unknown ids after them. */
export function toggleArea(rule, areaId, on, areaOrder) {
  const ids = new Set(Array.isArray(rule.areaIds) ? rule.areaIds : []);
  if (on) ids.add(areaId);
  else ids.delete(areaId);
  rule.areaIds = [...areaOrder.filter((a) => ids.has(a)), ...[...ids].filter((a) => !areaOrder.includes(a))];
}

/** The mapping as mapping.json: two spaces, the key order kept, a final newline. */
export const mappingJson = (mapping) => `${JSON.stringify(mapping, null, 2)}\n`;

// ---- codes of the folder ----

const CATEGORY = { EARLY: 'early', DAY: 'day', LATE: 'late', NIGHT: 'night' };
export const categoryLabel = (c) => (CATEGORY[c] ? t(`plugin.sp5.category.${CATEGORY[c]}`) : c);

/** What a rule decides for a shift, a group or an absence type, as text; none: no rule (not imported). */
export function resultText(kind, result) {
  const n = (result.index ?? 0) + 1;
  switch (result.kind) {
    case 'rule':
      return kind === 'groups' ? t('plugin.sp5.codes.ruleGroup', { n, areas: (result.areaIds ?? []).join(', ') })
        : t('plugin.sp5.codes.ruleShift', { n, area: result.areaId ?? '', category: result.category ? `, ${categoryLabel(result.category)}` : '' });
    case 'ignored':
      return t('plugin.sp5.codes.ignored', { list: t(`plugin.sp5.map.${{ shifts: 'ignoreShifts', groups: 'ignoreGroups', absenceTypes: 'ignoreAbsences' }[kind]}`), n });
    case 'excluded':
      return t('plugin.sp5.codes.excluded', { n });
    case 'compensatoryRest':
      return t('plugin.sp5.codes.compensatoryRest', { n });
    case 'inherited':
      return t('plugin.sp5.codes.inherited', { name: result.fromName ?? result.from ?? '', result: resultText(kind, result.result ?? {}) });
    default:
      return kind === 'absenceTypes' ? t('plugin.sp5.codes.absence') : t('plugin.sp5.codes.none');
  }
}

/** Which section and entry decides each result: { '<section>': Map(index → [labels]) } for the "trifft" cells. */
export function hitsBySection(codes) {
  const out = Object.fromEntries([...ENTRY_LISTS, ...PATTERN_LISTS].map((s) => [s, new Map()]));
  const add = (section, index, label) => {
    if (!out[section].has(index)) out[section].set(index, []);
    out[section].get(index).push(label);
  };
  const SECTION = {
    shifts: { rule: 'shifts', ignored: 'ignoreShifts' },
    groups: { rule: 'groups', ignored: 'ignoreGroups', excluded: 'excludeGroups' },
    absenceTypes: { compensatoryRest: 'compensatoryRestAbsences', ignored: 'ignoreAbsences' },
  };
  for (const kind of Object.keys(SECTION)) {
    for (const x of codes?.[kind] ?? []) {
      const section = SECTION[kind][x.result?.kind];
      if (section) add(section, x.result.index, kind === 'groups' ? x.name : x.code);
    }
  }
  return out;
}

/** Shifts and groups of the folder without a rule and not ignored: { shifts: [codes], groups: [names] }. */
export function unmatched(codes) {
  // a hidden shift without a rule is left out: the import drops it unless it has duties, and then it warns
  return { shifts: (codes?.shifts ?? []).filter((x) => x.result?.kind === 'none' && !x.hidden).map((x) => x.code),
    groups: (codes?.groups ?? []).filter((x) => x.result?.kind === 'none').map((x) => x.name) };
}

// ---- proposals ----

export const PROPOSAL_SECTIONS = ['areas', 'shifts', 'groups', 'compensatoryRest'];

/**
 * Takes a proposal into the mapping (in place): an area, a shift rule (a choice with the chosen area), a group rule,
 * an ignored shift or group, a compensatory rest type. A new area the entry needs comes along (the proposal's
 * newAreas). Returns the ids of the areas added.
 */
export function takeProposal(mapping, section, proposal, choice = null) {
  const added = [];
  const addArea = (area) => {
    const areas = sectionList(mapping, 'areas');
    if (area && !areas.some((a) => isObject(a) && a.id === area.id)) {
      areas.push({ id: area.id, name: area.name, color: area.color });
      added.push(area.id);
    }
  };
  if (section === 'areas') {
    addArea(proposal);
    return added;
  }
  const needed = proposal.kind === 'choice' ? [choice] : proposal.kind === 'rule' ? (proposal.areaId ? [proposal.areaId] : proposal.areaIds ?? []) : [];
  for (const area of proposal.newAreas ?? []) if (needed.includes(area.id)) addArea(area);
  if (section === 'shifts') {
    if (proposal.kind === 'ignored') sectionList(mapping, 'ignoreShifts').push(proposal.match);
    else sectionList(mapping, 'shifts').push({ match: proposal.match, areaId: proposal.kind === 'choice' ? choice : proposal.areaId });
  } else if (section === 'groups') {
    if (proposal.kind === 'ignored') sectionList(mapping, 'ignoreGroups').push(proposal.match);
    else sectionList(mapping, 'groups').push({ match: proposal.match, areaIds: [...proposal.areaIds] });
  } else if (section === 'compensatoryRest') sectionList(mapping, 'compensatoryRestAbsences').push(proposal.match);
  return added;
}

/** The short text of what a proposal adds: "A1 → TA", "A1: ignorieren", "Team Annahme → TA". */
export function proposalTitle(section, p) {
  const what = (section === 'areas' ? `${p.id} ${p.name}` : section === 'groups' ? p.name : `${p.code} ${p.name ?? ''}`).trim();
  if (section === 'areas') return p.family ? t('plugin.sp5.propose.areaFamily', { what, family: p.family }) : t('plugin.sp5.propose.area', { what, group: p.group });
  if (section === 'compensatoryRest') return t('plugin.sp5.propose.rest', { what });
  if (p.kind === 'ignored') return t('plugin.sp5.propose.ignored', { what });
  if (p.kind === 'choice') return t('plugin.sp5.propose.choice', { what, areas: p.choices.map((c) => c.areaId).join(', ') });
  return t('plugin.sp5.propose.rule', { what, areas: p.areaId ?? (p.areaIds ?? []).join(', ') });
}

/**
 * Takes every proposal that needs no choice into the mapping (in place), section by section, as "Alle übernehmen" of
 * each section does: an area that came along with a rule is no proposal of its own any more. Returns what was taken
 * ({ count, areas: the areas added }) and the proposals left (those with a choice of areas).
 */
export function takeEvery(mapping, proposals) {
  const left = Object.fromEntries(PROPOSAL_SECTIONS.map((s) => [s, [...(proposals?.[s] ?? [])]]));
  const areas = [];
  let count = 0;
  for (const section of PROPOSAL_SECTIONS) {
    for (const p of left[section].filter((x) => x.kind !== 'choice')) {
      if (!left[section].includes(p)) continue;
      const added = takeProposal(mapping, section, p);
      areas.push(...added);
      count += 1;
      left[section] = left[section].filter((x) => x !== p);
      if (section !== 'areas') left.areas = left.areas.filter((a) => !added.includes(a.id));
    }
  }
  return { count, areas, left };
}

/** How many proposals there are. */
export const proposalCount = (proposals) => PROPOSAL_SECTIONS.reduce((n, s) => n + (proposals?.[s]?.length ?? 0), 0);
