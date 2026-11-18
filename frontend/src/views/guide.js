// Desktop program: the guide of this version (docs/anleitung.md, built by scripts/build.mjs), readable without
// internet. #/anleitung/<anchor> shows one section; the links of the guide to its own sections are such routes.
import { GUIDE_FILE, GUIDE_URL } from '../lib/guide.js';
import { h } from '../lib/dom.js';
import { t } from '../lib/texts.js';

export async function render(container, ctx, [anchor] = []) {
  const res = await fetch(GUIDE_FILE, { credentials: 'same-origin' }).catch(() => null);
  if (!res?.ok) throw new Error(t('guide.missing', { url: GUIDE_URL }));
  const body = h('div.guide', {});
  body.innerHTML = await res.text(); // built from the repository's own Markdown, never from data
  // the program window opens no outside pages: there the address is shown to copy into a browser
  const online = ctx.frontEnd === 'browser'
    ? h('a', { href: GUIDE_URL, target: '_blank', rel: 'noopener' }, GUIDE_URL)
    : h('code', {}, GUIDE_URL);
  container.append(body.querySelector('h1'), h('p.muted.guide-online', {}, t('guide.online'), ' ', online), body);
  // a section: shown once the shell has put the page in place
  const target = anchor && body.querySelector(`[id="${CSS.escape(anchor)}"]`);
  const timer = target && setTimeout(() => target.scrollIntoView({ block: 'start' }), 0);
  return () => clearTimeout(timer);
}
