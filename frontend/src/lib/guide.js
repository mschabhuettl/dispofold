// The user guide (docs/anleitung/*.md, one page per task): on the documentation site, which always shows the latest
// release, and in the program as the copy built into this version (#/anleitung/<page>/<anchor>), on the server as on
// the own computer. The program shows its own copy, readable without internet; the site is only a fallback (a link
// at the bottom of each page of the guide). scripts/build.mjs uses this module too.

export const SITE = 'https://mschabhuettl.github.io/dispofold/';
export const GUIDE_URL = `${SITE}anleitung/`;

/** The pages of the guide in the order of the site navigation (mkdocs.yml): file name without .md and title (its h1). */
export const PAGES = [
  ['schnellstart', 'Schnellstart in 10 Schritten'],
  ['index', 'Anleitung für die Dienstplanung'],
  ['erste-schritte', 'Erste Schritte'],
  ['beispiel', 'Ein Monat Schritt für Schritt'],
  ['zahlen', 'Was die Zahlen bedeuten'],
  ['wie-plant', 'Wie das Programm plant'],
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
  ['erweiterungen', 'Erweiterungen'],
  ['nur-lesen', 'Hinweise für die Rolle „nur lesen“'],
  ['fragen', 'Häufige Fragen und Fehlerbehebung'],
  ['begriffe', 'Begriffe'],
];

/** The help pages of the switched on plugins after PAGES (main.js, lib/plugins.js), as [id, title]. */
export const EXTRA_PAGES = [];

/** All pages of the built-in guide in their order. */
export const allPages = () => [...PAGES, ...EXTRA_PAGES];

/** Built from docs/anleitung/<page>.md by scripts/build.mjs, with its images next to it. */
export const guideFile = (page) => `anleitung/${page}.html`;

/** Section of the guide for each page of the program: "<page>" or "<page>#<anchor of a heading on it>". */
export const SECTIONS = { plan: 'plan-bearbeiten', funktionen: 'plan-bearbeiten#plan-nach-funktion',
  zeitachse: 'plan-bearbeiten#bereichs-zeitachse', pruefen: 'planen#probleme', fairness: 'auswertungen#fairness',
  eingaben: 'eingaben', person: 'personen', bereiche: 'personen#bereiche-aller-personen', regeln: 'einstellungen', stammdaten: 'stammdaten', uebernehmen: 'daten#übernehmen', monate: 'daten#monate', sichern: 'daten#sichern',
  einstellungen: 'updates#updates-im-programm-am-eigenen-rechner', erweiterungen: 'erweiterungen' };

/** Anchor of a heading as on GitHub and on the site: lower case, punctuation removed, spaces to hyphens. */
export function slug(text) {
  return text.trim().toLowerCase().replace(/[^\p{L}\p{N}\p{M}_\- ]/gu, '').replace(/ /g, '-');
}

/** Address of the guide or one of its sections ("<page>" or "<page>#<anchor>"): the copy built into the program, else
 * the site. */
export function guideHref(builtIn, section = '') {
  const [page = 'index', anchor = ''] = section ? section.split('#') : [];
  if (builtIn) return `#/anleitung${page === 'index' && !anchor ? '' : `/${page}`}${anchor ? `/${anchor}` : ''}`;
  return `${GUIDE_URL}${page === 'index' ? '' : `${page}/`}${anchor ? `#${anchor}` : ''}`;
}
