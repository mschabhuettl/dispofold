// Self-test page driver (only injected with --selftest). Runs in the app page of the own origin, talks to the
// backend through the same scheme as the interface and reports each step to the window process.
(() => {
  if (window.top !== window || location.pathname.startsWith('/__shell/')) return;
  const MONTH = '2027-01';
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  async function report(name, ok, ms, detail, plan) {
    await fetch('/__shell/selftest/report', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, ok, ms: Math.round(ms), detail: String(detail ?? ''), plan }),
    });
  }

  async function step(name, fn) {
    const start = performance.now();
    try {
      const r = (await fn()) || {};
      await report(name, true, performance.now() - start, r.detail, r.plan);
      return true;
    } catch (e) {
      await report(name, false, performance.now() - start, (e && e.message) || e);
      return false;
    }
  }

  async function api(method, path, body) {
    const init = { method, headers: {} };
    if (body !== undefined) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    const r = await fetch(path, init);
    if (!r.ok) throw new Error(`${method} ${path}: ${r.status}`);
    const text = await r.text();
    return text ? JSON.parse(text) : null;
  }

  function score(s) {
    const m = /(-?\d+)hard\/(-?\d+)medium\/(-?\d+)soft/.exec(s || '');
    if (!m) throw new Error(`no score: ${s}`);
    return { hard: Number(m[1]), medium: Number(m[2]), soft: Number(m[3]) };
  }

  async function run() {
    // the wait page is served under app paths while the backend starts; it reloads into the app later
    if (document.querySelector('script[src^="/__shell/"]')) return;
    const begin = await (await fetch('/__shell/selftest/begin')).json();
    if (!begin.run) return;
    const ok =
      (await step('open app', async () => {
        for (let i = 0; i < 240 && !document.getElementById('user-name')?.textContent; i++) await sleep(250);
        if (!document.getElementById('user-name')?.textContent) throw new Error('interface did not load');
        const me = await api('GET', '/api/me');
        if (!me.desktop) throw new Error('not the desktop mode');
        return { detail: `role ${me.role}` };
      })) &&
      (await step('demo data', async () => {
        const s = await api('POST', '/api/import/demo');
        return { detail: JSON.stringify(s).slice(0, 200) };
      })) &&
      (await step('solve', async () => {
        await api('POST', `/api/plans/${MONTH}/solve`, { moveLimit: 20000 });
        let st = null;
        for (let i = 0; i < 1200; i++) {
          await sleep(1000);
          st = await api('GET', `/api/plans/${MONTH}`);
          if (!st.solving && st.plan) break;
        }
        if (!st || st.solving || !st.plan) throw new Error('solve did not finish');
        const plan = score(st.plan.score);
        return { detail: st.plan.score, plan };
      })) &&
      (await step('export', async () => {
        const r = await fetch(`/api/plans/${MONTH}/export?format=json`);
        if (!r.ok) throw new Error(`export: ${r.status}`);
        const blob = await r.blob();
        const saved = new Promise((resolve, reject) => {
          const t = setTimeout(() => reject(new Error('no dispofold-download event')), 60000);
          window.addEventListener('dispofold-download', (e) => { clearTimeout(t); resolve(e.detail); }, { once: true });
        });
        // same path as the interface: blob URL and a temporary <a download>
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `dienstplan-${MONTH}.json`;
        document.body.append(a);
        a.click();
        a.remove();
        const d = await saved;
        URL.revokeObjectURL(url);
        return { detail: `${d.name} in ${d.folder}` };
      })) &&
      (await step('quit', async () => {
        // reported before the request: the program may be gone before the answer arrives; the window process
        // checks the outcome itself (launcher exit 0)
        fetch('/api/desktop/quit', { method: 'POST' }).catch(() => {});
        return { detail: 'requested through the interface API' };
      }));
    if (!ok) await fetch('/__shell/selftest/abort', { method: 'POST' });
  }

  if (document.readyState === 'complete') run();
  else window.addEventListener('load', run, { once: true });
})();
