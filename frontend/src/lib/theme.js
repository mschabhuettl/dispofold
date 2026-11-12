// Light/dark switch. The theme itself is resolved by public/theme.js (loaded in <head>), which fires 'themechange'.
import { svg } from './dom.js';
import { t } from './texts.js';

const MOON = 'M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z';
const SUN = 'M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zM12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4';

export const isDark = () => document.documentElement.dataset.theme === 'dark';

/** Turns a button into the theme switch: a toggle "Dunkles Design" (aria-pressed), showing the icon of the other theme. */
export function initThemeToggle(button) {
  if (!button || !globalThis.dispofoldTheme) return;
  button.setAttribute('aria-label', t('app.theme.dark'));
  button.title = t('app.theme.dark');
  const draw = () => {
    const dark = isDark();
    button.setAttribute('aria-pressed', String(dark));
    button.replaceChildren(svg('svg', { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', 'stroke-width': 2,
      'stroke-linecap': 'round', 'stroke-linejoin': 'round', 'aria-hidden': 'true' }, svg('path', { d: dark ? SUN : MOON })));
  };
  button.addEventListener('click', () => globalThis.dispofoldTheme.toggle());
  document.addEventListener('themechange', draw);
  draw();
}
