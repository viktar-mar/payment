import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {Readable} from 'node:stream';
import {readFileSync} from 'node:fs';
const subject=await import('./stripe-manual-form-gateway.mjs').catch(()=>({}));
const ORIGIN='http://127.0.0.1:18083',ASSETS='http://127.0.0.1:18081',TOKEN='private_session_sentinel_012345678901234567';
const turn=async()=>{for(let n=0;n<20;n++)await Promise.resolve();await new Promise(setImmediate);};
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
const ready=()=>({version:1,binding:'BOUND',capability_started:true,capability_ready:true,loader_started:true,loader_ready:true,
  element_created:true,mount_called:true,mount_returned:true,ready_seen:true,loaderror_seen:false,view_completed:true,
  widget_completed:true,confirmation_blocked:false,catchup:'RESOLVED',operation:'COMPLETED',widget_state:'ready',
  element_state:'FRAME',pay_state:'ENABLED',pay_visible:true,bucket:'BEFORE_15S'});
const pending=()=>({...ready(),ready_seen:false,view_completed:false,widget_completed:false,catchup:'NOT_STARTED',operation:'PENDING',widget_state:'loading',pay_state:'ABSENT',pay_visible:false,bucket:'AFTER_15S'});
const evidence={loaderstart_seen:true,host_box:'POSITIVE_AREA',frame_box:'POSITIVE_AREA',host_visibility:'VISIBLE',frame_visibility:'VISIBLE',frame_load:'SEEN',csp_observation:'OBSERVED',csp_enforced_mask:2,csp_report_only_mask:4};
const wire=sample=>JSON.stringify({version:2,sample,evidence});
const validSession=()=>({version:2,apiBaseUrl:'http://127.0.0.1:18080',siteKey:'site-test',priceKey:'price-test',checkoutId:'11111111-2222-4333-8444-555555555555',
  checkoutToken:TOKEN,expiresAt:'2099-01-01T00:00:00.000Z',providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',
  state:{checkoutId:'11111111-2222-4333-8444-555555555555',paymentId:'22222222-2222-4333-8444-555555555555',amountMinor:2000,currency:'EUR',status:'REQUIRES_PAYMENT_METHOD',expiresAt:'2099-01-01T00:00:00.000Z',
    price:{priceId:'33333333-2222-4333-8444-555555555555',priceKey:'price-test',version:1,amountMinor:2000,currency:'EUR',taxMode:'NONE'}}});
class Reply extends EventEmitter {
  headers={};statusCode=200;chunks=[];writableFinished=false;destroyed=false;hold=false;
  setHeader(key,value){this.headers[key.toLowerCase()]=value;}
  end(value){if(value)this.chunks.push(Buffer.from(value));if(!this.hold){this.writableFinished=true;this.emit('finish');}}
  destroy(){this.destroyed=true;if(!this.hold)this.emit('close');}
  get text(){return Buffer.concat(this.chunks).toString();}
}
class Socket extends EventEmitter {destroyed=false;hold=false;destroy(){this.destroyed=true;if(!this.hold)this.emit('close');}}
function fixture({listenPending=false,closePending=false,fetcher,deadline=901000}={}) {
  let now=1000,handler;const timers=new Map(),calls=[],server=new EventEmitter();let count=0;
  server.listen=options=>{calls.push(['listen',options]);if(!listenPending)queueMicrotask(()=>server.emit('listening'));return server;};
  server.close=callback=>{calls.push(['close']);server.closedCallback=callback;if(!closePending)queueMicrotask(()=>{server.emit('close');callback();});};
  const upstream=async(url,options)=>{calls.push(['fetch',url,options]);if(fetcher)return fetcher(url,options);
    const html=url.endsWith('.html');return new Response(html?'<html><script type="module" src="/assets/manual-abc.js"></script></html>':'export default 1',
      {headers:{'content-type':html?'text/html':'application/javascript'}});};
  const dependencies={createServer:callback=>{calls.push(['createServer']);handler=callback;return server;},fetch:upstream,
    randomBytes:size=>{calls.push(['random',size]);return Buffer.alloc(size,17);},now:()=>now,
    setTimeout:(callback,ms)=>{const id=++count;timers.set(id,{callback,at:now+ms});return id;},clearTimeout:id=>timers.delete(id)};
  const session=validSession();
  const controller=new AbortController(),limits={deadline,signal:controller.signal};
  assert.equal(typeof subject.createManualFormGateway,'function');
  const gateway=subject.createManualFormGateway({origin:ORIGIN,assetEndpoint:ASSETS,session},limits,dependencies);
  const request=(method,path,{headers={},body=method==='POST'?'{}':'',hold=false,stream,rawHeaders}={})=>{
    const req=stream??Readable.from(body?[Buffer.from(body)]:[]);req.method=method;req.url=path;
    req.headers={host:'127.0.0.1:18083',...headers};req.rawHeaders=rawHeaders??Object.entries(req.headers).flatMap(([key,value])=>[key,value]);
    const res=new Reply();res.hold=hold;handler(req,res);return {req,res};
  };
  const send=async(...args)=>{const pair=request(...args);await turn();return pair.res;};
  const advance=async time=>{now=time;for(let n=0;n<50;n++){
    const due=[...timers].filter(([,timer])=>timer.at<=now);if(!due.length)break;
    for(const [id,timer]of due){timers.delete(id);timer.callback();}await turn();
  }};
  const auth=cookie=>({origin:ORIGIN,cookie,'content-type':'application/json'});
  const document=async()=>send('GET','/form-load-manual.html');
  const grant=async()=>{const doc=await document();const cookie=doc.headers['set-cookie']?.split(';')[0];assert.ok(cookie);const reply=await send('POST','/manual-session',{headers:auth(cookie)});return {cookie,reply,doc};};
  return {gateway,dependencies,session,limits,controller,calls,timers,server,send,request,advance,auth,document,grant,get now(){return now;}};
}

