// Unlock page: sends the password once to the window process, which forwards it to the launcher. The password
// is kept nowhere in the page; the fields are cleared after every attempt.
const MIN_LENGTH = 12;
const TEXTS = {
  'desktop.password.wrong': 'Das Passwort ist falsch.',
  'desktop.password.short': `Das Passwort muss mindestens ${MIN_LENGTH} Zeichen lang sein.`,
  'desktop.password.mismatch': 'Die beiden Passwörter stimmen nicht überein.',
};
const $ = (id) => document.getElementById(id);
let firstStart = false;

function showError(code) {
  $('error').textContent = code ? (TEXTS[code] || `Entsperren nicht möglich (Fehlercode ${code}).`) : '';
  $('error').hidden = !code;
}

function apply(state) {
  if (state.phase === 'ready') { location.replace('/'); return; }
  if (state.phase !== 'needPassword') { location.replace('/__shell/wait'); return; }
  firstStart = state.firstStart;
  $('title').textContent = firstStart ? 'Passwort festlegen' : 'Dispofold entsperren';
  $('intro').textContent = firstStart
    ? `Beim ersten Start wird ein Passwort für die Daten festgelegt (mindestens ${MIN_LENGTH} Zeichen).`
    : 'Bitte das Passwort für die Daten eingeben.';
  $('first').hidden = !firstStart;
  $('repeat-row').hidden = !firstStart;
  $('submit').textContent = firstStart ? 'Passwort festlegen' : 'Entsperren';
  showError(state.error);
}

async function submit(e) {
  e.preventDefault();
  const password = $('password').value;
  if (firstStart && password.length < MIN_LENGTH) { showError('desktop.password.short'); return; }
  if (firstStart && password !== $('repeat').value) { showError('desktop.password.mismatch'); return; }
  $('password').value = '';
  $('repeat').value = '';
  $('submit').disabled = true;
  showError(null);
  try {
    const r = await fetch('/__shell/unlock', {
      method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password }),
    });
    apply(await r.json());
  } finally {
    $('submit').disabled = false;
    $('password').focus();
  }
}

$('form').addEventListener('submit', submit);
fetch('/__shell/state').then((r) => r.json()).then(apply);
