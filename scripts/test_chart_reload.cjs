// Observable regression: repeated reloads keep the current chart, while a stock switch clears it.
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const elements = new Map(), events = new Map(), windowEvents = new Map(), draws = [];
function element(id) {
  if (!elements.has(id)) elements.set(id, {textContent:'', hidden:false, dataset:{}, setAttribute(){}, addEventListener(){}, replaceChildren(){}, append(){}});
  return elements.get(id);
}
let pending, fail = false;
const candle = id => ({period:'day', source:'test', adjustment:'unknown', note:'', fetchedAtMs:1, bars:[{time:1792512000,open:id,high:id,low:id,close:id,volume:1}]});
const context = {
  console, Intl, Date, setInterval(){},
  document:{getElementById:element,createElement:()=>({append(){}}),querySelectorAll:()=>[]},
  window:{__activeInstrumentId:1,addEventListener(name,fn){windowEvents.set(name,fn);},
    LocalStockChart:{Chart:class{constructor(){this.ma=[];}setData(series){draws.push(series);}draw(){}zoom(){}reset(){}}},
    StockPlatform:{event:{async listen(name,fn){events.set(name,fn);}},core:{async invoke(name,args){
      assert.strictEqual(name,'load_chart');
      if (pending) await pending;
      if (fail) throw new Error('upstream unavailable');
      return {series:candle(args.instrumentId),marketTimezone:'Asia/Hong_Kong'};
    }}}
  }
};
const flush = () => new Promise(resolve=>setImmediate(resolve));
(async()=>{
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../src/main/resources/static/chart-app.js'),'utf8'),context);
  await flush();
  assert.strictEqual(draws.at(-1).bars[0].close,1);
  let release;
  pending = new Promise(resolve=>{release=resolve;});
  context.window.__stockChartReload();
  assert(draws.at(-1), 'An in-flight reload of the same stock must keep its chart');
  release();pending=null;await flush();
  fail=true;context.window.__stockChartReload();await flush();
  assert.strictEqual(draws.at(-1).bars[0].close,1,'A failed reload must keep existing prices');
  assert(element('chart-warning').textContent.includes('upstream unavailable'));
  fail=false;pending = new Promise(resolve=>{release=resolve;});
  context.window.__activeInstrumentId=2;
  windowEvents.get('instrument-switch-start')({detail:{instrumentId:2}});
  context.window.__stockChartReload();
  assert.strictEqual(draws.at(-1),null,'Switching stocks must immediately clear the previous stock chart');
  release();pending=null;await flush();
  assert.strictEqual(draws.at(-1).bars[0].close,2);
  const count=draws.length;
  events.get('chart-data-updated')({payload:{instrumentId:1,period:'day'}});await flush();
  assert.strictEqual(draws.length,count,'Updates for another stock must not replace the active chart');
  console.log('PASS chart reload: current chart retained, failed reload retained, stock switch clears, foreign events ignored');
})().catch(error=>{console.error(error);process.exitCode=1;});
