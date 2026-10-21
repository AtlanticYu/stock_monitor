(async function(){
  'use strict';
  const $=id=>document.getElementById(id);
  const chart=new window.LocalStockChart.Chart($('stock-chart'),$('chart-details'));
  let period='day',series=null,seriesInstrumentId=null,seq=0,loading=false,lastPoll=0,cachePath='';
  let activeInstrumentId=window.__activeInstrumentId ?? null, activeGeneration=Number(window.__activeGeneration||0);
  const invoke=(name,args)=>window.StockPlatform.core.invoke(name,args);
  const warning=$('chart-warning'),meta=$('chart-meta'),source=$('chart-source');
  const orderBookList=$('order-book-list'), tickTradesList=$('tick-trades-list');
  const detailNumber=(value, digits=2)=>value!=null&&value!==''&&Number.isFinite(Number(value))?Number(value).toLocaleString('zh-CN',{minimumFractionDigits:digits,maximumFractionDigits:digits}):'--';
  const detailVolume=value=>{if(value==null||value==='')return '--';const n=Number(value);if(!Number.isFinite(n))return '--';if(n>=1e8)return `${(n/1e8).toFixed(2)}亿`;if(n>=1e4)return `${(n/1e4).toFixed(2)}万`;return n.toLocaleString('zh-CN',{maximumFractionDigits:0});};
  function setMarketDetailVisibility(){
    const panel=$('market-detail-panel');
    if(!panel)return;
    const visible=period==='intraday';
    panel.hidden=!visible;
    panel.setAttribute('aria-hidden',String(!visible));
    const layout=panel.closest?.('.chart-market-layout');
    if(layout)layout.classList.toggle('without-market-details',!visible);
  }
  function clearMarketDetails(){ renderMarketDetails(null); }
  function renderMarketDetails(payload){
    const levels=Array.isArray(payload?.orderBook)?payload.orderBook:[];
    const trades=Array.isArray(payload?.tickTrades)?payload.tickTrades:[];
    const market=String(payload?.market||window.__lastQuotePayload?.market||'');
    const bookEmpty=market==='hk'?'百度当前未提供港股盘口数据':'百度暂未返回盘口数据';
    const tradesEmpty=market==='hk'?'百度当前未提供港股分笔成交':'百度暂未返回分笔成交';
    if(orderBookList){orderBookList.replaceChildren();const ordered=[...levels].sort((a,b)=>{if(a.side!==b.side)return a.side==='sell'?-1:1;return a.side==='sell'?Number(b.level)-Number(a.level):Number(a.level)-Number(b.level);});if(!ordered.length){const empty=document.createElement('div');empty.className='market-detail-empty';empty.textContent=bookEmpty;orderBookList.append(empty);}else ordered.slice(0,10).forEach(level=>{const row=document.createElement('div');row.className=`order-book-row order-book-${level.side==='sell'?'sell':'buy'}`;const label=document.createElement('span');label.textContent=`${level.side==='sell'?'卖':'买'}${Number(level.level)||''}`;const price=document.createElement('span');price.textContent=detailNumber(level.price);const volume=document.createElement('span');volume.textContent=detailVolume(level.volume);row.append(label,price,volume);orderBookList.append(row);});}
    if(tickTradesList){tickTradesList.replaceChildren();if(!trades.length){const empty=document.createElement('div');empty.className='market-detail-empty';empty.textContent=tradesEmpty;tickTradesList.append(empty);}else trades.slice(0,50).forEach(trade=>{const row=document.createElement('div');const direction=trade.direction==='buy'||trade.direction==='sell'?trade.direction:'unknown';row.className=`tick-trade-row tick-trade-${direction}`;const time=document.createElement('span');time.textContent=String(trade.timeText||trade.time_text||'--');const price=document.createElement('span');price.textContent=detailNumber(trade.price);const volume=document.createElement('span');volume.textContent=detailVolume(trade.volume);const side=document.createElement('span');side.textContent=direction==='buy'?'买入':direction==='sell'?'卖出':'中性';row.append(time,price,volume,side);tickTradesList.append(row);});}
    const updated=payload?.updatedAtMs||payload?.receivedAtMs;const label=updated?`百度 · ${new Date(Number(updated)).toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',second:'2-digit'})}`:'等待数据';['order-book-updated','tick-trades-updated'].forEach(id=>{const node=$(id);if(node)node.textContent=label;});
  }
  window.__renderMarketDetails=renderMarketDetails;
  setMarketDetailVisibility();
  if(window.__lastQuotePayload)renderMarketDetails(window.__lastQuotePayload);
  function showWarning(message){warning.textContent=message||'';warning.hidden=!message;}
  function setSeries(s,reset=true,instrumentId=window.__activeInstrumentId ?? activeInstrumentId){
    if(s)seriesInstrumentId=Number(instrumentId);
    series=s;chart.setData(s,reset);
    setMarketDetailVisibility();
    source.textContent=s?`${s.source} · ${s.bars.length} ${s.period==='intraday'?'个点':'根'}`:'暂无数据';
    meta.textContent=s?`${s.adjustment}。${s.note} · 更新于 ${new Date(Number(s.fetchedAtMs)).toLocaleString('zh-CN')}${cachePath?` · 缓存目录：${cachePath}`:''}`:'暂无数据；请刷新采集，或在设置中导入行情 JSON。';
    $('chart-table-body').replaceChildren();
    if(s)for(const b of s.bars.slice(-30).reverse()){
      const tr=document.createElement('tr');
      const values=[new Intl.DateTimeFormat('zh-CN',{timeZone:(window.__activeMarketTimezone || 'Asia/Hong_Kong'),year:'numeric',month:'2-digit',day:'2-digit',...(period==='intraday'?{hour:'2-digit',minute:'2-digit'}:{})}).format(new Date(b.time*1000)),b.open,b.high,b.low,b.close,b.volume??'--'];
      for(const value of values){const td=document.createElement('td');td.textContent=String(value);tr.append(td);} $('chart-table-body').append(tr);
    }
  }
  async function load(reset=true){
    const request=++seq,requestedPeriod=period;loading=true;$('chart-refresh').disabled=true;
    $('chart-status').textContent='正在获取数据…';showWarning('');
    try {
      if(!window.StockPlatform)throw new Error('Web 服务尚未连接');
      const current = window.__activeInstrumentId || (await invoke('get_active_instrument'))?.id;
      if (!current) { setSeries(null); $('chart-status').textContent = '请点击左侧 + 添加股票'; return; }
      let reply;
      reply=await invoke('load_chart',{instrumentId:current,period:requestedPeriod});
      if(request!==seq)return;
      cachePath=reply.cachePath||cachePath;
      if(reply.marketTimezone)window.__activeMarketTimezone=reply.marketTimezone;
      if(reply.series)setSeries(reply.series,reset,current);
      else if(!series||series.period!==requestedPeriod)setSeries(null);
      showWarning(reply.warning);
      $('chart-status').textContent=reply.series?'已显示本地数据 · 服务端采集':series?'保留上次数据':'尚无行情，请刷新或导入数据';
    }catch(e){if(request===seq){showWarning(String(e));$('chart-status').textContent=series?'加载失败，保留上次数据':'加载失败';}}
    finally{if(request===seq){loading=false;$('chart-refresh').disabled=false;}}
  }
  function setPeriod(value){
    if(period===value)return;period=value;setSeries(null);setMarketDetailVisibility();
    document.querySelectorAll('[data-period]').forEach(b=>{b.setAttribute('aria-pressed',String(b.dataset.period===period));});
    $('chart-type').disabled=period==='intraday';chart.period=period;
    document.querySelectorAll('[data-ma]').forEach(el=>{el.disabled=period==='intraday';});
    load();
  }
  document.querySelectorAll('[data-period]').forEach(b=>b.addEventListener('click',()=>setPeriod(b.dataset.period)));
  $('chart-refresh').addEventListener('click',async()=>{await load(false);try{const message=await invoke('refresh_chart_page');$('chart-status').textContent=message;}catch(e){showWarning(String(e));}});
  $('chart-type').addEventListener('change',e=>{chart.type=e.target.value;chart.draw();});
  document.querySelectorAll('[data-ma]').forEach(el=>el.addEventListener('change',()=>{chart.ma[Number(el.dataset.ma)]=el.checked;chart.draw();}));
  $('chart-zoom-in').addEventListener('click',()=>chart.zoom(.8));
  $('chart-zoom-out').addEventListener('click',()=>chart.zoom(1.2));
  $('chart-reset').addEventListener('click',()=>chart.reset());
  window.__stockChartReload=()=>{clearMarketDetails();if(seriesInstrumentId!==Number(window.__activeInstrumentId ?? activeInstrumentId))setSeries(null);load(true);};
  if(typeof window.addEventListener==='function'){window.addEventListener('instrument-switch-start',event=>{activeInstrumentId=event.detail?.instrumentId??activeInstrumentId;clearMarketDetails();});}
  if(window.StockPlatform){
    await window.StockPlatform.event.listen('instrument-changed',event=>{
      const payload=event.payload||{};
      activeInstrumentId=payload.instrumentId ?? payload.instrument?.id ?? null;
      activeGeneration=Number(payload.generation||0);
      clearMarketDetails();
      window.__stockChartReload();
    });
    function matchesChart(payload){
      const sameInstrument=payload?.instrumentId==null||Number(payload.instrumentId)===Number(activeInstrumentId ?? window.__activeInstrumentId);
      const sourcePeriod=period==='intraday'?'intraday':'day';
      return sameInstrument&&(!payload?.period||payload.period===sourcePeriod);
    }
    await window.StockPlatform.event.listen('chart-data-updated',event=>{if(matchesChart(event.payload))load(false);});
    await window.StockPlatform.event.listen('chart-capture-status',event=>{
      if(typeof event.payload==='string'){showWarning(event.payload);return;}
      if(matchesChart(event.payload))load(false);
    });
    await window.StockPlatform.event.listen('quote-detail-update',event=>{
      const payload=event.payload||{};
      const sameInstrument=payload.instrumentId==null||activeInstrumentId==null||Number(payload.instrumentId)===Number(activeInstrumentId);
      const sameGeneration=!activeGeneration||payload.generation==null||Number(payload.generation)===activeGeneration;
      if(sameInstrument&&sameGeneration)renderMarketDetails(payload);
    });
    await window.StockPlatform.event.listen('quote-update',async()=>{
      if(loading||!['day','intraday'].includes(period))return;
      if(period==='day'&&series&&series.source!=='WebSocket 快照')return; // Historical adjusted candles never mix with raw daily quotes.
      const requestedPeriod=period,request=seq;
      try{const live=await invoke('live_chart',{instrumentId:window.__activeInstrumentId ?? null,period});if(live&&request===seq&&requestedPeriod===period){setSeries(live,false);$('chart-status').textContent=period==='intraday'?'分时已更新 · 按分钟合并真实采样':'快照已更新（非完整历史）';}}catch(e){showWarning(String(e));}
    });
  }
  setMarketDetailVisibility();
  await load();
  // Re-read stored data; the server owns the upstream refresh cadence.
  setInterval(()=>{if(!document.hidden&&!loading&&Date.now()-lastPoll>=60000){lastPoll=Date.now();load(false);}},60000);
})().catch(error=>{
  const warning=document.getElementById('chart-warning');
  warning.hidden=false;warning.textContent=`图表初始化失败：${error}`;
});
