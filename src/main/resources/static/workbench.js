(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const invoke = (name, args) => window.StockPlatform.core.invoke(name, args);
  let instruments = [], active = null, filter = '', editingId = null;
  let activeGeneration = 0, realtimeRequestToken = 0, analysisRequestToken = 0;
  let stateRevision = 0, loadRequestToken = 0, selectionRequestToken = 0;
  let indicatorPanelCollapsed = false, chartPanelCollapsed = false;
  let backtestRequestToken = 0, backtestHistoryRequestToken = 0, batchBacktestRequestToken = 0, backtestHistory = [];
  const BACKTEST_CALCULATION_VERSION = 2;
  const quoteById = new Map();
  const groups = { hk: '港股', 'cn-ab': 'A 股', 'cn-sh': '沪市 A 股', 'cn-sz': '深市 A 股', us: '美股' };
  const esc = v => String(v ?? '').replace(/[&<>'"]/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', "'":'&#39;', '"':'&quot;' }[c]));
  const marketName = m => groups[m] || m;
  const semanticTone = value => {
    const text = String(value ?? '').trim();
    if (!text || text === '--' || text.includes('等待') || text.includes('样本不足')) return 'neutral';
    if (/(异常|风险|超买|超卖|背离|放大|过热|高位|偏热)/.test(text)) return 'warning';
    if (/(上涨|上升|增强|偏多|多头|流入|突破|改善|强势)/.test(text)) return 'positive';
    if (/(下跌|下降|减弱|偏空|空头|流出|跌破|转弱|弱势|缩小)/.test(text)) return 'negative';
    return 'neutral';
  };
  function applySemanticTone(node, value) {
    if (!node) return;
    node.classList.remove('semantic-positive', 'semantic-negative', 'semantic-warning', 'semantic-neutral', 'semantic-error');
    node.classList.add(`semantic-${semanticTone(value)}`);
  }
  function applyExplicitTone(node, tone) {
    if (!node) return;
    node.classList.remove('semantic-positive', 'semantic-negative', 'semantic-warning', 'semantic-neutral', 'semantic-error');
    const safeTone = ['positive', 'negative', 'warning', 'neutral', 'error'].includes(String(tone)) ? String(tone) : 'neutral';
    node.classList.add(`semantic-${safeTone}`);
  }
  const signalLabel = signal => signal === 'strong-bullish' ? '强势偏多' : signal === 'bullish' ? '偏多' : signal === 'bearish' ? '偏空' : signal === 'strong-bearish' ? '强势偏空' : signal === 'insufficient-data' ? '样本不足' : '中性观察';
  // Legacy neutral score color #c58a2d is intentionally replaced by a gray-blue neutral tone.
  function updateScoreRing(score, signal) {
    const ring = $('analysis-score-ring');
    if (!ring) return;
    const numericScore = score == null ? null : Number(score);
    const percent = Number.isFinite(numericScore) ? Math.max(0, Math.min(100, numericScore)) : 0;
    const tone = numericScore == null || signal === 'insufficient-data' ? 'neutral'
      : signal === 'strong-bullish' || signal === 'bullish' || numericScore >= 70
        ? 'positive'
        : signal === 'strong-bearish' || signal === 'bearish' || numericScore < 45
          ? 'negative'
          : 'neutral';
    ring.style.setProperty('--score-percent', `${percent}%`);
    ring.style.setProperty('--score-color', tone === 'positive' ? 'var(--market-up)' : tone === 'negative' ? 'var(--market-down)' : (numericScore == null ? '#cbd5db' : '#718895'));
    ring.dataset.level = signal || 'neutral';
    ring.dataset.scoreTone = tone;
  }

  function renderList() {
    const root = $('instrument-list'); if (!root) return;
    const rows = instruments.filter(i => !filter || `${i.name}${i.code}${i.market}`.toLowerCase().includes(filter.toLowerCase()));
    if (!rows.length) { root.innerHTML = '<div class="empty-state">暂无匹配股票</div>'; return; }
    root.innerHTML = ''; let last = '';
    rows.forEach(i => {
      if (last !== i.market) { last = i.market; const h = document.createElement('div'); h.className = 'market-group'; h.textContent = marketName(i.market); root.append(h); }
      const q = quoteById.get(i.id);
      const ratioValue = q?.ratio == null ? null : Number.parseFloat(String(q.ratio).replace('%', ''));
      const tone = Number.isFinite(ratioValue) ? (ratioValue > 0 ? 'positive' : ratioValue < 0 ? 'negative' : 'flat') : 'neutral';
      const status = q ? `${q.price ?? '--'} · ${q.ratio == null ? '--' : `${q.ratio}%`}` : (i.enabled ? '缓存' : '停用');
      const b = document.createElement('button');
      b.className = `instrument-row ${active && active.id === i.id ? 'selected' : ''} ${i.enabled ? '' : 'disabled'} row-${tone}`;
      b.dataset.id = i.id;
      b.title = '单击切换，双击编辑';
      b.innerHTML = `<span class="instrument-row-main"><strong>${esc(i.name)}</strong><small><code>${esc(i.code)}</code><i></i>${esc(i.currency)}${(i.nameSource || i.name_source) === 'custom' ? ' · 自定义' : ''}</small></span><span class="instrument-row-status"><b>${esc(q?.price ?? '--')}</b><small>${esc(q?.ratio == null ? (i.enabled ? '缓存' : '停用') : `${q.ratio}%`)}</small></span>`;
      b.onclick = () => select(i.id);
      b.ondblclick = () => openStockDialog(i);
      root.append(b);
    });
  }

  function applyIndicatorPanelState() {
    const panel = $('indicator-panel');
    const toggle = $('toggle-indicator-panel');
    if (panel) panel.hidden = indicatorPanelCollapsed;
    if (toggle) {
      const expanded = !indicatorPanelCollapsed;
      toggle.setAttribute('aria-expanded', String(expanded));
      toggle.dataset.state = expanded ? 'expanded' : 'collapsed';
      toggle.classList.toggle('is-expanded', expanded);
      toggle.classList.toggle('is-collapsed', !expanded);
      toggle.textContent = expanded ? '收起指标 ↑' : '展开指标 ↓';
      toggle.setAttribute('aria-label', expanded ? '收起指标分析' : '展开指标分析');
      toggle.title = expanded ? '收起指标分析' : '展开指标分析';
    }
    const root = $('analysis-panel');
    if (root) root.classList.toggle('is-collapsed', indicatorPanelCollapsed);
  }
  function applyChartPanelState(redraw = false) {
    const body = $('chart-panel-body');
    const toggle = $('toggle-chart-panel');
    if (body) body.hidden = chartPanelCollapsed;
    if (toggle) {
      const expanded = !chartPanelCollapsed;
      toggle.setAttribute('aria-expanded', String(expanded));
      toggle.dataset.state = expanded ? 'expanded' : 'collapsed';
      toggle.classList.toggle('is-expanded', expanded);
      toggle.classList.toggle('is-collapsed', !expanded);
      toggle.textContent = expanded ? '收起价格走势 ↑' : '展开价格走势 ↓';
      toggle.setAttribute('aria-label', expanded ? '收起价格走势' : '展开价格走势');
      toggle.title = expanded ? '收起价格走势' : '展开价格走势';
    }
    const root = document.querySelector('.chart-panel');
    if (root) root.classList.toggle('is-collapsed', chartPanelCollapsed);
    if (redraw && !chartPanelCollapsed) {
      requestAnimationFrame(() => { if (window.__stockChartResize) window.__stockChartResize(); else if (window.__stockChartReload) window.__stockChartReload(); });
    }
  }

  function setAnalysisStatus(message, state = 'idle') {
    const node = $('analysis-panel-status');
    if (node) { node.textContent = message; node.dataset.state = state; }
  }
  function resetAnalysisPanel() {
    analysisRequestToken++;
    if ($('indicator-list')) $('indicator-list').innerHTML = '<span class="muted-text">等待新股票数据…</span>';
    if ($('composite-indicator-list')) $('composite-indicator-list').innerHTML = '<span class="muted-text">等待综合分析…</span>';
    if ($('indicator-count')) $('indicator-count').textContent = '等待数据';
    if ($('composite-count')) $('composite-count').textContent = '等待数据';
    setAnalysisStatus('切换股票后正在重新读取指标…');
  }

  function resetBacktestPanel(message = '切换股票后正在读取历史回测…') {
    backtestRequestToken++;
    backtestHistoryRequestToken++;
    batchBacktestRequestToken++;
    backtestHistory = [];
    if ($('backtest-result')) $('backtest-result').innerHTML = '<span class="muted-text">等待当前股票回测结果…</span>';
    if ($('batch-backtest-result')) $('batch-backtest-result').innerHTML = '<p class="muted-text">等待当前股票筛选结果。</p>';
    if ($('strategy-scan-result')) { $('strategy-scan-result').hidden = true; $('strategy-scan-result').innerHTML = ''; }
    if ($('backtest-status')) $('backtest-status').textContent = message;
    if ($('backtest-history-status')) $('backtest-history-status').textContent = '正在读取最近 10 次';
    if ($('backtest-history-list')) $('backtest-history-list').innerHTML = '<span class="muted-text">正在读取历史回测…</span>';
    if ($('backtest-comparison')) { $('backtest-comparison').hidden = true; $('backtest-comparison').innerHTML = ''; }
    if ($('compare-backtests')) $('compare-backtests').disabled = true;
  }

  function resetRealtimePanel() {
    realtimeEvents = [];
    const defaults = { score: '--', signal: '等待分析', recommendation: '研究建议：--', updated: '更新时间：--', source: '等待数据', trend: '--', momentum: '--', volatility: '--', volume: '--', period: '分时 + 日线', levels: '--' };
    if ($('analysis-score')) { $('analysis-score').textContent = defaults.score; applySemanticTone($('analysis-score'), defaults.score); }
    if ($('analysis-signal')) { $('analysis-signal').textContent = defaults.signal; applySemanticTone($('analysis-signal'), defaults.signal); }
    if ($('analysis-recommendation')) { $('analysis-recommendation').textContent = defaults.recommendation; applySemanticTone($('analysis-recommendation'), defaults.recommendation); }
    if ($('analysis-updated')) $('analysis-updated').textContent = defaults.updated;
    if ($('analysis-source')) $('analysis-source').textContent = defaults.source;
    if ($('alert-instrument-label')) $('alert-instrument-label').textContent = '当前股票 · 等待数据';
    if ($('alert-connection-label')) $('alert-connection-label').textContent = '连接状态：等待';
    updateScoreRing(null, 'neutral');
    [['analysis-trend', defaults.trend], ['analysis-momentum', defaults.momentum], ['analysis-volatility', defaults.volatility], ['analysis-volume', defaults.volume], ['analysis-period', defaults.period], ['analysis-levels', defaults.levels], ['analysis-confidence', defaults.confidence], ['analysis-quality', defaults.quality], ['analysis-risk-label', defaults.risk]].forEach(([id, value]) => { if ($(id)) $(id).textContent = value; });
    renderRiskTooltip({ level: '等待分析', warnings: [] });
    renderRealtimeEvents();
  }

  function renderActive(i) {
    const changed = active && Number(active.id) !== Number(i.id);
    active = i; window.__activeInstrumentId = i.id; window.__activeMarketTimezone = i.market === 'us' ? 'America/New_York' : 'Asia/Hong_Kong';
    if (changed) { resetRealtimePanel(); resetAnalysisPanel(); resetBacktestPanel(); }
    document.querySelector('.stock-name').textContent = i.name;
    document.querySelector('.stock-code').innerHTML = `<span>${esc(i.code)}</span><span class="stock-divider"></span><span>${esc(i.exchange)}</span><span class="currency-label">${esc(i.currency)}</span>`;
    if ($('chart-instrument-name')) $('chart-instrument-name').textContent = `${i.name} ${i.code}`;
    if ($('analysis-instrument-label')) $('analysis-instrument-label').textContent = `${i.name} ${i.code}`;
    document.querySelector('.price-currency').textContent = i.currency;
    $('web-panel-subtitle').textContent = `百度股市通 · ${i.name} ${i.code}`;
    if ($('alert-instrument-label')) $('alert-instrument-label').textContent = `${i.name} · ${i.code}`;
    document.body.dataset.market = i.market;
    const marketUp = i.market === 'us' ? '#16845b' : '#d94b4b';
    const marketDown = i.market === 'us' ? '#c0394b' : '#16845b';
    document.documentElement.style.setProperty('--market-up', marketUp);
    document.documentElement.style.setProperty('--market-down', marketDown);
    document.documentElement.style.setProperty('--up', marketUp);
    document.documentElement.style.setProperty('--down', marketDown);
    renderList();
  }

  async function load() {
    const request = ++loadRequestToken, revision = stateRevision;
    const isCurrent = () => request === loadRequestToken && revision === stateRevision;
    try {
      const rows = await invoke('list_instruments');
      if (!isCurrent()) return;
      instruments = rows;
      renderResearchBenchmarks();
      const selected = await invoke('get_active_instrument');
      if (!isCurrent()) return;
      active = selected;
      renderList();
      if (!active) { if ($('database-status')) $('database-status').textContent = '请点击 + 添加股票'; return; }
      renderActive(active);
      if ($('database-status')) $('database-status').textContent = `已连接数据库 · ${instruments.length} 只股票`;
      loadBacktestHistory(active?.id);
      if ($('db-path-label')) $('db-path-label').textContent = `数据库：${await invoke('database_path')}`;
      if ((active.nameSource || active.name_source) !== 'custom') {
        const nameId = Number(active.id);
        invoke('refresh_instrument_name', { instrumentId: nameId }).then(updated => {
          if (!updated || !active || Number(active.id) !== nameId) return;
          active = updated; instruments = instruments.map(item => Number(item.id) === nameId ? updated : item); renderActive(updated); renderList();
        }).catch(() => { /* keep cached name and data when Baidu is unavailable */ });
      }
    } catch (e) { if ($('database-status')) $('database-status').textContent = `数据库读取失败：${e}`; }
  }

  function notifyInstrumentSwitchStart(id) {
    try {
      if (typeof window.dispatchEvent === 'function' && typeof window.CustomEvent === 'function') {
        window.dispatchEvent(new window.CustomEvent('instrument-switch-start', { detail: { instrumentId: Number(id) } }));
      }
    } catch (_) { /* Older embedded webviews may not expose CustomEvent. */ }
  }

  async function select(id) {
    const selectionRequest = ++selectionRequestToken;
    stateRevision++;
    loadRequestToken++;
    const requested = instruments.find(item => Number(item.id) === Number(id));
    if (requested && (!active || Number(active.id) !== Number(requested.id))) {
      // Clear visible state before the server switches the selected instrument.
      // This prevents the previous quote/indicator snapshot from lingering while
      // the HTTP request and event stream switch to the new stock.
      activeGeneration++;
      window.__activeGeneration = activeGeneration;
      realtimeRequestToken++;
      notifyInstrumentSwitchStart(requested.id);
      resetRealtimePanel();
      resetAnalysisPanel();
      resetBacktestPanel();
      renderActive(requested);
      if (window.__stockChartReload) window.__stockChartReload();
    }
    try {
      const i = await invoke('set_active_instrument', { id });
      if (selectionRequest !== selectionRequestToken) return;
      renderActive(i);
      if (window.__stockChartReload) window.__stockChartReload();
      await loadRealtimePanel();
      await window.__runAnalysis();
      await loadBacktestHistory(i.id);
    } catch (e) { showHint(`切换股票失败：${e}`, true); }
  }

  function showDialog(dialog) { if (!dialog) return; if (typeof dialog.showModal === 'function') dialog.showModal(); else dialog.hidden = false; }
  function closeDialog(dialog) { if (!dialog) return; if (typeof dialog.close === 'function') dialog.close(); else dialog.hidden = true; }
  function showHint(message, error = false) { const node = $('settings-hint'); if (node) { node.textContent = message; node.classList.toggle('error-text', error); } const status = $('data-dialog-status'); if (status) status.textContent = message; }

  function normalizePageInput(value) {
    let text = String(value || '').trim();
    // Accept the documented short form as well as a pasted full URL.  A pure
    // numeric code is intentionally rejected because it is ambiguous between
    // SSE/SZSE/HK/US.
    if (/^(hk|ab|sh|sz|us|cn-ab|cn-sh|cn-sz)-[^\s/?#]+$/i.test(text)) text = `https://finance.baidu.com/stock/${text}`;
    else if (/^\/?stock\/(hk|ab|sh|sz|us|cn-ab|cn-sh|cn-sz)-[^\s/?#]+$/i.test(text)) text = `https://finance.baidu.com/${text.replace(/^\//, '')}`;
    else if (/^finance\.baidu\.com\/stock\//i.test(text)) text = `https://${text}`;
    return text;
  }

  function openStockDialog(item = null) {
    editingId = item?.id ?? null;
    $('settings-title').textContent = item ? '编辑股票' : '添加股票';
    $('settings-url').value = item?.pageUrl || '';
    $('settings-name').value = item && (item.nameSource || item.name_source) === 'custom' ? (item.name || '') : '';
    $('settings-enabled').checked = item?.enabled ?? true;
    showHint(item ? ((item.nameSource || item.name_source) === 'custom' ? '当前为自定义名称；自动刷新不会覆盖。' : '当前名称来自百度页面，清空名称后可重新自动识别。') : '保存时识别市场、代码和币种；显示名称可自行填写。');
    if ($('name-preview-status')) $('name-preview-status').textContent = '';
    showDialog($('settings-dialog'));
    setTimeout(() => $('settings-url')?.focus(), 0);
  }

  async function saveStock(event) {
    event.preventDefault();
    const pageUrl = normalizePageInput($('settings-url').value);
    if (!pageUrl) { showHint('请输入百度股票页面地址，例如 ab-688825 或 https://finance.baidu.com/stock/us-aapl。', true); return; }
    $('settings-url').value = pageUrl;
    const autoName = !$('settings-name').value.trim();
    if (autoName && $('name-preview-status')) { $('name-preview-status').textContent = '正在从百度页面识别股票名称…'; $('name-preview-status').classList.remove('error-text'); }
    try {
      let item;
      const args = { pageUrl, displayName: $('settings-name').value.trim() || null };
      if (editingId == null) item = await invoke('add_instrument', args);
      else item = await invoke('update_instrument', { id: editingId, ...args, enabled: $('settings-enabled').checked });
      instruments = await invoke('list_instruments');
      renderResearchBenchmarks();
      renderList();
      try {
        const selected = await invoke('set_active_instrument', { id: item.id });
        renderActive(selected);
        if (window.__stockChartReload) window.__stockChartReload();
        closeDialog($('settings-dialog'));
        showHint(`${(selected.nameSource || selected.name_source) === 'custom' ? '已保存自定义名称' : '已保存显示名称'}：${selected.name}（${selected.market} / ${selected.code}）。`); if ($('name-preview-status')) $('name-preview-status').textContent = `显示名称：${selected.name}`;
      } catch (switchError) {
        // The row is already persisted.  Do not report a successful INSERT as
        // a failed add when a later selection request fails.
        closeDialog($('settings-dialog'));
        showHint(`已保存 ${item.name}，但切换采集页面失败：${switchError}`, true);
      }
    } catch (e) { if ($('name-preview-status') && autoName) { $('name-preview-status').textContent = `名称识别失败：${e}`; $('name-preview-status').classList.add('error-text'); } showHint(`保存失败：${e}`, true); }
  }

  function setAlertProfileForm(profile) {
    if (!profile) return;
    if ($('alert-enabled')) $('alert-enabled').checked = profile.enabled !== false;
    if ($('alert-cooldown')) $('alert-cooldown').value = profile.cooldownSeconds ?? profile.cooldown_seconds ?? 900;
    if ($('alert-volume-multiple')) $('alert-volume-multiple').value = profile.volumeMultiple ?? profile.volume_multiple ?? 1.0;
    if ($('alert-score-threshold')) $('alert-score-threshold').value = profile.scoreChangeThreshold ?? profile.score_change_threshold ?? 10;
    if ($('alert-rsi-overbought')) $('alert-rsi-overbought').value = profile.rsiOverbought ?? profile.rsi_overbought ?? 70;
    if ($('alert-rsi-oversold')) $('alert-rsi-oversold').value = profile.rsiOversold ?? profile.rsi_oversold ?? 30;
  }
  async function loadAlertProfileForm() {
    if (!active) return;
    try { setAlertProfileForm(await invoke('load_alert_profile', { instrumentId: Number(active.id) })); }
    catch (e) { showHint(`读取提醒配置失败：${e}`, true); }
  }
  async function saveAlertProfile() {
    if (!active) { showHint('请先选择股票。', true); return; }
    const cooldownSeconds = Number($('alert-cooldown')?.value || 900);
    const volumeMultiple = Number($('alert-volume-multiple')?.value || 1.0);
    const scoreChangeThreshold = Number($('alert-score-threshold')?.value || 10);
    const rsiOverbought = Number($('alert-rsi-overbought')?.value || 70);
    const rsiOversold = Number($('alert-rsi-oversold')?.value || 30);
    if (!Number.isFinite(cooldownSeconds) || cooldownSeconds < 60 || cooldownSeconds > 86400 || !Number.isFinite(volumeMultiple) || volumeMultiple < 1 || !Number.isFinite(scoreChangeThreshold) || scoreChangeThreshold <= 0 || rsiOversold < 0 || rsiOverbought > 100 || rsiOversold >= rsiOverbought) { showHint('提醒参数不合法：请检查冷却时间、成交量倍数、评分阈值和 RSI 上下限。', true); return; }
    const profile = { instrumentId: Number(active.id), enabled: Boolean($('alert-enabled')?.checked), cooldownSeconds: Math.round(cooldownSeconds), volumeMultiple, scoreChangeThreshold, rsiOverbought, rsiOversold };
    try { await invoke('save_alert_profile', { profile }); showHint('提醒配置已保存；后续实时分析将使用新规则。'); }
    catch (e) { showHint(`保存提醒配置失败：${e}`, true); }
  }
  async function openDataDialog() {
    if ($('db-path-label')) $('db-path-label').textContent = `数据库：${await invoke('database_path')}`;
    try { if ($('retention-days')) $('retention-days').value = await invoke('get_retention_days'); } catch (_) {}
    await loadAlertProfileForm();
    showDialog($('data-dialog'));
  }
  async function backup() { window.StockPlatform.download('api/archive'); showHint('已请求下载数据库备份。'); }
  async function restore() {
    const file = $('data-path').files[0];
    if (!file) { showHint('请选择 JSON 备份文件。', true); return; }
    if (!window.confirm('恢复将替换当前数据库的业务数据，确认继续？')) return;
    try { const form = new FormData(); form.append('file', file); const result = await window.StockPlatform.request('api/archive', { method: 'POST', body: form }); showHint(`恢复完成；原数据备份：${result.backup}`); await load(); window.__stockChartReload?.(); } catch (e) { showHint(`恢复失败：${e}`, true); }
  }
  async function clearData() {
    if (!active || !window.confirm(`确认清理 ${active.name} 的所有行情、指标和回测数据？`)) return;
    try { await invoke('delete_instrument_data', { id: active.id }); showHint('当前股票数据已清理。'); if (window.__stockChartReload) window.__stockChartReload(); } catch (e) { showHint(`清理失败：${e}`, true); }
  }
  async function cleanupExpiredData() {
    const days = Number($('retention-days')?.value || 365);
    if (!Number.isInteger(days) || days < 1 || days > 3650) { showHint('数据保留天数必须在 1 到 3650 天之间。', true); return; }
    try { await invoke('save_retention_days', { retentionDays: days }); const result = await invoke('cleanup_expired_data', { retentionDays: days }); showHint(`过期数据已清理：${result.deletedRows} 条；清理前已自动备份。`); } catch (e) { showHint(`清理过期数据失败：${e}`, true); }
  }
  async function checkDatabaseIntegrity() { try { const result = await invoke('check_database_integrity'); showHint(`数据库完整性检查：${result}`); } catch (e) { showHint(`完整性检查失败：${e}`, true); } }
  async function exportDiagnostics() { window.StockPlatform.download('api/diagnostics'); }

  const categoryNames = { trend: '趋势', momentum: '动量', volatility: '波动', volume: '成交量 / 资金流', structure: '价格结构 / 形态', composite: '复合分析', other: '其他' };
  const categoryOrder = ['trend', 'momentum', 'volatility', 'volume', 'structure', 'composite', 'other'];
  const qualityLabel = value => ({ valid:'有效', 'insufficient-samples':'样本不足', 'missing-volume':'成交量缺失', 'invalid-ohlc':'数据异常', 'partial-data':'部分数据' }[String(value || '').toLowerCase()] || (value || '有效'));
  const formatIndicatorValue = value => {
    if (value == null || !Number.isFinite(Number(value))) return '样本不足';
    const number = Number(value);
    if (Math.abs(number) >= 100000000) return `${(number / 100000000).toFixed(2)}亿`;
    if (Math.abs(number) >= 10000) return `${(number / 10000).toFixed(2)}万`;
    return new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 4 }).format(number);
  };
  function indicatorTone(item, status) {
    const explicit = String(item.statusTone || item.status_tone || '').toLowerCase();
    if (explicit === 'positive') return 'bullish';
    if (explicit === 'negative') return 'bearish';
    if (['bullish','bearish','warning','neutral','error'].includes(explicit)) return explicit;
    const text = String(status || '').toLowerCase();
    if (text.includes('样本不足') || text.includes('中性') || text.includes('持平') || text.includes('未知') || text.includes('待综合')) return 'neutral';
    if (text.includes('超买') || text.includes('超卖') || text.includes('风险') || text.includes('波动') || text.includes('高位') || text.includes('背离')) return 'warning';
    if (text.includes('偏弱') || text.includes('空头') || text.includes('流出') || text.includes('下降') || text.includes('跌破')) return 'bearish';
    if (text.includes('偏强') || text.includes('多头') || text.includes('流入') || text.includes('上升') || text.includes('突破') || text.includes('有效')) return 'bullish';
    return 'neutral';
  }
  function directionSymbol(item) {
    const value = String(item.directionSymbol || item.direction_symbol || '').trim();
    if (value) return value;
    const direction = String(item.direction || '').toLowerCase();
    return direction.includes('上') || direction.includes('升') || direction.includes('多') ? '↑' : direction.includes('下') || direction.includes('降') || direction.includes('空') ? '↓' : '→';
  }
  function renderIndicatorCards(details, root, options = {}) {
    if (!root) return 0;
    const rows = Array.isArray(details) ? details.filter(Boolean) : [];
    const countNode = options.countId ? $(options.countId) : null;
    if (!rows.length) {
      root.innerHTML = `<span class="muted-text">${esc(options.emptyMessage || '暂无指标数据')}</span>`;
      if (countNode) countNode.textContent = '暂无数据';
      return 0;
    }
    const grouped = new Map();
    rows.forEach(item => { const key = item.category || 'other'; if (!grouped.has(key)) grouped.set(key, []); grouped.get(key).push(item); });
    const groups = [...grouped.entries()].sort((a, b) => categoryOrder.indexOf(a[0]) - categoryOrder.indexOf(b[0]));
    root.innerHTML = groups.map(([category, items]) => `<section class="indicator-group"><h3 class="indicator-group-title"${options.hideGroupTitle ? ' hidden' : ''}>${esc(categoryNames[category] || category)}</h3><div class="indicator-card-grid">${items.map(item => {
      const value = formatIndicatorValue(item.latestValue ?? item.value);
      const previous = item.previousValue == null ? '—' : formatIndicatorValue(item.previousValue);
      const status = item.status || item.signal || '样本不足';
      const tone = indicatorTone(item, status);
      const direction = directionSymbol(item);
      const source = item.source || options.source || '已存数据';
      const updated = item.updatedAt ? new Date(Number(item.updatedAt) * (Number(item.updatedAt) < 100000000000 ? 1000 : 1)).toLocaleString('zh-CN') : '--';
      const title = `${item.displayName || item.name || ''} ${item.name || ''}`;
      return `<article class="indicator-card tone-${esc(tone)}" tabindex="0" aria-label="${esc(title)}" data-indicator-card><div class="indicator-card-head"><strong>${esc(item.displayName || item.name)}</strong><code>${esc(item.name)}</code></div><div class="indicator-card-value">${esc(value)} <small>${esc(item.unit || '')}</small></div><div class="indicator-card-meta"><span>上一值 ${esc(previous)}</span><span>${esc(item.period || options.period || '--')}</span><span class="indicator-direction" aria-label="指标值较上一根变化" title="箭头表示指标数值相对上一根的变化，不代表买卖方向">${esc(direction)}</span><span class="indicator-status">${esc(status)}</span><span class="indicator-quality">${esc(qualityLabel(item.dataQuality || item.data_quality))}</span></div><div class="indicator-card-source">${esc(source)} · ${esc(updated)}</div><div class="indicator-tooltip" role="tooltip"><b>${esc(item.displayName || item.name)} · ${esc(item.name)}</b><p>${esc(item.description || '暂无定义说明。')}</p><p><strong>当前：</strong>${esc(status)} ${esc(direction)}；<strong>偏强：</strong>${esc(item.goodWhen || '结合趋势判断。')}</p><p><strong>偏弱：</strong>${esc(item.badWhen || '结合趋势判断。')}</p><p><strong>中性：</strong>${esc(item.neutralWhen || '方向不明或样本不足。')}</p><p><strong>计算：</strong>${esc(item.formula || '由标准化行情数据计算。')}</p><p><strong>注意：</strong>${esc(item.limitations || '不要单独作为研究结论。')}</p><small>周期：${esc(item.period || options.period || '--')} · 来源：${esc(source)} · 更新：${esc(updated)}</small></div></article>`;
    }).join('')}</div></section>`).join('');
    if (countNode) countNode.textContent = `${rows.length} 项`;
    root.querySelectorAll('[data-indicator-card]').forEach(card => card.addEventListener('click', event => { event.stopPropagation(); root.querySelectorAll('.is-pinned').forEach(x => { if (x !== card) x.classList.remove('is-pinned'); }); card.classList.toggle('is-pinned'); }));
    return rows.length;
  }
  function legacyIndicatorDetails(results, period, source, updatedAt) {
    return (results || []).map(item => { const vals = item.values || []; const latest = Number.isFinite(vals[vals.length - 1]) ? vals[vals.length - 1] : null; const previous = Number.isFinite(vals[vals.length - 2]) ? vals[vals.length - 2] : null; return { name:item.name, displayName:item.name, category:'other', latestValue:latest, previousValue:previous, unit:'数值', period, status:latest == null ? '样本不足' : '数值（不单独判断方向）', statusTone:'neutral', direction:'未知', description:'由标准化行情数据计算。', formula:'由标准化行情数据计算。', goodWhen:'结合趋势和周期判断。', badWhen:'单独使用可能误判。', neutralWhen:'样本不足或方向不明。', limitations:'请结合其他指标。', source, updatedAt }; });
  }
  window.__runAnalysis = async () => {
    if (!active) { setAnalysisStatus('请先添加股票', 'waiting'); return; }
    const token = ++analysisRequestToken;
    const instrumentId = active?.id == null ? null : Number(active.id);
    setAnalysisStatus('正在读取日线缓存并计算指标…', 'loading');
    try {
      let r;
      let period = 'day';
      let dayError = null;
      try {
        r = await invoke('load_analysis', { instrumentId, period, request: { maPeriods:[5,10,20], rsiPeriod:14, kdjPeriod:9, atrPeriod:14 } });
      } catch (error) {
        dayError = error;
        period = 'intraday';
        r = await invoke('load_analysis', { instrumentId, period, request: { maPeriods:[5,10,20], rsiPeriod:14, kdjPeriod:9, atrPeriod:14 } });
      }
      if (token !== analysisRequestToken || !active || (instrumentId != null && Number(active.id) !== instrumentId)) return;
      const sum = r.summary || {};
      const details = Array.isArray(r.indicatorDetails) && r.indicatorDetails.length ? r.indicatorDetails : legacyIndicatorDetails(r.indicators, period, r.source, Math.round(Number(r.fetchedAtMs || 0) / 1000));
      const count = renderIndicatorCards(details, $('indicator-list'), { period, source:r.source, countId:'indicator-count', emptyMessage:'暂无可用技术指标；样本可能不足。' });
      const compositeCount = renderIndicatorCards(r.compositeDetails, $('composite-indicator-list'), { period, source:r.source, countId:'composite-count', hideGroupTitle:true, emptyMessage:'暂无综合分析结果。' });
      const sourceText = r.source || (period === 'intraday' ? '数据库 分时缓存' : '数据库 日线缓存');
      setAnalysisStatus(`${period === 'intraday' ? '日线暂无数据，已使用分时缓存' : '指标已更新'} · ${count} 项技术指标 · ${compositeCount} 项复合分析 · ${sourceText}`, period === 'intraday' ? 'fallback' : 'ready');
      if (dayError) setAnalysisStatus(`日线暂无数据，已使用分时缓存 · ${count} 项指标 · ${sourceText}`, 'fallback');
    } catch (e) {
      if (token !== analysisRequestToken) return;
      const message = String(e?.message || e);
      renderIndicatorCards([], $('indicator-list'), { countId:'indicator-count', emptyMessage:'暂时无法读取行情数据，请先刷新数据。' });
      renderIndicatorCards([], $('composite-indicator-list'), { countId:'composite-count', emptyMessage:'暂无综合分析结果。' });
      setAnalysisStatus(`指标读取失败：${message}`, 'error');
    }
  };
  let realtimeEvents = [], realtimePaused = false, realtimeFilter = 'all';
  const numberText = value => value == null || !Number.isFinite(Number(value)) ? '--' : Number(value).toFixed(2);
  const severityLabel = value => ({ important:'重要', serious:'严重', warning:'警告', info:'提示' }[String(value || '').toLowerCase()] || '提醒');
  function renderRiskTooltip(risk) {
    const trigger = $('analysis-risk-label');
    const tooltip = $('analysis-risk-tooltip');
    if (!trigger || !tooltip) return;
    const warnings = Array.isArray(risk?.warnings) ? risk.warnings.filter(Boolean) : [];
    const level = risk?.level || '数据不可用';
    const reasonMarkup = warnings.length
      ? `<ul class="risk-tooltip-list">${warnings.map(item => `<li class="risk-tooltip-${esc(item.severity || 'warning')}"><b>${esc(item.title || '风险项')} · ${esc(severityLabel(item.severity))}</b><small>${esc(item.detail || '已触发风险规则')}</small></li>`).join('')}</ul>`
      : '<p class="muted-text">当前没有触发风险规则。非交易时段的数据延迟不会升级为严重风险。</p>';
    const metrics = [];
    if (risk?.atrPct != null && Number.isFinite(Number(risk.atrPct))) metrics.push(`ATR% ${Number(risk.atrPct).toFixed(2)}%`);
    if (risk?.drawdownPct != null && Number.isFinite(Number(risk.drawdownPct))) metrics.push(`回撤 ${Number(risk.drawdownPct).toFixed(2)}%`);
    if (risk?.gapPct != null && Number.isFinite(Number(risk.gapPct))) metrics.push(`跳空 ${Number(risk.gapPct).toFixed(2)}%`);
    if (risk?.volumeMultiple != null && Number.isFinite(Number(risk.volumeMultiple))) metrics.push(`量比 ${Number(risk.volumeMultiple).toFixed(2)}x`);
    if (risk?.liquidityState) metrics.push(`流动性 ${risk.liquidityState}`);
    tooltip.innerHTML = `<div class="risk-tooltip-title"><span>风险依据</span><span class="risk-tooltip-level">${esc(level)}</span></div>${reasonMarkup}${metrics.length ? `<div class="risk-tooltip-metrics">${metrics.map(item => `<span>${esc(item)}</span>`).join('')}</div>` : ''}`;
    trigger.setAttribute('aria-label', `风险：${level}，查看风险原因`);
    trigger.title = warnings.length ? `查看风险原因（${warnings.length} 项）` : '查看风险原因';
  }
  function renderRealtime(analysis) {
    if (!analysis) { resetRealtimePanel(); return; }
    if (!active || Number(analysis.instrumentId) !== Number(active.id)) return;
    if (activeGeneration && analysis.generation != null && Number(analysis.generation) !== Number(activeGeneration)) return;
    const score = analysis.score == null ? null : Number(analysis.score);
    const scoreText = Number.isFinite(score) ? score.toFixed(1) : '—';
    $('analysis-score').textContent = scoreText; applySemanticTone($('analysis-score'), analysis.signal === 'insufficient-data' ? '样本不足' : signalLabel(analysis.signal));
    updateScoreRing(score, analysis.signal);
    const signalText = signalLabel(analysis.signal);
    $('analysis-signal').textContent = signalText; applySemanticTone($('analysis-signal'), signalText);
    const recommendationText = `研究建议：${analysis.recommendation || '--'}`;
    $('analysis-recommendation').textContent = recommendationText; applySemanticTone($('analysis-recommendation'), recommendationText);
    $('analysis-updated').textContent = `更新时间：${analysis.updatedAtMs ? new Date(Number(analysis.updatedAtMs)).toLocaleTimeString() : '--'}`;
    $('analysis-source').textContent = analysis.source || '本地分析';
    [['analysis-trend', analysis.trend], ['analysis-momentum', analysis.momentum], ['analysis-volatility', analysis.volatility], ['analysis-volume', analysis.volumeState]].forEach(([id, value]) => { const node = $(id); if (node) { node.textContent = value || '--'; applySemanticTone(node, value); } });
    $('analysis-period').textContent = analysis.period === 'intraday' ? '分时实时' : '日线缓存';
    const confidence = Number(analysis.confidence);
    if ($('analysis-confidence')) $('analysis-confidence').textContent = Number.isFinite(confidence) ? `可信度 ${(confidence * 100).toFixed(0)}% · 有效权重 ${Number(analysis.availableWeight || 0).toFixed(0)}%` : '可信度 --';
    if ($('analysis-quality')) $('analysis-quality').textContent = `数据质量：${qualityLabel(analysis.dataQuality || analysis.data_quality)}`;
    const levels = analysis.supportResistance || {};
    $('analysis-levels').textContent = `支 ${numberText(levels.support)} · 阻 ${numberText(levels.resistance)}`;
    const risk = analysis.risk || {};
    const riskNode = $('analysis-risk-label');
    if (riskNode) { riskNode.textContent = `风险：${risk.level || '数据不可用'}`; applyExplicitTone(riskNode, risk.tone || 'neutral'); }
    renderRiskTooltip(risk);
    if ($('alert-instrument-label') && active) $('alert-instrument-label').textContent = `${active.name} · ${active.code}`;
    if ($('alert-connection-label')) $('alert-connection-label').textContent = `连接状态：${analysis.source || '本地分析'}`;
  }
  function renderRealtimeEvents() {
    const root = $('analysis-event-list'); if (!root) return;
    const currentEvents = realtimeEvents.filter(e => !active || Number(e.instrumentId) === Number(active.id));
    const unread = currentEvents.filter(e => !e.acknowledged).length;
    if ($('event-unread')) { $('event-unread').textContent = String(unread); $('event-unread').hidden = unread === 0; }
    const rows = currentEvents.filter(e => realtimeFilter === 'all' || e.severity === realtimeFilter);
    if (!rows.length) { root.innerHTML = '<span class="muted-text">暂无实时提醒</span>'; return; }
    root.innerHTML = rows.slice(0, 80).map((e, index) => { const reasons = JSON.stringify(e.reasons || [], null, 2); const snapshot = JSON.stringify(e.indicatorSnapshot || e.indicator_snapshot || [], null, 2); const quality = qualityLabel(e.dataQuality || e.data_quality); return `<article class="analysis-event ${e.acknowledged ? 'acknowledged' : ''}" data-severity="${esc(e.severity)}" data-index="${index}"><div class="analysis-event-head"><span>${esc(severityLabel(e.severity))} · ${esc(signalLabel(e.signal || 'neutral'))}</span><time>${e.timestamp ? new Date(Number(e.timestamp) * 1000).toLocaleTimeString() : '--'}</time></div><b>${esc(e.message || e.eventType || '实时提醒')}</b><p>评分 ${numberText(e.score)} · ${esc(e.period || '--')} · ${esc(quality)} · ${esc(e.source || '--')} ${e.acknowledged ? '· 已读' : ''}</p><small>触发依据：${esc(reasons)}
指标快照：${esc(snapshot)}</small>${e.id != null && !e.acknowledged ? `<button type="button" class="event-ack" data-event-id="${esc(e.id)}">标记已读</button>` : ''}</article>`; }).join('');
    root.querySelectorAll('.analysis-event').forEach(node => node.addEventListener('click', async event => {
      if (event.target.closest('.event-ack')) {
        event.stopPropagation();
        const id = Number(event.target.closest('.event-ack').dataset.eventId);
        try { await invoke('acknowledge_analysis_event', { eventId: id }); const item = realtimeEvents.find(x => Number(x.id) === id); if (item) item.acknowledged = true; renderRealtimeEvents(); } catch (error) { showHint(`标记提醒失败：${error}`, true); }
      } else node.classList.toggle('open');
    }));
    if (!realtimePaused) root.scrollTop = 0;
  }
  function appendRealtimeEvent(event) {
    if (!active || Number(event?.instrumentId) !== Number(active.id)) return;
    if (activeGeneration && event?.generation > 0 && Number(event.generation) !== Number(activeGeneration)) return;
    if (event.id != null && realtimeEvents.some(x => x.id === event.id)) return;
    realtimeEvents.unshift(event); realtimeEvents = realtimeEvents.slice(0, 100); renderRealtimeEvents();
  }
  async function loadRealtimePanel() {
    if (!active || !window.StockPlatform) return;
    const token = ++realtimeRequestToken;
    const instrumentId = Number(active.id);
    try {
      const analysis = await invoke('load_realtime_analysis', { instrumentId });
      if (token !== realtimeRequestToken || !active || Number(active.id) !== instrumentId) return;
      renderRealtime(analysis);
      const health = await invoke('get_data_health', { instrumentId });
      if (token !== realtimeRequestToken || !active || Number(active.id) !== instrumentId) return;
      if (health && $('alert-connection-label')) { const labels = { live: '实时', cached: '缓存', stale: '数据过期', reconnecting: '重连中', blocked: '请求冷却', captcha: '页面验证', partial: '部分行情受限', error: '行情异常', disconnected: '连接断开' }; const session = health.session ? ` · ${({open:'交易中',break:'午间休市',closed:'休市','pre-market':'盘前','after-hours':'盘后'})[health.session] || health.session}` : ''; const latency = health.latencyMs != null ? ` · ${health.latencyMs}ms` : ''; $('alert-connection-label').textContent = `数据源：${labels[health.state] || health.state || '未知'}${session}${latency}`; }
      const events = await invoke('list_analysis_events', { instrumentId, startDate: null, endDate: null, severity: null });
      if (token !== realtimeRequestToken || !active || Number(active.id) !== instrumentId) return;
      realtimeEvents = Array.isArray(events) ? events : []; renderRealtimeEvents();
    } catch (e) { if (token === realtimeRequestToken && $('analysis-source')) $('analysis-source').textContent = `分析不可：${e}`; }
  }

  const strategyDefaults = {
    ma: [['fastPeriod','快速均线周期',5],['slowPeriod','慢速均线周期',20]],
    macd: [['fast','DIF 快线',12],['slow','DEA 慢线',26],['signal','信号线',9]],
    rsi: [['period','RSI 周期',14],['oversold','超卖线',30],['overbought','超买线',70]],
    bollinger: [['period','周期',20],['std','标准差倍数',2]]
  };
  function renderStrategyParameters() {
    const root = $('strategy-parameters'); if (!root) return;
    const strategy = $('strategy-select')?.value || 'ma';
    root.innerHTML = `<span class="strategy-parameters-title">策略参数</span>${(strategyDefaults[strategy] || []).map(([key,label,value]) => `<label>${label}<input data-strategy-param="${key}" type="number" min="0.01" step="${key === 'std' ? '0.1' : '1'}" value="${value}" /></label>`).join('')}`;
  }
  function strategyParameters() {
    const strategy = $('strategy-select')?.value || 'ma'; const params = {};
    document.querySelectorAll('[data-strategy-param]').forEach(input => { const value = Number(input.value); params[input.dataset.strategyParam] = Number.isFinite(value) ? value : 0; });
    return params;
  }
  function backtestRequest(instrumentId) {
    const valueOrNull = id => { const value = $(id)?.value; return value ? value : null; };
    const n = id => Number($(id)?.value);
    return { instrumentId, strategy: $('strategy-select')?.value || 'ma', parameters: strategyParameters(), initialCapital: Number.isFinite(n('initial-capital')) ? n('initial-capital') : 100000, maxPosition: Number.isFinite(n('max-position')) ? n('max-position') : 1, startDate: valueOrNull('backtest-start-date'), endDate: valueOrNull('backtest-end-date'), commissionRate: valueOrNull('backtest-commission') == null ? null : n('backtest-commission'), stampDutyRate: valueOrNull('backtest-stamp-duty') == null ? null : n('backtest-stamp-duty'), slippageRate: valueOrNull('backtest-slippage') == null ? null : n('backtest-slippage'), allowFractionalShares: $('backtest-fractional')?.checked || false, enforcePriceLimit: $('backtest-enforce-limit')?.checked !== false };
  }
  const moneyText = (value, currency = '') => value == null || !Number.isFinite(Number(value)) ? '—' : `${Number(value).toLocaleString(undefined,{minimumFractionDigits:2,maximumFractionDigits:2})} ${esc(currency)}`;
  const dateText = value => value ? new Date(Number(value) * 1000).toLocaleDateString('zh-CN', { timeZone: window.__activeMarketTimezone || 'Asia/Hong_Kong' }) : '—';
  function renderEquityCurve(points) {
    const valid = Array.isArray(points) ? points.filter(point => Number.isFinite(Number(point?.value))) : [];
    if (valid.length < 2) return '<div class="equity-chart empty-state">暂无足够资金曲线数据</div>';
    const values = valid.map(point => Number(point.value));
    const min = Math.min(...values); const max = Math.max(...values); const range = max - min || 1;
    const width = 640; const height = 150;
    const path = valid.map((point, index) => { const x = (index / Math.max(1, valid.length - 1)) * width; const y = height - ((Number(point.value) - min) / range) * (height - 16) - 8; return `${index ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`; }).join(' ');
    return `<div class="equity-chart"><div class="equity-chart-label"><span>总权益资金曲线</span><small>${moneyText(min)} — ${moneyText(max)}</small></div><svg viewBox="0 0 ${width} ${height}" preserveAspectRatio="none" role="img" aria-label="回测总权益资金曲线"><path d="${path}" fill="none" stroke="var(--market-up)" stroke-width="2" vector-effect="non-scaling-stroke" /></svg></div>`;
  }

  function renderBacktestResult(r, options = {}) {
    const quality = r.dataQuality === 'valid' ? '数据有效' : r.dataQuality === 'insufficient-data' ? '样本不足' : r.dataQuality === 'data-error' ? '数据异常' : (r.dataQuality || '待确认');
    const usable = r.dataQuality === 'valid';
    const backtestPct = value => usable ? pctText(value) : '—';
    const backtestMoney = value => usable ? moneyText(value, r.currency) : '—';
    const currentVersion = Number(r.calculationVersion || 0);
    const legacy = currentVersion > 0 && currentVersion !== BACKTEST_CALCULATION_VERSION;
    const completedTrades = Array.isArray(r.completedTrades) ? r.completedTrades : [];
    const fills = Array.isArray(r.trades) ? r.trades.slice(-20).reverse() : [];
    const tradeTable = completedTrades.length ? `<div class="trade-detail-wrap"><table class="trade-detail-table"><thead><tr><th>开仓</th><th>平仓</th><th>开仓价</th><th>平仓价</th><th>净收益</th><th>收益率</th><th>持仓</th><th>手续费</th><th>印花税</th><th>滑点</th><th>平仓原因</th><th>策略平仓</th></tr></thead><tbody>${completedTrades.slice(-30).reverse().map(t => `<tr><td>${dateText(t.entryTime)}</td><td>${dateText(t.exitTime)}</td><td>${Number(t.entryPrice).toFixed(3)}</td><td>${Number(t.exitPrice).toFixed(3)}</td><td class="${Number(t.netPnl) >= 0 ? 'metric-positive' : 'metric-negative'}">${moneyText(t.netPnl,r.currency)}</td><td>${pctText(t.returnRate)}</td><td>${Number(t.holdingDays || 0).toFixed(1)} 天</td><td>${moneyText(t.commission,r.currency)}</td><td>${moneyText(t.stampDuty,r.currency)}</td><td>${moneyText(t.slippage,r.currency)}</td><td>${esc(t.exitReason || '')}</td><td>${t.closedByStrategy === false ? '否' : '是'}</td></tr>`).join('')}</tbody></table></div>` : fills.length ? `<div class="trade-detail-wrap"><table class="trade-detail-table"><thead><tr><th>时间</th><th>方向</th><th>价格</th><th>数量</th><th>现金</th><th>触发</th></tr></thead><tbody>${fills.map(t => `<tr><td>${dateText(t.time)}</td><td class="trade-${esc(t.side)}">${t.side === 'buy' ? '买入' : '卖出'}</td><td>${Number(t.price).toFixed(3)}</td><td>${Number(t.quantity).toFixed(2)}</td><td>${moneyText(t.cash,r.currency)}</td><td>${esc(t.reason || '')}</td></tr>`).join('')}</tbody></table></div>` : '<p class="muted-text">暂无完成交易明细。</p>';
    const open = r.openPosition;
    const noTrade = quality === '数据有效' && Number(r.tradeCount || 0) === 0 && !open;
    const signalCount = Number(r.signalCount || 0);
    const activityLabel = noTrade
      ? (signalCount === 0 ? '区间内无买卖信号，请扩大日期范围或调整参数' : '产生信号但未成交，可能受价格限制、交易单位或资金限制')
      : (open ? '区间结束仍有未平仓，收益按收盘价估值' : '已按策略成交记录计算');
    const signalSummary = `信号 ${Number(r.buySignalCount || 0)} 买 / ${Number(r.sellSignalCount || 0)} 卖 · 执行 ${Number(r.executedBuyCount || 0)} 买 / ${Number(r.executedSellCount || 0)} 卖`;
    const openCard = open ? `<div class="open-position-card ${Number(open.unrealizedPnl) >= 0 ? 'metric-positive-border' : 'metric-negative-border'}"><b>未平仓持仓</b><span>开仓 ${dateText(open.entryTime)} · ${Number(open.quantity).toFixed(2)} 股</span><span>开仓价 ${Number(open.entryPrice).toFixed(3)} · 当前价 ${Number(open.currentPrice).toFixed(3)} · 市值 ${moneyText(open.marketValue,r.currency)}</span><strong>未实现盈亏 ${moneyText(open.unrealizedPnl,r.currency)}</strong></div>` : '<div class="open-position-card neutral-border"><b>未平仓持仓</b><span>当前回测结束时无持仓</span></div>';
    const params = r.parameters && typeof r.parameters === 'object' ? JSON.stringify(r.parameters) : '默认参数';
    const rules = r.rules && typeof r.rules === 'object' ? r.rules : {};
    const costText = `手续费 ${pctText(rules.commissionRate)} · 印花税 ${pctText(rules.stampDutyRate)} · 滑点 ${pctText(rules.slippageRate)}`;
    const legacyBadge = legacy ? '<span class="backtest-quality status-legacy">历史版本，仅供参考</span>' : '';
    $('backtest-result').innerHTML = `<div class="backtest-result-head"><b>${esc(r.startDate || '—')} 至 ${esc(r.endDate || '—')}</b><span class="backtest-quality status-${esc(quality)}">${esc(quality)}</span>${legacyBadge}<span>样本 ${esc(r.sampleCount ?? 0)} · 预热 ${esc(r.warmupSampleCount ?? 0)}</span><span>数据源 ${esc(r.source || '数据库日 K')}</span><span>${esc(costText)}</span><span>${esc(activityLabel)}</span><span>${esc(signalSummary)}</span><span>参数 ${esc(params)}</span></div><div class="metrics-grid"><b>总收益率<br><em class="${Number(r.totalReturn) >= 0 ? 'metric-positive' : 'metric-negative'}">${backtestPct(r.totalReturn)}</em>${noTrade ? '<small class="metric-note">无成交</small>' : ''}</b><b>年化收益率<br>${backtestPct(r.annualizedReturn)}</b><b>历史平仓胜率<br>${usable && r.completedTradeCount ? pctText(r.winRate) : '—'}</b><b>完成交易<br>${esc(r.completedTradeCount ?? 0)} / 成交 ${esc(r.tradeCount ?? 0)}</b><b>盈亏比<br>${usable && Number(r.profitLossRatio) > 0 ? Number(r.profitLossRatio).toFixed(2) : '—'}</b><b>Profit Factor<br>${usable && r.profitFactor != null ? Number(r.profitFactor).toFixed(2) : '—'}</b><b>最大回撤<br><em class="metric-negative">${backtestPct(r.maxDrawdown)}</em></b><b>基准收益<br>${backtestPct(r.benchmarkReturn)}</b><b>期末现金<br>${backtestMoney(r.endingCash)}</b><b>期末持仓市值<br>${backtestMoney(r.endingPositionValue)}</b><b>期末总权益<br>${backtestMoney(r.endingEquity ?? r.endingCash)}</b><b>总费用<br>${backtestMoney(r.totalFees)}</b><b>平均持仓<br>${usable && r.completedTradeCount ? Number(r.averageHoldingDays || 0).toFixed(1) + ' 天' : '—'}</b></div>${openCard}${renderEquityCurve(r.equityCurve)}<h4 class="trade-detail-title">最近完成交易</h4>${tradeTable}<p class="form-hint">历史平仓胜率只统计已完成平仓交易；未平仓盈亏计入总权益，但不计入盈利比例。结果仅用于研究，不代表未来收益概率。</p>`;
  }

  function renderBacktestHistory(rows) {
    const root = $('backtest-history-list');
    if (!root) return;
    backtestHistory = Array.isArray(rows) ? rows.slice(0, 10) : [];
    if (!backtestHistory.length) { root.innerHTML = '<span class="muted-text">暂无历史回测记录</span>'; if ($('compare-backtests')) $('compare-backtests').disabled = true; return; }
    root.innerHTML = `<table class="backtest-history-table"><thead><tr><th></th><th>时间</th><th>策略</th><th>区间</th><th>收益</th><th>胜率</th><th>回撤</th><th>版本</th><th>操作</th></tr></thead><tbody>${backtestHistory.map((item, index) => { const result = item.result || {}; const version = Number(result.calculationVersion || 0); return `<tr><td><input type="checkbox" data-history-select="${index}" aria-label="选择第 ${index + 1} 条回测" /></td><td>${dateText(item.createdAt)}</td><td>${esc(item.strategy || '—')}</td><td>${esc(result.startDate || '—')} 至 ${esc(result.endDate || '—')}</td><td>${pctText(result.totalReturn)}</td><td>${result.completedTradeCount ? pctText(result.winRate) : '—'}</td><td>${pctText(result.maxDrawdown)}</td><td>${version ? `v${version}` : '旧记录'}</td><td><button type="button" class="text-button" data-history-load="${index}">加载</button></td></tr>`; }).join('')}</tbody></table>`;
    root.querySelectorAll('[data-history-load]').forEach(button => button.addEventListener('click', () => { const item = backtestHistory[Number(button.dataset.historyLoad)]; if (item?.result) renderBacktestResult(item.result, { historical: true }); }));
    root.querySelectorAll('[data-history-select]').forEach(input => input.addEventListener('change', () => { const selected = root.querySelectorAll('[data-history-select]:checked'); if ($('compare-backtests')) $('compare-backtests').disabled = selected.length !== 2; }));
    if ($('backtest-history-status')) $('backtest-history-status').textContent = `最近 ${backtestHistory.length} 次 · 当前股票`;
  }

  function renderBacktestComparison() {
    const root = $('backtest-history-list'); const selected = [...(root?.querySelectorAll('[data-history-select]:checked') || [])].map(input => backtestHistory[Number(input.dataset.historySelect)]).filter(Boolean);
    if (selected.length !== 2 || !$('backtest-comparison')) return;
    const metrics = [['总收益', 'totalReturn', pctText], ['年化收益', 'annualizedReturn', pctText], ['历史平仓胜率', 'winRate', pctText], ['Profit Factor', 'profitFactor', value => value == null ? '—' : Number(value).toFixed(2)], ['最大回撤', 'maxDrawdown', pctText], ['完成交易', 'completedTradeCount', value => String(value ?? 0)], ['基准收益', 'benchmarkReturn', pctText], ['期末总权益', 'endingEquity', value => moneyText(value)] ];
    const cells = metrics.map(([label, key, format]) => `<tr><th>${label}</th><td>${format(selected[0].result?.[key])}</td><td>${format(selected[1].result?.[key])}</td></tr>`).join('');
    $('backtest-comparison').hidden = false; $('backtest-comparison').innerHTML = `<h4>回测结果对比</h4><table class="backtest-comparison-table"><thead><tr><th>指标</th><th>${esc(selected[0].strategy || '结果 A')}</th><th>${esc(selected[1].strategy || '结果 B')}</th></tr></thead><tbody>${cells}</tbody></table>`;
  }

  async function loadBacktestHistory(instrumentId = active?.id) {
    if (!instrumentId || !window.StockPlatform) return;
    const token = ++backtestHistoryRequestToken; const id = Number(instrumentId);
    try {
      const rows = await invoke('list_backtests', { instrumentId: id });
      if (token !== backtestHistoryRequestToken || !active || Number(active.id) !== id) return;
      renderBacktestHistory(rows);
      const current = backtestHistory.find(item => Number(item.result?.calculationVersion || 0) === BACKTEST_CALCULATION_VERSION);
      if (current?.result) renderBacktestResult(current.result, { historical: true });
    } catch (error) {
      if (token === backtestHistoryRequestToken && $('backtest-history-status')) $('backtest-history-status').textContent = `历史记录读取失败：${error}`;
    }
  }

  window.__runBacktest = async () => {
    if (!active) await load();
    if (!active) return;
    const id = Number(active.id);
    const generation = Number(activeGeneration || window.__activeGeneration || 0);
    const token = ++backtestRequestToken;
    const status = $('backtest-status');
    const resultNode = $('backtest-result');
    const previous = resultNode?.innerHTML;
    try {
      if (status) status.textContent = '正在使用本地 数据库日 K 回测…';
      const r = await invoke('run_backtest', { request: backtestRequest(id) });
      if (token !== backtestRequestToken || !active || Number(active.id) !== id || (generation && Number(activeGeneration || window.__activeGeneration || 0) !== generation)) return;
      renderBacktestResult(r);
      if (status) status.textContent = `回测完成：${r.startDate || '—'} 至 ${r.endDate || '—'}，${r.sampleCount || 0} 条日 K，预热 ${r.warmupSampleCount || 0} 条`;
      await loadBacktestHistory(id);
    } catch (e) {
      if (token !== backtestRequestToken || !active || Number(active.id) !== id) return;
      if (resultNode && previous) resultNode.innerHTML = previous;
      if (status) status.textContent = `回测失败：${e}`;
    }
  };

  function strategyScanCandidates(strategy) {
    if (strategy === 'ma') return [3,5,8].flatMap(f => [20,30,60].filter(s => f < s).map(s => ({fastPeriod:f,slowPeriod:s})));
    if (strategy === 'macd') return [{fast:8,slow:21,signal:5},{fast:12,slow:26,signal:9},{fast:16,slow:32,signal:9}];
    if (strategy === 'rsi') return [10,14,21].flatMap(period => [{oversold:25,overbought:70},{oversold:30,overbought:70},{oversold:30,overbought:75}].map(level => ({period,...level})));
    return [14,20,30].flatMap(period => [1.5,2,2.5].map(std => ({period,std})));
  }
  window.__scanStrategyParameters = async () => {
    if (!active) return;
    const root = $('strategy-scan-result'); if (!root) return;
    const id = Number(active.id); const generation = Number(activeGeneration || window.__activeGeneration || 0); const token = ++backtestRequestToken; const strategy = $('strategy-select')?.value || 'ma'; const previous = root.innerHTML;
    root.hidden = false; root.innerHTML = '<p class="muted-text">正在使用已存日 K 扫描参数组合…</p>';
    try {
      const result = await invoke('scan_single_strategy_parameters', { request: backtestRequest(id), candidates: strategyScanCandidates(strategy) });
      if (token !== backtestRequestToken || !active || Number(active.id) !== id || (generation && Number(activeGeneration || window.__activeGeneration || 0) !== generation)) return;
      const candidates = Array.isArray(result.candidates) ? result.candidates : [];
      root.innerHTML = `<div class="batch-backtest-summary"><b>${esc(strategy)} 参数候选 ${candidates.length} 组</b><b>版本 v${esc(result.calculationVersion || 2)}</b></div><div class="batch-table-wrap"><table class="batch-backtest-table"><thead><tr><th>参数</th><th>收益</th><th>年化</th><th>胜率</th><th>最大回撤</th><th>完成交易</th><th>状态</th></tr></thead><tbody>${candidates.map(item => `<tr><td><code>${esc(JSON.stringify(item.parameters))}</code></td><td>${pctText(item.totalReturn)}</td><td>${pctText(item.annualizedReturn)}</td><td>${pctText(item.winRate)}</td><td>${pctText(item.maxDrawdown)}</td><td>${esc(item.completedTradeCount ?? 0)}</td><td class="status-${esc(item.status)}" title="${esc(item.reason || '')}">${esc(item.status === 'valid' ? '可比较' : item.status === 'insufficient-data' ? '样本不足' : '不可用')}</td></tr>`).join('')}</tbody></table></div><p class="form-hint">参数扫描仅用于历史研究；请同时观察回撤、交易次数和样本外稳定性，不应只选择最高收益。</p>`;
    } catch (error) { if (token === backtestRequestToken) root.innerHTML = previous || `<p class="error-text">参数扫描失败：${esc(error)}</p>`; }
  };
  const batchStatusLabel = value => ({profitable:'历史盈利',unprofitable:'历史未盈利','no-trades':'无完成交易','insufficient-data':'样本不足','insufficient-trades':'完成交易不足','data-error':'数据异常',disabled:'已停用','invalid-request':'参数无效'}[String(value)] || value || '未知');
  const pctText = value => value == null || !Number.isFinite(Number(value)) ? '—' : `${(Number(value) * 100).toFixed(1)}%`;
  window.__runBatchBacktest = async () => {
    const root = $('batch-backtest-result');
    if (!root) return;
    const id = Number(active?.id || 0);
    const generation = Number(activeGeneration || window.__activeGeneration || 0);
    const token = ++batchBacktestRequestToken;
    const previous = root.innerHTML;
    root.innerHTML = '<p class="muted-text">正在读取所有股票的 数据库日 K 并执行相同策略，请稍候…</p>';
    const minSamplesValue = Number($('backtest-min-samples')?.value);
    const minTradesValue = Number($('backtest-min-trades')?.value);
    const request = {
      ...backtestRequest(null),
      minSamples: Number.isFinite(minSamplesValue) ? minSamplesValue : 40,
      minCompletedTrades: Number.isFinite(minTradesValue) ? minTradesValue : 5,
    };
    delete request.instrumentId;
    try {
      const result = await invoke('screen_instruments_daily_backtest', { request });
      if (token !== batchBacktestRequestToken || Number(active?.id || 0) !== id || (generation && Number(activeGeneration || window.__activeGeneration || 0) !== generation)) return;
      const rows = Array.isArray(result.results) ? result.results : [];
      const summary = `<div class="batch-backtest-summary"><b>已添加 ${result.totalInstruments} 只</b><b>合格 ${result.eligibleInstruments} 只</b><b>历史盈利 ${result.profitableInstruments} 只</b><b>口径：${esc(result.source || '数据库日 K')}</b></div>`;
      const table = rows.length ? `<div class="batch-table-wrap"><table class="batch-backtest-table"><thead><tr><th>股票</th><th>市场</th><th>区间/样本/预热</th><th>历史平仓胜率*</th><th>总收益</th><th>基准</th><th>回撤</th><th>完成交易</th><th>状态</th></tr></thead><tbody>${rows.map(row => `<tr><td><strong>${esc(row.name)}</strong><br><code>${esc(row.code)}</code></td><td>${esc(row.market)}</td><td>${esc(row.startDate || '—')} 至 ${esc(row.endDate || '—')}<br>${esc(row.sampleCount ?? 0)} 条 · 预热 ${esc(row.warmupSampleCount ?? 0)} 条</td><td class="probability ${esc(row.status)}">${pctText(row.profitableProbability)}</td><td>${pctText(row.totalReturn)}</td><td>${pctText(row.benchmarkReturn)}</td><td>${pctText(row.maxDrawdown)}</td><td>${esc(row.completedTradeCount ?? 0)} / ${esc(row.tradeCount ?? 0)}</td><td class="status-${esc(row.status)}" title="${esc(row.reason || '')}">${esc(batchStatusLabel(row.status))}</td></tr>`).join('')}</tbody></table></div>` : '<p class="muted-text">没有可展示结果。</p>';
      root.innerHTML = `${summary}${table}<p class="form-hint">* 历史平仓胜率仅统计已完成交易；样本不足、完成交易不足或无交易显示“—”，不代表未来收益概率。</p>`;
      await loadBacktestHistory(id);
    } catch (e) {
      if (token !== batchBacktestRequestToken || Number(active?.id || 0) !== id) return;
      root.innerHTML = previous || `<p class="error-text">批量回测失败：${esc(e)}</p>`;
    }
  };

  function researchNumber(id, fallback) { const value = Number($(id)?.value); return Number.isFinite(value) ? value : fallback; }
  function renderResearchBenchmarks() {
    const select = $('research-benchmark'); if (!select) return;
    const current = select.value;
    const options = instruments.filter(item => item.enabled).map(item => `<option value="${esc(item.id)}">${esc(item.name)} · ${esc(item.code)} · ${esc(marketName(item.market))}</option>`).join('');
    select.innerHTML = '<option value="">不设置基准</option>' + options;
    if ([...select.options].some(option => option.value === current)) select.value = current;
  }
  function portfolioResearchRequest() {
    const benchmarkValue = Number($('research-benchmark')?.value);
    const benchmarkInstrumentId = Number.isFinite(benchmarkValue) && benchmarkValue > 0 ? benchmarkValue : null;
    const currency = $('research-currency')?.value || active?.currency;
    const ids = instruments.filter(item => item.enabled && item.currency === currency && Number(item.id) !== benchmarkInstrumentId).map(item => Number(item.id)).filter(Number.isFinite);
    return { instrumentIds: ids, initialCapital: researchNumber('research-initial-capital', 100000), startDate: $('research-start-date')?.value || null, endDate: $('research-end-date')?.value || null, priceBasis: $('research-price-basis')?.value || 'unknown', benchmarkInstrumentId, minSamples: Math.max(40, Math.floor(researchNumber('research-min-samples', 120))), trainRatio: 0.6, validationRatio: 0.2, enforcePriceLimit: $('research-enforce-price-limit')?.checked !== false, allowFractionalShares: $('research-allow-fractional')?.checked === true, factorConfig: { trendWeight: researchNumber('research-trend-weight', 0.3), momentumWeight: researchNumber('research-momentum-weight', 0.3), volatilityWeight: researchNumber('research-volatility-weight', 0.2), liquidityWeight: researchNumber('research-liquidity-weight', 0.1), volumeConfirmationWeight: researchNumber('research-volume-weight', 0.1), targetHoldings: Math.max(1, Math.floor(researchNumber('research-target-holdings', 5))), maxPositionWeight: researchNumber('research-max-weight', 0.2), rebalanceFrequency: $('research-rebalance')?.value || 'monthly' }, transactionCostConfig: { commissionRate: null, stampDutyRate: null, slippageRate: null, spreadBps: 0, maxParticipationRate: Math.min(1, Math.max(0.01, researchNumber('research-participation', 0.1))) } };
  }
  function researchMetric(result, name) { return (Array.isArray(result?.metrics) ? result.metrics : []).find(item => item.name === name); }
  function researchMetricText(result, name) { const value = researchMetric(result, name)?.value; return value == null || !Number.isFinite(Number(value)) ? '—' : pctText(value); }
  function researchCurveSvg(result) {
    const points = (Array.isArray(result?.equityCurve) ? result.equityCurve : []).filter(item => item?.initial !== true && Number.isFinite(Number(item?.equity)));
    if (points.length < 2) return '<p class="muted-text">资金曲线样本不足。</p>';
    const values = points.map(item => Number(item.equity)); const min = Math.min(...values); const max = Math.max(...values); const span = Math.max(max - min, Math.abs(max) * 0.000001, 1); const width = 720; const height = 120; const polyline = values.map((value, index) => `${(index / (values.length - 1) * width).toFixed(1)},${(height - 8 - ((value - min) / span) * (height - 16)).toFixed(1)}`).join(' ');
    return `<div class="research-equity-chart" aria-label="组合总权益资金曲线"><svg viewBox="0 0 ${width} ${height}" role="img" preserveAspectRatio="none"><polyline points="${polyline}" fill="none" stroke="var(--accent-blue,#3b82f6)" stroke-width="2" vector-effect="non-scaling-stroke" /></svg><div class="research-equity-axis"><span>低 ${moneyText(min, '')}</span><span>高 ${moneyText(max, '')}</span><span>${esc(points[points.length - 1]?.date || '—')}</span></div></div>`;
  }
  function renderPortfolioResearch(result) {
    const root = $('portfolio-research-result'); if (!root) return;
    const warnings = Array.isArray(result?.warnings) ? result.warnings : [];
    const snapshot = result?.dataSnapshot || {};
    const segments = Array.isArray(result?.segments) ? result.segments : [];
    const holdings = Array.isArray(result?.holdings) ? result.holdings.slice(-30).reverse() : [];
    const warningHtml = warnings.map(item => `<div class="research-warning">${esc(item)}</div>`).join('');
    const holdingHtml = holdings.length ? `<div class="batch-table-wrap"><table class="research-table"><thead><tr><th>日期</th><th>股票</th><th>因子得分</th><th>目标权重</th><th>实际权重</th><th>价格</th><th>市值</th><th>贡献收益</th><th>状态</th></tr></thead><tbody>${holdings.map(item => `<tr><td>${esc(item.date)}</td><td>${esc(item.name)}<br><code>${esc(item.code)}</code></td><td>${Number(item.factorScore || 0).toFixed(2)}</td><td>${pctText(item.targetWeight)}</td><td>${pctText(item.actualWeight)}</td><td>${Number(item.price || 0).toFixed(3)}</td><td>${moneyText(item.marketValue, item.market === 'us' ? 'USD' : '')}</td><td class="${Number(item.contribution) >= 0 ? 'metric-positive' : 'metric-negative'}">${moneyText(item.contribution, item.market === 'us' ? 'USD' : '')}</td><td>${esc(item.dataQuality || 'valid')}</td></tr>`).join('')}</tbody></table></div>` : '<p class="muted-text">暂无持仓记录，可能是样本不足或尚未到达再平衡日期。</p>';
    const segmentHtml = segments.map(segment => { const m = segment.metrics?.find(item => item.name === 'totalReturn'); return `<div class="research-segment"><h4>${esc(segment.name)}</h4><p>${esc(segment.startDate || '—')} 至 ${esc(segment.endDate || '—')}</p><p>区间收益 <strong>${m?.value == null ? '—' : pctText(m.value)}</strong></p><p>样本 ${esc(m?.sampleCount ?? segment.equityCurve?.length ?? 0)} 条</p></div>`; }).join('');
    const benchmarkNote = researchMetric(result, 'benchmarkReturn')?.value == null ? '未设置或无可用基准数据' : `基准 ${researchMetricText(result,'benchmarkReturn')} · 超额 ${researchMetricText(result,'excessReturn')} · Beta ${researchMetric(result,'beta')?.value == null ? '—' : Number(researchMetric(result,'beta').value).toFixed(2)} · Alpha ${researchMetricText(result,'alpha')}`;
    const tradeCount = researchMetric(result, 'tradeCount')?.value;
    const curveHtml = researchCurveSvg(result);
    root.innerHTML = `${warningHtml}<div class="research-summary"><b>总收益<em>${researchMetricText(result,'totalReturn')}</em></b><b>CAGR<em>${researchMetricText(result,'cagr')}</em></b><b>Sharpe<em>${researchMetric(result,'sharpe')?.value == null ? '—' : Number(researchMetric(result,'sharpe').value).toFixed(2)}</em></b><b>最大回撤<em>${researchMetricText(result,'maxDrawdown')}</em></b><b>Sortino<em>${researchMetric(result,'sortino')?.value == null ? '—' : Number(researchMetric(result,'sortino').value).toFixed(2)}</em></b><b>Calmar<em>${researchMetric(result,'calmar')?.value == null ? '—' : Number(researchMetric(result,'calmar').value).toFixed(2)}</em></b><b>成交次数<em>${tradeCount == null ? '—' : esc(tradeCount)}</em></b><b>期末总权益<em>${moneyText(result.endingEquity, '')}</em></b><b>期末现金<em>${moneyText(result.endingCash, '')}</em></b><b>持仓市值<em>${moneyText(result.endingPositionValue, '')}</em></b><b>总费用/滑点<em>${moneyText(result.totalFees, '')} / ${moneyText(result.totalSlippage, '')}</em></b></div><p class="form-hint">${esc(benchmarkNote)}</p><p class="form-hint">数据：${esc(snapshot.startDate || '—')} 至 ${esc(snapshot.endDate || '—')} · ${esc(snapshot.instrumentCount ?? 0)} 只股票 · 来源：${esc(snapshot.preferredSource || result.source || '百度页面采集优先')} · 价格口径：${esc(snapshot.priceBasis || 'unknown')} · 版本 v${esc(result.calculationVersion || 1)}</p><h4 class="trade-detail-title">总权益资金曲线</h4>${curveHtml}<div class="research-segments">${segmentHtml}</div><h4 class="trade-detail-title">组合持仓明细</h4>${holdingHtml}<p class="form-hint">研究模拟，不是实际基金业绩；仅使用本地日 K，信号收盘形成并按下一交易日开盘模拟成交，不代表未来收益。</p>`;
  }
  function setResearchMode(mode) {
    const portfolio = mode === 'portfolio' || mode === 'validation';
    document.querySelectorAll('[data-research-mode]').forEach(button => { const activeMode = button.dataset.researchMode === mode; button.classList.toggle('is-active', activeMode); button.setAttribute('aria-selected', String(activeMode)); });
    if ($('portfolio-research-panel')) $('portfolio-research-panel').hidden = !portfolio;
    document.querySelectorAll('.backtest-panel .backtest-form,.backtest-panel .strategy-parameters,.backtest-panel .backtest-advanced,.backtest-panel .backtest-status,.backtest-panel .backtest-result,.backtest-panel .batch-backtest-result,.backtest-panel .backtest-history,.backtest-panel .backtest-actions').forEach(node => { node.hidden = portfolio; });
    if ($('portfolio-research-status') && mode === 'validation') $('portfolio-research-status').textContent = '数据校验模式：检查所选币种已启用股票的日 K 样本、来源和复权口径。';
  }
  async function runPortfolioResearch() {
    const node = $('portfolio-research-status'); const resultNode = $('portfolio-research-result'); const token = ++backtestRequestToken; const id = Number(active?.id || 0); const generation = Number(activeGeneration || window.__activeGeneration || 0);
    try { if (node) node.textContent = '正在使用所选币种已启用股票的日 K 构建组合研究…'; const result = await invoke('run_portfolio_research', { request: portfolioResearchRequest() }); if (token !== backtestRequestToken || Number(active?.id || 0) !== id || (generation && Number(activeGeneration || window.__activeGeneration || 0) !== generation)) return; renderPortfolioResearch(result); if (node) node.textContent = `组合研究完成：${result.dataSnapshot?.startDate || '—'} 至 ${result.dataSnapshot?.endDate || '—'}`; }
    catch (error) { if (node) node.textContent = `组合研究失败：${error}`; if (resultNode && !resultNode.innerHTML.trim()) resultNode.innerHTML = `<p class="error-text">${esc(error)}</p>`; }
  }
  async function validatePortfolioResearch() {
    const node = $('portfolio-research-status'); try { if (node) node.textContent = '正在校验本地日 K 和数据来源…'; const report = await invoke('validate_research_data', { request: portfolioResearchRequest() }); const bad = (report.rows || []).filter(row => row.status !== 'valid'); if (node) node.textContent = `校验完成：${report.rows?.length || 0} 只股票，${bad.length ? `${bad.length} 只需要关注` : '未发现明显问题'}`; renderPortfolioResearch({ dataSnapshot: { instrumentCount: report.rows?.length || 0, priceBasis: report.priceBasis || 'unknown' }, warnings: bad.map(row => `${row.name}：${row.status}，样本 ${row.sampleCount || 0}，来源 ${row.source || '未知'}`), metrics: [], segments: [], holdings: [], calculationVersion: report.calculationVersion }); } catch (error) { if (node) node.textContent = `数据校验失败：${error}`; } }

  document.addEventListener('click', event => { if (!event.target.closest('[data-indicator-card]')) document.querySelectorAll('.indicator-card.is-pinned').forEach(x => x.classList.remove('is-pinned')); });
  document.addEventListener('DOMContentLoaded', () => {
    if (!$('instrument-list')) return;
    $('instrument-search').addEventListener('input', e => { filter = e.target.value; renderList(); });
    $('add-instrument').onclick = () => openStockDialog();
    $('open-settings').onclick = openDataDialog; $('save-alert-profile').onclick = saveAlertProfile;
    $('settings-form').addEventListener('submit', saveStock);
    $('settings-close').onclick = $('settings-cancel').onclick = () => closeDialog($('settings-dialog'));
    $('data-close').onclick = () => closeDialog($('data-dialog'));
    $('import-series').onclick = async () => {
      try { if (!active) throw new Error('请先添加或选择股票'); const file = $('series-file').files[0]; if (!file) throw new Error('请选择行情 JSON 文件'); if (file.size > 16 * 1024 * 1024) throw new Error('文件不能超过 16 MB'); const input = JSON.parse(await file.text()); const batches = Array.isArray(input.series) ? input.series : [input]; let count = 0; for (const data of batches) { const result = await window.StockPlatform.request(`api/instruments/${active.id}/series`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) }); count += result.imported; } showHint(`已导入 ${count} 根 K 线`); window.__stockChartReload?.(); window.__runAnalysis?.(); loadRealtimePanel(); } catch (e) { showHint(`导入失败：${e}`, true); }
    };
    $('export-series').onclick = () => { if (active) window.StockPlatform.download(`api/instruments/${active.id}/export`); };
    $('backup-db').onclick = backup; $('restore-db').onclick = restore; $('delete-stock-data').onclick = clearData; $('cleanup-expired-data').onclick = cleanupExpiredData; $('check-db-integrity').onclick = checkDatabaseIntegrity; $('export-diagnostics').onclick = exportDiagnostics;
    try {
      indicatorPanelCollapsed = localStorage.getItem('indicatorPanelCollapsed') === '1';
      chartPanelCollapsed = localStorage.getItem('priceChartCollapsed') === '1';
    } catch (_) { indicatorPanelCollapsed = false; chartPanelCollapsed = false; }
    applyIndicatorPanelState(); applyChartPanelState();
    $('toggle-indicator-panel').onclick = () => { indicatorPanelCollapsed = !indicatorPanelCollapsed; try { localStorage.setItem('indicatorPanelCollapsed', indicatorPanelCollapsed ? '1' : '0'); } catch (_) {} applyIndicatorPanelState(); };
    const colorLegendTrigger = $('color-legend-trigger');
    const colorLegendPopover = $('color-legend-popover');
    if (colorLegendTrigger && colorLegendPopover) {
      const positionColorLegend = () => {
        if (!colorLegendPopover.classList.contains('is-open')) return;
        // The center workspace is its own scroll container. Use a fixed
        // viewport position so the legend cannot be clipped by that scroller
        // or covered by the analysis panel below it.
        if (window.innerWidth <= 620) {
          colorLegendPopover.style.left = '14px';
          colorLegendPopover.style.right = '14px';
          colorLegendPopover.style.top = '110px';
          colorLegendPopover.style.bottom = 'auto';
          colorLegendPopover.style.width = 'auto';
          return;
        }
        colorLegendPopover.style.right = 'auto';
        colorLegendPopover.style.bottom = 'auto';
        colorLegendPopover.style.width = '';
        const triggerRect = colorLegendTrigger.getBoundingClientRect();
        const popoverRect = colorLegendPopover.getBoundingClientRect();
        const margin = 14;
        const gap = 9;
        const left = Math.max(margin, Math.min(triggerRect.left, window.innerWidth - popoverRect.width - margin));
        const below = triggerRect.bottom + gap;
        const above = triggerRect.top - popoverRect.height - gap;
        const top = below + popoverRect.height <= window.innerHeight - margin || above < margin ? below : above;
        colorLegendPopover.style.left = `${Math.round(left)}px`;
        colorLegendPopover.style.top = `${Math.round(Math.max(margin, top))}px`;
      };
      const setColorLegend = open => {
        colorLegendPopover.classList.toggle('is-open', open);
        colorLegendTrigger.setAttribute('aria-expanded', String(open));
        if (open) requestAnimationFrame(positionColorLegend);
      };
      const closeColorLegend = () => setColorLegend(false);
      colorLegendTrigger.onclick = event => {
        event.stopPropagation();
        setColorLegend(!colorLegendPopover.classList.contains('is-open'));
      };
      colorLegendTrigger.addEventListener('keydown', event => { if (event.key === 'Escape') { closeColorLegend(); colorLegendTrigger.blur?.(); } });
      window.addEventListener('resize', positionColorLegend);
      window.addEventListener('scroll', positionColorLegend, true);
      document.addEventListener('click', event => { if (!event.target.closest('.color-legend-anchor')) closeColorLegend(); });
    }
    const riskAnchor = document.querySelector('.risk-state-anchor');
    const riskTrigger = $('analysis-risk-label');
    if (riskAnchor && riskTrigger) {
      const setRiskTooltip = open => { riskAnchor.classList.toggle('is-open', open); riskTrigger.setAttribute('aria-expanded', String(open)); $('analysis-risk-tooltip')?.setAttribute('aria-hidden', String(!open)); };
      riskTrigger.onclick = event => { event.stopPropagation(); setRiskTooltip(!riskAnchor.classList.contains('is-open')); };
      riskTrigger.addEventListener('focus', () => setRiskTooltip(true));
      riskTrigger.addEventListener('keydown', event => { if (event.key === 'Escape') { setRiskTooltip(false); riskTrigger.blur?.(); } });
      document.addEventListener('click', event => { if (!event.target.closest('.risk-state-anchor')) setRiskTooltip(false); });
    }
    $('toggle-chart-panel').onclick = () => { chartPanelCollapsed = !chartPanelCollapsed; try { localStorage.setItem('priceChartCollapsed', chartPanelCollapsed ? '1' : '0'); } catch (_) {} applyChartPanelState(true); };
    renderStrategyParameters(); $('strategy-select').onchange = renderStrategyParameters; $('compare-backtests').onclick = renderBacktestComparison; $('calculate-analysis').onclick = () => window.__runAnalysis(); $('run-backtest').onclick = () => window.__runBacktest(); $('scan-strategy-parameters').onclick = () => window.__scanStrategyParameters(); $('run-batch-backtest').onclick = () => window.__runBatchBacktest();
    document.querySelectorAll('[data-research-mode]').forEach(button => button.onclick = () => setResearchMode(button.dataset.researchMode)); $('run-portfolio-research').onclick = runPortfolioResearch; $('validate-research-data').onclick = validatePortfolioResearch; setResearchMode('single');
    $('refresh-research-source').onclick = async () => { try { if ($('portfolio-research-status')) $('portfolio-research-status').textContent = '正在请求当前股票采集行情数据…'; if (active?.id) { const message = await invoke('refresh_instrument', { instrumentId: Number(active.id) }); if ($('portfolio-research-status')) $('portfolio-research-status').textContent = `${message}；数据更新后再运行组合研究`; } } catch (error) { if ($('portfolio-research-status')) $('portfolio-research-status').textContent = `行情采集失败：${error}`; } };
    $('event-filter').onchange = e => { realtimeFilter = e.target.value; renderRealtimeEvents(); };
    $('event-pause').onclick = () => { realtimePaused = !realtimePaused; $('event-pause').textContent = realtimePaused ? '继续滚动' : '暂停滚动'; };
    $('event-clear').onclick = async () => { try { await invoke('clear_analysis_events', { instrumentId: active?.id ?? null }); realtimeEvents = []; renderRealtimeEvents(); } catch (e) { showHint(`清理提醒失败：${e}`, true); } };
    load().then(async () => { await loadRealtimePanel(); await window.__runAnalysis(); });
  });
  if (window.StockPlatform) {
    window.StockPlatform.event.listen('instrument-name-updated', ({ payload }) => {
      if (!payload?.instrument || !active || Number(payload.instrumentId) !== Number(active.id)) return;
      const updated = payload.instrument;
      active = updated;
      instruments = instruments.map(item => Number(item.id) === Number(updated.id) ? updated : item);
      renderActive(updated); renderList();
    });
    window.StockPlatform.event.listen('chart-data-updated', ({ payload }) => { if (active && Number(payload?.instrumentId) === Number(active.id)) { window.__runAnalysis?.(); loadRealtimePanel(); } });
    window.StockPlatform.event.listen('quote-update', ({ payload }) => { if (payload?.instrumentId != null) quoteById.set(Number(payload.instrumentId), payload); renderList(); });
    window.StockPlatform.event.listen('quote-status', ({ payload }) => { if ($('alert-connection-label') && payload) $('alert-connection-label').textContent = `连接状态：${payload.message || payload.state || '未知'}`; });
    window.StockPlatform.event.listen('quote-health', ({ payload }) => { if (!$('alert-connection-label') || !payload) return; const labels = { live: '实时', cached: '缓存', stale: '数据过期', reconnecting: '重连中', blocked: '请求冷却', captcha: '页面验证', partial: '部分行情受限', error: '行情异常', disconnected: '连接断开' }; const session = payload.session ? ` · ${({open:'交易中',break:'午间休市',closed:'休市','pre-market':'盘前','after-hours':'盘后'})[payload.session] || payload.session}` : ''; const quality = payload.missingVolume ? ' · 成交量缺失' : (Number(payload.invalidBars) > 0 ? ` · ${Number(payload.invalidBars)} 条异常 K 线` : ''); const latency = payload.latencyMs != null ? ` · ${payload.latencyMs}ms` : ''; $('alert-connection-label').textContent = `数据源：${labels[payload.state] || payload.state || '未知'}${session}${latency}${quality}`; });
    window.StockPlatform.event.listen('instrument-changed', ({ payload }) => { const i = payload?.instrument || payload; if (i?.id) { stateRevision++; activeGeneration = Number(payload?.generation || (activeGeneration + 1)); window.__activeGeneration = activeGeneration; realtimeRequestToken++; if (payload?.cachedQuote) quoteById.set(Number(i.id), payload.cachedQuote); resetRealtimePanel(); resetAnalysisPanel(); resetBacktestPanel(); renderActive(i); renderList(); load().then(async () => { await loadRealtimePanel(); await window.__runAnalysis(); await loadBacktestHistory(i.id); }); } });
  }
  if (window.StockPlatform) {
    window.StockPlatform.event.listen('realtime-analysis-update', ({payload}) => renderRealtime(payload));
    window.StockPlatform.event.listen('analysis-event', ({payload}) => appendRealtimeEvent(payload));
    window.StockPlatform.event.listen('analysis-alert', ({payload}) => appendRealtimeEvent(payload));
  }
  window.__workbench = { load, select, openStockDialog, loadRealtimePanel };
})();
