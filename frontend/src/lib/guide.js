// The user guide (docs/anleitung/*.md, one page per task): on the documentation site, which always shows the latest
// release, and in the desktop program as the copy built into this version (#/anleitung/<page>/<anchor>). The desktop
// window opens no outside pages and the program makes no calls to the internet, so it always shows its own copy.
// scripts/build.mjs uses this module too.

export const SITE = 'https://mschabhuettl.github.io/dispofold/';
export const GUIDE_URL = `${SITE}anleitung/`;

/** The pages of the guide in the order of the site navigation (mkdocs.yml): file name without .md and title (its h1). */
export const PAGES = [
  ['index', 'Anleitung für die Dienstplanung'],
  ['erste-schritte', 'Erste Schritte'],
  ['personen', 'Personen'],
  ['stammdaten', 'Bereiche, Schichten und Bedarf'],
  ['eingaben', 'Abwesenheiten, Wünsche und Vorgaben'],
  ['planen', 'Planen und Prüfen'],
  ['plan-bearbeiten', 'Plan bearbeiten'],
  ['auswertungen', 'Auswertungen'],
  ['monatsabschluss', 'Monat abschließen'],
  ['daten', 'Daten und Korrekturdatei'],
  ['einstellungen', 'Regeln einstellen'],
  ['updates', 'Updates'],
  ['nur-lesen', 'Hinweise für die Rolle „nur lesen“'],
];

/** Built from docs/anleitung/<page>.md by scripts/build.mjs, with its images next to it. */
export const guideFile = (page) => `anleitung/${page}.html`;

/** Section of the guide for each page of the program: "<page>" or "<page>#<anchor of a heading on it>". */
export const SECTIONS = { plan: 'plan-bearbeiten', funktionen: 'plan-bearbeiten#plan-nach-funktion',
  zeitachse: 'plan-bearbeiten#bereichs-zeitachse', pruefen: 'planen#probleme', fairness: 'auswertungen#fairness',
  eingaben: 'eingaben', person: 'personen', regeln: 'einstellungen', stammdaten: 'stammdaten', import: 'daten',
  einstellungen: 'updates#updates-im-programm-am-eigenen-rechner' };

/** Anchor of a heading as on GitHub and on the site: lower case, punctuation removed, spaces to hyphens. */
export function slug(text) {
  return text.trim().toLowerCase().replace(/[^\p{L}\p{N}\p{M}_\- ]/gu, '').replace(/ /g, '-');
}

/** Address of the guide or one of its sections ("<page>" or "<page>#<anchor>"): the built-in copy in the desktop
 * program, else the site. */
export function guideHref(desktop, section = '') {
  const [page = 'index', anchor = ''] = section ? section.split('#') : [];
  if (desktop) return `#/anleitung${page === 'index' && !anchor ? '' : `/${page}`}${anchor ? `/${anchor}` : ''}`;
  return `${GUIDE_URL}${page === 'index' ? '' : `${page}/`}${anchor ? `#${anchor}` : ''}`;
}
