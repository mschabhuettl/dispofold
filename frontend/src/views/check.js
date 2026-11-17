// Prüfen: what is wrong with the plan of the month and with the master data.
import { monthLabel } from '../lib/dates.js';
import { h } from '../lib/dom.js';
import { t } from '../lib/texts.js';

export async function render(root, ctx) {
  root.append(h('h1', {}, t('check.title', { month: monthLabel(ctx.month) })));
}
