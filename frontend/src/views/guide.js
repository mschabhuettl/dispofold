// Desktop program: the guide of this version (docs/anleitung/*.md, built by scripts/build.mjs), readable without
// internet. #/anleitung/<page>/<anchor> shows one page at a section; the links of the guide are such routes.
import { GUIDE_URL, allPages, guideFile, guideHref } from '../lib/guide.js';
import { h } from '../lib/dom.js';
import { t } from '../lib/texts.js';

export async function render(container, ctx, [page = 'index', anchor] = []) {
  const PAGES = allPages();
  const index = Math.max(0, PAGES.findIndex(([id]) => id === page));
  const [id] = PAGES[index];
  const res = await fetch(guideFile(id), { credentials: 'same-origin' }).catch(() => null);
  if (!res?.ok) throw new Error(t('guide.missing', { url: GUIDE_URL }));
  const body = h('div.guide', {});
  body.innerHTML = await res.text(); // built from the repository's own Markdown, never from data
  // the program window opens no outside pages: there the address is shown to copy into a browser
  const address = guideHref(false, id);
  const online = ctx.frontEnd === 'browser'
    ? h('a', { href: address, target: '_blank', rel: 'noopener' }, address)
    : h('code', {}, address);
  // the other pages: back to the overview, previous and next page
  const link = (i, text) => h('a', { href: guideHref(true, PAGES[i][0]) }, text);
  const pages = h('nav.guide-pages', { 'aria-label': t('guide.pages') },
    index > 0 && link(0, t('guide.overview')),
    index > 1 && link(index - 1, t('guide.previous', { page: PAGES[index - 1][1] })),
    index + 1 < PAGES.length && link(index + 1, t('guide.next', { page: PAGES[index + 1][1] })));
  container.append(body.querySelector('h1'), h('p.muted.guide-online', {}, t('guide.online'), ' ', online), body, pages);
  // the same for the links of the guide to the other documents on the site
  if (ctx.frontEnd !== 'browser') {
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
