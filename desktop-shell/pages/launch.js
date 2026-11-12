// Launch page of the browser front end: exchanges the one-time code from the fragment for the session cookie.
// The fragment never reaches a server log; it is removed from the address bar right away.
const code = location.hash.slice(1);
history.replaceState(null, '', '/__shell/launch');
fetch('/__shell/launch', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ code }),
}).then((r) => {
  if (r.ok) { location.replace('/'); return; }
  document.getElementById('busy').hidden = true;
  document.getElementById('expired').hidden = false;
});
