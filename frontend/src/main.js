// Application shell: user, month picker, navigation and hash routing.
import { createApi } from './lib/api.js';
import { datasetCache as createDatasetCache } from './lib/dataset-cache.js';
import { addMonths, monthLabel } from './lib/dates.js';
import { restartAndReload, frontEndMode, showPage } from './lib/desktop.js';
import { guarded, h, toast } from './lib/dom.js';
import { applyTexts, t } from './lib/texts.js';
import { initThemeToggle } from './lib/theme.js';
import * as roster from './views/roster.js';
import * as timeline from './views/timeline.js';
import * as person from './views/person.js';
import * as fairness from './views/fairness.js';
import * as rules from './views/rules.js';
import * as inputs from './views/inputs.js';
import * as masterdata from './views/masterdata.js';
import * as importExport from './views/import.js';
import * as settings from './views/settings.js';

const ROUTES = [
  { path: 'plan', label: t('nav.plan'), view: roster },
  { path: 'zeitachse', label: t('nav.timeline'), view: timeline },
  { path: 'person', label: t('nav.person'), view: person },
  { path: 'fairness', label: t('nav.fairness'), view: fairness },
  { path: 'regeln', label: t('nav.rules'), view: rules },
  { path: 'eingaben', label: t('nav.inputs'), view: inputs },
  { path: 'stammdaten', label: t('nav.masterdata'), view: masterdata },
  { path: 'import', label: t('nav.import'), view: importExport },
];
const SETTINGS_ROUTE = { path: 'einstellungen', label: t('nav.settings'), view: settings };

let desktop = false;
let frontEnd = 'window'; // desktop only: 'window' or 'browser' (GET /__shell/mode)
const api = createApi({
  onUnauthorized: () => {
    // the desktop program has no login page: the request did not come through its window
    if (desktop) {
      stopView();
      showPage(t('desktop.unauthorized.title'), frontEnd === 'browser' ? t('desktop.unauthorized.restart') : t('desktop.unauthorized.text'));
    } else location.href = 'login.html';
  },
  onRestarting: (on) => {
    // desktop window: the backend restarts, requests are repeated meanwhile; not an error
    const status = document.getElementById('restart-status');
    if (status) status.hidden = !on;
    if (!on && ctx.month && !ctx.isDirty?.()) render(); // load the data again from the restarted backend
  },
});

// desktop window: the shell saved a download and tells where (never fired in a browser)
window.addEventListener('dispofold-download', (e) => {
  const { name, folder } = e.detail ?? {};
  if (name) toast(t('desktop.download.saved', { name, folder: folder ?? '' }));
});
const main = document.getElementById('main');
const monthInput = document.getElementById('month');
let cleanup = null;
const datasetCache = createDatasetCache(() => api.dataset());
let renderCount = 0;
let currentHash = location.hash;
const MONTH_RE = /^\d{4}-(0[1-9]|1[0-2])$/;

const ctx = {
  api,
  month: null,
  me: null,
  canEdit: false,
  /** Dataset, loaded once per view (see render) and again after an import or a change of master data. */
  dataset: () => datasetCache.get(),
  invalidateDataset: () => datasetCache.invalidate(),
  navigate: (path) => { location.hash = `#/${path}`; },
  /** Switches the planning month, e.g. to the month of freshly imported data. */
  setMonth: (month) => setMonth(month),
  /** Set by a view with unsaved changes; asked before leaving the view or changing the month. */
  isDirty: null,
  mayLeave: () => mayLeave(),
  /** Ends the current view (timers, listeners) before the page is replaced, e.g. for a restart. */
  stop: () => stopView(),
};

let bannerTimer = null;

function stopView() {
  clearTimeout(bannerTimer);
  renderCount++; // a view still loading is discarded
  if (cleanup) cleanup();
  cleanup = null;
  ctx.isDirty = null;
}

function mayLeave() {
  return !ctx.isDirty?.() || confirm(t('common.unsavedChanges'));
}

function defaultMonth(months) {
  const now = new Date();
  const next = addMonths(`${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`, 1);
  const stored = sessionStorage.getItem('month');
  if (MONTH_RE.test(stored ?? '')) return stored;
  if (months.some((m) => m.month === next) || months.length === 0) return next;
  return months.at(-1).month;
}

