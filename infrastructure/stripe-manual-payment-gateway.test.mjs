import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {Readable} from 'node:stream';
import {readFile} from 'node:fs/promises';
const subject=await import('./stripe-manual-payment-gateway.mjs').catch(()=>({}));
const ORIGIN='http://127.0.0.1:18083',ASSETS='http://127.0.0.1:18081';
const turn=async()=>{for(let n=0;n<20;n++)await Promise.resolve();await new Promise(setImmediate);};
const defer=()=>{let resolve;const promise=new Promise(yes=>{resolve=yes;});return {promise,resolve};};
const session=()=>({version:2,apiBaseUrl:'http://127.0.0.1:18080',siteKey:'site-test',priceKey:'price-test',checkoutId:'11111111-2222-4333-8444-555555555555',checkoutToken:'x'.repeat(43),expiresAt:'2099-01-01T00:00:00.000Z',providerType:'STRIPE',confirmationMode:'CLIENT_PROVIDER',state:{checkoutId:'11111111-2222-4333-8444-555555555555',paymentId:'22222222-2222-4333-8444-555555555555',amountMinor:2000,currency:'EUR',status:'REQUIRES_PAYMENT_METHOD',expiresAt:'2099-01-01T00:00:00.000Z',price:{priceId:'33333333-2222-4333-8444-555555555555',priceKey:'price-test',version:1,amountMinor:2000,currency:'EUR',taxMode:'NONE'}}});
class Reply extends EventEmitter {
  headers={};statusCode=200;chunks=[];writableFinished=false;destroyed=false;hold=false;
  setHeader(key,value){this.headers[key.toLowerCase()]=value;}
  end(value){if(value)this.chunks.push(Buffer.from(value));if(!this.hold){this.writableFinished=true;this.emit('finish');}}
  destroy(){this.destroyed=true;this.emit('close');}
  get text(){return Buffer.concat(this.chunks).toString();}
}
function fixture({authorize=async()=>true,fetcher,listenPending=false,closePending=false,deadline=2701000,factory=subject.createManualPaymentGateway,gatewayOptions={}}={}){
  let now=1000,handler,serial=0;const timers=new Map(),calls=[],server=new EventEmitter();
  server.listen=options=>{calls.push(['listen',options]);if(!listenPending)queueMicrotask(()=>server.emit('listening'));};
  server.close=callback=>{server.closedCallback=callback;if(!closePending)queueMicrotask(()=>callback());};
  const deps={now:()=>now,createServer:callback=>{handler=callback;return server;},randomBytes:size=>Buffer.alloc(size,17),
    setTimeout:(callback,ms)=>{const id=++serial;timers.set(id,{at:now+ms,callback});return id;},clearTimeout:id=>timers.delete(id),
    fetch:async(url,options)=>{calls.push(['fetch',url,options]);if(fetcher)return fetcher(url,options);
      if(url.endsWith('manifest.json'))return Response.json({'manual-payment.html':{file:'assets/manualPayment-abc.js',isEntry:true,css:['assets/manual-abc.css'],imports:['_shared.js']},'_shared.js':{file:'assets/shared-abc.js'}});
      return new Response(url.endsWith('.html')?'<html>Manual payment</html>':'export default 1',{headers:{'Content-Type':url.endsWith('.html')?'text/html':url.endsWith('.css')?'text/css':'application/javascript'}});}};
  assert.equal(typeof subject.createManualPaymentGateway,'function','manual gateway must exist');
  const gateway=factory({origin:ORIGIN,assetEndpoint:ASSETS,session:session(),authorizeConfirmation:authorize,...gatewayOptions},{deadline},deps);
  const request=(method,path,{headers={},body=method==='POST'?'{}':'',stream,hold=false,rawHeaders}={})=>{
    const req=stream??Readable.from(body?[Buffer.from(body)]:[]);req.method=method;req.url=path;req.headers={host:'127.0.0.1:18083',...headers};req.rawHeaders=rawHeaders??Object.entries(req.headers).flat();
    const res=new Reply();res.hold=hold;handler(req,res);return {req,res};};
  const send=async(...args)=>{const {res}=request(...args);await turn();return res;};
  const advance=async target=>{while(true){const next=[...timers].filter(([,v])=>v.at<=target).sort((a,b)=>a[1].at-b[1].at)[0];if(!next)break;now=next[1].at;timers.delete(next[0]);next[1].callback();await turn();}now=target;await turn();};
  const auth=cookie=>({origin:ORIGIN,cookie,'content-type':'application/json'});
  const grant=async()=>{const document=await send('GET','/manual-payment.html'),cookie=document.headers['set-cookie'].split(';')[0];const reply=await send('POST','/manual-payment-session',{headers:auth(cookie)});assert.equal(reply.statusCode,200);return cookie;};
  return {gateway,send,request,advance,auth,grant,calls,server,deps,deadline};
}
test('one document/session/permit; durable authorization completes before the sole permit response',async()=>{
  const durable=defer();let authorizations=0;const f=fixture({authorize:()=>{authorizations++;return durable.promise;}});assert.deepEqual(await f.gateway.open(),{url:ORIGIN+'/manual-payment.html'});
  assert.deepEqual(f.calls.find(call=>call[0]==='listen')[1],{host:'127.0.0.1',port:18083,exclusive:true});
  const cookie=await f.grant();assert.equal((await f.send('GET','/manual-payment.html')).statusCode,410);
  const secondSession=await f.send('POST','/manual-payment-session',{headers:f.auth(cookie)});assert.equal(secondSession.statusCode,410);
  const first=f.request('POST','/manual-payment-confirmation',{headers:f.auth(cookie)});await turn();assert.equal(authorizations,1);assert.equal(first.res.writableFinished,false);
  const second=await f.send('POST','/manual-payment-confirmation',{headers:f.auth(cookie)});assert.equal(second.statusCode,410);
  durable.resolve(true);await turn();const permitReplies=[first.res,second].filter(reply=>reply.statusCode===200&&reply.text==='{"authorized":true}').length;assert.equal(permitReplies,1);
  assert.deepEqual(await f.gateway.wait(),{outcome:'CONFIRMATION_GRANTED'});assert.equal(await f.gateway.close({deadline:f.deadline+120000}),true);
});
// Break: trusting request selection, leaking selection beyond the protected exchange or regranting.
test('3DS scenario is only the selected protected session header with unchanged body and one grant',async()=>{
  for(const selected of [false,true]){
    let grants=0;const f=fixture({gatewayOptions:selected?{threeDSecure:true}:{},authorize:async()=>{grants++;return true;}});await f.gateway.open();
    try{
      const doc=await f.send('GET','/manual-payment.html');assert.equal(doc.headers['x-payment-scenario'],undefined);const cookie=doc.headers['set-cookie'].split(';')[0];
      const denied=await f.send('POST','/manual-payment-session',{headers:f.auth('wrong=1')});assert.equal(denied.statusCode,403);assert.equal(denied.headers['x-payment-scenario'],undefined);
      const query=await f.send('POST','/manual-payment-session?scenario=3DS',{headers:f.auth(cookie)});assert.equal(query.statusCode,404);assert.equal(query.headers['x-payment-scenario'],undefined);
      const exchange=await f.send('POST','/manual-payment-session',{headers:{...f.auth(cookie),'x-payment-scenario':selected?'ordinary':'3DS'}});assert.equal(exchange.statusCode,200);assert.deepEqual(JSON.parse(exchange.text),session());assert.equal(exchange.headers['x-payment-scenario'],selected?'3DS':undefined);
      const repeated=await f.send('POST','/manual-payment-session',{headers:f.auth(cookie)});assert.equal(repeated.statusCode,410);assert.equal(repeated.headers['x-payment-scenario'],undefined);
      for(const path of ['/assets/shared-abc.js','/payment-return.html'])assert.equal((await f.send('GET',path)).headers['x-payment-scenario'],undefined);
      const permit=await f.send('POST','/manual-payment-confirmation',{headers:f.auth(cookie)});assert.equal(permit.text,'{"authorized":true}');assert.equal(permit.headers['x-payment-scenario'],undefined);assert.equal((await f.send('POST','/manual-payment-confirmation',{headers:f.auth(cookie)})).statusCode,410);assert.equal(grants,1);
    }finally{assert.equal(await f.gateway.close({deadline:f.deadline+120000}),true);}
  }
});
test('gateway rejects nonliteral or accessor 3DS selection without acquiring IO',()=>{
  let reads=0;for(const value of [false,'true',undefined,null])assert.throws(()=>fixture({gatewayOptions:{threeDSecure:value}}),/GATEWAY_UNCONFIRMED/);
  const options={origin:ORIGIN,assetEndpoint:ASSETS,session:session(),authorizeConfirmation:async()=>true};Object.defineProperty(options,'threeDSecure',{enumerable:true,get(){reads++;return true;}});
  assert.throws(()=>subject.createManualPaymentGateway(options,{deadline:2701000},new Proxy({},{get(){reads++;throw null;}})),/GATEWAY_UNCONFIRMED/);assert.equal(reads,0);
});
test('only manifest-owned assets and sanitized fixed return are served, never ordinary recovery',async()=>{
  const f=fixture();await f.gateway.open();const privateQueryCanary='PRIVATE_RETURN_CANARY';
  const returned=f.request('GET','/payment-return.html?payment_intent_client_secret='+privateQueryCanary);await turn();const result=returned.res,capturedLogs=JSON.stringify(f.calls);assert.equal(returned.req.url,'/payment-return.html');assert.equal(result.statusCode,200);assert.match(result.text,/history.replaceState/);assert.doesNotMatch(result.text,/read-saved-payment|href="\/"|payment-return.ts/);
  assert.equal(capturedLogs.includes(privateQueryCanary),false);assert.equal(f.calls.some(c=>JSON.stringify(c).includes(privateQueryCanary)),false);
  assert.equal((await f.send('GET','/assets/shared-abc.js')).statusCode,200);assert.equal((await f.send('GET','/assets/foreign-abc.js')).statusCode,404);
  assert.equal((await f.send('GET','/.vite/manifest.json')).statusCode,404);assert.equal((await f.send('GET','/')).statusCode,404);
  assert.equal((await f.send('POST','/manual-payment-session',{headers:f.auth('payment_manual_payment=wrong')})).statusCode,403);await f.gateway.close({deadline:f.deadline+120000});
});
test('host/origin/cookie/method rejection cannot expose a private session',async()=>{
  const f=fixture();await f.gateway.open();const doc=await f.send('GET','/manual-payment.html'),cookie=doc.headers['set-cookie'].split(';')[0];
  for(const [method,headers,status]of [['GET',f.auth(cookie),405],['POST',{...f.auth(cookie),host:'localhost:18083'},403],['POST',{...f.auth(cookie),origin:ASSETS},403],['POST',{...f.auth(cookie),cookie:cookie+'; '+cookie},403]]){
    const r=await f.send(method,'/manual-payment-session',{headers});assert.equal(r.statusCode,status);assert.equal(r.text.includes('checkoutToken'),false);
  }
  const headers={host:'127.0.0.1:18083',...f.auth(cookie)};for(const key of ['host','origin','cookie'])assert.equal((await f.send('POST','/manual-payment-session',{headers:f.auth(cookie),rawHeaders:[...Object.entries(headers).flat(),key,headers[key]]})).statusCode,403);
  assert.equal((await f.send('POST','/manual-payment-session',{headers:f.auth(cookie)})).statusCode,200);await f.gateway.close({deadline:f.deadline+120000});
});
test('well-formed unrelated cookies preserve the sole session and confirmation authority',async t=>{
  // Rejecting a valid cookie list, confusing substrings with names, or spending
  // authority twice must fail these public-route assertions.
  const owned='payment_manual_payment='+'11'.repeat(32);
  const cases=[
    ['unrelated before',`theme=dark; ${owned}`],
    ['unrelated after',`${owned}; theme=dark`],
    ['multiple without spaces',`first=1;${owned};last=2`],
    ['separator spaces and tabs',`first=1; \t${owned};\t last=2`],
    ['owned-name substrings',`prefix_payment_manual_payment=other; ${owned}; note=payment_manual_payment=other; payment_manual_payment_suffix=other`],
    ['equals and quoted values',`encoded=a=b==; quoted="a=b=="; empty=; quoted_empty=""; ${owned}`],
    ['case-sensitive unrelated name',`Payment_manual_payment=other; ${owned}`],
    ['maximum ASCII length',`padding=${'a'.repeat(8095)}; ${owned}`],
  ];
  for(const [name,cookie]of cases)await t.test(name,async()=>{
    let authorizations=0;const f=fixture({authorize:async()=>{authorizations++;return true;}});await f.gateway.open();
    try{
      const doc=await f.send('GET','/manual-payment.html');assert.equal(doc.statusCode,200);
      assert.equal(cookie.length<=8192,true);if(name==='maximum ASCII length')assert.equal(cookie.length,8192);
      const response=await f.send('POST','/manual-payment-session',{headers:f.auth(cookie)});
      assert.equal(response.statusCode,200,'valid cookie list allows session');assert.deepEqual(JSON.parse(response.text),session());assert.equal(response.headers['set-cookie'],undefined);assert.equal(authorizations,0);
      assert.equal((await f.send('POST','/manual-payment-session',{headers:f.auth(cookie)})).statusCode,410);
      const permit=await f.send('POST','/manual-payment-confirmation',{headers:f.auth(cookie)});
      assert.equal(permit.statusCode,200,'valid cookie list allows confirmation');assert.equal(permit.text,'{"authorized":true}');assert.equal(permit.headers['set-cookie'],undefined);
      assert.equal((await f.send('POST','/manual-payment-confirmation',{headers:f.auth(cookie)})).statusCode,410);assert.equal(authorizations,1);
      assert.deepEqual(await f.gateway.wait(),{outcome:'CONFIRMATION_GRANTED'});
    }finally{assert.equal(await f.gateway.close({deadline:f.deadline+120000}),true);}
  });
});
test('invalid cookie lists deny both routes without exposing or spending authority',async t=>{
  const owned='payment_manual_payment='+'11'.repeat(32),wrong='payment_manual_payment='+'22'.repeat(32);
  const cases=[
    ['missing owned','theme=dark'],['empty header',''],['empty owned','payment_manual_payment='],['wrong owned',wrong],
    ['quoted owned','payment_manual_payment="'+'11'.repeat(32)+'"'],['wrong case','Payment_manual_payment='+'11'.repeat(32)],
    ['upper-case value','payment_manual_payment='+'AB'.repeat(32)],['encoded value','payment_manual_payment=%31'+'11'.repeat(31)+'1'],
    ['duplicate identical',`${owned}; ${owned}`],['conflict valid first',`${owned}; ${wrong}`],['conflict valid last',`${wrong}; ${owned}`],
    ['owned value leading space','payment_manual_payment= '+'11'.repeat(32)],['owned value trailing space',owned+' '],
    ['leading header space',' '+owned],['leading header tab','\t'+owned],['trailing header tab',owned+'\t'],
    ['space before separator',`${owned} ; theme=dark`],['tab before separator',`${owned}\t; theme=dark`],
    ['unrelated space before separator',`theme=dark ; ${owned}`],['name padding',`payment_manual_payment =${'11'.repeat(32)}`],
    ['owned value internal space','payment_manual_payment='+'11'.repeat(16)+' '+'11'.repeat(16)],
    ['leading empty segment',`; ${owned}`],['trailing empty segment',`${owned};`],['middle empty segment',`a=b;;${owned}`],
    ['missing equals',`broken; ${owned}`],['empty name',`=value; ${owned}`],['name whitespace',`bad name=value; ${owned}`],['name colon',`bad:name=value; ${owned}`],
    ['unquoted whitespace',`bad=some value; ${owned}`],['quoted whitespace',`bad="some value"; ${owned}`],['unterminated quote',`bad="value; ${owned}`],
    ['suffix after quote',`bad="value"suffix; ${owned}`],['escaped value',`bad=back\\slash; ${owned}`],['comma value',`bad=a,b; ${owned}`],
    ['non-ASCII value',`bad=caf\u00e9; ${owned}`],['non-ASCII name',`caf\u00e9=value; ${owned}`],
    ['NUL',`bad=a\u0000b; ${owned}`],['CR',`bad=a\rb; ${owned}`],['LF',`bad=a\nb; ${owned}`],['DEL',`bad=a\u007fb; ${owned}`],['tab inside value',`bad=a\tb; ${owned}`],
    ['over maximum ASCII length',`padding=${'a'.repeat(8096)}; ${owned}`],
    ['array header',[owned]],['absent header',undefined],
    ['duplicate header lines',owned,'duplicate'],['missing raw header',owned,'missing'],
  ];
  for(const [name,cookie,rawMode]of cases)await t.test(name,async()=>{
    let authorizations=0;const f=fixture({authorize:async()=>{authorizations++;return true;}});await f.gateway.open();
    try{
      await f.send('GET','/manual-payment.html');
      const headers={host:'127.0.0.1:18083',...f.auth(cookie)};if(cookie===undefined)delete headers.cookie;
      const rawHeaders=rawMode==='duplicate'?[...Object.entries(headers).flat(),'Cookie',owned]:rawMode==='missing'?['host',headers.host,'origin',headers.origin,'content-type','application/json']:undefined;
      const deny=async path=>{const response=await f.send('POST',path,{headers,rawHeaders});assert.equal(response.statusCode,403,'invalid cookie list is forbidden');assert.equal(response.text,'');assert.equal(response.headers['set-cookie'],undefined);assert.equal(authorizations,0);};
      await deny('/manual-payment-session');
      assert.equal((await f.send('POST','/manual-payment-session',{headers:f.auth(owned)})).statusCode,200,'denial does not consume session');
      await deny('/manual-payment-confirmation');
      const permit=await f.send('POST','/manual-payment-confirmation',{headers:f.auth(owned)});assert.equal(permit.statusCode,200,'denial does not consume confirmation');assert.equal(permit.text,'{"authorized":true}');assert.equal(authorizations,1);
    }finally{assert.equal(await f.gateway.close({deadline:f.deadline+120000}),true);}
  });
});
test('invalid/duplicate/oversize bodies spend exchange authority irreversibly',async()=>{
  for(const body of ['{"x":1}','{"x":1,"x":1}','[]','{',' '.repeat(33)+'{}']){
    const f=fixture();await f.gateway.open();const doc=await f.send('GET','/manual-payment.html'),cookie=doc.headers['set-cookie'].split(';')[0];
    assert.ok([400,413].includes((await f.send('POST','/manual-payment-session',{headers:f.auth(cookie),body})).statusCode));assert.equal((await f.send('POST','/manual-payment-session',{headers:f.auth(cookie)})).statusCode,410);
    assert.deepEqual(await f.gateway.wait(),{outcome:'FAILED'});await f.gateway.close({deadline:f.deadline+120000});
  }
});
test('concurrent session bodies and pre-delivery confirmation cannot regrant authority',async()=>{
  const f=fixture();await f.gateway.open();const doc=await f.send('GET','/manual-payment.html'),cookie=doc.headers['set-cookie'].split(';')[0],stream=new Readable({read(){}});
  const first=f.request('POST','/manual-payment-session',{headers:f.auth(cookie),stream});await turn();assert.equal((await f.send('POST','/manual-payment-session',{headers:f.auth(cookie)})).statusCode,410);
  assert.equal((await f.send('POST','/manual-payment-confirmation',{headers:f.auth(cookie)})).statusCode,409);stream.push('{}');stream.push(null);await turn();assert.equal(first.res.statusCode,200);
  await f.gateway.close({deadline:f.deadline+120000});
});
test('aborted permit response and rejected/late authorizer never restore authority',async()=>{
  for(const scenario of ['aborted','rejected','late','nonliteral']){
    const durable=defer();let authorizations=0;const f=fixture({authorize:()=>{authorizations++;return durable.promise;}});await f.gateway.open();const cookie=await f.grant();
    const first=f.request('POST','/manual-payment-confirmation',{headers:f.auth(cookie),hold:scenario==='aborted'});await turn();
    if(scenario==='aborted')first.res.emit('close');if(scenario==='late')await f.advance(301000);durable.resolve(scenario==='nonliteral'?{}:scenario==='rejected'?false:true);await turn();
    assert.equal((await f.send('POST','/manual-payment-confirmation',{headers:f.auth(cookie)})).statusCode,410);assert.equal(authorizations,1);
    assert.deepEqual(await f.gateway.wait(),{outcome:scenario==='aborted'?'FAILED':'FAILED'});await f.gateway.close({deadline:f.deadline+120000});
  }
});
test('cutoff includes full five-minute admission, clips session expiry and denies late work',async()=>{
  const f=fixture();await f.gateway.open();await f.grant();await f.advance(301000);assert.deepEqual(await f.gateway.wait(),{outcome:'NOT_STARTED'});assert.equal((await f.send('GET','/manual-payment.html')).statusCode,410);await f.gateway.close({deadline:f.deadline+120000});
  const short=fixture({deadline:390999});await assert.rejects(short.gateway.open());assert.equal(short.calls.length,0);
});
test('hanging bind/fetch/request/authorization/close stay bounded and late success cannot reopen',async()=>{
  for(const scenario of ['bind','manifest','asset','body','authorize','close']){
    const pending=defer();const f=fixture({listenPending:scenario==='bind',closePending:scenario==='close',authorize:scenario==='authorize'?()=>pending.promise:async()=>true,
      fetcher:['manifest','asset'].includes(scenario)?async url=>{if(scenario==='manifest'||url.endsWith('.html'))return pending.promise;return Response.json({'manual-payment.html':{file:'assets/manualPayment-abc.js',isEntry:true}});}:undefined});
    const opening=f.gateway.open();opening.catch(()=>{});await turn();
    if(scenario==='bind'||scenario==='manifest'){await f.advance(11000);await assert.rejects(opening);}else{
      await opening;if(scenario==='asset')f.request('GET','/manual-payment.html');else if(scenario==='body'){const doc=await f.send('GET','/manual-payment.html');f.request('POST','/manual-payment-session',{headers:f.auth(doc.headers['set-cookie'].split(';')[0]),stream:new Readable({read(){}})});}else if(scenario==='authorize'){const cookie=await f.grant();f.request('POST','/manual-payment-confirmation',{headers:f.auth(cookie)});}await turn();if(scenario!=='close')await f.advance(11000);
    }
    const cleanup=f.gateway.close({deadline:12000});await turn();await f.advance(12000);assert.equal(await cleanup,['bind','manifest','asset','authorize','close'].includes(scenario)?false:true,scenario);
    if(scenario==='bind')f.server.emit('listening');else if(scenario==='close')f.server.closedCallback();else pending.resolve(scenario==='authorize'?true:Response.json({}));await turn();
    assert.notEqual((await f.gateway.wait()).outcome,'CONFIRMATION_GRANTED');assert.equal(f.calls.filter(c=>c[0]==='listen').length<=1,true);
  }
});
test('malformed, foreign-entry and escaped asset manifests fail before binding',async()=>{
  for(const manifest of [null,{}, {'manual-payment.html':{file:'https://foreign.invalid/a.js',isEntry:true}},
    {'manual-payment.html':{file:'assets/../private.js',isEntry:true}}, {'manual-payment.html':{file:'assets/a.js',isEntry:true,imports:['missing']}},
    {'manual-payment.html':{file:'assets/a.js',isEntry:true},'index.html':{file:'assets/foreign.js',isEntry:true}},
    {'manual-payment.html':{file:'assets/a.js',isEntry:true,assets:['private.env']}},
    {'manual-payment.html':{file:'assets/a.js',isEntry:true,proxy:'http://foreign.invalid'}}]){
    const f=fixture({fetcher:async()=>Response.json(manifest)});await assert.rejects(f.gateway.open());assert.equal(f.calls.some(c=>c[0]==='listen'),false);assert.equal(await f.gateway.close({deadline:2000}),true);
  }
});
test('rejected confirmation bodies consume permit without calling durable authorization',async()=>{
  for(const body of ['{"authorized":true}','{"x":1,"x":1}',' '.repeat(33)+'{}']){
    let authorizations=0;const f=fixture({authorize:async()=>{authorizations++;return true;}});await f.gateway.open();const cookie=await f.grant();
    assert.ok([400,413].includes((await f.send('POST','/manual-payment-confirmation',{headers:f.auth(cookie),body})).statusCode));
    assert.equal((await f.send('POST','/manual-payment-confirmation',{headers:f.auth(cookie)})).statusCode,410);assert.equal(authorizations,0);await f.gateway.close({deadline:f.deadline+120000});
  }
});
test('behavioral mutation removing permit reservation is detected by concurrent authorization',async()=>{
  const source=await readFile(new URL('./stripe-manual-payment-gateway.mjs',import.meta.url),'utf8');
  const mutant=await import('data:text/javascript,'+encodeURIComponent(source.replace('permitUsed=true;','')));
  const durable=defer();let count=0;const f=fixture({factory:mutant.createManualPaymentGateway,authorize:()=>{count++;return durable.promise;}});await f.gateway.open();const cookie=await f.grant();
  f.request('POST','/manual-payment-confirmation',{headers:f.auth(cookie)});f.request('POST','/manual-payment-confirmation',{headers:f.auth(cookie)});await turn();
  assert.throws(()=>assert.equal(count,1),assert.AssertionError,'one-shot assertion catches missing reservation');durable.resolve(true);await turn();await f.gateway.close({deadline:f.deadline+120000});
});
test('one immutable HTTP deadline covers slow body plus durable authorization and denies late grant',async()=>{
  const durable=defer();let authorizations=0;const f=fixture({authorize:()=>{authorizations++;return durable.promise;}});await f.gateway.open();const cookie=await f.grant(),stream=new Readable({read(){}});
  const first=f.request('POST','/manual-payment-confirmation',{headers:f.auth(cookie),stream});await f.advance(9000);stream.push('{}');stream.push(null);await turn();assert.equal(authorizations,1);
  let outcome;void f.gateway.wait().then(value=>{outcome=value;});await f.advance(11000);assert.deepEqual(outcome,{outcome:'FAILED'},'body_and_authorization_share_10s');
  const cleanup=f.gateway.close({deadline:12000});await turn();await f.advance(12000);assert.equal(await cleanup,false,'unsettled_authorization_remains_owned');
  await f.advance(17000);durable.resolve(true);await turn();assert.notEqual(first.res.text,'{"authorized":true}');assert.equal((await f.send('POST','/manual-payment-confirmation',{headers:f.auth(cookie)})).statusCode,410);assert.equal(authorizations,1);
});
test('session response delivery shares the immutable request cutoff without reopening exchange authority',async()=>{
  const f=fixture();await f.gateway.open();const doc=await f.send('GET','/manual-payment.html'),cookie=doc.headers['set-cookie'].split(';')[0];
  const response=await f.send('POST','/manual-payment-session',{headers:f.auth(cookie),hold:true});assert.equal(response.writableFinished,false);await f.advance(11000);
  assert.equal(response.destroyed,true);assert.deepEqual(await f.gateway.wait(),{outcome:'FAILED'});assert.equal((await f.send('POST','/manual-payment-confirmation',{headers:f.auth(cookie)})).statusCode,410);assert.equal(await f.gateway.close({deadline:12000}),true);
});
test('request destroy failure still cancels the response and retains uncertain cleanup',async()=>{
  const f=fixture();await f.gateway.open();const doc=await f.send('GET','/manual-payment.html'),cookie=doc.headers['set-cookie'].split(';')[0];
  const {req,res}=f.request('POST','/manual-payment-session',{headers:f.auth(cookie),hold:true});await turn();req.destroy=()=>{throw Error('synthetic_destroy_failure');};
  await f.advance(11000);assert.equal(res.destroyed,true);assert.deepEqual(await f.gateway.wait(),{outcome:'FAILED'});const cleanup=f.gateway.close({deadline:12000});await f.advance(12000);assert.equal(await cleanup,false);
});
