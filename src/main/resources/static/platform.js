(() => {
  'use strict';
  const base = new URL('./', document.baseURI);
  const api = path => new URL(path, base).toString();
  let session, storage;
  async function headersForRequest(options = {}) {
    const headers = new Headers(options.headers || {});
    if (storage?.mode === 'supabase') {
      const { data, error } = await storage.db.auth.getSession();
      if (error || !data.session) { location.href = api('login'); throw new Error('请登录数据空间'); }
      headers.set('Authorization', `Bearer ${data.session.access_token}`);
    } else if (options.method && options.method !== 'GET' && session?.csrfHeader) {
      headers.set(session.csrfHeader, session.csrfToken);
    }
    return headers;
  }
  const ready = (window.StockStorageReady || Promise.resolve({ mode: 'mysql' }))
    .then(async config => {
      storage = config;
      const response = await fetch(api('api/session'), { credentials: 'same-origin', headers: await headersForRequest() });
      if (response.status === 401) { location.href = api('login'); throw new Error('请登录后继续'); }
      if (!response.ok) throw new Error('登录状态读取失败');
      session = await response.json(); return session;
    });
  ready.catch(() => {});
  async function request(path, options = {}) {
    await ready;
    const response = await fetch(api(path), { ...options, headers: await headersForRequest(options), credentials: 'same-origin' });
    if (response.status === 401) { location.href = api('login'); throw new Error('登录已过期'); }
    if (!response.ok) { const error = await response.json().catch(() => ({})); throw new Error(error.message || `请求失败 (${response.status})`); }
    const text = await response.text(); return text ? JSON.parse(text) : null;
  }
  const handlers = new Map(); let events, streamStarted = false, streamStopped = false, streamController;
  function dispatch(name, data) {
    let payload; try { payload = JSON.parse(data); } catch (_) { return; }
    handlers.get(name)?.forEach(fn => { try { fn({ payload }); } catch (error) { console.error(error); } });
  }
  async function cloudEvents() {
    while (!streamStopped) {
      try {
        streamController = new AbortController();
        const response = await fetch(api('api/events'), { headers: await headersForRequest(), signal: streamController.signal, cache: 'no-store' });
        if (response.status === 401) { location.href = api('login'); return; }
        if (!response.ok || !response.body) throw new Error('推送连接失败');
        const reader = response.body.getReader(), decoder = new TextDecoder();
        let pending = '';
        while (!streamStopped) {
          const { value, done } = await reader.read(); if (done) break;
          pending += decoder.decode(value, { stream: true }); pending = pending.replace(/\r\n/g, '\n');
          let boundary;
          while ((boundary = pending.indexOf('\n\n')) >= 0) {
            const packet = pending.slice(0, boundary); pending = pending.slice(boundary + 2);
            let name = 'message'; const values = [];
            for (const line of packet.split('\n')) {
              if (line.startsWith('event:')) name = line.slice(6).trim();
              if (line.startsWith('data:')) values.push(line.slice(5).replace(/^ /, ''));
            }
            if (values.length) dispatch(name, values.join('\n'));
          }
        }
      } catch (error) {
        if (streamStopped) break;
        const label = document.getElementById('connection-label'); if (label) label.textContent = '推送连接重连中';
      }
      if (!streamStopped) await new Promise(resolve => setTimeout(resolve, 3000));
    }
  }
  async function listen(name, callback) {
    await ready;
    if (!handlers.has(name)) {
      handlers.set(name, new Set());
      if (events) events.addEventListener(name, event => dispatch(name, event.data));
    }
    handlers.get(name).add(callback);
    if (!streamStarted) {
      streamStarted = true;
      if (storage.mode === 'supabase') cloudEvents();
      else {
        events = new EventSource(api('api/events'));
        for (const eventName of handlers.keys()) events.addEventListener(eventName, event => dispatch(eventName, event.data));
        events.onerror = () => { const label = document.getElementById('connection-label'); if (label) label.textContent = '推送连接重连中'; };
      }
      window.addEventListener('beforeunload', () => { streamStopped = true; streamController?.abort(); events?.close(); });
    }
    return () => handlers.get(name)?.delete(callback);
  }
  async function download(path) {
    if (storage?.mode !== 'supabase') { const a = document.createElement('a'); a.href = api(path); a.download = ''; a.click(); return; }
    await ready;
    const response = await fetch(api(path), { headers: await headersForRequest() });
    if (!response.ok) throw new Error(`下载失败 (${response.status})`);
    const url = URL.createObjectURL(await response.blob()), a = document.createElement('a');
    a.href = url; a.download = 'stocks-export.json'; a.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  window.StockPlatform = {
    core: { invoke: (name, args = {}) => request(`api/commands/${encodeURIComponent(name)}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(args) }) },
    event: { listen }, request, url: api, download,
    async logout() {
      await ready;
      if (storage.mode === 'supabase') { streamStopped = true; streamController?.abort(); await storage.db.auth.signOut(); location.href = api('login'); return; }
      const form = document.createElement('form'); form.method = 'POST'; form.action = api('logout');
      const input = document.createElement('input'); input.type = 'hidden'; input.name = '_csrf'; input.value = session.csrfToken;
      form.append(input); document.body.append(form); form.submit();
    }
  };
})();
