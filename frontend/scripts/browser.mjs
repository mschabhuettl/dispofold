// Shared Playwright launch for the check scripts and screenshots.mjs: the full Chromium (channel 'chromium', not the
// headless shell, which has only English UI strings) with --lang and LANG/LANGUAGE renders native date fields and
// file pickers in German. `npx playwright install chromium` provides both builds.
import { chromium } from 'playwright';

export const launchBrowser = () =>
  chromium.launch({ channel: 'chromium', args: ['--lang=de-DE'], env: { ...process.env, LANG: 'de_DE.UTF-8', LANGUAGE: 'de' } });
