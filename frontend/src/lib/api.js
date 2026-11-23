// REST client for /api (see docs/api.md). fetch and the 401 handler are injectable for tests.
import { messageText } from './codes.js';
import { t } from './texts.js';

export class ApiError extends Error {
  /** code and params as sent by the server (docs/api.md, "Fehlercodes"), null for plain HTTP errors. */
  constructor(status, message, code = null, params = null, body = null) {
    super(message);
    this.status = status;
    this.code = code;
    this.params = params;
    /** the parsed error body, e.g. with the list of import errors */
    this.body = body;
  }
}

const STATUS_TEXT = {
  400: t('api.invalidInput'),
  403: t('api.forbidden'),
  404: t('api.notFound'),
  409: t('api.conflict'),
  411: t('api.lengthRequired'),
  413: t('api.tooLarge'),
  502: t('api.unreachable'),
};

/**
 * Localized message for an error response: the text for the server's { code, params } from texts.js, else its
 * { error } fallback text (or a short plain text), else a text for the HTTP status.
 */
export function errorMessage(status, body) {
  const plain = typeof body === 'string' && body.trim() && body.length <= 300 && !body.trimStart().startsWith('<') ? body.trim() : null;
  const object = body && typeof body === 'object';
  const server = object && typeof body.code === 'string' ? messageText(body)
    : object && typeof body.error === 'string' ? body.error : plain;
  return server ?? STATUS_TEXT[status] ?? t('api.serverError', { status });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Waiting times while the desktop backend restarts: 0.5 s doubling up to 5 s. */
export const restartDelay = (attempt) => Math.min(5000, 500 * 2 ** attempt);

/**
 * onRestarting(true|false) is called when the desktop backend starts and ends a restart (503 desktop.restarting);
 * meanwhile requests are repeated, so callers never see that error.
 */
export function createApi({ fetchImpl = (...a) => globalThis.fetch(...a), onUnauthorized = () => {
  globalThis.location.href = 'login.html';
}, onRestarting = () => {}, wait = sleep } = {}) {
  let restarting = false;
  // Only relative URLs, JSON strings or plain bytes as bodies: the desktop window (custom scheme in WebKitGTK)
  // crashes on Blob, File or FormData bodies and does not report redirects reliably.
  async function send(method, path, body, accept, revision) {
    const init = { method, headers: { Accept: accept }, credentials: 'same-origin' };
    // master data changes carry the revision they were made on (0 for an empty dataset)
    if (revision != null) init.headers['If-Match'] = String(revision);
    if (body instanceof Uint8Array) {
      // file upload: the bytes as they are
      init.headers['Content-Type'] = 'application/octet-stream';
      init.body = body;
    } else if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    let res;
    for (let attempt = 0; ; attempt++) {
      try {
        res = await fetchImpl(path, init);
      } catch {
        throw new ApiError(0, t('api.unreachable'));
      }
      if (res.status !== 503) break;
      // the desktop window answers 503 desktop.restarting while the backend restarts: wait and repeat
      const text = await res.text();
      let data = null;
      try { data = JSON.parse(text); } catch { /* not the restart answer */ }
      if (data?.code !== 'desktop.restarting') {
        res = { status: 503, ok: false, headers: res.headers, text: async () => text };
        break;
      }
      if (!restarting) {
        restarting = true;
        onRestarting(true);
      }
      await wait(restartDelay(attempt));
    }
    if (restarting) {
      restarting = false;
      onRestarting(false);
    }
    // Form authentication may answer with the login page (after a followed redirect) instead of 401;
    // the API itself never answers with HTML.
    const type = res.headers?.get?.('Content-Type') ?? '';
    if (res.status === 401 || (res.ok && type.startsWith('text/html'))) {
      onUnauthorized();
      throw new ApiError(401, t('api.unauthenticated'));
    }
    return res;
  }

  async function failure(res) {
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    const coded = data && typeof data === 'object' && typeof data.code === 'string';
    return new ApiError(res.status, errorMessage(res.status, data), coded ? data.code : null, coded ? (data.params ?? {}) : null,
      data && typeof data === 'object' ? data : null);
  }

  async function request(method, path, body, revision) {
    const res = await send(method, path, body, 'application/json', revision);
    if (!res.ok) throw await failure(res);
    const text = await res.text();
    let data = null;
    if (text) {
      try {
        data = JSON.parse(text);
      } catch {
        data = text;
      }
    }
    return data;
  }

  const m = (month) => encodeURIComponent(month);
  const k = (key) => encodeURIComponent(key);
  const replace = (yes) => (yes ? '?replace=true' : '');
  /** A file download as { blob, filename }; saved by the caller (no navigation to the export URL). */
  async function download(path, fallback, method = 'GET', body = undefined) {
    const res = await send(method, path, body, '*/*');
    if (!res.ok) throw await failure(res);
    const disposition = res.headers?.get?.('Content-Disposition') ?? '';
    const named = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
    return { blob: await res.blob(), filename: named ? decodeURIComponent(named[1]) : fallback };
  }
  return {
    request,
    me: () => request('GET', '/api/me'),
    logout: () => request('POST', '/api/logout'),
    dataset: () => request('GET', '/api/dataset'),
    createMaster: (kind, item, revision) => request('POST', `/api/dataset/${k(kind)}`, item, revision),
    updateMaster: (kind, key, item, revision) => request('PUT', `/api/dataset/${k(kind)}/${k(key)}`, item, revision),
    deleteMaster: (kind, key, revision, confirmed) => request('DELETE', `/api/dataset/${k(kind)}/${k(key)}${confirmed ? '?confirm=true' : ''}`, undefined, revision),
    saveRestrictions: (personId, list, revision) => request('PUT', `/api/dataset/people/${k(personId)}/restrictions`, list, revision),
    /** No longer taken over from the source (true) or again (false); the server sets the day excludedFrom */
    setExcluded: (personId, excluded, revision) => request('PUT', `/api/dataset/people/${k(personId)}/excluded`, excluded, revision),
    exportDataset: () => download('/api/dataset/export', 'dispofold-datensatz.json'),
    importDemo: (replaceChanged) => request('POST', `/api/import/demo${replace(replaceChanged)}`),
    /** keep: carry the master data changed in Dispofold since the last import over to the new dataset */
    importDataset: (dataset, replaceChanged, keep = false) => request('POST', `/api/import/dataset${keep ? '?keep=true' : replace(replaceChanged)}`, dataset),
    /** Dry run of an import with keep: { changed, kept, conflicts: [{ personId, areaId, field }], notes } */
    importPreview: (dataset) => request('POST', '/api/import/preview', dataset),
    /** Corrections in effect: { file, notes, count, earlier, withdrawn, complete }; 409 corrections.noBaseline without a stored import */
    correctionsFile: () => request('GET', '/api/data/corrections'),
    /** The loaded corrections file waiting for an import: { loaded, summary, none } (none: no corrections at all) */
    correctionsState: () => request('GET', '/api/data/corrections/state'),
    /** What a corrections file (its bytes, sent as they are) would bring; 400 corrections.load.* when it cannot be used */
    checkCorrections: (bytes) => request('POST', '/api/data/corrections/check', bytes),
    /** Loads a corrections file (its bytes, sent as they are), replacing the one loaded before; its summary */
    loadCorrections: (bytes) => request('PUT', '/api/data/corrections/loaded', bytes),
    removeCorrections: () => request('DELETE', '/api/data/corrections/loaded'),
    /** Pseudonymised { dataset, plan, key, people }; 409 anonymize.leak when a name would remain */
    anonymized: (month) => request('GET', `/api/data/anonymized?month=${m(month)}`),
    rules: () => request('GET', '/api/rules'),
    saveRules: (rules) => request('PUT', '/api/rules', rules),
    constraints: () => request('GET', '/api/constraints'),
    months: () => request('GET', '/api/months'),
    period: (month) => request('GET', `/api/periods/${m(month)}`),
    savePeriod: (month, period) => request('PUT', `/api/periods/${m(month)}`, period),
    planState: (month) => request('GET', `/api/plans/${m(month)}`),
    solve: (month, options) => request('POST', `/api/plans/${m(month)}/solve`, options),
    stop: (month) => request('POST', `/api/plans/${m(month)}/stop`),
    setCell: (month, assignment) => request('PUT', `/api/plans/${m(month)}/cells`, assignment),
    pin: (month) => request('POST', `/api/plans/${m(month)}/pin`),
    unpin: (month) => request('POST', `/api/plans/${m(month)}/unpin`),
    analysis: (month) => request('GET', `/api/plans/${m(month)}/analysis`),
    person: (month, id) => request('GET', `/api/plans/${m(month)}/people/${encodeURIComponent(id)}`),
    /** The hits of every person for one rule: { [constraintId]: [{ personId, ...hit }] }. */
    hits: (month, constraintId) => request('GET', `/api/plans/${m(month)}/hits?constraint=${k(constraintId)}`),
    coverage: (month, areaId) => request('GET', `/api/plans/${m(month)}/coverage/${encodeURIComponent(areaId)}`),
    /** Planners only: who could be asked in a gap {areaId, from, to} (local date-times as in the analysis). */
    candidates: (month, gap) => request('GET', `/api/plans/${m(month)}/candidates?areaId=${k(gap.areaId)}&from=${k(gap.from)}&to=${k(gap.to)}`),
    /** Planners only: every penalty of the plan [{ personId (null: no person), ...hit }] for the problem list. */
    problems: (month) => request('GET', `/api/plans/${m(month)}/problems`),
    finalize: (month) => request('POST', `/api/plans/${m(month)}/finalize`),
    reopen: (month) => request('POST', `/api/plans/${m(month)}/reopen`),
    importPlan: (month, plan) => request('POST', `/api/plans/${m(month)}/import`, plan),
    /** Server build, planners only: { current, available: { version, title, notes, url } | null }. */
    release: () => request('GET', '/api/release'),
    // desktop program only
    desktopQuit: () => request('POST', '/api/desktop/quit'),
    desktopUpdate: (check) => request('GET', `/api/desktop/update?check=${check ? 'true' : 'false'}`),
    desktopSettings: (settings) => request('PUT', '/api/desktop/update/settings', settings),
    desktopInstall: () => request('POST', '/api/desktop/update/install'),
    desktopUploadUpdate: async (file) => request('POST', '/api/desktop/update/file', new Uint8Array(await file.arrayBuffer())),
    // plugins (docs/plugins.md)
    /** Planners: every plugin [{ id, name, version, description, enabled, importSource }]; readers: the switched on ones. */
    extensions: () => request('GET', '/api/extensions'),
    setExtension: (id, enabled) => request('PUT', `/api/extensions/${k(id)}`, { enabled }),
    /** A plugin's own routes below /api/plugins/<id>/ (404 plugin.unknown while it is off). */
    plugin: (id) => ({
      get: (path) => request('GET', `/api/plugins/${k(id)}/${path}`),
      put: (path, body) => request('PUT', `/api/plugins/${k(id)}/${path}`, body),
      post: (path, body) => request('POST', `/api/plugins/${k(id)}/${path}`, body),
      /** A file the route answers to a POST of body, as { blob, filename }. */
      download: (path, body, fallback) => download(`/api/plugins/${k(id)}/${path}`, fallback, 'POST', body),
      /** A file the route answers to a GET, as { blob, filename }. */
      file: (path, fallback) => download(`/api/plugins/${k(id)}/${path}`, fallback),
    }),
    /** Import from a plugin's import source with its own request; as importDataset and importPreview. */
    pluginImport: (id, req, replaceChanged, keep = false) => request('POST', `/api/import/plugin/${k(id)}${keep ? '?keep=true' : replace(replaceChanged)}`, req),
    pluginImportPreview: (id, req) => request('POST', `/api/import/plugin/${k(id)}/preview`, req),
    /** Folders plugins may read [{ id }] and the entries of one [{ name, dir }]. */
    fileRoots: () => request('GET', '/api/files/roots'),
    fileList: (root, path = '') => request('GET', `/api/files/list?root=${k(root)}&path=${k(path)}`),
    /** Desktop program: the window's folder dialog; the window allows the folder and answers { id }, or { id: null }. */
    pickFolder: () => request('POST', '/__shell/pick-folder'),
    /** Export of the month as { blob, filename }. */
    exportPlan: (month, format) => download(`/api/plans/${m(month)}/export?format=${encodeURIComponent(format)}`, `dienstplan-${month}.${format}`),
  };
}

/** Person hours of uncovered and of overstaffed demand from a plan analysis; null while there is none. */
export function demandFigures(analysis) {
  if (analysis?.uncoveredHours == null || analysis?.overstaffedHours == null) return null;
  return { uncovered: analysis.uncoveredHours, overstaffed: analysis.overstaffedHours };
}

/** Weight of an uncovered person minute in the medium score; overstaffed minutes (weight 1) always stay below it. */
const DEMAND_WEIGHT = 1000000000n;

/**
 * The same figures read from a score like '0hard/-2000000060medium/-5soft' (2 uncovered minutes, 60 overstaffed),
 * e.g. while solving, when there is no analysis yet; null without a medium part.
 */
export function scoreFigures(score) {
  const m = /(-?\d+)medium/.exec(String(score ?? ''));
  if (!m) return null;
  const medium = -BigInt(m[1]);
  return { uncovered: Number(medium / DEMAND_WEIGHT) / 60, overstaffed: Number(medium % DEMAND_WEIGHT) / 60 };
}
