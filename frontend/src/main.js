// Application shell: user, month picker, navigation and hash routing.
import { createApi } from './lib/api.js';
import { datasetCache as createDatasetCache } from './lib/dataset-cache.js';
import { withEffectiveRestrictions } from './lib/area-states.js';
import { addMonths, monthLabel } from './lib/dates.js';
import { restartAndReload, frontEndMode, showPage } from './lib/desktop.js';
import { guarded, h, toast } from './lib/dom.js';
import { EXTRA_PAGES, guideHref, SECTIONS } from './lib/guide.js';
import { helpPages, pagePath, register, routes as pluginRoutes } from './lib/plugins.js';
import { askForChecks, showReleaseNotice } from './lib/release.js';
import { applyTexts, t } from './lib/texts.js';
import { initThemeToggle } from './lib/theme.js';
import * as roster from './views/roster.js';
import * as timeline from './views/timeline.js';
import * as functions from './views/functions.js';
import * as person from './views/person.js';
import * as areaMatrix from './views/area-matrix.js';
import * as fairness from './views/fairness.js';
import * as check from './views/check.js';
import * as rules from './views/rules.js';
import * as inputs from './views/inputs.js';
import * as masterdata from './views/masterdata.js';
import * as backup from './views/backup.js';
import * as months from './views/months.js';
import * as takeOver from './views/take-over.js';
import * as settings from './views/settings.js';
import * as guide from './views/guide.js';
import { view as extensionsView } from './views/extensions.js';
import BUILT_IN from 'dispofold:plugins';

/** The plugins built into this program (scripts/build.mjs), with their texts added; see lib/plugins.js. */
const PLUGINS = BUILT_IN.map((p) => register(p));

// Pages in menu groups: the menu shows one entry per group, the pages of a group are tabs above the page. month: the
// page belongs to the selected month (the month picker shows only there). viewer: the page is offered to viewers too,
// as a string the group it has for them.
const ROUTES = [
  { path: 'plan', group: 'plan', label: t('nav.plan'), tab: t('nav.tab.people'), view: roster, month: true, viewer: 'plan' },
  { path: 'funktionen', group: 'plan', label: t('nav.functions'), tab: t('nav.tab.functions'), view: functions, month: true, viewer: 'plan' },
  { path: 'zeitachse', group: 'plan', label: t('nav.timeline'), tab: t('nav.tab.timeline'), view: timeline, month: true, viewer: 'plan' },
  { path: 'eingaben', group: 'inputs', label: t('nav.inputs'), view: inputs, month: true },
  { path: 'pruefen', group: 'check', label: t('nav.check'), tab: t('nav.tab.problems'), view: check, month: true },
  { path: 'fairness', group: 'check', label: t('nav.fairness'), tab: t('nav.tab.fairness'), view: fairness, month: true, viewer: 'fairness' },
  { path: 'person', group: 'people', label: t('nav.person'), tab: t('nav.tab.peopleList'), view: person, month: true, viewer: 'people' },
  { path: 'bereiche', group: 'people', label: t('nav.areaMatrix'), tab: t('nav.tab.areaMatrix'), view: areaMatrix, viewer: 'people' },
  // the monthly work with data: take data over, the months, backups (plugins may add pages, e.g. a mapping)
  { path: 'uebernehmen', group: 'data', label: t('nav.takeOver'), tab: t('nav.tab.takeOver'), view: takeOver },
  { path: 'monate', group: 'data', label: t('nav.months'), tab: t('nav.tab.months'), view: months },
  { path: 'sichern', group: 'data', label: t('nav.backup'), tab: t('nav.tab.backup'), view: backup, month: true },
  { path: 'regeln', group: 'settings', label: t('nav.rules'), tab: t('nav.tab.rules'), view: rules },
  { path: 'stammdaten', group: 'settings', label: t('nav.masterdata'), tab: t('nav.tab.masterdata'), view: masterdata },
];
/** Old addresses (bookmarks, links of older versions) and where they lead now; plugins add their own (moved). */
const MOVED = { import: 'uebernehmen' };
/** The settings tab used last: the menu entry Einstellungen opens it again. */
const SETTINGS_TAB = 'settingsTab';
const SETTINGS_ROUTE = { path: 'einstellungen', group: 'settings', label: t('nav.settings'), tab: t('nav.tab.updates'), view: settings };
// planners: the plugins, switched on and off; a change shows the program anew with the plugin's pages or without
const EXTENSIONS_ROUTE = { path: 'erweiterungen', group: 'settings', label: t('nav.extensions'), tab: t('nav.tab.extensions'),
  view: extensionsView(PLUGINS, () => location.reload()) };
