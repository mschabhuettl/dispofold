// Printing the plan views: the header line of a printout. Pure, no DOM.
import { t } from './texts.js';

/** "Entwurf" for every plan that is not closed, "abgeschlossen" for a closed month. */
export function printStatus(planStatus) {
  return planStatus === 'CLOSED' ? t('print.status.closed') : t('print.status.draft');
}

/** Header line of a printout: title (with the month), plan status and the print date (dd.mm.yyyy). */
export function printHeader(title, planStatus, now = new Date()) {
  const date = `${String(now.getDate()).padStart(2, '0')}.${String(now.getMonth() + 1).padStart(2, '0')}.${now.getFullYear()}`;
  return t('print.header', { title, status: printStatus(planStatus), date });
}
