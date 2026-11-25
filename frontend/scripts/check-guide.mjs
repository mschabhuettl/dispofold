// Check of the guide (docs/anleitung) against the user interface texts: every menu path written "A > B" or "A → B"
// names menu groups, tabs and sections that exist, and every term in bold that is no run-in heading (ending in ":")
// or question is a text of the interface (lib/texts.js, the extensions' texts) or listed below as a word of the
// guide itself. A renamed button, tab or setting then fails here until the guide follows.
//   node scripts/check-guide.mjs [folder of the guide]
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const guide = process.argv[2] ?? join(root, 'docs', 'anleitung');

const texts = [];
for (const file of [join(root, 'frontend', 'src', 'lib', 'texts.js'),
  ...readdirSync(join(root, 'plugins')).map((p) => join(root, 'plugins', p, 'frontend', 'texts.js'))]) {
  let module;
  try {
    module = await import(pathToFileURL(file));
  } catch {
    continue;
  }
  // labels only: help texts, rule descriptions, hits and messages name things in passing, and an old word in one of
  // them would let a renamed button pass
  // them would let a renamed button pass; a short one is a label too (a link text such as "Mehr dazu in der Anleitung")
  texts.push(...Object.entries(module.TEXTS).filter(([key, text]) => String(text).split(/\s+/).length <= 5
    || !/^(help|hit|error)\.|\.(description|intro\w*|help\w*)$/.test(key)).map(([, text]) => text));
}
/** A text as the interface shows it, without placeholders, plural forms and quotes. */
const plain = (s) => String(s).replace(/\{[^}]*\}/g, ' ').replace(/[„“"]/g, '').replace(/\s+/g, ' ').trim();
const ui = texts.map(plain);
/** A text with placeholders as a pattern that a term with the values filled in matches. */
const escape = (s) => s.replace(/[.*+?^$()|[\]\\]/g, '\\$&');
// only texts with enough of their own words: "{a} {b}" would match anything; a value is at most a few words
const patterns = texts.filter((s) => /\{[^}]*\}/.test(s) && plain(s).replace(/[^A-Za-zÄÖÜäöüß]/g, '').length >= 6).map((s) =>
  new RegExp(`^${String(s).replace(/[„“"]/g, '').split(/\{[^}]*\}/).map((part) => escape(part.replace(/\s+/g, ' '))).join('[^ ]+(?: [^ ]+){0,2}?')}$`));
const inUi = (term) => ui.some((text) => text.includes(term)) || patterns.some((re) => re.test(term));
const exact = new Set(ui);

/** Words in bold that are the guide's own: run-in headings such as "Wann ändern", keys, glossary terms, words of the
 * operating system; a new one goes here on purpose. */
const GUIDE_WORDS = new Set(JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'guide-words.json'), 'utf8')));

const problems = [];
let paths = 0;
let terms = 0;
for (const name of readdirSync(guide).filter((f) => f.endsWith('.md'))) {
  // paragraph by paragraph: a term in bold may run over a line break
  const lines = readFileSync(join(guide, name), 'utf8').split('\n');
  const paragraphs = [];
  lines.forEach((text, i) => {
    if (!text.trim() || /^\s*(#|\||[-*] |\d+\. )/.test(text) || !paragraphs.length) paragraphs.push({ line: i + 1, text });
    else paragraphs[paragraphs.length - 1].text += ` ${text.trim()}`;
  });
  paragraphs.forEach(({ line: start, text: line }) => {
    const where = `${name}:${start}`;
    // a menu path starts at a menu group; each further step is one word (a tab or a section)
    for (const [path] of line.replace(/\*\*/g, '').matchAll(/(?:Dienstplan|Eingaben|Prüfen|Personen|Daten|Einstellungen)(?:(?: > | → )[A-ZÄÖÜ][\wäöüßÄÖÜ-]*)+/g)) {
      paths++;
      for (const part of path.split(/ > | → /).map((p) => p.trim())) {
        if (!exact.has(part)) problems.push(`${where}: menu path "${path}": "${part}" is no text of the interface`);
      }
    }
    for (const [, bold] of line.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').matchAll(/\*\*([^*]+)\*\*/g)) {
      // run-in headings end in ".", "?" or ":"; one of at most three words before ":" is mostly a field name and is
      // checked; a trailing comma belongs to the sentence
      if (/[.?]\s*$/.test(bold) || / > | → /.test(bold)) continue;
      if (/:\s*$/.test(bold) && bold.trim().split(/\s+/).length > 3) continue;
      const term = plain(bold).replace(/[,:]$/, '');
      if (!term || !/[A-Za-zÄÖÜäöü]/.test(term)) continue;
      terms++;
      if (!inUi(term) && !GUIDE_WORDS.has(term)) problems.push(`${where}: **${term}** is no text of the interface`);
    }
  });
}
for (const p of problems) console.log(`FAIL ${p}`);
console.log(`${problems.length ? 'FAIL' : 'ok  '} guide: ${paths} menu paths and ${terms} terms in bold checked, ${problems.length} not found`);
process.exit(problems.length ? 1 : 0);