// the guide built into the program (every form, also for viewers); the documentation site is only a fallback
const GUIDE_ROUTE = { path: 'anleitung', group: 'guide', label: t('nav.guide'), view: guide };
/** The intro under the title of each page, by path. */
const INTROS = { plan: t('help.page.plan'), funktionen: t('help.page.funktionen'), zeitachse: t('help.page.zeitachse'),
  pruefen: t('help.page.pruefen'), fairness: t('help.page.fairness'), eingaben: t('help.page.eingaben'), person: t('help.page.person'), bereiche: t('help.page.bereiche'),
  regeln: t('help.page.regeln'), stammdaten: t('help.page.stammdaten'), uebernehmen: t('help.page.uebernehmen'), monate: t('help.page.monate'),
  sichern: t('help.page.sichern'), einstellungen: t('help.page.einstellungen'),
  erweiterungen: t('help.page.erweiterungen'), anleitung: t('help.page.anleitung') };
const GROUPS = { plan: t('nav.group.plan'), check: t('nav.group.check'), inputs: t('nav.group.inputs'), people: t('nav.group.people'),
  fairness: t('nav.group.fairness'), data: t('nav.group.data'), settings: t('nav.group.settings'), guide: t('nav.guide') };

let desktop = false;
let frontEnd = 'window'; // desktop only: 'window' or 'browser' (GET /__shell/mode)
/** Link to the guide or one of its sections: its copy built into the program, readable without internet. */
const guideLink = (anchor = '') => ({ href: guideHref(true, anchor) });
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
// the pages see the area states as read and `restrictions` with them as rows, as the server plans (lib/area-states.js)
// (planners only: viewers get no restrictions and see the states only on Personen → Bereiche)
const datasetCache = createDatasetCache(() => api.dataset().then((d) => (ctx.canEdit ? withEffectiveRestrictions(d) : { ...d, exceptions: [] })));
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
  /**
   * Switches the planning month, e.g. to the month of freshly imported data. quiet: on a page that does not belong to
   * a month, only the month is chosen and the page stays as it is (with what it shows, e.g. the result of an import).
   */
  setMonth: (month, { quiet = false } = {}) => setMonth(month, quiet),
  /** Set by a view with unsaved changes; asked before leaving the view or changing the month. */
  isDirty: null,
  mayLeave: () => mayLeave(),
  /** Ends the current view (timers, listeners) before the page is replaced, e.g. for a restart. */
  stop: () => stopView(),
  /** Desktop only: 'window' or 'browser', see frontEnd. */
  frontEnd: 'window',
  /** The front ends of the switched on plugins (lib/plugins.js). */
  plugins: [],
};

