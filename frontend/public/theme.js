// Resolves the colour theme before the page paints: a stored choice ('light' or 'dark') wins, otherwise the
// system preference. Sets data-theme on <html>, switches the Bryntum theme file and fires 'themechange'.
// Plain script (no module) so it runs synchronously in <head>, after the Bryntum stylesheet link.
(function () {
  var KEY = 'dispofold-theme';
  var media = window.matchMedia('(prefers-color-scheme: dark)');
  var choice = null;
  try { choice = localStorage.getItem(KEY); } catch (e) { /* storage blocked: follow the system */ }

  function apply() {
    var theme = choice === 'dark' || choice === 'light' ? choice : (media.matches ? 'dark' : 'light');
    var root = document.documentElement;
    if (root.getAttribute('data-theme') === theme) return;
    root.setAttribute('data-theme', theme);
    var link = document.getElementById('bryntum-theme');
    if (link) link.href = 'vendor/bryntum/svalbard-' + theme + '.css';
    document.dispatchEvent(new CustomEvent('themechange', { detail: theme }));
  }

  window.dispofoldTheme = {
    get: function () { return document.documentElement.getAttribute('data-theme'); },
    toggle: function () {
      choice = this.get() === 'dark' ? 'light' : 'dark';
      try { localStorage.setItem(KEY, choice); } catch (e) { /* the choice then lasts for this page only */ }
      apply();
    },
  };

  if (media.addEventListener) media.addEventListener('change', apply);
  apply();
})();
