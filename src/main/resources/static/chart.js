/* Local Canvas chart engine. No remote scripts, screenshots, or iframe charts. */
(function(root) {
  'use strict';
  // Palette mirrors styles.css; red/green retain their existing financial meaning.
  const UP='#c04450', DOWN='#087e67', FLAT='#6e7e8e', LINE='#236782', GRID='#e4ebf0', TEXT='#607386';
  const MA_COLORS=['#97640b','#326bb0','#8554a2'];
  function movingAverage(bars, length) {
    let sum=0;
    return bars.map((b,i)=>{sum+=b.close;if(i>=length)sum-=bars[i-length].close;return i>=length-1?sum/length:null;});
  }
  function visibleRange(total,count,end=total) {
    end=Math.max(Math.min(total,Math.max(1,count)),Math.min(total,end));
    return {start:Math.max(0,end-count),end};
  }
  function priceRange(bars,extra=[]) {
    let low=Infinity,high=-Infinity;
    for(const bar of bars){low=Math.min(low,bar.low);high=Math.max(high,bar.high);}
    for(const value of extra){if(Number.isFinite(value)){low=Math.min(low,value);high=Math.max(high,value);}}
    if(!Number.isFinite(low)||!Number.isFinite(high))return {min:0,max:1};
    const pad=Math.max((high-low)*.09,Math.abs(high)*.001,0.001);
    return {min:low-pad,max:high+pad};
  }
  const validReference=n=>Number.isFinite(n)&&n>0;
  function priceChange(price,previousClose) {
    if(!validReference(price)||!validReference(previousClose))return null;
    const raw=price-previousClose;
    const amount=Math.abs(raw)<=Math.max(price,previousClose)*1e-10?0:raw;
    return {amount,percent:amount/previousClose*100,direction:Math.sign(amount)};
  }
  const directionColor=direction=>direction>0?UP:direction<0?DOWN:FLAT;
  function intradayRange(bars,previousClose) {
    if(!validReference(previousClose))return priceRange(bars);
    // Keep yesterday's close at zero in the centre; panning must not change the reference.
    let distance=Math.max(previousClose*.002,.001);
    for(const b of bars)distance=Math.max(distance,Math.abs(b.close-previousClose));
    const half=distance*1.09;
    return {min:previousClose-half,max:previousClose+half};
  }
  function intradayVolumeDirection(bars,index,previousClose) {
    // Samples are not OHLC candles (open == close). Compare to the preceding real sample,
    // including the sample just outside the viewport; first sample compares to yesterday.
    return priceChange(bars[index]?.close,index>0?bars[index-1].close:previousClose)?.direction??0;
  }
  function intradaySegments(a,b,previousClose) {
    if(!validReference(previousClose))return [{from:a,to:b,color:LINE}];
    const da=a.price-previousClose,db=b.price-previousClose;
    if(da*db<0) {
      const cross={x:a.x+(b.x-a.x)*(-da)/(db-da),price:previousClose};
      return [{from:a,to:cross,color:directionColor(Math.sign(da))},{from:cross,to:b,color:directionColor(Math.sign(db))}];
    }
    return [{from:a,to:b,color:directionColor(priceChange((a.price+b.price)/2,previousClose)?.direction??0)}];
  }
  const number=(n,d=3)=>Number.isFinite(n)?n.toLocaleString('en-US',{minimumFractionDigits:d,maximumFractionDigits:d}):'--';
  const signed=(n,d=3)=>{
    if(!Number.isFinite(n))return '--';
    const rounded=Number(n.toFixed(d));
    return `${rounded>0?'+':''}${number(rounded===0?0:rounded,d)}`;
  };
  const volume=n=>n==null?'--':n>=1e8?`${(n/1e8).toFixed(2)}亿`:n>=1e4?`${(n/1e4).toFixed(2)}万`:number(n,0);
  function dateLabel(t,intraday=false) {
    const opts=intraday?{hour:'2-digit',minute:'2-digit',hour12:false}:{year:'numeric',month:'2-digit',day:'2-digit'};
    return new Intl.DateTimeFormat('zh-CN',{...opts,timeZone:(root.__activeMarketTimezone || 'Asia/Hong_Kong')}).format(new Date(t*1000));
  }
  class Chart {
    constructor(canvas,detail) {
      this.canvas=canvas;this.ctx=canvas.getContext('2d');this.detail=detail;
      this.bars=[];this.count=80;this.end=0;this.hover=null;this.type='candles';this.period='day';this.ma=[true,true,true];
      this.observer=new ResizeObserver(()=>this.draw());this.observer.observe(canvas.parentElement);
      canvas.addEventListener('pointerdown',e=>{this.drag={x:e.clientX,end:this.end};canvas.setPointerCapture(e.pointerId);});
      canvas.addEventListener('pointerup',()=>{this.drag=null;});
      canvas.addEventListener('pointercancel',()=>{this.drag=null;});
      canvas.addEventListener('pointerleave',()=>{if(!this.drag){this.hover=null;this.draw();}});
      canvas.addEventListener('pointermove',e=>{
        const rect=canvas.getBoundingClientRect(),x=e.clientX-rect.left;
        if(this.drag&&this.geometry){const steps=Math.round((e.clientX-this.drag.x)/this.geometry.step);this.end=Math.max(Math.min(this.count,this.bars.length),Math.min(this.bars.length,this.drag.end-steps));}
        if(this.geometry){this.hover=Math.max(this.geometry.start,Math.min(this.geometry.end-1,this.geometry.start+Math.floor((x-this.geometry.left)/this.geometry.step)));}
        this.draw();
      });
      canvas.addEventListener('dblclick',()=>this.reset());
      canvas.addEventListener('keydown',e=>{
        if(['ArrowLeft','ArrowRight','+','-','Home','End'].includes(e.key))e.preventDefault();
        if(e.key==='+'||e.key==='=')this.zoom(.8);else if(e.key==='-')this.zoom(1.2);
        else if(e.key==='Home'){this.end=Math.min(this.count,this.bars.length);this.draw();}
        else if(e.key==='End')this.reset();
        else if(e.key==='ArrowLeft'||e.key==='ArrowRight'){this.end=Math.min(this.bars.length,Math.max(Math.min(this.count,this.bars.length),this.end+(e.key==='ArrowLeft'?-5:5)));this.draw();}
      });
    }
    setData(series,reset=true) {
      const oldLength=this.bars.length,atEnd=this.end===oldLength,showAll=this.count===oldLength;
      this.bars=series?.bars||[];this.period=series?.period||'day';this.previousClose=validReference(series?.previousClose)?series.previousClose:null;this.hover=null;
      this.averages=[5,10,20].map(n=>movingAverage(this.bars,n));
      if(reset||oldLength===0){this.count=this.period==='intraday'?this.bars.length:Math.min(80,this.bars.length);this.end=this.bars.length;}
      else if(atEnd){this.end=this.bars.length;if(this.period==='intraday'&&showAll)this.count=this.bars.length;}
      this.draw();
    }
    reset(){this.count=Math.min(this.period==='intraday'?this.bars.length:80,this.bars.length);this.end=this.bars.length;this.hover=null;this.draw();}
    zoom(factor){if(!this.bars.length)return;this.count=Math.max(Math.min(5,this.bars.length),Math.min(this.bars.length,Math.round(this.count*factor)));this.hover=null;this.draw();}
    draw(){
      const rect=this.canvas.getBoundingClientRect(),w=rect.width,h=rect.height,dpr=window.devicePixelRatio||1;
      if(w<20||h<20)return;
      this.canvas.width=Math.round(w*dpr);this.canvas.height=Math.round(h*dpr);
      const c=this.ctx;c.setTransform(dpr,0,0,dpr,0,0);c.fillStyle='#ffffff';c.fillRect(0,0,w,h);c.font='11px ui-monospace, monospace';
      if(!this.bars.length){this.geometry=null;c.fillStyle=TEXT;c.textAlign='center';c.fillText('暂无价格走势 · 请刷新数据或在设置中导入行情',w/2,h/2);this.detail.textContent='开 --　高 --　低 --　收 --　量 --';return;}
      const range=visibleRange(this.bars.length,Math.max(1,this.count),this.end);this.end=range.end;
      const intraday=this.period==='intraday',hasReference=intraday&&validReference(this.previousClose);
      const bars=this.bars.slice(range.start,range.end),left=intraday?76:12,right=w-76,top=22,bottom=h*.67,volTop=h*.76,volBottom=h-30;
      const step=(right-left)/bars.length,extra=[];
      if(this.period!=='intraday')this.averages.forEach((m,i)=>{if(this.ma[i])extra.push(...m.slice(range.start,range.end).filter(v=>v!==null));});
      const prices=intraday?intradayRange(this.bars,this.previousClose):priceRange(bars,extra),y=p=>bottom-(p-prices.min)/(prices.max-prices.min)*(bottom-top),x=i=>left+(i+.5)*step;
      this.geometry={...range,left,step};c.lineWidth=1;c.textAlign='left';
      for(let i=0;i<=4;i++){
        const yy=top+(bottom-top)*i/4,price=prices.max-(prices.max-prices.min)*i/4;
        c.strokeStyle=GRID;c.beginPath();c.moveTo(left,yy);c.lineTo(right,yy);c.stroke();
        c.fillStyle=hasReference?directionColor(priceChange(price,this.previousClose)?.direction??0):TEXT;
        if(intraday){
          c.textAlign='right';c.fillText(number(price),left-9,yy+4);
          c.textAlign='left';c.fillText(hasReference?`${signed(priceChange(price,this.previousClose).percent,2)}%`:'--%',right+9,yy+4);
        }else c.fillText(number(price),right+9,yy+4);
      }
      if(intraday){
        c.fillStyle=TEXT;c.textAlign='left';
        c.fillText(hasReference?`昨收 ${number(this.previousClose)} · 红涨/绿跌（相对昨收）`:'昨收未知 · 不计算涨跌幅',left,13);
        if(hasReference){c.strokeStyle=TEXT;c.setLineDash([5,4]);c.beginPath();c.moveTo(left,y(this.previousClose));c.lineTo(right,y(this.previousClose));c.stroke();c.setLineDash([]);}
      }
      const volMax=Math.max(1,...bars.map(b=>b.volume||0));
      bars.forEach((b,i)=>{if(b.volume!=null){c.fillStyle=intraday?directionColor(intradayVolumeDirection(this.bars,range.start+i,this.previousClose)):(b.close>=b.open?UP:DOWN);c.globalAlpha=.65;const vh=b.volume/volMax*(volBottom-volTop);c.fillRect(x(i)-Math.min(16,step*.65)/2,volBottom-vh,Math.max(1,Math.min(16,step*.65)),vh);}});c.globalAlpha=1;
      c.fillStyle=TEXT;c.fillText(bars.some(b=>b.volume!=null)?`成交量（股） ${volume(volMax)}${intraday?' · 量柱：邻点涨跌':''}`:'成交量：当前数据源未提供',left,volTop-8);
      c.save();c.beginPath();c.rect(left,top,right-left,bottom-top);c.clip();
      if(this.type==='line'||intraday){
        c.lineWidth=1.8;
        if(intraday){
          for(let i=1;i<bars.length;i++)for(const segment of intradaySegments({x:x(i-1),price:bars[i-1].close},{x:x(i),price:bars[i].close},this.previousClose)){
            c.strokeStyle=segment.color;c.beginPath();c.moveTo(segment.from.x,y(segment.from.price));c.lineTo(segment.to.x,y(segment.to.price));c.stroke();
          }
        }else{c.strokeStyle=LINE;c.beginPath();bars.forEach((b,i)=>i?c.lineTo(x(i),y(b.close)):c.moveTo(x(i),y(b.close)));c.stroke();}
        if(bars.length===1){c.fillStyle=hasReference?directionColor(priceChange(bars[0].close,this.previousClose).direction):LINE;c.beginPath();c.arc(x(0),y(bars[0].close),3,0,Math.PI*2);c.fill();}
      }else bars.forEach((b,i)=>{c.strokeStyle=c.fillStyle=intraday?directionColor(intradayVolumeDirection(this.bars,range.start+i,this.previousClose)):(b.close>=b.open?UP:DOWN);c.lineWidth=1;c.beginPath();c.moveTo(x(i),y(b.high));c.lineTo(x(i),y(b.low));c.stroke();const bw=Math.max(1,Math.min(16,step*.65));c.fillRect(x(i)-bw/2,Math.min(y(b.open),y(b.close)),bw,Math.max(1,Math.abs(y(b.open)-y(b.close))));});
      if(this.period!=='intraday')this.averages.forEach((m,j)=>{if(!this.ma[j])return;c.strokeStyle=MA_COLORS[j];c.lineWidth=1.2;c.beginPath();let started=false;for(let i=range.start;i<range.end;i++){if(m[i]==null)continue;if(started)c.lineTo(x(i-range.start),y(m[i]));else{c.moveTo(x(i-range.start),y(m[i]));started=true;}}c.stroke();});
      c.restore();
      c.textAlign='center';c.fillStyle=TEXT;
      const ticks=Math.min(4,bars.length);for(let j=0;j<ticks;j++){const i=Math.round(j*(bars.length-1)/Math.max(1,ticks-1));c.fillText(dateLabel(bars[i].time,this.period==='intraday'),Math.min(right-28,Math.max(left+34,x(i))),h-9);}
      const selected=this.hover==null?range.end-1:Math.max(range.start,Math.min(range.end-1,this.hover));
      const b=this.bars[selected];
      if(this.hover!=null){c.strokeStyle='#8498aa';c.setLineDash([3,4]);c.beginPath();c.moveTo(x(selected-range.start),top);c.lineTo(x(selected-range.start),volBottom);c.moveTo(left,y(b.close));c.lineTo(right,y(b.close));c.stroke();c.setLineDash([]);}
      const maText=this.period==='intraday'?'':this.averages.map((m,i)=>this.ma[i]?` MA${[5,10,20][i]} ${number(m[selected])}`:'').join('');
      const change=intraday?priceChange(b.close,this.previousClose):null;
      this.detail.textContent=intraday?`${dateLabel(b.time,true)}　价格 ${number(b.close)}　涨跌 ${signed(change?.amount)}　涨幅 ${change?`${signed(change.percent,2)}%`:'--'}　昨收 ${number(this.previousClose)}　量 ${volume(b.volume)}`:`${dateLabel(b.time)}　开 ${number(b.open)}　高 ${number(b.high)}　低 ${number(b.low)}　收 ${number(b.close)}　量 ${volume(b.volume)}${maText}`;
    }
  }
  const api={Chart,movingAverage,visibleRange,priceRange,priceChange,intradayRange,intradayVolumeDirection,intradaySegments};
  if(typeof module!=='undefined'&&module.exports)module.exports=api;
  else root.LocalStockChart=api;
})(typeof window==='undefined'?globalThis:window);
