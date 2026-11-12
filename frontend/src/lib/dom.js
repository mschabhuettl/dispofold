// Tiny DOM helpers. h('tag.class', {attrs, onclick}, ...children); children may be strings, nodes, arrays or null.
import { t } from './texts.js';

export function h(tag, attrs, ...children) {
  const [name, ...classes] = tag.split('.');
  const el = document.createElement(name);
  if (classes.length) el.className = classes.join(' ');
  for (const [k, v] of Object.entries(attrs ?? {})) {
    if (v == null || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = [el.className, v].filter(Boolean).join(' ');
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v);
    else if (k in el && k !== 'list' && typeof v !== 'string') el[k] = v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  append(el, children);
  return el;
}

export function append(el, children) {
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false) continue;
    el.append(c instanceof Node ? c : String(c));
  }
  return el;
}

const SVG_NS = 'http://www.w3.org/2000/svg';
export function svg(tag, attrs, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs ?? {})) if (v != null) el.setAttribute(k, v);
  return append(el, children);
}

let toastTimer;
/** How long a toast stays: success messages fade after 4 s, errors stay until the user closes them. */
export const toastDuration = (error) => (error ? null : 4000);

export function toast(message, error = false) {
  const el = document.getElementById('toast');
  if (!el) return; // the page was replaced (desktop end or restart page)
  const close = () => { el.hidden = true; };
  el.replaceChildren();
  append(el, [h('span', {}, message),
    error && h('button.toast-close', { type: 'button', 'aria-label': t('common.closeMessage'), title: t('common.closeMessage'), onclick: close }, '×')]);
  el.className = error ? 'toast error' : 'toast';
  el.hidden = false;
  // announce through a live region that is always present
  const live = document.getElementById('live');
  if (live) live.textContent = message;
  clearTimeout(toastTimer);
  const ms = toastDuration(error);
  if (ms) toastTimer = setTimeout(close, ms);
}

export const fmt = {
  num: (v, digits = 0) => (v == null ? '–' : Number(v).toLocaleString('de-AT', { minimumFractionDigits: digits, maximumFractionDigits: digits })),
  pct: (v) => (v == null ? '–' : `${Math.round(v * 100)} %`),
};

/** Runs an async action, shows errors as toast. Returns the result or undefined on error. */
export async function guarded(action, successMessage) {
  try {
    const r = await action();
    if (successMessage) toast(successMessage);
    return r;
  } catch (e) {
    toast(e.message || String(e), true);
    return undefined;
  }
}

/**
 * Offers a downloaded Blob as a file: object URL, temporary <a download> click, then revoke.
 * Works in browsers and in the desktop window, where links to export URLs on the custom scheme fail.
 */
export function saveBlob(blob, filename, { doc = globalThis.document, urls = globalThis.URL, later = (f) => setTimeout(f, 1000) } = {}) {
  const url = urls.createObjectURL(blob);
  const a = doc.createElement('a');
  a.href = url;
  a.download = filename;
  a.style.display = 'none';
  doc.body.append(a);
  a.click();
  a.remove();
  later(() => urls.revokeObjectURL(url));
}
