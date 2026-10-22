// Browser contract: exact Supabase schema, user bearer headers, and fragmented SSE frames.
const fs=require('fs'),vm=require('vm'),assert=require('assert');
const flush=()=>new Promise(resolve=>setImmediate(resolve));
(async()=>{
 let clientOptions;
 const config={mode:'supabase',url:'https://example.invalid/supabase',anonKey:'public-key',schema:'u_yuzhengxin'};
 const storageContext={URL,console,document:{baseURI:'https://example.invalid/zhoubao/'},fetch:async()=>({ok:true,json:async()=>config}),window:{StockSupabaseSDK:{createClient:(url,key,options)=>{clientOptions={url,key,options};return{};}}}};
 vm.runInNewContext(fs.readFileSync('src/main/resources/static/storage-client.js','utf8'),storageContext);
 await storageContext.window.StockStorageReady;
 assert.strictEqual(clientOptions.options.db.schema,'u_yuzhengxin');
 assert.strictEqual(clientOptions.url,config.url);
 const calls=[],updates=[];let pendingRead;
 const chunk='event:quote-update\r\ndata:{"instrumentId":7,"price":100}\r\n\r\n';
 const chunks=[chunk.slice(0,25),chunk.slice(25)];
 const context={console,URL,Headers,TextDecoder,AbortController,setTimeout:()=>0,location:{href:''},
  document:{baseURI:'https://example.invalid/zhoubao/',getElementById:()=>({})},
  window:{addEventListener(){},StockStorageReady:Promise.resolve({mode:'supabase',db:{auth:{getSession:async()=>({data:{session:{access_token:'alice-token'}}})}}})},
  fetch:async(url,options)=>{
   calls.push({url,options});
   if(url.endsWith('/api/session'))return{ok:true,status:200,json:async()=>({username:'alice'})};
   if(url.endsWith('/api/events'))return{ok:true,status:200,body:{getReader:()=>({read:async()=>chunks.length?{value:new TextEncoder().encode(chunks.shift()),done:false}:new Promise(resolve=>{pendingRead=resolve;})})}};
   return{ok:true,status:200,text:async()=> '[]'};
  }
 };
 vm.runInNewContext(fs.readFileSync('src/main/resources/static/platform.js','utf8'),context);
 await context.window.StockPlatform.core.invoke('list_instruments');
 await context.window.StockPlatform.event.listen('quote-update',event=>updates.push(event.payload));
 await flush();await flush();
 assert.strictEqual(updates.length,1);assert.strictEqual(updates[0].price,100);
 for(const call of calls){assert(call.url.startsWith('https://example.invalid/zhoubao/'));assert.strictEqual(call.options.headers.get('Authorization'),'Bearer alice-token');assert(!call.url.includes('alice-token'));}
 assert.strictEqual(calls.find(c=>c.url.includes('/commands/')).options.method,'POST');
 assert.strictEqual(context.location.href,'');
 console.log('PASS Supabase browser: explicit schema, refreshed user bearer on API/SSE, fragmented event parsing, no tokens in URLs');
})().catch(error=>{console.error(error);process.exitCode=1;});
