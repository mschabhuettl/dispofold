// Login page: fills the texts and shows the error message after a failed login (/login.html?error=1).
import { applyTexts } from './lib/texts.js';
import { initThemeToggle } from './lib/theme.js';

applyTexts();
initThemeToggle(document.querySelector('.theme-toggle'));
if (new URLSearchParams(location.search).has('error')) document.getElementById('login-error').hidden = false;
