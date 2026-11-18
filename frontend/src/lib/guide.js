// The user guide (docs/anleitung.md): on the documentation site, which always shows the latest release, and in the
// desktop program as the copy built into this version (#/anleitung). The desktop window opens no outside pages and
// the program makes no calls to the internet, so it always shows its own copy. scripts/build.mjs uses this module too.

export const SITE = 'https://mschabhuettl.github.io/dispofold/';
export const GUIDE_URL = `${SITE}anleitung/`;
/** Built from docs/anleitung.md by scripts/build.mjs, with its images next to it. */
export const GUIDE_FILE = 'anleitung/anleitung.html';

/** Section of the guide for each page: the anchor of its heading in docs/anleitung.md. */
export const SECTIONS = { plan: 'dienstplan', funktionen: 'plan-nach-funktion', zeitachse: 'bereichs-zeitachse',
  pruefen: 'probleme', fairness: 'fairness', eingaben: 'eingaben', person: 'personen', regeln: 'regeln',
  stammdaten: 'stammdaten', import: 'daten', einstellungen: 'updates' };

/** Anchor of a heading as on GitHub and on the site: lower case, punctuation removed, spaces to hyphens. */
export function slug(text) {
  return text.trim().toLowerCase().replace(/[^\p{L}\p{N}\p{M}_\- ]/gu, '').replace(/ /g, '-');
}

/** Address of the guide or one of its sections: the built-in copy in the desktop program, else the site. */
export function guideHref(desktop, anchor = '') {
  if (desktop) return anchor ? `#/anleitung/${anchor}` : '#/anleitung';
  return anchor ? `${GUIDE_URL}#${anchor}` : GUIDE_URL;
}
