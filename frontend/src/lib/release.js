// Notice of a newer version, planners only: in the server build from GET /api/release (the server's own check), in
// the desktop program from its update source (after the user agreed to the check on the first start).
import { h } from './dom.js';
import { t } from './texts.js';

const VERSION = /^v?(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})\.(0|[1-9]\d{0,8})$/;
const KEY = 'release-dismissed';

/** The numbers of a release version x.y.z, or null (pre-releases such as 1.4.0-rc.1 and anything else). */
export function parseVersion(text) {
  const m = VERSION.exec(String(text ?? ''));
  return m ? m.slice(1).map(Number) : null;
}

/** Whether release version a is newer than b, by number; any release is newer than an invalid or missing b. */
export function isNewer(a, b) {
  const x = parseVersion(a);
  const y = parseVersion(b);
  if (!x) return false;
  if (!y) return true;
  const i = x.findIndex((n, k) => n !== y[k]);
  return i >= 0 && x[i] > y[i];
}

/** Whether the notice shows: a release is offered and it is newer than the version dismissed last. */
export const showNotice = (available, dismissedVersion) => Boolean(available) && isNewer(available.version, dismissedVersion);

/** The version whose notice was dismissed in this browser, or null. */
export function dismissed(storage = globalThis.localStorage) {
  try { return storage.getItem(KEY); } catch { return null; }
}

export function dismiss(version, storage = globalThis.localStorage) {
  try { storage.setItem(KEY, version); } catch { /* no storage: the notice comes back with the next start */ }
}

/**
 * Shows the notice in the banner when it applies. `install` (desktop): the install flow behind "Jetzt installieren";
 * without it the dialog lists the update steps of the server. `window`: the desktop program window, which opens no
 * other pages, so the release page is shown as an address.
 */
export function showReleaseNotice(banner, available, { current, install = null, window = false } = {}) {
  if (!banner || !showNotice(available, dismissed())) return;
  const close = h('button.btn-icon', { type: 'button', 'aria-label': t('release.dismiss'), title: t('release.dismiss'),
    onclick: () => {
      dismiss(available.version);
      banner.hidden = true;
      banner.replaceChildren();
    } }, '×');
  banner.replaceChildren(h('span', {}, t('release.notice', { version: available.version })),
    h('button.btn.btn-secondary.btn-small', { type: 'button', onclick: () => releaseDialog(available, { current, install, window }) },
      t('release.details')), close);
  banner.hidden = false;
}

/** The release: title, notes as plain text (never as HTML), the release page and what to do next. */
function releaseDialog(available, { current, install, window }) {
  const { version } = available;
  const page = /^https:\/\//.test(available.url ?? '') ? available.url : null;
  const closeButton = h('button.btn.btn-secondary', { type: 'button', onclick: () => dialog.close() }, t('release.close'));
  // a failed install is told here: the page behind the modal dialog (and its toast) cannot be reached
  const problem = h('p.alert.alert-error', { role: 'alert', hidden: true });
  const installButton = install && h('button.btn.btn-primary', { type: 'button', onclick: async () => {
    installButton.disabled = true;
    problem.hidden = true;
    try {
      await install();
    } catch (e) {
      problem.textContent = e.message || String(e);
      problem.hidden = false;
    } finally {
      installButton.disabled = false;
    }
  } }, t('release.install'));
  const dialog = h('dialog.dialog.release-dialog', { 'aria-labelledby': 'release-title' },
    h('h2', { id: 'release-title' }, available.title || t('release.title', { version })),
    h('p.muted', {}, t('release.current', { version: current ?? '?', next: version })),
    available.notes ? h('pre.release-notes', {}, available.notes) : h('p', {}, t('release.noNotes')),
    page && h('p', {}, window ? [t('release.page'), ' ', h('code', {}, page)]
      : h('a', { href: page, target: '_blank', rel: 'noopener' }, t('release.page.open'))),
    !install && [h('h3', {}, t('release.steps')),
      h('ol', {}, [t('release.step.backup'), t('release.step.tag', { version }), t('release.step.pull'),
        t('release.step.restart')].map((step) => h('li', {}, step)))],
    install && problem,
    h('div.toolbar', {}, installButton, closeButton));
  dialog.addEventListener('close', () => dialog.remove());
  document.body.append(dialog);
  dialog.showModal();
  (installButton || closeButton).focus();
}

/**
 * Desktop, first start without saved update settings: asks once whether to check for new versions automatically.
 * Resolves true for "Ja", false for "Nein" and null when the dialog closed without an answer (then nothing is saved
 * and the next start asks again).
 */
export function askForChecks() {
  const yes = h('button.btn.btn-primary', { type: 'button', autofocus: true, onclick: () => dialog.close('yes') }, t('release.consent.yes'));
  const no = h('button.btn.btn-secondary', { type: 'button', onclick: () => dialog.close('no') }, t('release.consent.no'));
  const dialog = h('dialog.dialog', { id: 'update-consent', 'aria-labelledby': 'update-consent-title' },
    h('h2', { id: 'update-consent-title' }, t('release.consent.question')),
    h('p', {}, t('release.consent.text')),
    h('div.toolbar', {}, yes, no));
  dialog.addEventListener('cancel', (e) => e.preventDefault()); // Esc is no answer
  return new Promise((resolve) => {
    dialog.addEventListener('close', () => {
      dialog.remove();
      resolve({ yes: true, no: false }[dialog.returnValue] ?? null);
    });
    document.body.append(dialog);
    dialog.showModal();
    yes.focus();
  });
}
