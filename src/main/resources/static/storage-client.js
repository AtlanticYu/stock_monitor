(() => {
  'use strict';
  const base = new URL('./', document.baseURI);
  window.StockStorageReady = fetch(new URL('api/storage', base), { credentials: 'same-origin' })
    .then(async response => {
      if (!response.ok) throw new Error('无法读取存储配置');
      const config = await response.json();
      if (config.mode !== 'supabase') return { mode: 'mysql' };
      if (!window.StockSupabaseSDK) throw new Error('Supabase 客户端未加载');
      const db = window.StockSupabaseSDK.createClient(config.url, config.anonKey, {
        db: { schema: config.schema },
        auth: { storageKey: `stocks-monitor:${config.schema}`, persistSession: true, autoRefreshToken: true, detectSessionInUrl: false }
      });
      return { ...config, db };
    });
  window.StockStorageReady.catch(() => {});
})();