// Break caught: a constructor can acquire a listener or consult dependencies before admission.
test('constructor is inert and open binds only its distinct canonical loopback endpoint once',async()=>{
  const f=fixture();assert.deepEqual(f.calls,[]);assert.deepEqual(await f.gateway.open(),{url:ORIGIN+'/form-load-manual.html'});
  assert.deepEqual(f.calls[1],['listen',{host:'127.0.0.1',port:18083,exclusive:true}]);await assert.rejects(f.gateway.open());
  assert.equal(f.calls.filter(([kind])=>kind==='createServer').length,1);assert.equal(await f.gateway.close({deadline:f.limits.deadline+120000}),true);
});
test('first document and one authenticated empty exchange deliver session only in JSON once',async()=>{
  const f=fixture();await f.gateway.open();const {cookie,doc,reply}=await f.grant();assert.equal(doc.statusCode,200);assert.equal(reply.statusCode,200);
  assert.deepEqual(JSON.parse(reply.text),f.session);assert.equal(reply.headers['cache-control'],'no-store');
  assert.match(doc.headers['set-cookie'],/^payment_manual_form=[a-f0-9]{64}; HttpOnly; SameSite=Strict; Path=\/$/);
  assert.equal(doc.text.includes(TOKEN),false);assert.equal(JSON.stringify(doc.headers).includes(TOKEN),false);
  assert.equal((await f.document()).statusCode,410);assert.equal((await f.send('POST','/manual-session',{headers:f.auth(cookie)})).statusCode,410);
  assert.equal(f.calls.filter(([kind])=>kind==='random').length,1);await f.gateway.close({deadline:f.limits.deadline+120000});
});
for(const patch of [{host:'evil.test'},{host:'localhost:18083'},{host:undefined},{origin:'https://evil.test'},{origin:undefined},{cookie:undefined},{cookie:'payment_manual_form=wrong'},
  {cookie:'payment_manual_form=wrong; payment_manual_form=wrong'},{authorization:'private-header',origin:'http://127.0.0.1:18083/'}])
