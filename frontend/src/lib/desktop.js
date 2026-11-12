// Desktop program only: full-page states after quitting and while restarting for an update.
import { h } from './dom.js';
import { t } from './texts.js';

/** Replaces the whole page with a centred message box (the app shell is gone afterwards). */
export function showPage(title, text, extra = null) {
  const box = h('main.center-box', { role: 'status' },
    h('div.brand', {}, h('img', { src: 'img/logo.svg', alt: '' }), h('p.app-name', {}, t('app.name'))),
    h('h1', { tabindex: '-1' }, title), text && h('p.muted', {}, text), extra);
  document.body.className = 'center-page';
  document.body.replaceChildren(box);
  box.querySelector('h1').focus(); // the focused button is gone; move focus to the new message
  document.title = `${title} · ${t('app.name')}`;
  return box;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/**
 * How the desktop program shows this page: 'window' (own program window) or 'browser' (the user's browser through
 * the program's local proxy, the default on Linux). Older shells have no /__shell/mode: then 'window'.
 */
export async function frontEndMode(fetchImpl = (...a) => globalThis.fetch(...a)) {
  try {
    const res = await fetchImpl('/__shell/mode', { cache: 'no-store', credentials: 'same-origin' });
    if (!res.ok || !(res.headers?.get?.('Content-Type') ?? '').includes('json')) return 'window';
    return (await res.json())?.frontEnd === 'browser' ? 'browser' : 'window';
  } catch {
    return 'window';
  }
}

async function ready(fetchImpl) {
  try {
    // an API call through the window's proxy: 503 desktop.restarting while the backend is away (it comes back on a
    // new port behind the same origin), JSON once it is back; an HTML answer is the shell's restart page
    const res = await fetchImpl('/api/me', { cache: 'no-store', credentials: 'same-origin', headers: { Accept: 'application/json' } });
    return res.ok && (res.headers?.get?.('Content-Type') ?? 'application/json').includes('json');
  } catch {
    return false;
  }
}

/**
 * Waits until the program has gone down and is ready again, then reloads the page.
 * The old instance may still answer for a moment, so "ready" only counts after it was seen down once
 * or after `settleMs`. Gives up after `timeoutMs`.
 */
export async function waitForRestart({ fetchImpl = (...a) => globalThis.fetch(...a), intervalMs = 1000, settleMs = 15000,
  timeoutMs = 180000, reload = () => globalThis.location.reload(), now = () => Date.now(), wait = sleep } = {}) {
  const start = now();
  let wentDown = false;
  while (now() - start < timeoutMs) {
    await wait(intervalMs);
    const up = await ready(fetchImpl);
    if (!up) wentDown = true;
    else if (wentDown || now() - start >= settleMs) {
      reload();
      return true;
    }
  }
  return false;
}

/** Shows the restart page and reloads when the new version is ready. */
export async function restartAndReload() {
  const box = showPage(t('desktop.restart.title'), t('desktop.restart.text'));
  const ok = await waitForRestart();
  if (!ok) box.append(h('p.alert.alert-error', { role: 'alert' }, t('desktop.restart.timeout')));
}