function stopView() {
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
  const parts = location.hash.replace(/^#\/?/, '').split('/');
  // a plugin page: #/plugin/<id>/<page>/<params>
  let [path, ...rest] = parts[0] === 'plugin' ? [parts.slice(0, 3).join('/'), ...parts.slice(3)] : parts;
  // an old address: the page it moved to, under that page's address
  const moved = Object.hasOwn(MOVED, path) ? MOVED[path].split('/') : null;
  if (moved && ROUTES.some((r) => r.path === moved[0])) {
    [path, ...rest] = moved;
    history.replaceState(null, '', `#/${moved.join('/')}`);
  }
  const route = ROUTES.find((r) => r.path === path) ?? ROUTES[0];
  // an unknown or hidden page (e.g. Import/Export for viewers) shows the first page under its own address
  if (route.path !== path && path) history.replaceState(null, '', `#/${route.path}`);
  const decode = (x) => { try { return decodeURIComponent(x); } catch { return x; } };
  return { route, params: rest.map(decode) };
}

let releaseAsked = 0;
/**
 * Server build, planners only (viewers are not told): what the server's own check found. Asked again on a page change
 * at most every 10 minutes, so an open session also learns of a release the server finds later; never on a timer, so
 * an idle page is not sent to the login page.
 */
function serverRelease() {
  if (desktop || !ctx.canEdit || Date.now() - releaseAsked < 600000) return;
  releaseAsked = Date.now();
  api.release().then((r) => showReleaseNotice(document.getElementById('banner'), r?.available, { current: r?.current }))
    .catch(() => {});
}

async function render() {
  serverRelease();
  const token = ++renderCount;
  const { route, params } = parseHash();
  for (const a of document.querySelectorAll('#nav a')) {
    if (a.dataset.group === route.group) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  }
  document.querySelector('.month-picker').hidden = !route.month;
  if (route.group === 'settings') {
    try { localStorage.setItem(SETTINGS_TAB, route.path); } catch { /* the default tab then */ }
    const link = document.querySelector('#nav a[data-group="settings"]');
    if (link) link.setAttribute('href', `#/${route.path}`);
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
    // the short intro of the page under its title, again after a view draws itself anew
    const section = route.section ?? SECTIONS[route.path];
    // one person's page has its own intro, the list the general one
    const text = route.intro ?? (route.path === 'person' && params[0] && params[0] !== 'neu' ? t('help.page.personOne') : INTROS[route.path]);
    const intro = h('p.page-intro.muted', {}, text, section && [' ', h('a', guideLink(section), t('help.guideLink'))]);
    const placeIntro = () => {
      const title = container.querySelector('h1');
      if (title && title.nextElementSibling !== intro) title.after(intro);
    };
    placeIntro();
    new MutationObserver(placeIntro).observe(container, { childList: true });
    main.replaceChildren(...tabs(route), container);
    if (typeof result === 'function') cleanup = result;
  } catch (e) {
    if (token !== renderCount) return;
    main.replaceChildren(...tabs(route), h('h1', {}, route.label), h('p.alert.alert-error', { role: 'alert' }, e.message || String(e)));
  }
}

/** Tabs of the pages in the group of the route, none for a group of one page. */
function tabs(route) {
  const pages = ROUTES.filter((r) => r.group === route.group);
  if (pages.length < 2) return [];
  return [h('nav.page-tabs', { 'aria-label': GROUPS[route.group] }, pages.map((r) =>
    h('a', { href: `#/${r.path}`, 'aria-current': r === route ? 'page' : null }, r.tab ?? r.label)))];
}

/** Month choices: a year back and forward around the selected month. */
function renderMonthOptions(center) {
  const months = Array.from({ length: 25 }, (_, i) => addMonths(center, i - 12));
  monthInput.replaceChildren(...months.map((m) => h('option', { value: m }, monthLabel(m))));
}

function setMonth(month, quiet = false) {
  if (!MONTH_RE.test(month ?? '') || (ctx.month && month !== ctx.month && !quiet && !mayLeave())) {
    monthInput.value = ctx.month ?? '';
    return;
  }
  ctx.month = month;
  renderMonthOptions(month);
  monthInput.value = month;
  sessionStorage.setItem('month', month);
  if (!quiet || parseHash().route.month) render();
}

async function start() {
  const me = await guarded(() => api.me());
  if (!me) return;
  ctx.me = me;
  ctx.canEdit = me.role === 'planner';
  // viewers get the plan, the people and the fairness overview only
  // the pages of the switched on plugins, each after the pages of its menu group (own groups at the end)
  const listed = (await api.extensions().catch(() => null)) ?? [];
  const enabled = new Set(listed.filter((x) => x.enabled).map((x) => x.id));
  if (ctx.canEdit) ROUTES.push(EXTENSIONS_ROUTE);
  for (const r of pluginRoutes(PLUGINS, enabled, GROUPS, t)) {
    // after the page it names, else after the last page of its group
    const after = r.after ? ROUTES.findIndex((x) => x.path === r.after && x.group === r.group) : -1;
    const last = after >= 0 ? after : ROUTES.findLastIndex((x) => x.group === r.group);
    if (last < 0) ROUTES.push(r);
    else ROUTES.splice(last + 1, 0, r);
  }
  for (const p of PLUGINS.filter((x) => enabled.has(x.id))) {
    for (const [from, to] of Object.entries(p.moved ?? {})) MOVED[pagePath(p.id, from)] = to;
  }
  EXTRA_PAGES.push(...helpPages(PLUGINS, enabled));
  ctx.plugins = PLUGINS.filter((p) => enabled.has(p.id));
  if (!ctx.canEdit) {
    const order = ['plan', 'people', 'fairness'];
    const rank = (g) => (order.includes(g) ? order.indexOf(g) : order.length);
    ROUTES.splice(0, ROUTES.length, ...ROUTES.filter((r) => r.viewer).map((r) => ({ ...r, group: r.viewer }))
      .sort((a, b) => rank(a.group) - rank(b.group)));
  }
  document.getElementById('user-name').textContent = me.username;
  document.getElementById('user-role').textContent = ctx.canEdit ? t('app.role.planner') : t('app.role.readonly');
  desktop = me.desktop === true;
  if (desktop) {
    frontEnd = await frontEndMode();
    ctx.frontEnd = frontEnd;
    // Updates with the other settings, before Erweiterungen
    const settingsEnd = ROUTES.findLastIndex((r) => r.group === 'settings' && r !== EXTENSIONS_ROUTE);
    ROUTES.splice(settingsEnd + 1, 0, SETTINGS_ROUTE);
    const quitButton = document.getElementById('quit');
    quitButton.hidden = false;
    quitButton.addEventListener('click', quit);
    desktopUpdates();
  } else {
    const logout = document.getElementById('logout');
    logout.hidden = false;
    logout.addEventListener('click', async () => {
      await guarded(() => api.logout());
      location.href = 'login.html';
    });
  }
  ROUTES.push(GUIDE_ROUTE);
  const first = new Map();
  for (const r of ROUTES) if (!first.has(r.group)) first.set(r.group, r);
  // Einstellungen: the tab used last
  let settingsTab = null;
  try { settingsTab = localStorage.getItem(SETTINGS_TAB); } catch { /* none */ }
  const remembered = ROUTES.find((r) => r.group === 'settings' && r.path === settingsTab);
  if (remembered) first.set('settings', remembered);
  const items = [...first.values()].map((r) => h('li', {}, h('a', { href: `#/${r.path}`, 'data-group': r.group }, GROUPS[r.group])));
  document.getElementById('nav').replaceChildren(...items);

  const months = (await guarded(() => api.months())) ?? [];
  monthInput.addEventListener('change', () => setMonth(monthInput.value));
  document.getElementById('month-prev').addEventListener('click', () => setMonth(addMonths(ctx.month, -1)));
  document.getElementById('month-next').addEventListener('click', () => setMonth(addMonths(ctx.month, 1)));
  window.addEventListener('hashchange', () => {
    if (mayLeave()) render();
    else history.replaceState(null, '', currentHash || '#/');
  });
  window.addEventListener('beforeunload', (e) => { if (ctx.isDirty?.()) e.preventDefault(); });
  // narrow windows: the menu is folded away behind a button and folds again after a choice
  const navToggle = document.getElementById('nav-toggle');
  const sidebar = document.querySelector('.sidebar');
  const fold = (open) => {
    sidebar.classList.toggle('open', open);
    navToggle.setAttribute('aria-expanded', String(open));
  };
  navToggle.addEventListener('click', () => fold(!sidebar.classList.contains('open')));
  document.getElementById('nav').addEventListener('click', (e) => { if (e.target.closest('a')) fold(false); });
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

/**
 * Desktop: without saved update settings asks once whether to check for new versions ("Ja" sets the project's
 * releases API, "Nein" no source); then, with a source and the check on start switched on, asks the source once.
 * Nothing goes to the network before that answer.
 */
async function desktopUpdates() {
  let info = await api.desktopUpdate(false).catch(() => null);
  if (!info) return;
  if (info.ask) {
    const answer = await askForChecks();
    if (answer === null) return; // no answer: nothing saved, nothing checked
    const settings = { source: answer ? info.releases : '', checkOnStart: true };
    if (await guarded(() => api.desktopSettings(settings)) === undefined) return;
    info = { ...info, ...settings };
    // the Updates page may have loaded behind the question: show the saved settings there (the modal question kept
    // anyone from editing the form meanwhile)
    if (parseHash().route === SETTINGS_ROUTE) render();
  }
  if (!info.source || !info.checkOnStart) return;
  const found = await api.desktopUpdate(true).catch(() => null);
  showReleaseNotice(document.getElementById('banner'), found?.available, { current: found?.current,
    window: frontEnd !== 'browser', install: installUpdate });
}

/**
 * Desktop: "Jetzt installieren" in the notice; the same flow as in Einstellungen > Updates. A failure is thrown to
 * the release dialog, which shows it (a toast would sit behind the modal dialog).
 */
async function installUpdate() {
  if (!mayLeave()) return;
  await api.desktopInstall();
  stopView();
  restartAndReload();
}

applyTexts();
initThemeToggle(document.querySelector('.theme-toggle'));
start();
