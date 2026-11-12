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
  async function send(method, path, body, accept) {
    const init = { method, headers: { Accept: accept }, credentials: 'same-origin' };
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

  async function request(method, path, body) {
    const res = await send(method, path, body, 'application/json');
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
  return {
    request,
    me: () => request('GET', '/api/me'),
    logout: () => request('POST', '/api/logout'),
    dataset: () => request('GET', '/api/dataset'),
    savePerson: (p) => request('PUT', `/api/dataset/people/${encodeURIComponent(p.id)}`, p),
    importDemo: () => request('POST', '/api/import/demo'),
    importDataset: (dataset) => request('POST', '/api/import/dataset', dataset),
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
    unpin: (month) => request('POST', `/api/plans/${m(month)}/unpin`),
    analysis: (month) => request('GET', `/api/plans/${m(month)}/analysis`),
    person: (month, id) => request('GET', `/api/plans/${m(month)}/people/${encodeURIComponent(id)}`),
    coverage: (month, areaId) => request('GET', `/api/plans/${m(month)}/coverage/${encodeURIComponent(areaId)}`),
    finalize: (month) => request('POST', `/api/plans/${m(month)}/finalize`),
    reopen: (month) => request('POST', `/api/plans/${m(month)}/reopen`),
    importPlan: (month, plan) => request('POST', `/api/plans/${m(month)}/import`, plan),
    // desktop program only
    desktopQuit: () => request('POST', '/api/desktop/quit'),
    desktopUpdate: (check) => request('GET', `/api/desktop/update?check=${check ? 'true' : 'false'}`),
    desktopSettings: (settings) => request('PUT', '/api/desktop/update/settings', settings),
    desktopInstall: () => request('POST', '/api/desktop/update/install'),
    desktopUploadUpdate: async (file) => request('POST', '/api/desktop/update/file', new Uint8Array(await file.arrayBuffer())),
    /** Export of the month as { blob, filename }; saved by the caller (no navigation to the export URL). */
    exportPlan: async (month, format) => {
      const res = await send('GET', `/api/plans/${m(month)}/export?format=${encodeURIComponent(format)}`, undefined, '*/*');
      if (!res.ok) throw await failure(res);
      const disposition = res.headers?.get?.('Content-Disposition') ?? '';
      const named = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);
      const filename = named ? decodeURIComponent(named[1]) : `dienstplan-${month}.${format}`;
      return { blob: await res.blob(), filename };
    },
  };
}

/** Parses a Timefold score like '0hard/-120medium/-53210soft' (also with 'init' prefix). */
export function parseScore(score) {
  const out = { hard: null, medium: null, soft: null };
  for (const m of String(score ?? '').matchAll(/(-?\d+)(hard|medium|soft)/g)) out[m[2]] = Number(m[1]);
  return out;
}
