(() => {
  'use strict';
  const base = new URL('./', document.baseURI);
  const api = path => new URL(path, base).toString();
  let session;
  const ready = fetch(api('api/session'), { credentials: 'same-origin' }).then(async response => {
    if (response.status === 401) { location.href = api('login'); throw new Error('请登录后继续'); }
    if (!response.ok) throw new Error('登录状态读取失败');
    session = await response.json(); return session;
  });
  // Handled here too, so an expired session never causes an unhandled bootstrap rejection.
  ready.catch(() => {});
  async function request(path, options = {}) {
    await ready;
    const headers = new Headers(options.headers || {});
    if (options.method && options.method !== 'GET') headers.set(session.csrfHeader, session.csrfToken);
    const response = await fetch(api(path), { ...options, headers, credentials: 'same-origin' });
    if (response.status === 401) { location.href = api('login'); throw new Error('登录已过期'); }
    if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message || `请求失败 (${response.status})`); }
    const text = await response.text(); return text ? JSON.parse(text) : null;
  }
  const handlers = new Map(); let events;
  async function listen(name, callback) {
    await ready;
    if (!events) {
      events = new EventSource(api('api/events'));
      events.onerror = () => { const label = document.getElementById('connection-label'); if (label) label.textContent = '推送连接重连中'; };
      window.addEventListener('beforeunload', () => events.close());
    }
    if (!handlers.has(name)) {
      handlers.set(name, new Set());
      events.addEventListener(name, event => { let payload; try { payload = JSON.parse(event.data); } catch (_) { return; } handlers.get(name).forEach(fn => { try { fn({ payload }); } catch (e) { console.error(e); } }); });
    }
    handlers.get(name).add(callback); return () => handlers.get(name)?.delete(callback);
  }
  window.StockPlatform = {
    core: { invoke: (name, args = {}) => request(`api/commands/${encodeURIComponent(name)}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(args) }) },
    event: { listen }, request, url: api,
    download(path) { const a = document.createElement('a'); a.href = api(path); a.download = ''; a.click(); },
    async logout() { await ready; const form = document.createElement('form'); form.method = 'POST'; form.action = api('logout'); const input = document.createElement('input'); input.type = 'hidden'; input.name = '_csrf'; input.value = session.csrfToken; form.append(input); document.body.append(form); form.submit(); }
  };
})();
