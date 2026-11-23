// The guide of this version (docs/anleitung/*.md, built by scripts/build.mjs), readable without internet, on the server
// as in the desktop program. #/anleitung/<page>/<anchor> shows one page at a section; the links of the guide are such
// routes. At the bottom the same page on the documentation site, as a fallback.
import { GUIDE_URL, allPages, guideFile, guideHref } from '../lib/guide.js';
import { h } from '../lib/dom.js';
import { t } from '../lib/texts.js';

export async function render(container, ctx, [page = 'index', anchor] = []) {
  const PAGES = allPages();
  const found = PAGES.findIndex(([id]) => id === page);
  // the help page of a plugin that is switched off (core pages link to it, e.g. to read before switching it on):
  // built in all the same, shown without the neighbouring pages
  const plugin = found < 0 && /^plugin-[a-z0-9]+-[a-z0-9-]+$/.test(page) ? page : null;
  const index = Math.max(0, found);
  const id = plugin ?? PAGES[index][0];
  const res = await fetch(guideFile(id), { credentials: 'same-origin' }).catch(() => null);
  const html = res?.ok ? await res.text() : '';
  // a web server may answer a missing file with the program's own page
  if (!html || /^\s*<!doctype/i.test(html)) throw new Error(t('guide.missing', { url: GUIDE_URL }));
  const body = h('div.guide', {});
  body.innerHTML = html; // built from the repository's own Markdown, never from data
  // the desktop program's window opens no outside pages: there the address is shown to copy into a browser
  const browser = ctx.me?.desktop !== true || ctx.frontEnd === 'browser';
  const address = guideHref(false, id);
  const online = browser
    ? h('a', { href: address, target: '_blank', rel: 'noopener' }, address)
    : h('code', {}, address);
  // the other pages: back to the overview (not the first page: the quick start comes before it), previous and next
  // page, without repeating the overview
  const overview = PAGES.findIndex(([p]) => p === 'index');
  const link = (i, text) => h('a', { href: guideHref(true, PAGES[i][0]) }, text);
  const neighbour = (i) => !plugin && i >= 0 && i < PAGES.length && i !== overview;
  const pages = h('nav.guide-pages', { 'aria-label': t('guide.pages') },
    id !== 'index' && link(overview, t('guide.overview')),
    neighbour(index - 1) && link(index - 1, t('guide.previous', { page: PAGES[index - 1][1] })),
    neighbour(index + 1) && link(index + 1, t('guide.next', { page: PAGES[index + 1][1] })));
  container.append(body.querySelector('h1'), body, pages, h('p.muted.guide-online', {}, t('guide.online'), ' ', online));
  // the same for the links of the guide to the other documents on the site
  if (!browser) {
    for (const a of body.querySelectorAll('a[target="_blank"]')) a.replaceWith(a.textContent, ' (', h('code', {}, a.href), ')');
  }
  // a section, else the top of the page: shown once the shell has put the page in place, and again while the text
  // above it still settles (fonts loading late wrap it differently) until the reader scrolls or after two seconds
  const target = anchor && body.querySelector(`[id="${CSS.escape(anchor)}"]`);
  const show = () => (target ? target.scrollIntoView({ block: 'start' }) : window.scrollTo(0, 0));
  const timer = setTimeout(show, 0);
  const settle = target && new ResizeObserver(show);
  const stop = () => {
    settle?.disconnect();
    for (const type of ['wheel', 'touchmove', 'keydown']) window.removeEventListener(type, stop);
  };
  if (settle) {
    settle.observe(body);
    for (const type of ['wheel', 'touchmove', 'keydown']) window.addEventListener(type, stop, { passive: true });
  }
  const end = setTimeout(stop, 2000);
  return () => { clearTimeout(timer); clearTimeout(end); stop(); };
}
