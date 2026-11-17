// Texts and files of the Daten page: notes and conflicts of the corrections file and of an import keeping the
// changes made in Dispofold (the server sends ids and field names only), and the files of the pseudonymised export.
import { messageText } from './codes.js';
import { t } from './texts.js';

const FIELDS = {
  weeklyHours: t('data.field.weeklyHours'), areaIds: t('data.field.areaIds'), hoursAccount: t('data.field.hoursAccount'),
  compRestBalance: t('data.field.compRestBalance'), familyRules: t('data.field.familyRules'), entryDate: t('data.field.entryDate'),
  exitDate: t('data.field.exitDate'), shiftMix: t('data.field.shiftMix'), restrictions: t('data.field.restrictions'),
  exclude: t('data.field.exclude'), demand: t('data.field.demand'), externalId: t('data.field.externalId'),
  firstName: t('data.field.firstName'), lastName: t('data.field.lastName'), shortName: t('data.field.shortName'),
};

const CHANGES = { added: t('data.change.added'), removed: t('data.change.removed'), changed: t('data.change.changed') };

export const fieldLabel = (field) => FIELDS[field] ?? field;

/** Short names by person id from datasets (later ones do not replace earlier ones). */
export function shortNames(...datasets) {
  const names = new Map();
  for (const d of datasets) for (const p of d?.people ?? []) if (!names.has(p.id) && p.shortName) names.set(p.id, p.shortName);
  return names;
}

/** "WER (P0001)", or the id alone when the short name is not known. */
export const personLabel = (id, names) => (names.get(id) ? `${names.get(id)} (${id})` : id);

/** A note { code, params } with the person by short name and field and change in German. */
export function noteText(note, names) {
  const params = { ...note.params };
  if (params.personId != null) params.personId = personLabel(params.personId, names);
  if (params.field != null) params.field = fieldLabel(params.field);
  if (params.change != null) params.change = CHANGES[params.change] ?? params.change;
  return messageText({ ...note, params });
}

/** A conflict { personId, areaId, field }: the person (or the area's demand) and the field. */
export function conflictText(c, names) {
  return c.areaId ? t('data.conflict.area', { areaId: c.areaId })
    : t('data.conflict.person', { person: personLabel(c.personId, names), field: fieldLabel(c.field) });
}

export const CORRECTIONS_FILE = 'korrekturen.json';
export const KEY_FILE = 'schluessel.csv';

/** The corrections file as saved: JSON with a final newline. */
export const correctionsBlob = (file) => new Blob([`${JSON.stringify(file, null, 1)}\n`], { type: 'application/json' });

/** The files of a pseudonymised export: dataset and plan to share; the key stays separate. */
export function anonymizedFiles(result, month) {
  const json = (x) => new Blob([`${JSON.stringify(x, null, 1)}\n`], { type: 'application/json' });
  return {
    dataset: { blob: json(result.dataset), name: 'dispofold-datensatz-anonym.json' },
    plan: { blob: json(result.plan), name: `dienstplan-${month}-anonym.json` },
    key: { blob: new Blob([result.key], { type: 'text/csv;charset=utf-8' }), name: KEY_FILE },
  };
}
