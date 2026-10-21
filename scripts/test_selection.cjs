// Delayed startup snapshots must not overwrite a newer selection, including a round trip to the same stock.
const assert=require('assert'),fs=require('fs'),path=require('path'),vm=require('vm');
const html=fs.readFileSync(path.join(__dirname,'../src/main/resources/static/index.html'),'utf8');
const inline=[...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match=>match[1]).find(source=>source.includes('async function initialize'));
const flush=()=>new Promise(resolve=>setImmediate(resolve));
async function verifySelection(id,generation,price){
 const elements=new Map(),events=new Map();
 function element(id){if(!elements.has(id))elements.set(id,{textContent:'',hidden:false,dataset:{},className:'',classList:{add(){},remove(){},toggle(){}},addEventListener(){}});return elements.get(id);}
 let completeStartup;
 const snapshot=new Promise(resolve=>{completeStartup=resolve;});
 const context={console,Date,Number,document:{querySelector:element,body:{classList:{remove(){},toggle(){}}}},window:{
  StockPlatform:{event:{async listen(name,fn){events.set(name,fn);}},core:{async invoke(name){
   if(name==='web_panel_state')return {};
   if(name==='start_monitor')return snapshot;
   throw new Error(name);
  }}},addEventListener(){}
 }};
 vm.runInNewContext(inline,context);await flush();
 context.window.__activeInstrumentId=id;
 events.get('instrument-changed')({payload:{instrumentId:id,generation,cachedQuote:{instrumentId:id,price,ratio:'-0.8',receivedAtMs:200}}});
 completeStartup({quote:{instrumentId:1,price:'216.47',receivedAtMs:100},status:{instrumentId:1,generation:1,state:'cached'}});
 await flush();
 assert.strictEqual(context.window.__activeInstrumentId,id);
 assert.strictEqual(context.window.__activeGeneration,generation);
 assert.strictEqual(element('#price').textContent,price);
}
(async()=>{
 assert(inline);
 await verifySelection(2,2,'26.84');
 await verifySelection(1,3,'222.00');
 console.log('PASS selection: delayed startup cannot override another stock or a newer selection of the same stock');
})().catch(error=>{console.error(error);process.exitCode=1;});