test('foreign or missing request authority cannot consume or receive the session: '+JSON.stringify(patch),async()=>{
  const f=fixture();await f.gateway.open();const doc=await f.document(),cookie=doc.headers['set-cookie'].split(';')[0];
  const bad=await f.send('POST','/manual-session',{headers:{...f.auth(cookie),...patch}});assert.equal(bad.statusCode,403);assert.equal(bad.text.includes(TOKEN),false);
  assert.equal((await f.send('POST','/manual-session',{headers:f.auth(cookie)})).statusCode,200);await f.gateway.close({deadline:f.limits.deadline+120000});
});
for(const path of ['/manual-session?x=1','/%6danual-session','/assets/../manual-session','/assets/%2e%2e/x.js','/assets/a.js?x=1','/assets/dir/a.js','//assets/a.js','/assets/a.map','/','http://127.0.0.1:18083/manual-session'])
test('raw route allowlist rejects '+path+' without fetching or issuing authority',async()=>{
  const f=fixture();await f.gateway.open();const res=await f.send('GET',path);assert.equal(res.statusCode,404);assert.equal(f.calls.filter(([kind])=>kind==='fetch'||kind==='random').length,0);
  assert.equal(res.headers['cache-control'],'no-store');await f.gateway.close({deadline:f.limits.deadline+120000});
});
for(const [method,path,want] of [['GET','/manual-session',405],['PUT','/manual-session',405],['POST','/assets/a.js',405],['HEAD','/form-load-manual.html',405],['DELETE','/unknown',404]])
test('method boundary '+method+' '+path,async()=>{
  const f=fixture();await f.gateway.open();assert.equal((await f.send(method,path)).statusCode,want);assert.equal((await f.document()).statusCode,200);await f.gateway.close({deadline:f.limits.deadline+120000});
});
for(const body of ['{"x":1}','[]','null','{',' '.repeat(33),'{} '.repeat(20)])
test('authenticated malformed or oversized exchange consumes handoff and fails without retry: '+JSON.stringify(body),async()=>{
  const f=fixture();await f.gateway.open();const doc=await f.document(),cookie=doc.headers['set-cookie'].split(';')[0];
  assert.ok([400,413].includes((await f.send('POST','/manual-session',{headers:f.auth(cookie),body})).statusCode));
  assert.equal((await f.send('POST','/manual-session',{headers:f.auth(cookie)})).statusCode,410);
  assert.deepEqual(await f.gateway.wait(),{observation:'NO_REPORT',end:'FAILED',sample:null,evidence:null});await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('session consumption precedes partial delivery and stalled response offers no retry',async()=>{
  const f=fixture();await f.gateway.open();const doc=await f.document(),cookie=doc.headers['set-cookie'].split(';')[0];
  const res=await f.send('POST','/manual-session',{headers:f.auth(cookie),hold:true});assert.equal(res.text.includes(TOKEN),true);
  assert.equal((await f.send('POST','/manual-session',{headers:f.auth(cookie)})).statusCode,410);
  const closing=f.gateway.close({deadline:f.now+100});await turn();await f.advance(f.now+100);assert.equal(await closing,false);res.emit('close');
});
test('parallel exchanges consume before await and exactly one JSON response contains authority',async()=>{
  const f=fixture();await f.gateway.open();const doc=await f.document(),cookie=doc.headers['set-cookie'].split(';')[0];
  const [a,b]=await Promise.all([f.send('POST','/manual-session',{headers:f.auth(cookie)}),f.send('POST','/manual-session',{headers:f.auth(cookie)})]);
  assert.deepEqual([a.statusCode,b.statusCode].sort(),[200,410]);assert.equal([a,b].filter(res=>res.text.includes(TOKEN)).length,1);await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('favicon is inert and assets use only fixed upstream GET without credentials or redirects',async()=>{
  const f=fixture();await f.gateway.open();assert.equal((await f.send('GET','/favicon.ico')).statusCode,204);
  const res=await f.send('GET','/assets/manual-abc.js',{headers:{cookie:'private-cookie',authorization:'private-auth',origin:'https://foreign.test'}});
  assert.equal(res.statusCode,200);const [,url,options]=f.calls.find(([kind])=>kind==='fetch');assert.equal(url,ASSETS+'/assets/manual-abc.js');
  assert.equal(options.method,'GET');assert.equal(options.redirect,'error');assert.equal(options.credentials,'omit');assert.equal(JSON.stringify(options).includes('private-'),false);
  for(const key of ['content-security-policy','referrer-policy','x-content-type-options','x-frame-options','permissions-policy'])assert.ok(res.headers[key]);
  assert.equal(res.headers['referrer-policy'],'no-referrer');assert.equal(res.headers['x-frame-options'],'DENY');assert.equal(res.headers['x-content-type-options'],'nosniff');
  const nginx=readFileSync(new URL('../examples/demo-site/nginx.conf',import.meta.url),'utf8');
  assert.equal(res.headers['content-security-policy'],nginx.match(/add_header Content-Security-Policy "([^"]+)" always;/)[1]);
  assert.equal((await f.document()).statusCode,200);await f.gateway.close({deadline:f.limits.deadline+120000});
});
for(const patch of [{origin:'http://localhost:18083'},{origin:'http://127.0.0.1:18083/'},{origin:'http://127.0.0.1:80'},{assetEndpoint:'http://evil.test:18081'},
  {assetEndpoint:ORIGIN},{assetEndpoint:ASSETS+'/'},{assetEndpoint:'http://user@127.0.0.1:18081'},{assetEndpoint:ASSETS+'?x'}, {extra:true}])
test('invalid gateway configuration rejects before dependency getters: '+JSON.stringify(patch),async()=>{
  let touches=0;const trap=new Proxy({},{get(){touches++;throw Error(TOKEN);}});let gateway;
  try{gateway=subject.createManualFormGateway({origin:ORIGIN,assetEndpoint:ASSETS,session:validSession(),...patch},{deadline:901000},trap);}catch{}
  if(gateway)await assert.rejects(gateway.open(),error=>!String(error).includes(TOKEN));assert.equal(touches,0);
});
test('ready is browser reported and wait preserves its original visual inspection cutoff',async()=>{
  const f=fixture();await f.gateway.open();const {cookie}=await f.grant();let settled=false;const waiting=f.gateway.wait().then(value=>{settled=true;return value;});
  assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(ready())})).statusCode,204);
  await f.advance(60999);assert.equal(settled,false);await f.advance(61000);
  assert.deepEqual(await waiting,{observation:'READY_REPORTED',end:'READY_REPORTED',sample:ready(),evidence});
  assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(ready())})).statusCode,410);await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('one pending checkpoint and terminal report are accepted, a second pending is refused',async()=>{
  const f=fixture();await f.gateway.open();const {cookie}=await f.grant();await f.advance(16000);
  assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(pending())})).statusCode,204);
  assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(pending())})).statusCode,410);
  const sample={...ready(),bucket:'AFTER_15S'};assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(sample)})).statusCode,204);
  await f.advance(61000);assert.deepEqual(await f.gateway.wait(),{observation:'READY_REPORTED',end:'READY_REPORTED',sample,evidence});await f.gateway.close({deadline:f.limits.deadline+120000});
});
for(const [index,sample] of [{...ready(),pay_visible:false},{...ready(),pay_state:'DISABLED'},{...ready(),loader_started:false},{...ready(),unknown:TOKEN},{sample:ready(),evidence},
  {...pending(),bucket:'BEFORE_15S'},{...ready(),bucket:'AT_LIMIT'}].entries())
