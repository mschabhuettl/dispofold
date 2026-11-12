// Dev server without dependencies: serves dist/ and answers /api/* from an in-memory mock (dev/mock-api.mjs).
// Login: any user name, "viewer" or "ansicht" gets the read-only role. Usage: node scripts/dev-server.mjs [port]
// MOCK_DESKTOP=1 imitates the desktop program: no login, /api/me says desktop, the /api/desktop/* endpoints and
// /q/health/ready answer (503 for a few seconds after an update "restarts" the program; API requests then get 503
// desktop.restarting as from the desktop shell, also after POST /api/dev/restart?ms=4000). No update source by default;
// MOCK_UPDATE_SOURCE=https://updates.example.invalid/x offers version 1.3.0 (a source starting with http://offline fails).
// MOCK_FRONTEND=window|browser answers GET /__shell/mode like the desktop shell (unset: 404 like an older shell).
// Uses tests/fixtures/synthetic/*dataset*.synthetic.json when present, otherwise a generated mini dataset.
import http from 'node:http';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { extname, join, normalize, dirname, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createMockApi } from '../dev/mock-api.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(root, 'dist');
const fixtures = join(root, '..', 'tests', 'fixtures', 'synthetic');
const port = Number(process.argv[2] ?? process.env.PORT ?? 8080);
const TYPES = { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png',
  '.jpg': 'image/jpeg', '.woff2': 'font/woff2', '.ttf': 'font/ttf', '.txt': 'text/plain; charset=utf-8', '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon' };

function fixtureDataset() {
  if (!existsSync(fixtures)) return null;
  for (const f of readdirSync(fixtures).filter((n) => /dataset.*synthetic.*\.json$/i.test(n)).sort()) {
    const data = JSON.parse(readFileSync(join(fixtures, f), 'utf8'));
    if (data.format === 'dispofold/dataset') {
      console.log('using fixture', f);
      return data;
    }
  }
  return null;
}

const fixture = fixtureDataset();
const desktop = process.env.MOCK_DESKTOP === '1';
const handle = createMockApi({ dataset: fixture, month: process.env.MOCK_MONTH ?? fixture?.periods?.[0]?.month ?? '2027-01', desktop,
  updateSource: process.env.MOCK_UPDATE_SOURCE ?? '' });

function role(req) {
  if (desktop) return 'planner';
  const m = /(?:^|;\s*)mock-role=(planner|viewer)/.exec(req.headers.cookie ?? '');
  return m ? m[1] : null;
}

async function readBody(req) {
  const chunks = [];
  for await (const c of req) chunks.push(c);
  return Buffer.concat(chunks);
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, 'http://localhost');
  const raw = await readBody(req);
  if (url.pathname === '/q/health/ready') {
    const down = handle.restarting();
    res.writeHead(down ? 503 : 200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ status: down ? 'DOWN' : 'UP' }));
  }
  if (desktop && url.pathname === '/__shell/mode') {
    // MOCK_FRONTEND=window|browser answers like the shell; unset: 404 like an older shell (the page then assumes window)
    if (!process.env.MOCK_FRONTEND) { res.writeHead(404); return res.end('not found'); }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ frontEnd: process.env.MOCK_FRONTEND }));
  }
  if (desktop && url.pathname === '/api/dev/restart' && req.method === 'POST') {
    // dev only: imitate a backend restart in the desktop window for some seconds
    handle.restartFor(Number(url.searchParams.get('ms') ?? 4000));
    res.writeHead(204);
    return res.end();
  }
  if (handle.restarting() && url.pathname.startsWith('/api/')) {
    // what the desktop shell answers while the backend restarts
    res.writeHead(503, { 'Content-Type': 'application/json' });
    return res.end(JSON.stringify({ code: 'desktop.restarting', params: {}, error: 'Dispofold startet neu.' }));
  }
  if (url.pathname === '/j_security_check' && req.method === 'POST') {
    const user = new URLSearchParams(raw.toString('utf8')).get('j_username') ?? '';
    const r = /^(viewer|ansicht)$/i.test(user) ? 'viewer' : 'planner';
    res.writeHead(302, { 'Set-Cookie': `mock-role=${r}; Path=/; HttpOnly; SameSite=Strict`, Location: '/' });
    return res.end();
  }
  if (url.pathname.startsWith('/api/')) {
    const r = role(req);
    if (url.pathname === '/api/logout') {
      res.writeHead(204, { 'Set-Cookie': 'mock-role=; Path=/; Max-Age=0' });
      return res.end();
    }
    if (!r) { res.writeHead(401); return res.end(); }
    let body = null;
    const octets = (req.headers['content-type'] ?? '').startsWith('application/octet-stream');
    try { body = octets ? raw : raw.length ? JSON.parse(raw.toString('utf8')) : null; } catch { res.writeHead(400); return res.end('{"error":"Ungültiges JSON"}'); }
    const out = handle(req.method, url.pathname, url.searchParams, body, r);
    const headers = {};
    if (out.download) headers['Content-Disposition'] = `attachment; filename="${out.download}"`;
    if (out.raw != null) {
      res.writeHead(out.status, { ...headers, 'Content-Type': out.type });
      return res.end(out.raw);
    }
    if (out.body == null) { res.writeHead(out.status, headers); return res.end(); }
    res.writeHead(out.status, { ...headers, 'Content-Type': 'application/json' });
    return res.end(JSON.stringify(out.body));
  }
  let file;
  try { file = normalize(join(dist, url.pathname === '/' ? 'index.html' : decodeURIComponent(url.pathname))); } catch { file = null; }
  if (!file || !file.startsWith(dist + sep) || !existsSync(file)) { res.writeHead(404); return res.end('not found'); }
  if (file.endsWith('index.html') && !role(req)) { res.writeHead(302, { Location: '/login.html' }); return res.end(); }
  res.writeHead(200, { 'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream' });
  res.end(readFileSync(file));
}).listen(port, () => console.log(`dev server on http://localhost:${port}`));
