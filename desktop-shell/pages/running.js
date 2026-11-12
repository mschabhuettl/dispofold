// Small window of the browser front end: opens the browser again or ends Dispofold.
document.getElementById('open').addEventListener('click', () => fetch('/__shell/open', { method: 'POST' }));
document.getElementById('quit').addEventListener('click', (e) => {
  e.target.disabled = true;
  fetch('/__shell/quit', { method: 'POST' });
});
