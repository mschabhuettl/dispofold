// Wait page: shown for page loads while the backend (re)starts. Reloads the page once the backend is ready.
async function poll() {
  try {
    const state = await (await fetch('/__shell/state')).json();
    if (state.phase === 'ready') {
      if (location.pathname.startsWith('/__shell/')) location.replace('/');
      else location.reload();
      return;
    }
    if (state.phase === 'needPassword') { location.replace('/__shell/unlock'); return; }
  } catch {
    // the window process answers itself; try again
  }
  setTimeout(poll, 400);
}
poll();