function parseHash() {
  const [path, ...rest] = location.hash.replace(/^#\/?/, '').split('/');
  const route = ROUTES.find((r) => r.path === path) ?? ROUTES[0];
  const decode = (x) => { try { return decodeURIComponent(x); } catch { return x; } };
  return { route, params: rest.map(decode) };
}

async function render() {
  const token = ++renderCount;
  const { route, params } = parseHash();
  for (const a of document.querySelectorAll('#nav a')) {
    if (a.dataset.path === route.path) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  }
  currentHash = location.hash;
  if (cleanup) cleanup();
  cleanup = null;
  ctx.isDirty = null;
  datasetCache.invalidate(); // master data may have changed elsewhere since the last view
  main.replaceChildren(h('p.muted', {}, t('common.loading')));
  document.title = t('app.title', { page: route.label });
  try {
    const container = h('div', {});
    const result = await route.view.render(container, ctx, params);
    if (token !== renderCount) {
      // a newer navigation started while this view was loading
      if (typeof result === 'function') result();
      return;
    }
    main.replaceChildren(container);
    if (typeof result === 'function') cleanup = result;
  } catch (e) {
    if (token !== renderCount) return;
    main.replaceChildren(h('h1', {}, route.label), h('p.alert.alert-error', { role: 'alert' }, e.message || String(e)));
  }
}

/** Month choices: a year back and forward around the selected month. */
function renderMonthOptions(center) {
  const months = Array.from({ length: 25 }, (_, i) => addMonths(center, i - 12));
  monthInput.replaceChildren(...months.map((m) => h('option', { value: m }, monthLabel(m))));
}

function setMonth(month) {
  if (!MONTH_RE.test(month ?? '') || (ctx.month && month !== ctx.month && !mayLeave())) {
    monthInput.value = ctx.month ?? '';
    return;
  }
  ctx.month = month;
  renderMonthOptions(month);
  monthInput.value = month;
  sessionStorage.setItem('month', month);
  render();
}

async function start() {
  const me = await guarded(() => api.me());
  if (!me) return;
  ctx.me = me;
  ctx.canEdit = me.role === 'planner';
  document.getElementById('user-name').textContent = me.username;
  document.getElementById('user-role').textContent = ctx.canEdit ? t('app.role.planner') : t('app.role.readonly');
  desktop = me.desktop === true;
  if (desktop) {
    frontEnd = await frontEndMode();
    ROUTES.push(SETTINGS_ROUTE);
    const quitButton = document.getElementById('quit');
    quitButton.hidden = false;
    quitButton.addEventListener('click', quit);
    updateBanner(true);
  } else {
    const logout = document.getElementById('logout');
    logout.hidden = false;
    logout.addEventListener('click', async () => {
      await guarded(() => api.logout());
      location.href = 'login.html';
    });
  }
  document.getElementById('nav').replaceChildren(...ROUTES.map((r) =>
    h('li', {}, h('a', { href: `#/${r.path}`, 'data-path': r.path }, r.label))));

  const months = (await guarded(() => api.months())) ?? [];
  monthInput.addEventListener('change', () => setMonth(monthInput.value));
  document.getElementById('month-prev').addEventListener('click', () => setMonth(addMonths(ctx.month, -1)));
  document.getElementById('month-next').addEventListener('click', () => setMonth(addMonths(ctx.month, 1)));
  window.addEventListener('hashchange', () => {
    if (mayLeave()) render();
    else history.replaceState(null, '', currentHash || '#/');
  });
  window.addEventListener('beforeunload', (e) => { if (ctx.isDirty?.()) e.preventDefault(); });
  document.querySelector('.skip-link').addEventListener('click', (e) => {
    e.preventDefault();
    main.focus();
  });
  setMonth(defaultMonth(months));
}

/** Desktop: ends the program after a confirmation and shows the end page in place. */
async function quit() {
  if (!mayLeave() || !confirm(t('desktop.quit.confirm'))) return;
  try {
    await api.desktopQuit();
  } catch (e) {
    // no answer is expected when the program is already gone
    if (e.status !== 0) {
      toast(e.message, true);
      return;
    }
  }
  stopView();
  // in the program window the shell closes the window; a browser tab stays open and is closed by the user
  if (frontEnd === 'browser') showPage(t('desktop.quit.ended'), t('desktop.quit.closeTab'));
  else showPage(t('desktop.quit.title'), null);
}

/** Desktop: banner when the start check found an update; the check may still be running right after the start. */
async function updateBanner(retry) {
  const info = await api.desktopUpdate(false).catch(() => null);
  const banner = document.getElementById('banner');
  if (!banner) return;
  if (info?.available) {
    const install = h('button.btn.btn-primary.btn-small', { type: 'button', onclick: async () => {
      if (!mayLeave()) return;
      install.disabled = true;
      const ok = await guarded(() => api.desktopInstall());
      install.disabled = false;
      if (ok === undefined) return;
      stopView();
      restartAndReload();
    } }, t('settings.install'));
    banner.replaceChildren(h('span', {}, t('desktop.banner', { version: info.available.version })), install,
      h('a', { href: '#/einstellungen' }, t('desktop.banner.details')));
    banner.hidden = false;
  } else if (retry && info?.checkOnStart && !info.error && !info.errorCode) {
    bannerTimer = setTimeout(() => updateBanner(false), 30000);
  }
}

applyTexts();
initThemeToggle(document.querySelector('.theme-toggle'));
start();