test('malformed or incoherent report cannot manufacture ready: case '+index,async()=>{
  const f=fixture();await f.gateway.open();const {cookie}=await f.grant();assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(sample)})).statusCode,400);
  assert.deepEqual(await f.gateway.wait(),{observation:'INVALID_REPORT',end:'FAILED',sample:null,evidence:null});await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('browser AFTER_15S sample is valid before gateway15s because client clock starts before exchange',async()=>{
  const f=fixture();await f.gateway.open();const {cookie}=await f.grant();
  assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(pending())})).statusCode,204);
  const sample={...ready(),bucket:'AFTER_15S'};assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(sample)})).statusCode,204);
  await f.advance(61000);assert.deepEqual(await f.gateway.wait(),{observation:'READY_REPORTED',end:'READY_REPORTED',sample,evidence});await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('observation rejects duplicate JSON keys and over 4096 bytes without reflecting private payload',async()=>{
  for(const body of ['{"version":1,'+wire(ready()).slice(1),' '.repeat(4097)]){
    const f=fixture();await f.gateway.open();const {cookie}=await f.grant();const res=await f.send('POST','/manual-observation',{headers:f.auth(cookie),body});
    assert.ok([400,413].includes(res.statusCode));assert.equal(res.text.includes(TOKEN),false);assert.equal((await f.gateway.wait()).observation,'INVALID_REPORT');await f.gateway.close({deadline:f.limits.deadline+120000});
  }
});
test('no start times out at admission cutoff and no reads or writes occur after it',async()=>{
  const f=fixture();await f.gateway.open();await f.advance(f.limits.deadline-120000);assert.deepEqual(await f.gateway.wait(),{observation:'NOT_STARTED',end:'NOT_STARTED',sample:null,evidence:null});
  const before=f.calls.length;assert.equal((await f.document()).statusCode,410);assert.equal(f.calls.length,before);await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('started timeout has no invented sample and abort has precedence over historical ready',async()=>{
  const f=fixture();await f.gateway.open();await f.grant();await f.advance(61000);assert.deepEqual(await f.gateway.wait(),{observation:'NO_REPORT',end:'LOCAL_LIMIT',sample:null,evidence:null});await f.gateway.close({deadline:f.limits.deadline+120000});
  const g=fixture();await g.gateway.open();const {cookie}=await g.grant();await g.send('POST','/manual-observation',{headers:g.auth(cookie),body:wire(ready())});g.controller.abort();
  assert.deepEqual(await g.gateway.wait(),{observation:'READY_REPORTED',end:'ABORTED',sample:ready(),evidence});await g.gateway.close({deadline:g.limits.deadline+120000});
});
test('an authenticated stalled body cannot cross original cutoff into a session handoff',async()=>{
  const f=fixture();await f.gateway.open();const doc=await f.document(),cookie=doc.headers['set-cookie'].split(';')[0],stream=new Readable({read(){}});
  const {res}=f.request('POST','/manual-session',{headers:f.auth(cookie),stream});await turn();await f.advance(61000);stream.push('{}');stream.push(null);await turn();
  assert.equal(res.text.includes(TOKEN),false);assert.deepEqual(await f.gateway.wait(),{observation:'NO_REPORT',end:'LOCAL_LIMIT',sample:null,evidence:null});await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('delayed first authenticated body spends authority before an overlapping valid exchange',async()=>{
  const f=fixture();await f.gateway.open();const doc=await f.document(),cookie=doc.headers['set-cookie'].split(';')[0],stream=new Readable({read(){}});
  const {res}=f.request('POST','/manual-session',{headers:f.auth(cookie),stream});await turn();
  const overlap=await f.send('POST','/manual-session',{headers:f.auth(cookie)});assert.equal(overlap.statusCode,410);assert.equal(overlap.text.includes(TOKEN),false);
  stream.push('{}');stream.push(null);await turn();assert.equal(res.statusCode,200);assert.deepEqual(JSON.parse(res.text),f.session);await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('declared exchange oversize rejects before stream read, fails once and never forwards session',async()=>{
  const f=fixture();await f.gateway.open();const doc=await f.document(),cookie=doc.headers['set-cookie'].split(';')[0];let reads=0;
  const stream=new Readable({read(){reads++;}}),{res}=f.request('POST','/manual-session',{headers:{...f.auth(cookie),'content-length':'33'},stream});await turn();
  assert.equal(reads,0);assert.equal(res.statusCode,413);assert.equal(res.text.includes(TOKEN),false);
  assert.equal((await f.send('POST','/manual-session',{headers:f.auth(cookie)})).statusCode,410);assert.equal((await f.gateway.wait()).end,'FAILED');await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('timeout cancels and settles the owned streamed upstream body before positive close',async()=>{
  let cancelled=false;const f=fixture({fetcher:async()=>new Response(new ReadableStream({pull(){},cancel(){cancelled=true;}}),{headers:{'content-type':'application/javascript'}})});
  await f.gateway.open();const {res}=f.request('GET','/assets/a.js');await turn();await f.advance(11000);assert.equal(res.statusCode,502);assert.equal(cancelled,true);
  assert.equal(await f.gateway.close({deadline:12000}),true);
});
test('body cancellation rejection cannot count as positive owned settlement',async()=>{
  const f=fixture({fetcher:async()=>new Response(new ReadableStream({pull(controller){controller.enqueue(new Uint8Array(2097153));},cancel(){throw Error(TOKEN);}}),{headers:{'content-type':'application/javascript'}})});
  await f.gateway.open();const res=await f.send('GET','/assets/a.js');assert.equal(res.statusCode,502);
  const closing=f.gateway.close({deadline:2000});await turn();await f.advance(2000);assert.equal(await closing,false);
});
test('original deadline is checked after a late await even before its timer callback runs',async()=>{
  let clock=1000;const late=deferred(),f=fixture({fetcher:()=>late.promise});f.dependencies.now=()=>clock;await f.gateway.open();const {res}=f.request('GET','/assets/a.js');await turn();
  clock=11000;late.resolve(new Response('late-private-body',{headers:{'content-type':'application/javascript'}}));await turn();assert.equal(res.statusCode,502);assert.equal(res.text.includes('late-private-body'),false);
  assert.equal((await f.gateway.wait()).end,'FAILED');assert.equal(await f.gateway.close({deadline:12000}),true);
});
for(const kind of ['redirect','oversize','mime','throw'])test('upstream '+kind+' latches FAILED without reflecting unsafe input',async()=>{
  const f=fixture({fetcher:async()=>{if(kind==='throw')throw Error(TOKEN);if(kind==='redirect')return new Response(null,{status:302,headers:{location:'https://evil.test/'+TOKEN}});
    if(kind==='oversize')return new Response('x'.repeat(2*1024*1024+1));return new Response('private-content',{headers:{'content-type':'image/png'}});}});
  await f.gateway.open();const res=await f.document();assert.equal(res.statusCode,502);assert.equal(res.text.includes(TOKEN),false);assert.equal((await f.gateway.wait()).end,'FAILED');
  assert.equal((await f.document()).statusCode,410);await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('upstream headers and streaming body share a 10 second bound, unresolved fetch prevents positive cleanup',async()=>{
  const late=deferred(),f=fixture({fetcher:()=>late.promise});await f.gateway.open();const {res}=f.request('GET','/assets/a.js');await turn();await f.advance(11000);
  assert.equal(res.statusCode,502);assert.equal((await f.gateway.wait()).end,'FAILED');const cleanup=f.gateway.close({deadline:12000});await turn();await f.advance(12000);assert.equal(await cleanup,false);
  late.resolve(new Response('late',{headers:{'content-type':'application/javascript'}}));await turn();assert.equal(res.text.includes('late'),false);
});
test('closing pending listen owns late acquisition and cannot claim success until listener close callback',async()=>{
  const f=fixture({listenPending:true,closePending:true});const opening=f.gateway.open();opening.catch(()=>{});await turn();const cleanup=f.gateway.close({deadline:12000});let done=false;cleanup.then(()=>{done=true;});await turn();
  assert.equal(done,false);f.server.emit('listening');await turn();assert.equal(done,false);assert.ok(f.server.closedCallback);f.server.closedCallback();f.server.emit('close');await turn();assert.equal(await cleanup,true);await assert.rejects(opening);
});
test('close proves owned socket and response settlement independently of destroy dispatch',async()=>{
  const f=fixture();await f.gateway.open();const socket=new Socket();socket.hold=true;f.server.emit('connection',socket);
  const cleanup=f.gateway.close({deadline:12000});let settled=false;cleanup.then(()=>{settled=true;});await turn();assert.equal(socket.destroyed,true);assert.equal(settled,false);
  socket.emit('close');await turn();assert.equal(await cleanup,true);
});
test('premature response close spends the lease and fails rather than allowing later ready proof',async()=>{
  const f=fixture();await f.gateway.open();const {cookie,reply}=await (async()=>{const doc=await f.document(),cookie=doc.headers['set-cookie'].split(';')[0];return {cookie,reply:await f.send('POST','/manual-session',{headers:f.auth(cookie),hold:true})};})();
  reply.emit('close');await turn();assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(ready())})).statusCode,410);
  assert.deepEqual(await f.gateway.wait(),{observation:'NO_REPORT',end:'FAILED',sample:null,evidence:null});await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('owned response error is sanitized and closes without an unhandled error event',async()=>{
  const f=fixture();await f.gateway.open();const doc=await f.document(),cookie=doc.headers['set-cookie'].split(';')[0];
  const reply=await f.send('POST','/manual-session',{headers:f.auth(cookie),hold:true});assert.doesNotThrow(()=>reply.emit('error',Error(TOKEN)));
  assert.deepEqual(await f.gateway.wait(),{observation:'NO_REPORT',end:'FAILED',sample:null,evidence:null});reply.emit('close');await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('synchronous listen refusal is bounded and never leaks raw errors or unhandled rejection',async()=>{
  const f=fixture();f.server.listen=()=>{throw Error(TOKEN);};await assert.rejects(f.gateway.open(),error=>!String(error).includes(TOKEN));await turn();
  assert.equal((await f.gateway.wait()).end,'FAILED');const cleanup=f.gateway.close({deadline:2000});await turn();await f.advance(2000);assert.equal(await cleanup,false);
});
test('invalid cleanup ceiling revokes authority without extending the original resource budget',async()=>{
  const f=fixture();await f.gateway.open();assert.equal(await f.gateway.close({deadline:f.limits.deadline+120001}),false);
  assert.equal((await f.document()).statusCode,410);assert.equal((await f.gateway.wait()).end,'FAILED');assert.equal(await f.gateway.close({deadline:f.limits.deadline+120000}),true);
});
test('cleanup cannot turn a listener callback at the original cleanup cutoff into success',async()=>{
  const f=fixture({closePending:true});let clock=1000;f.dependencies.now=()=>clock;await f.gateway.open();const cleanup=f.gateway.close({deadline:2000});await turn();
  clock=2000;f.server.closedCallback();await turn();assert.equal(await cleanup,false);
});
test('canonical configuration snapshots session data and rejects unsafe nested capability records before IO',async()=>{
  const f=fixture();f.session.checkoutToken='x'.repeat(43);f.session.state.price.amountMinor=9000;await f.gateway.open();const {reply}=await f.grant();assert.equal(JSON.parse(reply.text).checkoutToken,TOKEN);
  await f.gateway.close({deadline:f.limits.deadline+120000});
  for(const patch of [s=>s.providerType='FAKE',s=>s.apiBaseUrl='http://localhost:18080',s=>s.state.status='SUCCEEDED',s=>s.state.price.taxMode='INCLUSIVE',
    s=>s.state.amountMinor=1000,s=>s.extra=TOKEN,s=>s.state.price.extra=TOKEN,s=>Object.defineProperty(s,'checkoutToken',{enumerable:true,get(){throw Error(TOKEN);}})]){
    let touches=0;const session=validSession();patch(session);const deps=new Proxy({},{get(){touches++;throw Error(TOKEN);}});
    assert.throws(()=>subject.createManualFormGateway({origin:ORIGIN,assetEndpoint:ASSETS,session},{deadline:901000},deps),error=>!String(error).includes(TOKEN));assert.equal(touches,0);
  }
});
test('duplicate Host, Origin and cookie header lines never consume an otherwise legitimate lease',async()=>{
  for(const key of ['host','origin','cookie']){
    const f=fixture();await f.gateway.open();const doc=await f.document(),cookie=doc.headers['set-cookie'].split(';')[0],headers={host:'127.0.0.1:18083',...f.auth(cookie)};
    const rawHeaders=[...Object.entries(headers).flatMap(([name,value])=>[name,value]),key,headers[key]];
    assert.equal((await f.send('POST','/manual-session',{headers:f.auth(cookie),rawHeaders})).statusCode,403);
    assert.equal((await f.send('POST','/manual-session',{headers:f.auth(cookie)})).statusCode,200);await f.gateway.close({deadline:f.limits.deadline+120000});
  }
});
test('pending checkpoint followed by finite terminal/limit preserves exact final sample without readiness',async()=>{
  for(const terminal of [{...pending(),bucket:'AT_LIMIT'},{...pending(),operation:'FAILED'}]){
    const f=fixture();await f.gateway.open();const {cookie}=await f.grant();await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(pending())});
    assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(terminal)})).statusCode,204);
    assert.deepEqual(await f.gateway.wait(),{observation:terminal.operation==='FAILED'?'TERMINAL_REPORTED':'PENDING_REPORTED',end:terminal.operation==='FAILED'?'FAILED':'LOCAL_LIMIT',sample:terminal,evidence});
    await f.gateway.close({deadline:f.limits.deadline+120000});
  }
});
test('spent exchange alone cannot establish observation authority before session response finishes',async()=>{
  const f=fixture();await f.gateway.open();const doc=await f.document(),cookie=doc.headers['set-cookie'].split(';')[0],stream=new Readable({read(){}});
  const {res}=f.request('POST','/manual-session',{headers:f.auth(cookie),stream});await turn();
  assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(ready())})).statusCode,409);
  stream.push('{}');stream.push(null);await turn();assert.equal(res.writableFinished,true);
  assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(ready())})).statusCode,204);
  await f.advance(61000);assert.equal((await f.gateway.wait()).observation,'READY_REPORTED');await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('at most two observation bodies reserve read authority before await; concurrent pending and ready are supported',async()=>{
  const f=fixture();await f.gateway.open();const {cookie}=await f.grant(),first=new Readable({read(){}}),second=new Readable({read(){}});
  const a=f.request('POST','/manual-observation',{headers:f.auth(cookie),stream:first}),b=f.request('POST','/manual-observation',{headers:f.auth(cookie),stream:second});await turn();
  let reads=0;const third=new Readable({read(){reads++;}}),c=f.request('POST','/manual-observation',{headers:f.auth(cookie),stream:third});await turn();
  assert.equal(c.res.statusCode,410);assert.equal(reads,0);
  first.push(wire(pending()));first.push(null);await turn();assert.equal(a.res.statusCode,204);
  second.push(wire(ready()));second.push(null);await turn();assert.equal(b.res.statusCode,204);
  await f.advance(61000);assert.deepEqual(await f.gateway.wait(),{observation:'READY_REPORTED',end:'READY_REPORTED',sample:ready(),evidence});await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('a previously admitted slow pending body cannot overwrite an accepted ready sample',async()=>{
  const f=fixture();await f.gateway.open();const {cookie}=await f.grant(),stream=new Readable({read(){}});
  const {res}=f.request('POST','/manual-observation',{headers:f.auth(cookie),stream});await turn();
  await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(ready())});stream.push(wire(pending()));stream.push(null);await turn();assert.equal(res.statusCode,410);
  await f.advance(61000);assert.deepEqual(await f.gateway.wait(),{observation:'READY_REPORTED',end:'READY_REPORTED',sample:ready(),evidence});await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('already admitted malformed body fails while retaining historical ready sample, never restoring readiness',async()=>{
  const f=fixture();await f.gateway.open();const {cookie}=await f.grant(),stream=new Readable({read(){}});
  const {res}=f.request('POST','/manual-observation',{headers:f.auth(cookie),stream});await turn();
  await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(ready())});stream.push('{');stream.push(null);await turn();assert.equal(res.statusCode,400);
  assert.deepEqual(await f.gateway.wait(),{observation:'READY_REPORTED',end:'FAILED',sample:ready(),evidence});await f.advance(61000);
  assert.equal((await f.gateway.wait()).end,'FAILED');await f.gateway.close({deadline:f.limits.deadline+120000});
});
test('expired admission consults only the clock before rejecting listener/fetch/random dependency access',async()=>{
  let forbidden=0;const deps=new Proxy({now:()=>1000},{get(target,key){if(key==='now')return target.now;forbidden++;throw Error(TOKEN);}});
  const gateway=subject.createManualFormGateway({origin:ORIGIN,assetEndpoint:ASSETS,session:validSession()},{deadline:500},deps);
  await assert.rejects(gateway.open(),error=>!String(error).includes(TOKEN));assert.equal(forbidden,0);
});

