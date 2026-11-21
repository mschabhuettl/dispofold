// The example plugin's routes for the dev server (frontend/scripts/dev-server.mjs with MOCK_TEST_PLUGINS=1).
export function mock() {
  let gruss = 'Hallo';
  return (method, path, body, role) => {
    if (path === 'gruss' && method === 'GET') return { status: 200, body: { gruss } };
    if (path !== 'einstellungen') return null;
    if (role !== 'planner') return { status: 403, body: { code: 'auth.forbidden', params: {}, error: 'Nur für Planung' } };
    if (method === 'GET') return { status: 200, body: { gruss } };
    const next = String(body?.gruss ?? '').trim();
    if (!next || next.length > 40) {
      return { status: 400, body: { code: 'plugin.beispiel.greeting', params: { max: 40 }, error: 'Der Gruß braucht 1 bis 40 Zeichen' } };
    }
    gruss = next;
    return { status: 200, body: { gruss } };
  };
}
