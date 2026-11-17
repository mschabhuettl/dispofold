// Output of a plan: the "Ausgabe" menu of the plan views (print, export) and closing the month with a checklist.
import { monthLabel } from '../lib/dates.js';
import { guarded, h, saveBlob, toast } from '../lib/dom.js';
import { closeChecklist } from '../lib/plan-status.js';
import { t } from '../lib/texts.js';

export const EXPORT_FORMATS = ['xlsx', 'csv', 'json'];

/** Downloads the plan of the month in a format (xlsx, csv, json) and saves the file. */
export async function exportPlanFile(api, month, format) {
  const file = await guarded(() => api.exportPlan(month, format));
  if (file) saveBlob(file.blob, file.filename);
}

/**
 * The "Ausgabe" menu: a button that opens a list of actions. items() returns the item buttons and runs at every
 * opening, so the items follow the plan state. Arrow keys move between the items; Escape, Tab or a click outside
 * close it.
 */
export function outputMenu(items) {
  const list = h('ul.menu-list', { id: 'output-menu', hidden: true });
  const toggle = h('button.btn.btn-secondary', { type: 'button', 'aria-expanded': 'false', 'aria-controls': 'output-menu',
    onclick: () => (list.hidden ? open() : close()) }, t('output.menu'), h('span', { 'aria-hidden': 'true' }, ' ▾'));
  const box = h('div.menu', {}, toggle, list);
  const onDocClick = (e) => { if (!box.contains(e.target)) close(); };

  function open() {
    list.replaceChildren(...items().flat().filter(Boolean).map((b) => h('li', {}, b)));
    list.hidden = false;
    toggle.setAttribute('aria-expanded', 'true');
    list.querySelector('button:not(:disabled)')?.focus();
    document.addEventListener('click', onDocClick, true);
  }

  function close(focusToggle = false) {
    list.hidden = true;
    toggle.setAttribute('aria-expanded', 'false');
    document.removeEventListener('click', onDocClick, true);
    if (focusToggle) toggle.focus();
  }

  // an item closes the list before its action runs (print dialog, close dialog)
  list.addEventListener('click', (e) => { if (e.target.closest('button')) close(true); }, true);
  box.addEventListener('keydown', (e) => {
    if (list.hidden) return;
    if (e.key === 'Escape') {
      e.preventDefault();
      close(true);
    } else if (e.key === 'Tab') {
      close(); // focus moves on as usual
    } else if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      const buttons = [...list.querySelectorAll('button:not(:disabled)')];
      const i = buttons.indexOf(document.activeElement);
      const next = e.key === 'ArrowDown' ? (i + 1) % buttons.length : (i <= 0 ? buttons.length : i) - 1;
      buttons[next]?.focus();
    }
  });
  return box;
}

/** Menu item "Drucken": the browser's print dialog with the print layout of the view. */
export function printItem() {
  return h('button', { type: 'button', title: t('print.buttonHelp'), 'data-print': '', onclick: () => window.print() }, t('print.button'));
}

/** Menu items of the plan export (planners only; the server refuses it while a solve runs). */
export function exportItems(api, month, solving) {
  return EXPORT_FORMATS.map((f) => h('button', { type: 'button', disabled: solving, onclick: () => exportPlanFile(api, month, f) },
    t('output.export', { format: f.toUpperCase() })));
}

/**
 * "Monat abschließen": a dialog with the checklist (rule violations, open gaps, plan out of date) from the current
 * plan state and analysis. With open items, closing needs the extra tick "Trotzdem abschließen". After closing,
 * the view is drawn again.
 */
export async function closeMonth(ctx) {
  const { api, month } = ctx;
  const state = await guarded(() => api.planState(month));
  if (!state) return;
  const analysis = state.plan.assignments.length ? await api.analysis(month).catch(() => null) : null;
  if (!(await confirmClose(month, closeChecklist(state, analysis)))) return;
  const ok = await guarded(() => api.finalize(month));
  if (ok === undefined) return;
  toast(t('import.finalize.done', { month: monthLabel(month) }));
  ctx.setMonth(month); // draw again with the new status
}

/** The modal dialog; resolves true when the month is to be closed. */
function confirmClose(month, items) {
  const pending = items.some((i) => !i.ok);
  const confirmButton = h('button.btn.btn-primary', { type: 'button', disabled: pending }, t('import.finalize'));
  const dialog = h('dialog.dialog', { 'aria-labelledby': 'close-month-title' },
    h('h2', { id: 'close-month-title' }, t('import.finalize.confirm', { month: monthLabel(month) })),
    h('ul.checklist', {}, items.map((i) => h('li', { class: i.ok ? 'ok' : 'open' },
      h('span.mark', { 'aria-hidden': 'true' }, i.ok ? '✓' : '!'),
      h('span.visually-hidden', {}, i.ok ? t('closeMonth.ok') : t('closeMonth.open')), ' ', i.text))),
    pending && h('p', {}, t('closeMonth.openItems')),
    pending && h('label.check-inline', {},
      h('input', { type: 'checkbox', id: 'close-anyway', onchange: (e) => { confirmButton.disabled = !e.target.checked; } }),
      ' ', t('closeMonth.anyway')),
    h('div.toolbar', {}, confirmButton,
      h('button.btn.btn-secondary', { type: 'button', autofocus: true, onclick: () => dialog.close() }, t('common.cancel'))));
  return new Promise((resolve) => {
    confirmButton.addEventListener('click', () => dialog.close('yes'));
    dialog.addEventListener('close', () => {
      dialog.remove();
      resolve(dialog.returnValue === 'yes');
    });
    document.body.append(dialog);
    dialog.showModal();
  });
}