test('manual legacy samples and mixed or nested duplicate envelopes fail without evidence',async()=>{
  const valid=wire(ready());
  for(const body of [JSON.stringify(ready()),valid.replace('"version":2','"version":1'),valid.replace('"evidence":{','"evidence":{"loaderstart_seen":true,'),valid.replace('"sample":{','"sample":{"ver\\u0073ion":1,')]){
    const f=fixture();await f.gateway.open();const {cookie}=await f.grant();
    assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body})).statusCode,400);
    assert.deepEqual(await f.gateway.wait(),{observation:'INVALID_REPORT',end:'FAILED',sample:null,evidence:null});
    await f.gateway.close({deadline:f.limits.deadline+120000});
  }
});
test('cutoff retains accepted checkpoint evidence and rejects a late replacement',async()=>{
  const f=fixture();await f.gateway.open();const {cookie}=await f.grant();
  await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(pending())});
  await f.advance(61000);
  assert.equal((await f.send('POST','/manual-observation',{headers:f.auth(cookie),body:wire(ready())})).statusCode,410);
  assert.deepEqual(await f.gateway.wait(),{observation:'PENDING_REPORTED',end:'LOCAL_LIMIT',sample:pending(),evidence});
  await f.gateway.close({deadline:f.limits.deadline+120000});
});
