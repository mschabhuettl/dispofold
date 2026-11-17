// The one help element: a small "i" button next to a label or number that opens a short explanation below it.
// Mouse and touch: a click or tap opens and closes it, a click or tap elsewhere closes it. Keyboard: Enter or Space on
// the button opens and closes it, Escape closes it and leaves the focus on the button. The text is in the page only
// while it is open, laid over the page (scrolling tables do not cut it off). One help is open at a time.
import { h } from './dom.js';
import { t } from './texts.js';

let counter = 0;
let current = null; // close function of the open help

/**
 * Help button for a text. label names what it explains (the button is called "Hilfe: <label>"); id sets the id of the
 * text box (default info-<n>).
 */
export function info(text, label = '', id = `info-${++counter}`) {
  const box = h('div.info-text', { id, role: 'status' });
  const button = h('button.info-btn', { type: 'button', 'aria-expanded': 'false', 'aria-controls': id,
    'aria-label': label ? t('help.button.label', { label }) : t('help.button') });

  const onDocClick = (e) => { if (!button.contains(e.target) && !box.contains(e.target)) close(); };
  const onKey = (e) => {
    if (e.key !== 'Escape') return;
    // the help closes first; a dialog around it stays open
    e.stopPropagation();
    close();
    if (button.isConnected) button.focus();
  };

  function open() {
    current?.();
    // in the page first, then the text: screen readers announce the change of the status box
    document.body.append(box);
    box.textContent = text;
    const r = button.getBoundingClientRect();
    const room = document.documentElement.clientWidth;
    box.style.left = `${Math.max(8, Math.min(r.left, room - box.offsetWidth - 8)) + window.scrollX}px`;
    // below the button, above it when the window has no room below
    const below = r.bottom + 4 + box.offsetHeight <= document.documentElement.clientHeight || r.top < box.offsetHeight + 4;
    box.style.top = `${(below ? r.bottom + 4 : r.top - 4 - box.offsetHeight) + window.scrollY}px`;
    button.setAttribute('aria-expanded', 'true');
    document.addEventListener('click', onDocClick, true);
    document.addEventListener('keydown', onKey, true);
    window.addEventListener('hashchange', close);
    current = close;
  }

  function close() {
    box.remove();
    button.setAttribute('aria-expanded', 'false');
    document.removeEventListener('click', onDocClick, true);
    document.removeEventListener('keydown', onKey, true);
    window.removeEventListener('hashchange', close);
    if (current === close) current = null;
  }

  button.addEventListener('click', () => (box.isConnected ? close() : open()));
  return button;
}
