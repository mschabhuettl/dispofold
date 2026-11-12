// Einstellungen of the desktop program: update source, check on start, check now, install, update from a file.
import { restartAndReload } from '../lib/desktop.js';
import { append, guarded, h } from '../lib/dom.js';
import { updateProblem } from '../lib/codes.js';
import { t } from '../lib/texts.js';

export async function render(root, ctx) {
  const { api } = ctx;
  let info = await api.desktopUpdate(false);

  const source = h('input', { id: 'update-source', type: 'text', value: info.source ?? '', spellcheck: 'false',
    placeholder: t('settings.source.placeholder') });
  const checkOnStart = h('input', { id: 'update-on-start', type: 'checkbox', checked: Boolean(info.checkOnStart) });
  const status = h('div', { 'aria-live': 'polite' });
  const file = h('input', { id: 'update-file', type: 'file', accept: '.zip,application/zip' });

  const button = (label, fn, cls = 'btn-secondary') => {
    const b = h(`button.btn.${cls}`, { type: 'button' }, label);
    b.addEventListener('click', async () => {
      b.disabled = true;
      try { await fn(); } finally { b.disabled = false; }
    });
    return b;
  };

  async function install(action) {
    if (!ctx.mayLeave()) return;
    const ok = await guarded(action);
    if (ok === undefined) return;
    ctx.stop();
    restartAndReload();
  }

  function drawStatus() {
    const a = info.available;
    const problem = updateProblem(info);
    status.replaceChildren();
    append(status, [
      h('p', {}, t('settings.current', { version: info.current ?? '?' })),
      problem && (problem.fault ? h('p.alert.alert-error', { role: 'alert' }, t('settings.error', { error: problem.text }))
        : h('p.muted', {}, problem.text)),
      a ? h('div.alert', {},
        h('strong', {}, t('settings.available', { version: a.version })),
        a.notes && h('pre', {}, a.notes),
        h('div.row', { style: { marginTop: '.6rem', marginBottom: 0 } },
          button(t('settings.install'), () => install(() => api.desktopInstall()), 'btn-primary')))
        : !problem && h('p.muted', {}, t('settings.none')),
    ]);
  }

  root.append(h('h1', {}, t('settings.title')),
    h('div.settings', {},
      h('section.section', { 'aria-labelledby': 'h-update' }, h('h2', { id: 'h-update' }, t('settings.updates')),
        status,
        h('div.field', {}, h('label', { for: 'update-source' }, t('settings.source')), source,
          h('span.muted', {}, t('settings.source.help'))),
        h('label.check', {}, checkOnStart, t('settings.checkOnStart')),
        h('div.row', {},
          button(t('common.save'), async () => {
            const body = { source: source.value.trim(), checkOnStart: checkOnStart.checked };
            const res = await guarded(() => api.desktopSettings(body), t('settings.saved'));
            if (res !== undefined) Object.assign(info, body);
          }, 'btn-primary'),
          button(t('settings.checkNow'), async () => {
            const res = await guarded(() => api.desktopUpdate(true));
            if (res) {
              info = res;
              drawStatus();
            }
          }))),
      h('section.section', { 'aria-labelledby': 'h-file' }, h('h2', { id: 'h-file' }, t('settings.file')),
        h('p.muted', {}, t('settings.file.help')),
        h('div.field', {}, h('label', { for: 'update-file' }, t('settings.file.label')), file),
        button(t('settings.file.install'), async () => {
          const f = file.files[0];
          if (!f) return guarded(() => Promise.reject(new Error(t('settings.file.missing'))));
          if (!confirm(t('settings.file.confirm', { name: f.name }))) return undefined;
          return install(() => api.desktopUploadUpdate(f));
        }))));
  drawStatus();
}
